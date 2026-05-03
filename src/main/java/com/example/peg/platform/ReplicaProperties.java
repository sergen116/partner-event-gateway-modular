package com.example.peg.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional read-replica connection. When {@link #getUrl()} is null or blank,
 * {@link DataSourceConfig} routes the read {@code JdbcTemplate} to the primary
 * pool — the local docker-compose has only one Postgres, so this keeps dev and
 * CI working unchanged. Set {@code REPLICA_DB_URL} in deployed environments to
 * point read traffic at a streaming replica.
 */
@ConfigurationProperties("app.datasource.replica")
@Data
public class ReplicaProperties {

    private String url;
    private String username;
    private String password;

    private Hikari hikari = new Hikari();

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    @Data
    public static class Hikari {
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private long connectionTimeout = 5000;
    }
}
