package com.maning.audit.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 *
 * <p>为什么必须自定义 RedisTemplate：
 * Spring Boot 默认的 RedisTemplate<String,Object> 用 JdkSerializationRedisSerializer，
 * 存进去的 key/value 是一堆不可读的二进制，redis-cli 里看到的是 \xac\xed\x00...
 * 排查问题时完全没法看。这里换成 String + JSON，肉眼可读。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // key 用 String 序列化：redis-cli 里能直接看到 "audit:stat:project:1"
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // value 用 JSON 序列化：可读、跨语言、体积比 JDK 序列化小
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 必须开启类型信息，否则反序列化回来的是 LinkedHashMap 而不是原类型
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        // 支持 LocalDateTime 等 Java 8 时间类型，否则缓存带时间的对象会报错
        om.registerModule(new JavaTimeModule());
        jsonSerializer.setObjectMapper(om);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
