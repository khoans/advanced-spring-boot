package com.oms.config.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Configures read/write routing via {@link RoutingDataSource}.
 * Activated only when {@code oms.routing.enabled=true}.
 *
 * In dev, both primary and replica point to the same PostgreSQL instance.
 * In prod, replica points to a PostgreSQL read replica.
 */
@Configuration
@ConditionalOnProperty(name = "oms.routing.enabled", havingValue = "true")
public class DataSourceRoutingConfig {

    @Bean
    @ConfigurationProperties("oms.datasource.primary")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("oms.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean
    public DataSource replicaDataSource() {
        return replicaDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setTargetDataSources(Map.of(
                DataSourceType.PRIMARY, primaryDataSource(),
                DataSourceType.READ_REPLICA, replicaDataSource()));
        routingDataSource.setDefaultTargetDataSource(primaryDataSource());
        return routingDataSource;
    }
}
