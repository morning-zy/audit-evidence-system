package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志（AOP 切面自动写入）
 */
@Data
@TableName("operation_log")
public class OperationLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String module;

    private String operation;

    private String method;

    private String requestUri;

    private String requestMethod;

    private String params;

    private String result;

    private Long operatorId;

    private String operatorName;

    private String ip;

    private Long costMs;

    private Integer success;

    private String errorMsg;

    private LocalDateTime createTime;
}
