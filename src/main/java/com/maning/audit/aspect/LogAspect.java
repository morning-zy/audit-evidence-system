package com.maning.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maning.audit.annotation.Log;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.result.Result;
import com.maning.audit.entity.OperationLog;
import com.maning.audit.mapper.OperationLogMapper;
import com.maning.audit.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 操作日志切面
 *
 * <p><b>@Around 而不是 @After</b>：需要同时拿到"耗时"和"是否抛异常"，
 * 只有环绕通知能同时做这两件事。
 *
 * <p><b>写日志失败不能影响主流程</b>：整个日志保存包在 try-catch 里，
 * 日志挂了也只是少一条记录，绝不能因为记日志失败把正常请求打挂。
 * 这是"非核心链路"必须遵守的原则。真要求高可靠应该改成异步
 * （@Async 或 MQ），本项目为了直观先用同步。
 *
 * <p><b>必须 pjp.proceed()</b>：环绕通知里不调用 proceed 就等于把原方法吞了，
 * 接口会静默返回 null。这是 AOP 最常见的坑。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LogAspect {

    private final OperationLogMapper logMapper;
    private final ObjectMapper objectMapper;

    /** 参数截断长度：大对象（比如带 base64 的请求）不能全量入库 */
    private static final int MAX_TEXT_LEN = 2000;

    @Around("@annotation(logAnnotation)")
    public Object around(ProceedingJoinPoint pjp, Log logAnnotation) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;

        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                saveLog(pjp, logAnnotation, result, error, System.currentTimeMillis() - start);
            } catch (Exception e) {
                // 吞掉日志异常，绝不影响业务
                log.warn("操作日志保存失败: {}", e.getMessage());
            }
        }
    }

    private void saveLog(ProceedingJoinPoint pjp, Log anno, Object result,
                         Throwable error, long cost) {
        OperationLog entity = new OperationLog();

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        entity.setMethod(signature.getDeclaringTypeName() + "." + signature.getName());
        entity.setModule(anno.module());
        entity.setOperation(anno.value());
        entity.setCostMs(cost);
        entity.setSuccess(error == null ? 1 : 0);
        entity.setCreateTime(LocalDateTime.now());

        // 操作人身份：优先取 ThreadLocal（普通请求由拦截器写入）
        // 登录接口是个特例 —— 拦截器放行了 /auth/login，UserContext 此时还是空的，
        // 如果只读 ThreadLocal，登录日志的操作人永远是 null，审计留痕就断了。
        // 所以这里补一层兜底：从方法返回值里解出登录用户的身份。
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getRealName();
        if (operatorId == null && result != null) {
            LoginVO login = extractLoginVO(result);
            if (login != null) {
                operatorId = login.getUserId();
                operatorName = login.getRealName();
            }
        }
        entity.setOperatorId(operatorId);
        entity.setOperatorName(operatorName);

        if (error != null) {
            entity.setErrorMsg(truncate(error.getClass().getSimpleName() + ": " + error.getMessage()));
        }

        // 从 RequestContextHolder 拿当前请求，比在方法参数里传 request 干净
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            entity.setRequestUri(request.getRequestURI());
            entity.setRequestMethod(request.getMethod());
            entity.setIp(getClientIp(request));
        }

        if (anno.saveParams()) {
            entity.setParams(truncate(formatArgs(pjp.getArgs())));
        }
        if (anno.saveResult() && result != null) {
            entity.setResult(truncate(toJson(result)));
        }

        logMapper.insert(entity);
    }

    /**
     * 从方法返回值里剥出 LoginVO
     *
     * <p>返回值可能是 Result&lt;LoginVO&gt;，也可能是裸的 LoginVO，两种都要能处理。
     * 用 instanceof 逐层判断，比反射取字段更直接，也不受泛型擦除影响。
     */
    private LoginVO extractLoginVO(Object result) {
        Object data = result;
        if (result instanceof Result<?> r) {
            data = r.getData();
        }
        return data instanceof LoginVO vo ? vo : null;
    }

    /**
     * 格式化方法参数
     *
     * <p>过滤掉 HttpServletRequest / MultipartFile：前者不可序列化且无意义，
     * 后者会输出一堆流对象信息。这是实际写切面时一定会遇到的问题。
     */
    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args)
                .filter(a -> a != null
                        && !(a instanceof HttpServletRequest)
                        && !(a instanceof MultipartFile)
                        && !(a instanceof jakarta.servlet.http.HttpServletResponse))
                .map(this::toJson)
                .collect(Collectors.joining(", "));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) + "...(truncated)" : s;
    }

    /**
     * 获取真实客户端 IP
     *
     * <p>为什么看 X-Forwarded-For：经过 Nginx 反向代理后，
     * getRemoteAddr() 拿到的是代理服务器 IP，不是用户 IP。
     * X-Forwarded-For 是逗号分隔的链路，第一个才是原始客户端。
     */
    private String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String ip = request.getHeader(h);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                int comma = ip.indexOf(',');
                return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
