package cn.minglli.lumora.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(properties = {
        "lumora.wechat-app-id=wx-app-id",
        "lumora.wechat-original-id=gh_original",
        "lumora.wechat-token=wechat-token",
        "lumora.wechat-aes-key=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
        "lumora.wechat-app-secret=wechat-app-secret",
        "lumora.mysql-host=localhost",
        "lumora.mysql-port=3306",
        "lumora.mysql-database=lumora",
        "lumora.mysql-username=lumora",
        "lumora.mysql-password=lumora",
        "lumora.mail-username=sender@qq.com",
        "lumora.mail-auth-code=mail-auth-code",
        "lumora.report-recipients=owner@example.com",
        "lumora.report-admin-key=admin-secret",
        "spring.flyway.enabled=false"
})
public abstract class MySqlContainerTest {

    /**
     * Singleton container, started once for the whole JVM and never stopped here.
     *
     * <p>Deliberately not {@code @Testcontainers}/{@code @Container}: that pair stops
     * the container in each subclass's {@code afterAll}, while Spring's context cache
     * survives across test classes. The second integration class to run would then
     * reuse a cached datasource pointing at a container that no longer exists, and
     * fail with "Connection refused" 30 seconds at a time. Ryuk removes the container
     * when the JVM exits.
     */
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("lumora")
            .withUsername("lumora")
            .withPassword("lumora")
            .withCommand("--default-time-zone=+00:00");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
    }

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl()
                                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                        MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
