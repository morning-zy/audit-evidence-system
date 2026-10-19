package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 变量抽样计划（细节测试）
 *
 * <p><b>和 {@link AuditSampling} 的区别必须一眼能看出来</b>：
 * 那张表测的是"控制有没有被执行"，落库的是偏差率；
 * 这张表测的是"金额对不对"，落库的是错报金额。
 * 两者都是抽样，但一个推比率、一个推金额，结论口径完全不同，
 * 所以没有合并成一张表 —— 合并只会逼着每个字段都允许为空，
 * 最后谁也不敢确定某个字段归哪套逻辑管。
 *
 * <p>金额一律用 DECIMAL(18,2)：审计报告上的数字要能和企业账面逐分对平，
 * 用 double 存 1,234,567.89 会变成 1234567.8899999999，累加几万条后
 * 差异肉眼可见。计算过程用 double（快且够用），落库前统一收敛到 2 位。
 *
 * <p>可追溯性要求同样体现在字段设计上：可容忍错报、置信水平、估计方法、
 * 样本量依据、结论说明全部落库。复核人看到"错报上限 43,000"时，
 * 必须能自己把这 43,000 复算出来，而不是只能相信系统。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_variable_sampling")
public class AuditVariableSampling extends BaseEntity {

    /** 计划编号，如 VS20260001 */
    private String samplingNo;

    private Long projectId;

    /** 关联的审计事项（通常是某个财务报表科目），可为空 */
    private Long issueId;

    /** 被测试的科目/项目名称，如"存货—原材料" */
    private String accountName;

    /* ============ 总体与抽样参数 ============ */

    /** 总体账面金额 */
    private BigDecimal populationBookValue;

    /** 总体项数 */
    private Integer populationSize;

    /** 可容忍错报（实际执行的重要性水平，或更低） */
    private BigDecimal tolerableMisstatement;

    /** 预期错报，用于判断是否需要扩大样本；不参与公式计算，仅作参考 */
    private BigDecimal expectedMisstatement;

    /** 预估单笔标准差；为空表示先做试点样本 */
    private BigDecimal assumedStdDev;

    /** 置信水平：NINETY_FIVE / NINETY */
    private String confidenceLevel;

    /** 估计方法：MEAN_PER_UNIT / DIFFERENCE / RATIO */
    private String method;

    /* ============ 抽样结果 ============ */

    /** 应抽取的样本量 */
    private Integer sampleSize;

    /** 是否为试点阶段（未提供标准差，先抽 30 项估 s） */
    private Boolean isPilot;

    /** 已检查样本数（审定金额已录入的数量） */
    private Integer checkedCount;

    /** 估计总体错报（点估计） */
    private BigDecimal projectedMisstatement;

    /** 抽样风险允许度（精度） */
    private BigDecimal precisionAmount;

    /** 错报上限 = 点估计 + 精度 */
    private BigDecimal upperMisstatement;

    /** 样本标准差，留痕用 */
    private BigDecimal sampleStdDev;

    /** 结论：0 未评价 / 1 可接受 / 2 不可接受 */
    private Integer conclusion;

    /** 样本量计算依据，直接打印进底稿 */
    private String basis;

    /** 结论说明 */
    private String conclusionText;

    private Long createBy;
}
