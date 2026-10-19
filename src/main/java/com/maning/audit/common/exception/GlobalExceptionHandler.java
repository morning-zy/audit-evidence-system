package com.maning.audit.common.exception;

import com.maning.audit.common.result.Result;
import com.maning.audit.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p>为什么用 @RestControllerAdvice：把"异常 -> 响应体"的转换逻辑从
 * 各个 Controller 里抽出来，Controller 只写正常流程，业务异常直接抛。
 * 这是分层设计里"横切关注点"的典型例子。
 *
 * <p>返回 HTTP 200 + 业务 code 是我们团队的约定（前端只判 code）；
 * 如果你想让 HTTP 状态码也语义化，把方法返回值改成 ResponseEntity 即可。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期，不打堆栈，只记 warn */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** @RequestBody 上的 @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return Result.error(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /** 表单/Query 参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", msg);
        return Result.error(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /** 方法参数上的 @Validated 校验失败（如 @NotNull 直接标在参数上） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return Result.error(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "缺少必要参数: " + e.getParameterName());
    }

    /** 请求体 JSON 格式错误 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误");
    }

    /** 请求方式不支持，如用 GET 调了 POST 接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.error(ResultCode.METHOD_NOT_ALLOWED.getCode(),
                "不支持 " + e.getMethod() + " 方式, 请用 " + e.getSupportedHttpMethods());
    }

    /** 404：接口路径不存在 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e) {
        return Result.error(ResultCode.NOT_FOUND.getCode(), "接口不存在: " + e.getRequestURL());
    }

    /**
     * 404：静态资源不存在
     *
     * <p><b>这个异常必须单独处理，否则会掉进兜底变成 5000</b>。
     *
     * <p>Spring Boot 3.2 起，访问不存在的静态资源（含访问一个没写 index 的目录）
     * 不再走 <code>NoHandlerFoundException</code>，而是抛
     * <code>NoResourceFoundException</code>。老代码里只处理了前者，
     * 于是用户访问 <code>/api/ui</code> 这种路径时，看到的是
     * "系统繁忙, 请稍后重试" —— 明明只是路径不对，却像服务器挂了。
     *
     * <p>这类"把 404 误报成 500"的问题特别坑：排查时会往服务端找原因，
     * 而真正的问题往往是路径少了个字符。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("静态资源不存在: {}", e.getResourcePath());
        return Result.error(ResultCode.NOT_FOUND.getCode(), "资源不存在: " + e.getResourcePath());
    }

    /** 上传文件超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        return Result.error(ResultCode.FILE_UPLOAD_ERROR.getCode(), "上传文件过大, 单个文件不超过 20MB");
    }

    /** 兜底：所有非预期异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 非预期异常必须打完整堆栈，否则线上排查全靠猜
        log.error("系统异常", e);
        return Result.error(ResultCode.SYSTEM_ERROR.getCode(), "系统繁忙, 请稍后重试");
    }
}
