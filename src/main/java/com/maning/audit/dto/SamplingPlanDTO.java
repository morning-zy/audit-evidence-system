package com.maning.audit.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建抽样计划的入参。
 *
 * <p>三个数学参数决定样本量，缺一不可：
 * <ul>
 *   <li>populationSize —— 总体有多大（本期该控制涉及的全部笔数）</li>
 *   <li>tolerableRate —— 能容忍多少偏差（超过就不信赖）</li>
 *   <li>expectedRate —— 预期会有多少偏差（依据历史数据）</li>
 * </ul>
 *
 * <p>比率一律用小数表示：5% 传 0.05，不要传 5。
 * 这个约定必须在接口文档里写死，否则前端传 5 会被当成 500%，
 * 校验直接拦掉还算走运，如果校验放宽就会算出荒谬的样本量。
 */
@Data
public class SamplingPlanDTO {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 关联审计事项，可为空 */
    private Long issueId;

    @NotBlank(message = "被测试的控制名称不能为空")
    @Size(max = 128, message = "控制名称不能超过128个字符")
    private String controlName;

    @NotNull(message = "总体规模不能为空")
    @Min(value = 1, message = "总体规模必须大于0")
    @Max(value = 10_000_000, message = "总体规模超出合理范围")
    private Integer populationSize;

    @NotNull(message = "可容忍偏差率不能为空")
    @DecimalMin(value = "0.0001", message = "可容忍偏差率必须大于0")
    @DecimalMax(value = "0.9999", message = "可容忍偏差率必须小于1（用小数表示，5% 传 0.05）")
    private BigDecimal tolerableRate;

    /** 留空视为 0：没有历史数据时按零偏差预期抽样，样本量最大，最保守 */
    @DecimalMin(value = "0.0", message = "预期偏差率不能为负")
    @DecimalMax(value = "0.9999", message = "预期偏差率必须小于1")
    private BigDecimal expectedRate;

    /** NINETY_FIVE / NINETY，留空默认 95% */
    private String confidenceLevel;

    /** STATISTICAL / JUDGMENTAL，留空默认统计抽样 */
    private String method;
}
