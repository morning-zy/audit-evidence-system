package com.maning.audit.common.result;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应体
 *
 * <p>为什么要有它：前端只认一种结构，不用每个接口都猜返回格式。
 * 前端拿到 code 就知道走成功分支还是错误分支，比 HTTP 状态码更好用
 * （HTTP 200 也可能是业务失败，比如"余额不足"）。
 *
 * @param <T> 业务数据类型
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    /** 业务状态码：200 成功，其余为各类失败 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 时间戳，便于前端/日志排查时序问题 */
    private Long timestamp;

    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(ResultCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> r = success(data);
        r.setMessage(message);
        return r;
    }

    public static <T> Result<T> error(String message) {
        return error(ResultCode.ERROR.getCode(), message);
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return error(resultCode.getCode(), resultCode.getMessage());
    }

    /**
     * 分页结果快捷构造：把 Page 对象转成前端需要的结构，
     * 避免把 MP 的 Page 内部字段（如 orders、optimizeCountSql）暴露给前端
     */
    public static <T> Result<PageResult<T>> page(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        return success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }
}
