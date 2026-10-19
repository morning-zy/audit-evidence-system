package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计事项 VO
 */
@Data
@Schema(description = "审计事项")
public class IssueVO {

    private Long id;
    private Long projectId;
    private String projectName;
    private String issueNo;
    private String issueTitle;
    private String riskPoint;
    private Long auditorId;
    private String auditorName;
    private Integer status;
    private String statusName;
    private Integer evidenceCount;
    private LocalDateTime createTime;
}
