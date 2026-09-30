package cn.minglli.lumora.analytics;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;

@Service
public class IpLocationService {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final String apiKey;
    private long nextLookupAt;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public IpLocationService(ObjectMapper json, JdbcTemplate jdbc,
            @Value("${lumora.geo-api-key:}") String apiKey) {
        this.json = json;
        this.jdbc = jdbc;
        this.apiKey = apiKey;
    }

    private synchronized boolean acquireLookup() {
        long now = System.currentTimeMillis();
        if (now < nextLookupAt) return false;
        nextLookupAt = now + (apiKey.isBlank() ? 11000 : 1000);
        return true;
    }

    public Map<String, String> lookup(String ip) {
        Cached existing = cache.get(ip);
        if (existing != null && existing.expires().isAfter(Instant.now())) return existing.value();
        var stored = jdbc.queryForList("SELECT payload FROM ip_location_cache WHERE ip = ? AND expires_at > CURRENT_TIMESTAMP", ip);
        if (!stored.isEmpty()) {
            try {
                Map<String, String> saved = json.readValue(stored.get(0).get("payload").toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
                return saved;
            } catch (Exception ignored) { }
        }
        Map<String, String> value = Map.of("location", "未知", "status", "unavailable");
        try {
            // Numeric addresses only: never resolve arbitrary hostnames.
            if (!ip.matches("[0-9a-fA-F:.]{2,45}") || (!ip.contains(":") && !ip.matches("[0-9.]+"))) return value;
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                return Map.of("location", "内网 / 保留地址", "status", "private");
            }
            if (!acquireLookup()) return Map.of("location", "等待查询", "status", "pending");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://ipinfo.is/" + ip))
                    .timeout(Duration.ofSeconds(3)).header("Accept", "application/json");
            if (!apiKey.isBlank()) builder.header("x-api-key", apiKey);
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) return Map.of("location", "等待查询", "status", "pending");
            if (response.statusCode() == 200 && response.body().length() < 65536) {
                JsonNode data = json.readTree(response.body());
                String country = data.path("country").path("long_name").asText("");
                String region = data.path("region").asText("");
                String city = data.path("city").asText("");
                String location = String.join(" · ", java.util.stream.Stream.of(country, region, city)
                        .filter(part -> !part.isBlank()).distinct().toList());
                if (!location.isBlank()) {
                    var result = new java.util.HashMap<String, String>();
                    result.put("location", location);
                    result.put("status", "ok");
                    if (data.path("latitude").isNumber() && data.path("longitude").isNumber()
                            && Math.abs(data.path("latitude").asDouble()) <= 90
                            && Math.abs(data.path("longitude").asDouble()) <= 180) {
                        result.put("latitude", data.path("latitude").asText());
                        result.put("longitude", data.path("longitude").asText());
                    }
                    value = Map.copyOf(result);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) { /* Location lookup must never block access statistics. */ }
        if (cache.size() >= 4096) cache.clear();
        Instant expires = Instant.now().plusSeconds(value.get("status").equals("ok") ? 604800 : 300);
        cache.put(ip, new Cached(value, expires));
        try {
            jdbc.update("""
                    INSERT INTO ip_location_cache (ip, payload, expires_at) VALUES (?, ?, ?)
                    ON CONFLICT (ip) DO UPDATE SET payload = EXCLUDED.payload, expires_at = EXCLUDED.expires_at
                    """, ip, json.writeValueAsString(value), Timestamp.from(expires));
            jdbc.update("DELETE FROM ip_location_cache WHERE expires_at < CURRENT_TIMESTAMP");
        } catch (Exception ignored) { /* Keep the short-lived memory fallback. */ }
        return value;
    }

    private record Cached(Map<String, String> value, Instant expires) { }
}
