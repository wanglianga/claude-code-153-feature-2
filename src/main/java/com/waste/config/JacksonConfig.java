package com.waste.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Jackson 全局配置：BigDecimal 统一按两位小数字面量输出（金额/重量口径一致，避免 24 与 24.00 混用）。
 * 业务中三位小数仅存在于账单明细 JSON 文本内，不经过对象序列化。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalScaleCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new StdScalarSerializer<>(BigDecimal.class) {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        });
        return builder -> builder.modulesToInstall(module);
    }
}
