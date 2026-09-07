package com.demo.securityapp.config;

import com.demo.securityapp.delivery.EmbeddedActiveMqDelivery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

@Configuration
public class DeliveryConfiguration {
    @Bean(destroyMethod = "close")
    EmbeddedActiveMqDelivery operationDelivery(Environment env) {
        return new EmbeddedActiveMqDelivery(
                Path.of(env.getRequiredProperty("processor.broker.directory")),
                env.getProperty("processor.broker.name", "integrity-processor"));
    }
}
