package com.maning.audit.common.result;

import lombok.Getter;

/**
 * 业务状态码枚举
 *
 * <p>用枚举而不是散落的魔法数字：改文案只改一处，而且 IDE 能跳转、能穷举。
 * 码段划分：2xx 成功 / 4xx 客户端错误 / 5xx 服务端错误
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    ERROR(500, "操作失败"),

    /* ---------- 参数与请求 ---------- */
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方式不支持"),

    /* ---------- 登录相关 ---------- */
    LOGIN_FAILED(1001, "用户名或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已被禁用"),
    TOKEN_INVALID(1003, "登录凭证无效"),
    TOKEN_EXPIRED(1004, "登录凭证已过期"),

    /* ---------- 业务通用 ---------- */
    DATA_NOT_FOUND(2001, "数据不存在"),
    DATA_EXISTS(2002, "数据已存在"),
    DATA_STATE_ERROR(2003, "当前数据状态不允许该操作"),
    OPERATION_REPEAT(2004, "请勿重复操作"),

    /* ---------- 审计业务专属 ---------- */
    PROJECT_NOT_EDITABLE(3001, "项目当前状态不允许编辑"),
    ISSUE_NOT_IN_PROJECT(3002, "审计事项不属于该项目"),
    EVIDENCE_STATUS_ILLEGAL(3003, "证据状态流转不合法"),
    EVIDENCE_NO_PERMISSION(3004, "只能操作本人提交的证据"),
    EVIDENCE_NO_SELF_REVIEW(3005, "不能复核自己提交的证据"),
    PROJECT_HAS_ISSUE(3006, "项目下存在审计事项，不能删除"),
    ISSUE_HAS_EVIDENCE(3007, "事项下存在证据，不能删除"),
    EVIDENCE_REVIEW_LEVEL_DENIED(3008, "当前复核级别与您的岗位不匹配"),

    /* ---------- 审计抽样 ---------- */
    SAMPLING_PARAM_ILLEGAL(3009, "抽样参数不合法"),
    SAMPLING_NOT_FOUND(3010, "抽样计划不存在"),
    SAMPLING_ITEM_EXCEED(3011, "样本数量已超出计划抽取数"),
    SAMPLING_ALREADY_CONCLUDED(3012, "该抽样已出具结论，不能再修改样本"),
    SAMPLING_DEVIATION_DESC_REQUIRED(3013, "判定为偏差时必须填写偏差描述"),

    /* ---------- 变量抽样（细节测试） ---------- */
    VARIABLE_SAMPLING_PARAM_ILLEGAL(3014, "变量抽样参数不合法"),
    VARIABLE_SAMPLING_NOT_FOUND(3015, "变量抽样计划不存在"),
    VARIABLE_SAMPLING_ITEM_EXCEED(3016, "样本数量已超出计划抽取数"),
    VARIABLE_SAMPLING_ALREADY_CONCLUDED(3017, "该抽样已出具结论，不能再修改样本"),
    VARIABLE_SAMPLING_ITEM_INCOMPLETE(3018, "样本缺少审定金额，不能评价"),

    /* ---------- 系统 ---------- */
    SYSTEM_ERROR(5000, "系统内部错误"),
    FILE_UPLOAD_ERROR(5001, "文件上传失败"),
    FILE_TYPE_NOT_ALLOWED(5002, "不支持的文件类型");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
