package com.maning.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器
 *
 * <p>为什么要用 BCrypt 而不是 MD5/SHA：
 * ① 自带随机盐，同一密码每次加密结果不同，彩虹表失效
 * ② 计算慢（默认 10 轮，约 50-100ms），暴力破解成本高
 * ③ MD5 早已被证明不可用于密码存储
 *
 * <p>matches() 是单向校验：拿明文 + 数据库里的哈希，内部重新算一次比对，
 * 所以永远不需要"解密"。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
