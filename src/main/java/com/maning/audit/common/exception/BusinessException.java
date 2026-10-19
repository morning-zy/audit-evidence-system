package com.maning.audit.common.exception;

import com.maning.audit.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 *
 * <p>关键设计：继承 RuntimeException（非受检异常）。
 * 如果继承 Exception，Service 每个方法都得 try-catch 或者 throws，
 * 业务代码会被异常声明污染。业务异常本质是"可预期的流程分支"，
 * 交给全局异常处理器统一转成响应体即可，不需要调用方显式处理。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.ERROR.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String detailMessage) {
        super(detailMessage);
        this.code = resultCode.getCode();
    }

    /**
     * 条件成立时抛异常，用于替代大量 if-throw 样板
     * 用法：BusinessException.throwIf(count > 0, ResultCode.DATA_EXISTS);
     */
    public static void throwIf(boolean condition, ResultCode resultCode) {
        if (condition) {
            throw new BusinessException(resultCode);
        }
    }

    public static void throwIf(boolean condition, ResultCode resultCode, String detailMessage) {
        if (condition) {
            throw new BusinessException(resultCode, detailMessage);
        }
    }
}
