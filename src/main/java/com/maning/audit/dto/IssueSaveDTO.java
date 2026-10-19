package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审计事项 - 新增/修改 DTO
 */
@Data
@Schema(description = "审计事项保存请求")
public class IssueSaveDTO {

    @Schema(description = "事项ID（修改时必传）")
    private Long id;

    @NotNull(message = "所属项目不能为空")
    @Schema(description = "所属项目ID", example = "1")
    private Long projectId;

    @NotBlank(message = "事项标题不能为空")
    @Size(max = 128, message = "事项标题最长128字符")
    @Schema(description = "事项标题", example = "存货账实差异核查")
    private String issueTitle;

    @Size(max = 512)
    @Schema(description = "风险点描述")
    private String riskPoint;

    @NotNull(message = "承办审计员不能为空")
    @Schema(description = "承办审计员用户ID", example = "3")
    private Long auditorId;
}
