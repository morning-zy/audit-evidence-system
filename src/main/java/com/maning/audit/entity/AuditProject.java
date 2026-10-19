package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计项目
 *
 * <p>一个项目 = 一次独立的审计任务，是整个业务的主线容器。
 * 项目 -> 审计事项 -> 审计证据 是三层归属关系。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_project")
public class AuditProject extends BaseEntity {

    private String projectCode;

    private String projectName;

    /** FINANCIAL / IT_SYSTEM / COMPLIANCE / PERFORMANCE */
    private String auditType;

    private String auditedUnit;

    /** 项目负责人 */
    private Long leaderId;

    private java.time.LocalDate startDate;

    private java.time.LocalDate endDate;

    /** 0草稿 1进行中 2待复核 3已归档 */
    private Integer status;

    /** 1低 2中 3高 */
    private Integer riskLevel;

    private String description;

    private Long createBy;
}
