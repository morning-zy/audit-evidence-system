package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目列表/详情 VO
 */
@Data
@Schema(description = "审计项目")
public class ProjectVO {

    private Long id;
    private String projectCode;
    private String projectName;
    private String auditType;
    private String auditTypeName;
    private String auditedUnit;
    private Long leaderId;
    private String leaderName;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer status;
    private String statusName;
    private Integer riskLevel;
    private String riskLevelName;
    private String description;
    private LocalDateTime createTime;

    /* ---------- 聚合统计字段（列表页一并带出，避免前端二次请求） ---------- */
    @Schema(description = "事项总数")
    private Integer issueTotal;
    @Schema(description = "已完成事项数")
    private Integer issueCompleted;
    @Schema(description = "证据总数")
    private Integer evidenceTotal;
    @Schema(description = "已确认证据数")
    private Integer evidenceConfirmed;
    @Schema(description = "进度百分比")
    private Integer progress;
}
