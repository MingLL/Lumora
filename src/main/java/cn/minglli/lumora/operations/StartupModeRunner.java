package cn.minglli.lumora.operations;

import java.util.List;

import cn.minglli.lumora.config.LumoraProperties;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class StartupModeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupModeRunner.class);
    private static final List<String> REQUIRED_TABLES =
            List.of("wechat_event", "daily_report", "report_delivery_attempt");

    private final LumoraProperties properties;

    public StartupModeRunner(LumoraProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        String mode = System.getenv().getOrDefault("LUMORA_MODE", "serve");
        log.info("Lumora startup mode: {}", mode);
        switch (mode) {
            case "migrate" -> runMigrate();
            case "schema-smoke" -> runSchemaSmoke();
            default -> { }
        }
    }

    private void runMigrate() {
        String url = jdbcUrl();
        String username = env("MIGRATION_MYSQL_USERNAME", properties.getMysqlUsername());
        String password = env("MIGRATION_MYSQL_PASSWORD", properties.getMysqlPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        log.info("Flyway migration completed");
        System.exit(0);
    }

    private void runSchemaSmoke() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), properties.getMysqlUsername(), properties.getMysqlPassword());
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        for (String table : REQUIRED_TABLES) {
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
        }
        log.info("Schema smoke check passed for tables {}", REQUIRED_TABLES);
        System.exit(0);
    }

    private String jdbcUrl() {
        return "jdbc:mysql://%s:%d/%s?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                .formatted(properties.getMysqlHost(), properties.getMysqlPort(), properties.getMysqlDatabase());
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
