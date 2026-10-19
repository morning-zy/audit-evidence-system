package com.maning.audit.config;

import com.maning.audit.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册拦截器 / 跨域 / 静态资源
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuditProperties auditProperties;

    /** 无需登录即可访问的路径白名单 */
    private static final String[] EXCLUDE_PATHS = {
            "/auth/login",
            "/error",
            // 接口文档：不放行的话打开 swagger-ui.html 会被鉴权拦下来
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/favicon.ico",
            // 前端静态页面：登录页本身当然不能要求登录
            // 注意：这里放行的是"页面文件"，接口仍然全部走鉴权，前端靠 JWT 调接口
            "/ui",
            "/ui/",
            "/ui/**"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS);
    }

    /**
     * 跨域配置
     *
     * <p>allowedOriginPatterns 而不是 allowedOrigins：
     * Spring 5.3+ 规定 allowedOrigins("*") 与 allowCredentials(true) 不能共存，
     * 用 patterns 才能既允许多域名又能带 Cookie。
     *
     * <p>注意：当前 context-path 是 /api，所以跨域路径是 /api/**。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 静态资源映射：把本地上传目录暴露成可访问的 URL
     * 这样前端能直接 <img src="/api/files/xxx.png"> 显示证据附件
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = auditProperties.getFile().getUploadDir();
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + (uploadDir.endsWith("/") ? uploadDir : uploadDir + "/"));
    }

    /**
     * 前端入口跳转：/ui 和 /ui/ → /ui/index.html
     *
     * <p><b>为什么必须显式写这段</b>：
     * 用户敲 <code>http://localhost:8090/api/ui</code>（不带尾斜杠）时，
     * Spring 会把请求交给默认的静态资源处理器去"找目录"，
     * 而 spring-boot 3 的 <code>ResourceHttpRequestHandler</code> 遇到目录请求
     * 抛的是 <code>NoResourceFoundException</code>，被全局异常处理器兜成了 5000。
     *
     * <p>表现就很迷惑：页面文件明明在，但用户看到的是"系统繁忙"。
     * 文件路径写全（/ui/index.html）反而是正常的 —— 这种"少个斜杠就报错"
     * 的坑，只能靠显式路由兜住。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/ui", "/ui/index.html");
        registry.addRedirectViewController("/ui/", "/ui/index.html");
    }
}
