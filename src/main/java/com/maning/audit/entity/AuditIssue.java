package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计事项
 *
 * <p>项目下的具体审计点，是证据的挂载容器。
 * 分层理由：审计实务里一个项目会拆成若干"审计事项"，每个事项独立取证，
 * 不能把证据直接挂在项目上——那样无法表达"这个证据证明的是哪个问题"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_issue")
public class AuditIssue extends BaseEntity {

    private Long projectId;

    private String issueNo;

    private String issueTitle;

    private String riskPoint;

    /** 承办审计员 */
    private Long auditorId;

    /** 0待执行 1证据收集中 2已完成 */
    private Integer status;

    /**
     * 证据数量冗余字段
     *
     * <p>为什么冗余：列表页要显示"每个事项有多少条证据"，
     * 如果每次都 count(*)，N 个事项就是 N+1 次查询。
     * 代价是新增/删除证据时要同步更新这个字段——用事务+行锁保证一致。
     * 这是"读多写少"场景下典型的反范式设计。
     */
    private Integer evidenceCount;
}
