package com.maning.audit.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc-openapi 原生 UI）
 *
 * <p>访问地址：http://localhost:8090/api/swagger-ui.html
 *
 * <p><b>为什么不是 knife4j：</b> knife4j 4.5.0 内嵌 springdoc 2.3.0，
 * 在 Spring Boot 3.5（Spring 6.2）下抛
 * {@code NoSuchMethodError: ControllerAdviceBean.<init>(Object)}；
 * 单独升级 springdoc 到 2.8.x 后又会撞
 * {@code getGroupConfigs()} 返回值从 List 改成 Set 的不兼容。
 * 与其和版本较劲，不如用 springdoc 官方 UI —— 功能不缺，而且不会有版本陷阱。
 *
 * <p>这个坑本身很值得记：<b>三方库的"便利封装"往往绑定特定版本，
 * 升级 Spring Boot 时最容易炸的就是这一类。</b>
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "Authorization";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("审计证据管理系统 API")
                        .description("""
                                Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis
                                
                                业务链路：审计项目 -> 审计事项 -> 审计证据 -> 分级复核
                                
                                使用步骤：
                                1. 调 /auth/login 拿到 token
                                2. 点右上角 Authorize，填入 token（不需要加 Bearer 前缀）
                                3. 其余接口即可正常调试
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("马宁")))
                // 声明 JWT 鉴权头，Swagger UI 里填一次 token 就能调所有接口
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("登录接口返回的 token")));
    }

    /* ---------- 按业务模块分组，避免所有接口堆在一个列表里 ---------- */

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder().group("01-认证").pathsToMatch("/auth/**").build();
    }

    @Bean
    public GroupedOpenApi projectApi() {
        return GroupedOpenApi.builder().group("02-审计项目").pathsToMatch("/project/**").build();
    }

    @Bean
    public GroupedOpenApi issueApi() {
        return GroupedOpenApi.builder().group("03-审计事项").pathsToMatch("/issue/**").build();
    }

    @Bean
    public GroupedOpenApi evidenceApi() {
        return GroupedOpenApi.builder().group("04-审计证据").pathsToMatch("/evidence/**").build();
    }

    @Bean
    public GroupedOpenApi statApi() {
        return GroupedOpenApi.builder().group("05-统计与文件")
                .pathsToMatch("/stat/**", "/file/**").build();
    }

    @Bean
    public GroupedOpenApi logApi() {
        return GroupedOpenApi.builder().group("06-操作日志").pathsToMatch("/log/**").build();
    }

    @Bean
    public GroupedOpenApi samplingApi() {
        return GroupedOpenApi.builder().group("07-审计抽样").pathsToMatch("/sampling/**").build();
    }

    @Bean
    public GroupedOpenApi variableSamplingApi() {
        return GroupedOpenApi.builder().group("08-变量抽样").pathsToMatch("/variable-sampling/**").build();
    }
}
