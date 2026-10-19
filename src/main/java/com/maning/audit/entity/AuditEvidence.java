package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计证据（核心业务表）
 *
 * <p>状态机： 草稿(0) -> 待复核(1) -> 已确认(2)
 *                              └-> 已驳回(3) -> 待复核(1)
 *
 * <p>为什么要有 submit_by / review_by 两个人员字段：
 * 审计准则要求"编制与复核分离"，同一个人不能既编制又复核。
 * 这是业务规则，不是技术选择，体现在 Service 层校验里。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_evidence")
public class AuditEvidence extends BaseEntity {

    private String evidenceNo;

    private Long projectId;

    private Long issueId;

    private String evidenceName;

    /** DOCUMENT / ELECTRONIC / INTERVIEW / OBSERVATION / ANALYTICS */
    private String evidenceType;

    private String source;

    /** 可靠性 1低(D) 2中(C) 3高(B) 4很高(A)
     *  <p>注意：这个值<b>不再由用户填写</b>，而是由 EvidenceReliabilityEvaluator
     *  按准则 1301 号第三条的五原则，根据下面五个维度自动算出。
     *  保留这个字段是为了排序、统计和前端展示，不是录入项。 */
    private Integer reliability;

    /* ============ 以下五个字段对应《审计准则 1301 号》第三条可靠性五原则 ============ */

    /** 来源：EXTERNAL 外部独立 / INTERNAL 内部生成 / MANAGEMENT 管理层声明 */
    private String sourceType;

    /** 获取方式：DIRECT 审计人员直接获取 / INDIRECT 被审计单位提供 / INFERRED 推算 */
    private String acquireMethod;

    /** 存在形式：WRITTEN 书面 / ELECTRONIC 电子 / ORAL 口头 */
    private String formType;

    /** 原件性：ORIGINAL 原件 / SCANNED 扫描件 / COPY 复印件 */
    private String originality;

    /** 内部控制是否有效。仅对内部来源生效（准则第三条第二项） */
    private Boolean controlEffective;

    /** 可靠性判定依据（由计算器生成的一句话，写入底稿供复核人追溯） */
    private String reliabilityBasis;

    private String filePath;

    private Long fileSize;

    private String remark;

    /** 0草稿 1待详细复核 2已确认 3已驳回 4待一般复核 5待重点复核 */
    private Integer status;

    /**
     * 当前复核级别：0未提交，1待详细复核，2待一般复核，3待重点复核，4三级已完成。
     *
     * <p>与 status 的区别（这是三级复核里最容易搞混的地方）：
     * <ul>
     *   <li>status 说"这件事处于什么状态"，是给业务看的总览</li>
     *   <li>currentLevel 说"当前卡在第几级复核"，是流程进度的数字表达</li>
     * </ul>
     * 两者高度相关但不等价：已驳回时 status=3 而 currentLevel=0（退回起点）；
     * 三级全部通过时 status=2 而 currentLevel=4（走完了）。
     * 之所以两个都存，是因为"第几级"在列表排序、待办分组、统计时高频使用，
     * 每次都用 status 反推级别既啰嗦又容易漏分支。
     */
    private Integer currentLevel;

    private Long submitBy;

    private java.time.LocalDateTime submitTime;

    private Long reviewBy;

    private java.time.LocalDateTime reviewTime;

    private String reviewComment;
}
