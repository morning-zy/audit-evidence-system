package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * 审计项目 - 新增/修改 DTO
 */
@Data
@Schema(description = "审计项目保存请求")
public class ProjectSaveDTO {

    @Schema(description = "项目ID（修改时必传，新增时不传）")
    private Long id;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称最长128字符")
    @Schema(description = "项目名称", example = "2026年度财务收支合规性审计")
    private String projectName;

    @NotBlank(message = "审计类型不能为空")
    @Schema(description = "审计类型 FINANCIAL/IT_SYSTEM/COMPLIANCE/PERFORMANCE", example = "FINANCIAL")
    private String auditType;

    @NotBlank(message = "被审计单位不能为空")
    @Size(max = 128, message = "被审计单位最长128字符")
    @Schema(description = "被审计单位", example = "江苏华信实业有限公司")
    private String auditedUnit;

    @NotNull(message = "项目负责人不能为空")
    @Schema(description = "项目负责人用户ID", example = "2")
    private Long leaderId;

    @Schema(description = "计划开始日期", example = "2026-09-01")
    private LocalDate startDate;

    @Schema(description = "计划结束日期", example = "2026-11-30")
    private LocalDate endDate;

    @NotNull(message = "风险等级不能为空")
    @Min(value = 1, message = "风险等级取值为1-3")
    @Max(value = 3, message = "风险等级取值为1-3")
    @Schema(description = "风险等级 1低 2中 3高", example = "3")
    private Integer riskLevel;

    @Size(max = 512, message = "项目描述最长512字符")
    @Schema(description = "项目描述")
    private String description;
}
