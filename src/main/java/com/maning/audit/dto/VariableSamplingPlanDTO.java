package com.maning.audit.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建变量抽样计划的入参。
 *
 * <p>金额一律用"元"为单位、保留两位小数，不要传"万元"——
 * 单位混用是金额类系统最常见的生产事故，且事后极难发现：
 * 一个 12,000 元的样本被当成 12,000 万元，算出来的可容忍错报比例离谱，
 * 但公式不会报错，只会安静地给出一个错误答案。
 */
@Data
public class VariableSamplingPlanDTO {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 关联审计事项，可为空 */
    private Long issueId;

    @NotBlank(message = "被测试的科目/项目名称不能为空")
    @Size(max = 128, message = "科目名称不能超过128个字符")
    private String accountName;

    @NotNull(message = "总体账面金额不能为空")
    @DecimalMin(value = "0.01", message = "总体账面金额必须大于0")
    private BigDecimal populationBookValue;

    @NotNull(message = "总体项数不能为空")
    @Min(value = 1, message = "总体项数必须大于0")
    @Max(value = 10_000_000, message = "总体项数超出合理范围")
    private Integer populationSize;

    @NotNull(message = "可容忍错报不能为空")
    @DecimalMin(value = "0.01", message = "可容忍错报必须大于0")
    private BigDecimal tolerableMisstatement;

    /** 预期错报，仅作参考与提示，不参与样本量公式 */
    @DecimalMin(value = "0.0", message = "预期错报不能为负")
    private BigDecimal expectedMisstatement;

    /**
     * 预估单笔标准差。
     *
     * <p>留空表示"不知道"，系统先给 30 项试点样本，抽完后用实际标准差重算正式样本量 ——
     * 这是审计实务的标准做法。硬要求用户先填一个标准差，
     * 等于逼他随便编一个数，那样算出来的样本量还不如不填。
     */
    @DecimalMin(value = "0.0", message = "预估标准差不能为负")
    private BigDecimal assumedStdDev;

    /** NINETY_FIVE / NINETY，留空默认 95% */
    private String confidenceLevel;

    /** MEAN_PER_UNIT / DIFFERENCE / RATIO，留空默认差额估计 */
    private String method;
}
