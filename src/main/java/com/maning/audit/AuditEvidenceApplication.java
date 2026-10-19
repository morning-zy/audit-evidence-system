package com.maning.audit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 审计证据管理系统 启动类
 *
 * <p>@MapperScan 写在启动类上而不是每个 Mapper 上加 @Mapper：
 * 前者集中管理、后加 Mapper 不用改代码，是更常见的工程做法。
 */
@SpringBootApplication
@MapperScan("com.maning.audit.mapper")
public class AuditEvidenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditEvidenceApplication.class, args);
        System.out.println("""

                ============================================================
                  审计证据管理系统 启动成功
                  接口文档: http://localhost:8090/api/swagger-ui.html
                ============================================================
                """);
    }
}
