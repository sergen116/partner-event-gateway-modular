package com.example.peg.platform;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Owns the writer and reader DataSources / JdbcTemplates explicitly.
 *
 * <p>Defining a custom {@link DataSource} bean disables Spring Boot's
 * autoconfigured one, so this class must reproduce the writer setup that
 * {@code spring.datasource.*} previously got for free, and add a second
 * pool for read-replica traffic.
 *
 * <h2>Routing</h2>
 * <ul>
 *   <li>{@link #primaryDataSource} (and the default {@link JdbcTemplate})
 *       carry every write — ingest inserts, state-transition updates, audit
 *       rows, outbox/pgmq workers, idempotency-check {@code findById} calls.
 *       This bean is {@link Primary @Primary} so existing
 *       {@code @Autowired JdbcTemplate} injections keep pointing at the writer
 *       with no code changes.</li>
 *   <li>{@code readJdbc} is consumed only by {@code EventRepository.query}
 *       and {@code EventRepository.count}. Replication lag is acceptable on
 *       the cross-partner internal listing endpoint; it is NOT acceptable on
 *       any read that participates in a write transaction.</li>
 *   <li>When {@link ReplicaProperties#isConfigured()} is false (local dev,
 *       CI, anywhere {@code REPLICA_DB_URL} is unset), the read bean is
 *       aliased to the primary DataSource so there is exactly one pool.</li>
 * </ul>
 *
 * <h2>Flyway</h2>
 * Flyway autoconfig binds to the {@link Primary @Primary} DataSource — i.e.
 * the writer. If a future change ever flips {@code @Primary} to the replica,
 * Flyway will migrate the wrong database.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "replicaDataSource")
    public DataSource replicaDataSource(
            @Qualifier("primaryDataSource") DataSource primary,
            ReplicaProperties replicaProps) {
        if (!replicaProps.isConfigured()) {
            return primary;
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(replicaProps.getUrl());
        ds.setUsername(replicaProps.getUsername());
        ds.setPassword(replicaProps.getPassword());
        ds.setMaximumPoolSize(replicaProps.getHikari().getMaximumPoolSize());
        ds.setMinimumIdle(replicaProps.getHikari().getMinimumIdle());
        ds.setConnectionTimeout(replicaProps.getHikari().getConnectionTimeout());
        ds.setReadOnly(true);
        ds.setPoolName("HikariPool-replica");
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("primaryDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "readJdbc")
    public JdbcTemplate readJdbc(@Qualifier("replicaDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
