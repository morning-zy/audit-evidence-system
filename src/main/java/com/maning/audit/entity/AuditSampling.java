package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 审计抽样计划（依据《审计准则第 1314 号——审计抽样》）
 *
 * <p><b>这张表为什么要存这么多字段，而不是只存"样本量"</b>：
 * 审计底稿的可追溯性要求，复核人看到"抽 60 个"时必须能回答三个问题：
 * 为什么是 60 而不是 30 或 100？允许出现几个偏差？结论是怎么得出来的？
 * 所以 tolerableRate / expectedRate / confidenceLevel / allowedDeviations /
 * basis / conclusionText 全部落库，而不是现场重算 ——
 * 重算依赖算法版本，两年后算法改了就复现不出当时的结论，底稿就失真的。
 *
 * <p><b>比率为什么用 BigDecimal 而不是 double</b>：
 * 这些值要和底稿上的百分数逐位对齐（5.00% 不能显示成 5.000000001%），
 * 且要参与大小比较。double 的二进制表示会在 4 位小数上产生肉眼可见的偏差，
 * BigDecimal 存 DECIMAL(6,4) 是精确的。计算过程的浮点误差只发生在
 * SamplingCalculator 内部，落库前统一收敛到 4 位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_sampling")
public class AuditSampling extends BaseEntity {

    private String samplingNo;

    private Long projectId;

    /** 关联的审计事项；可为空，因为有些抽样直接挂在项目下 */
    private Long issueId;

    /** 被测试的内部控制名称，如"采购付款审批" */
    private String controlName;

    /* ============ 抽样参数（决定样本量的三个输入） ============ */

    /** 总体规模：本期该控制涉及的全部凭证/交易笔数 */
    private Integer populationSize;

    /** 可容忍偏差率，0.0500 表示 5% */
    private BigDecimal tolerableRate;

    /** 预期偏差率，依据历史偏差率或小规模测试确定 */
    private BigDecimal expectedRate;

    /** 置信水平：NINETY_FIVE / NINETY */
    private String confidenceLevel;

    /** 抽样方法：STATISTICAL 统计抽样 / JUDGMENTAL 判断抽样 */
    private String method;

    /* ============ 抽样结果 ============ */

    /** 应抽取的样本量 */
    private Integer sampleSize;

    /** 允许出现的偏差数，超出即判定控制不可信赖 */
    private Integer allowedDeviations;

    /**
     * 是否退化为全查。
     *
     * <p>当总体规模小于计算出的样本量时（比如总体只有 30 笔，算出来要抽 59 笔），
     * 抽样没有意义，直接全部检查。这种情况在审计上叫"详细检查"，
     * 结论的措辞和抽样不同 —— 不能声称"在 95% 置信水平下推断总体"，
     * 因为根本没抽样。
     */
    @TableField("is_census")
    private Boolean isCensus;

    /** 已实际检查的样本数 */
    private Integer checkedCount;

    /** 已发现的偏差数 */
    private Integer deviationCount;

    /** 样本偏差率（点估计） */
    private BigDecimal deviationRate;

    /** 总体偏差率上限（单侧置信上限），这才是判断控制是否可信赖的依据 */
    private BigDecimal upperLimit;

    /** 结论：0 未执行 / 1 控制可信赖 / 2 控制不可信赖 */
    private Integer conclusion;

    /** 计算依据，直接打印进底稿 */
    private String basis;

    /** 结论说明，含上限与可容忍率的比较 */
    private String conclusionText;

    private Long createBy;
}
