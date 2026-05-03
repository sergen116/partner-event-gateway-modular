package com.example.peg;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spins up a Postgres container with pgmq + pg_partman pre-installed (Tembo image),
 * and configures the Spring datasource to point at it.
 *
 * <p>Used by integration tests via:
 * {@code @ContextConfiguration(initializers = PgmqPostgresInitializer.class)}.
 */
public class PgmqPostgresInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse("quay.io/tembo/pg17-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("events")
                .withUsername("postgres")
                .withPassword("postgres")
                // pg_partman_bgw isn't strictly required for tests because we don't rely on
                // the background worker for partition lifecycle inside a single test run.
                .withReuse(false);
        POSTGRES.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                // Smaller pool — tests don't need 30 connections
                "spring.datasource.hikari.maximum-pool-size=5",
                // Tighter polling/VT for faster tests
                "app.consumer.poll-interval-ms=100",
                "app.consumer.visibility-timeout-seconds=10",
                "app.consumer.batch-size.events_order_created=20",
                "app.consumer.batch-size.events_shipment_updated=20",
                "app.consumer.batch-size.events_return_requested=20",
                "app.consumer.batch-size.events_address_updated=20",
                "app.consumer.batch-size.events_order_cancelled=20",
                "app.consumer.max-attempts=2",
                "app.runtime.mode=CONSUMER_ALL"
        ).applyTo(ctx.getEnvironment());
    }

    public static PostgreSQLContainer<?> container() {
        return POSTGRES;
    }
}
