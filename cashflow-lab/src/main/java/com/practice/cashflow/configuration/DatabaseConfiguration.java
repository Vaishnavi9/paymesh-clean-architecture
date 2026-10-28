package com.practice.cashflow.configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("db")
public class DatabaseConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(
            @Value("${app.database.url}") String url,
            @Value("${app.database.username}") String username,
            @Value("${app.database.password}") String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Set database credentials in config/database-local.properties or DB_USER/DB_PASSWORD.");
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(2000);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        return jdbc;
    }
}
