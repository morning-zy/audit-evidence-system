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
 * 变量抽样样本明细。
 *
 * <p>比属性抽样的样本多两个金额字段：账面金额和审定金额。
 * 错报 = 账面 - 审定，这个差值再参与三种估计方法的计算。
 *
 * <p><b>为什么 audited_value 允许为空</b>：
 * 空值代表"这张凭证已经抽到、但还没检查"。审计现场就是这样 ——
 * 抽样的当天往往只能定下抽哪些，实际翻凭证要几天。
 * 如果把未检查硬写成 0，等于宣称"审定金额为零"，错报会瞬间爆表，
 * 整个推断结果都是假的。所以必须区分"没检查"和"检查后为零"。
 *
 * <p>三个字段都加了 {@code updateStrategy = ALWAYS}：
 * MyBatis-Plus 默认跳过 null 字段，撤销检查（把审定金额清空）时
 * 那条 UPDATE 里根本不会出现这些列，结果就是"点了撤销但页面没变"。
 * 这是只有真机点一遍才会发现的坑。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_variable_sampling_item")
public class AuditVariableSamplingItem extends BaseEntity {

    private Long samplingId;

    private Integer seqNo;

    private String voucherNo;

    private LocalDate voucherDate;

    /** 账面金额（被审计单位账上的数） */
    private BigDecimal bookValue;

    /** 审定金额（审计人员核实后的数）；NULL 表示尚未检查 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal auditedValue;

    /** 错报金额 = 账面 - 审定；正数高估、负数低估，未检查时为 NULL */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal misstatement;

    /** 检查说明/错报原因，有错报时必填 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    /** 检查时间；NULL 表示尚未检查 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime checkedTime;
}
