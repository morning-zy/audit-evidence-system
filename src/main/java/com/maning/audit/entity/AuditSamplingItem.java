package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 抽样样本明细 —— 每一条对应一张实际检查过的凭证。
 *
 * <p><b>deviation 的语义要说清楚，这是整个模块最容易被误解的字段</b>：
 * 它表示"这个样本是否偏离了内部控制的要求"，而不是"这笔金额对不对"。
 *
 * <p>举例：制度要求"单笔 5 万元以上付款须经财务总监签字"，
 * 抽到的凭证金额 8 万但只有财务经理签字 —— 这是偏差。
 * 至于金额本身是不是记错了科目，那是实质性程序要查的事，不在控制测试范围内。
 *
 * <p>混淆两者会导致结论失效：如果拿"金额是否错误"当偏差，
 * 测的其实是财务报表认定，控制测试的样本量公式也就不再适用了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_sampling_item")
public class AuditSamplingItem extends BaseEntity {

    private Long samplingId;

    /** 样本序号，从 1 开始，对应底稿上的编号 */
    private Integer seqNo;

    /** 凭证号/单据号，审计留痕的最小单位 —— 必须能追回到原始凭证 */
    private String voucherNo;

    private LocalDate voucherDate;

    private BigDecimal amount;

    /**
     * 是否存在偏差 1 有偏差。
     *
     * <p>updateStrategy = ALWAYS 是必须的：撤销检查时要把 deviation 改回 NULL，
     * MyBatis-Plus 默认的 NOT_NULL 策略会把 null 字段从 UPDATE 语句里剔掉，
     * 结果是"接口返回成功、数据库里还是偏差"，且 SQL 日志里看不出任何异常。
     * 这三个字段的语义里 NULL 是有意义的（未检查 / 无描述 / 未检查时间），
     * 所以必须允许显式写入 null。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean deviation;

    /** 偏差描述，有偏差时必填（Service 层校验） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String deviationDesc;

    /** 检查时间；为 NULL 表示样本已抽取但尚未检查 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime checkedTime;
}
