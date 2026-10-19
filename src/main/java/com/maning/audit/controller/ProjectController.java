package com.maning.audit.controller;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.ProjectQuery;
import com.maning.audit.dto.ProjectSaveDTO;
import com.maning.audit.service.ProjectService;
import com.maning.audit.vo.ProjectVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计项目接口
 */
@Tag(name = "02-审计项目", description = "项目的增删改查与状态流转")
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "分页查询项目列表")
    @GetMapping("/page")
    public Result<PageResult<ProjectVO>> page(ProjectQuery query) {
        return Result.success(projectService.page(query));
    }

    @Operation(summary = "项目详情")
    @GetMapping("/{id}")
    public Result<ProjectVO> detail(@Parameter(description = "项目ID") @PathVariable Long id) {
        return Result.success(projectService.detail(id));
    }

    @Operation(summary = "项目进度列表（看板用）")
    @GetMapping("/progress")
    public Result<List<ProjectVO>> progress(
            @RequestParam(defaultValue = "8") Integer limit) {
        return Result.success(projectService.progressList(limit));
    }

    @Log(module = "审计项目", value = "新增项目")
    @Operation(summary = "新增项目")
    @PostMapping
    public Result<Long> save(@Valid @RequestBody ProjectSaveDTO dto) {
        return Result.success("创建成功", projectService.save(dto));
    }

    @Log(module = "审计项目", value = "修改项目")
    @Operation(summary = "修改项目")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody ProjectSaveDTO dto) {
        projectService.update(dto);
        return Result.success("修改成功", null);
    }

    @Log(module = "审计项目", value = "删除项目")
    @Operation(summary = "删除项目", description = "项目下有事项时不允许删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.success("删除成功", null);
    }

    @Log(module = "审计项目", value = "项目状态流转")
    @Operation(summary = "项目状态流转",
            description = "0草稿 1进行中 2待复核 3已归档；允许: 0->1, 1->2, 2->3, 2->1")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        projectService.changeStatus(id, status);
        return Result.success("状态更新成功", null);
    }
}
