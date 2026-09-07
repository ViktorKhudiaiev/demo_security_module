package com.demo.transferapp.config;

import com.demo.integrity.client.IntegrityClient;
import com.demo.transferapp.client.ProcessorClient;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class AppConfiguration {
    @Bean
    IntegrityClient integrityClient(
            @Value("${demo.key-url}") String url,
            @Value("${demo.writer-token}") String token) {
        return new IntegrityClient(url, token);
    }

    @Bean
    ProcessorClient processorClient(
            @Value("${demo.processor-url}") String url,
            @Value("${demo.processor-token}") String token) {
        return new ProcessorClient(url, token);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

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
}
