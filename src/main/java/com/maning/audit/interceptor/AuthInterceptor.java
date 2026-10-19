package com.maning.audit.interceptor;

import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.result.Result;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器
 *
 * <p>为什么用 HandlerInterceptor 而不是 Spring Security 过滤器链：
 * 本项目是前后端分离 + 纯接口，没有页面跳转、没有会话、没有表单登录，
 * 引入完整的 Security 过滤器链要写大量配置才能"关掉"它的默认行为，
 * 反而增加理解和排查成本。用轻量拦截器，逻辑一目了然。
 * （如果要做细粒度 RBAC、方法级注解鉴权，那还是上 Security 更合适）
 *
 * <p>三个关键点：
 * ① preHandle 返回 false 表示请求到此为止，不进入 Controller
 * ② 响应体必须自己 setContentType + charset，否则中文乱码
 * ③ ThreadLocal 的 remove 放在 afterCompletion，用 finally 语义保证一定执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AuditProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 预检请求直接放行，否则浏览器跨域会失败
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(props.getJwt().getHeader());
        if (!StringUtils.hasText(header)) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "请先登录");
            return false;
        }

        String token = header.startsWith(props.getJwt().getPrefix())
                ? header.substring(props.getJwt().getPrefix().length())
                : header;

        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            // 区分"过期"和"无效"，前端据此决定是静默续期还是跳登录页
            if (jwtUtil.isExpired(token)) {
                writeError(response, ResultCode.TOKEN_EXPIRED.getCode(), "登录已过期, 请重新登录");
            } else {
                writeError(response, ResultCode.TOKEN_INVALID.getCode(), "登录凭证无效");
            }
            return false;
        }

        // 写入线程上下文，后续 Service 里 UserContext.getUserId() 直接可用
        UserContext.set(new UserContext.LoginUser(
                claims.get("userId", Number.class).longValue(),
                claims.get("username", String.class),
                claims.get("realName", String.class),
                claims.get("role", String.class)
        ));
        return true;
    }

    /**
     * 请求完成后务必清理 ThreadLocal。
     * Tomcat 的线程是复用的，不清理会导致下一个请求读到上一个用户的身份——
     * 这是"数据串号"类线上事故的经典成因。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, Integer code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK); // 业务码放在 body 里，HTTP 层保持 200
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
