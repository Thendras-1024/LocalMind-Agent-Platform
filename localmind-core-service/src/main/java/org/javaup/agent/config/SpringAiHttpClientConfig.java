package org.javaup.agent.config;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

@Configuration
public class SpringAiHttpClientConfig {

    @Bean
    public ObjectMapper springAiHttpObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public RestClientCustomizer springAiNumberSafeRestClientCustomizer(ObjectMapper springAiHttpObjectMapper) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(springAiHttpObjectMapper);
        return builder -> builder.messageConverters(converters -> replaceJacksonConverter(converters, converter));
    }

    private void replaceJacksonConverter(List<HttpMessageConverter<?>> converters,
                                         MappingJackson2HttpMessageConverter replacement) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                converters.set(i, replacement);
                return;
            }
        }
        converters.add(replacement);
    }
}
