package cn.minglli.lumora.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
        "lumora.wechat-app-id=wx-app-id",
        "lumora.wechat-original-id=gh_original",
        "lumora.wechat-token=wechat-token",
        "lumora.wechat-aes-key=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
        "lumora.wechat-app-secret=wechat-app-secret",
        "lumora.postgres-host=localhost",
        "lumora.postgres-port=5432",
        "lumora.postgres-database=lumora",
        "lumora.postgres-username=lumora",
        "lumora.postgres-password=lumora",
        "lumora.mail-username=sender@qq.com",
        "lumora.mail-auth-code=mail-auth-code",
        "lumora.report-recipients=owner@example.com",
        "lumora.report-admin-key=admin-secret",
        "spring.flyway.enabled=false"
})
public abstract class PostgresContainerTest {

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
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lumora")
            .withUsername("lumora")
            .withPassword("lumora");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET TIME ZONE 'UTC'");
    }

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
