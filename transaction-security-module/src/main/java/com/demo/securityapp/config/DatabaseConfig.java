package com.demo.securityapp.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToEnable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .featuresToDisable(
                        DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    private HikariDataSource source(Environment env, String name) {
        HikariConfig config = new HikariConfig();
        String prefix = "processor." + name + ".";
        config.setJdbcUrl(env.getRequiredProperty(prefix + "url"));
        config.setUsername(env.getRequiredProperty(prefix + "username"));
        config.setPassword(env.getRequiredProperty(prefix + "password"));
        config.setPoolName(name);
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource primarySource(Environment env) {
        return source(env, "primary");
    }

    @Bean(destroyMethod = "close")
    HikariDataSource auditSource(Environment env) {
        return source(env, "audit");
    }

    @Bean(destroyMethod = "close")
    HikariDataSource settlementSource(Environment env) {
        return source(env, "settlement");
    }

    private JdbcTemplate template(HikariDataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    JdbcTemplate primaryDb(HikariDataSource primarySource) {
        return template(primarySource);
    }

    @Bean
    JdbcTemplate auditDb(HikariDataSource auditSource) {
        return template(auditSource);
    }

    @Bean
    JdbcTemplate settlementDb(HikariDataSource settlementSource) {
        return template(settlementSource);
    }
}
