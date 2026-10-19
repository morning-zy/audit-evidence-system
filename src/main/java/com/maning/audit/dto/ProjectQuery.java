package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 项目分页查询条件
 */
@Data
@Schema(description = "项目分页查询条件")
public class ProjectQuery extends PageQuery {

    @Schema(description = "状态 0草稿 1进行中 2待复核 3已归档")
    private Integer status;

    @Schema(description = "审计类型")
    private String auditType;

    @Schema(description = "风险等级")
    private Integer riskLevel;

    @Schema(description = "被审计单位（模糊）")
    private String auditedUnit;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "开始日期-起")
    private LocalDate beginDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "开始日期-止")
    private LocalDate endDate;
}
