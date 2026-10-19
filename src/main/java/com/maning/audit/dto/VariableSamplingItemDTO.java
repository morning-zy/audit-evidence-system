package com.maning.audit.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 变量抽样样本录入。
 *
 * <p>{@code auditedValue} 允许为空：表示"这张凭证抽到了但还没看"。
 * 分批检查是审计现场常态 —— 先录入抽样清单，再逐张翻凭证回填。
 * 不允许空的话，用户只能填 0 应付，而 0 在计算里是"审定金额为零"，
 * 每一张都会被算成全额错报，结果比不填还糟。
 */
@Data
public class VariableSamplingItemDTO {

    @NotBlank(message = "凭证号不能为空")
    @Size(max = 64, message = "凭证号不能超过64个字符")
    private String voucherNo;

    private LocalDate voucherDate;

    @NotNull(message = "账面金额不能为空")
    @DecimalMin(value = "0.0", message = "账面金额不能为负")
    private BigDecimal bookValue;

    /** 审定金额，留空表示尚未检查 */
    @DecimalMin(value = "0.0", message = "审定金额不能为负")
    private BigDecimal auditedValue;

    /** 检查说明；审定金额与账面不一致时必填 */
    @Size(max = 512, message = "检查说明不能超过512个字符")
    private String remark;
}
