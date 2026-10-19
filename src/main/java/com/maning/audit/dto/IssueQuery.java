package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 事项分页查询条件
 */
@Data
@Schema(description = "事项分页查询条件")
public class IssueQuery extends PageQuery {

    @Schema(description = "所属项目ID")
    private Long projectId;

    @Schema(description = "状态 0待执行 1证据收集中 2已完成")
    private Integer status;

    @Schema(description = "承办审计员ID")
    private Long auditorId;
}
