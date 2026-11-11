package com.travelbilling.api;

import java.io.IOException;
import java.math.BigDecimal;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

@Configuration
public class JsonConfig {

    /**
     * NUMERIC(19,4) comes back from Postgres padded: 220.0000, 0.070000.
     * On the way out we drop only the padding zeros (220.0000 → 220, 331.7000 → 331.7).
     * Nothing is ever rounded: the number in the JSON is exactly the stored value.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer bigDecimalWithoutPadding() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeNumber(value.stripTrailingZeros().toPlainString());
            }
        });
    }
}
