package com.maning.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 录入单个样本的检查结果。
 *
 * <p>一次请求可以提交多个样本（批量录入），Service 会逐条校验。
 *
 * <p>deviation 为 true 时 deviationDesc 必填 —— 这条校验不放在这里用注解做，
 * 而是放在 Service 里，因为要抛业务错误码（3013），让前端能精确定位到哪一行没填。
 * 用一个笼统的"参数校验失败"提示，用户还得自己找是哪个样本。
 */
@Data
public class SamplingItemDTO {

    @NotBlank(message = "凭证号不能为空")
    @Size(max = 64, message = "凭证号不能超过64个字符")
    private String voucherNo;

    private LocalDate voucherDate;

    private BigDecimal amount;

    /** 是否偏离控制要求；null 视为未检查 */
    private Boolean deviation;

    /** 偏差描述，deviation=true 时必填 */
    @Size(max = 512, message = "偏差描述不能超过512个字符")
    private String deviationDesc;
}
