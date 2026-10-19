package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据流转记录 VO
 */
@Data
@Schema(description = "证据流转记录")
public class FlowVO {

    private Long id;
    private Long evidenceId;
    private String action;
    private String actionName;
    private Integer fromStatus;
    private String fromStatusName;
    private Integer toStatus;
    private String toStatusName;

    @Schema(description = "本次操作所属复核级别 1详细 2一般 3重点，null=非复核动作")
    private Integer reviewLevel;

    @Schema(description = "复核级别中文名")
    private String reviewLevelName;

    private Long operatorId;
    private String operatorName;
    private String comment;
    private LocalDateTime createTime;
}
