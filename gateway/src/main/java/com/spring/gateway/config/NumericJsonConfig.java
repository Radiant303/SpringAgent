package com.spring.gateway.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * JSON 整数解析配置。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@Configuration
public class NumericJsonConfig {
    @Bean
    public JsonMapperBuilderCustomizer strictIntegers() {
        return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
