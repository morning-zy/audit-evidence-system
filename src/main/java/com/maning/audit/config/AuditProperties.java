package com.maning.audit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务配置属性绑定
 *
 * <p>用 @ConfigurationProperties 而不是到处写 @Value：
 * ① 配置项有类型、有默认值、IDE 能补全
 * ② 成组配置放一起，比散落 10 个 @Value 好维护
 */
@Data
@Component
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    private Jwt jwt = new Jwt();
    private Cache cache = new Cache();
    private FileConfig file = new FileConfig();

    @Data
    public static class Jwt {
        private String secret;
        /** 过期时间（分钟） */
        private Long expireMinutes = 120L;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Data
    public static class Cache {
        private String projectStatKey = "audit:stat:project:";
        private Long projectStatTtl = 300L;
    }

    @Data
    public static class FileConfig {
        private String uploadDir;
    }
}
