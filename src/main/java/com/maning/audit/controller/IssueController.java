package com.maning.audit.controller;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.IssueQuery;
import com.maning.audit.dto.IssueSaveDTO;
import com.maning.audit.service.IssueService;
import com.maning.audit.vo.IssueVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计事项接口
 */
@Tag(name = "03-审计事项", description = "项目下的具体审计点")
@RestController
@RequestMapping("/issue")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @Operation(summary = "分页查询事项")
    @GetMapping("/page")
    public Result<PageResult<IssueVO>> page(IssueQuery query) {
        return Result.success(issueService.page(query));
    }

    @Operation(summary = "事项详情")
    @GetMapping("/{id}")
    public Result<IssueVO> detail(@PathVariable Long id) {
        return Result.success(issueService.detail(id));
    }

    @Operation(summary = "查询某项目下全部事项")
    @GetMapping("/list")
    public Result<List<IssueVO>> listByProject(@RequestParam Long projectId) {
        return Result.success(issueService.listByProject(projectId));
    }

    @Log(module = "审计事项", value = "新增事项")
    @Operation(summary = "新增事项")
    @PostMapping
    public Result<Long> save(@Valid @RequestBody IssueSaveDTO dto) {
        return Result.success("创建成功", issueService.save(dto));
    }

    @Log(module = "审计事项", value = "修改事项")
    @Operation(summary = "修改事项")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody IssueSaveDTO dto) {
        issueService.update(dto);
        return Result.success("修改成功", null);
    }

    @Log(module = "审计事项", value = "删除事项")
    @Operation(summary = "删除事项", description = "事项下有证据时不允许删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        issueService.delete(id);
        return Result.success("删除成功", null);
    }

    @Log(module = "审计事项", value = "事项状态流转")
    @Operation(summary = "事项状态流转",
            description = "0待执行 1证据收集中 2已完成；标记完成前必须所有证据已确认")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        issueService.changeStatus(id, status);
        return Result.success("状态更新成功", null);
    }
}
