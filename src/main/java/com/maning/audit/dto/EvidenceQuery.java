package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 证据分页查询条件
 */
@Data
@Schema(description = "证据分页查询条件")
public class EvidenceQuery extends PageQuery {

    @Schema(description = "所属项目ID")
    private Long projectId;

    @Schema(description = "所属事项ID")
    private Long issueId;

    @Schema(description = "状态 0草稿 1待复核 2已确认 3已驳回")
    private Integer status;

    @Schema(description = "证据类型")
    private String evidenceType;

    @Schema(description = "可靠性等级")
    private Integer reliability;

    @Schema(description = "是否只看我提交的")
    private Boolean onlyMine;

    @Schema(description = "是否只看我的待办（只查我作为主管可复核的）")
    private Boolean todoOnly;
}
