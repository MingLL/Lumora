package cn.minglli.lumora.analytics;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IpLocationController {
    private final IpLocationService locations;
    public IpLocationController(IpLocationService locations) { this.locations = locations; }

    @GetMapping("/api/analytics/location")
    public ResponseEntity<Map<String, String>> location(@RequestParam String ip) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locations.lookup(ip));
    }
}
