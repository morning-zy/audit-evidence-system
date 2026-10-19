package com.maning.audit.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化配置。
 *
 * <p>MyBatis-Plus 的主键是 19 位雪花 ID（如 2112127015807406081），
 * 超过 JavaScript 的 Number.MAX_SAFE_INTEGER（2^53-1 = 9007199254740991）。
 * 如果按数字下发，浏览器 JSON.parse 之后末几位会被静默改写：
 * 数据库里是 ...6081，前端拿到的是 ...6000，点"详情"就是一个 404，
 * 而且报错信息里看不出任何异常 —— 这类问题排查成本极高。
 *
 * <p>所以 Long 一律序列化成字符串。只处理 Long，不动 Integer：
 * 样本量、状态码、进度这些前端要做算术和比较，转成字符串反而添乱。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
