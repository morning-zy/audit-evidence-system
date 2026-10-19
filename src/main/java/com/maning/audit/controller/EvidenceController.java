package com.maning.audit.controller;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.EvidenceQuery;
import com.maning.audit.dto.EvidenceSaveDTO;
import com.maning.audit.dto.ReviewDTO;
import com.maning.audit.service.EvidenceService;
import com.maning.audit.vo.EvidenceVO;
import com.maning.audit.vo.FlowVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计证据接口 —— 核心业务
 *
 * <p>RESTful 风格的状态流转为什么用 PUT /{id}/submit 而不是
 * POST /{id}/submit 或者 PUT /{id}?action=submit：
 * ① 用子资源路径表达"动作"，语义清晰
 * ② 用 PUT 表示幂等（重复提交应该被状态校验拦住，不会产生多次效果）
 * 严格来说动作型接口不算纯 REST，但这是业界最通用、最不别扭的写法。
 */
@Tag(name = "04-审计证据", description = "证据采集、提交、复核（状态机核心）")
@RestController
@RequestMapping("/evidence")
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService evidenceService;

    @Operation(summary = "分页查询证据",
            description = "支持按项目/事项/状态/类型/可靠性过滤；onlyMine=true 只看自己提交的；todoOnly=true 看待我复核的")
    @GetMapping("/page")
    public Result<PageResult<EvidenceVO>> page(EvidenceQuery query) {
        return Result.success(evidenceService.page(query));
    }

    @Operation(summary = "证据详情")
    @GetMapping("/{id}")
    public Result<EvidenceVO> detail(@PathVariable Long id) {
        return Result.success(evidenceService.detail(id));
    }

    @Log(module = "审计证据", value = "新增证据")
    @Operation(summary = "新增证据", description = "初始状态为草稿")
    @PostMapping
    public Result<Long> save(@Valid @RequestBody EvidenceSaveDTO dto) {
        return Result.success("创建成功", evidenceService.save(dto));
    }

    @Log(module = "审计证据", value = "修改证据")
    @Operation(summary = "修改证据", description = "只有草稿/已驳回状态可改")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody EvidenceSaveDTO dto) {
        evidenceService.update(dto);
        return Result.success("修改成功", null);
    }

    @Log(module = "审计证据", value = "删除证据")
    @Operation(summary = "删除证据", description = "只有草稿/已驳回状态可删")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        evidenceService.delete(id);
        return Result.success("删除成功", null);
    }

    @Log(module = "审计证据", value = "提交复核")
    @Operation(summary = "提交复核", description = "草稿/已驳回 -> 待复核；必须已上传附件")
    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        evidenceService.submit(id);
        return Result.success("已提交复核", null);
    }

    @Log(module = "审计证据", value = "复核通过")
    @Operation(summary = "复核通过（逐级推进）",
            description = "一级通过→待一般复核→二级通过→待重点复核→三级通过→已确认。"
                    + "每个级别由对应岗位操作：项目负责人/部门经理/主任会计师")
    @PutMapping("/{id}/approve")
    public Result<String> approve(@PathVariable Long id,
                                  @RequestBody(required = false) ReviewDTO dto) {
        String nextStage = evidenceService.approve(id, dto == null ? null : dto.getComment());
        return Result.success(nextStage, nextStage);
    }

    @Log(module = "审计证据", value = "复核驳回")
    @Operation(summary = "复核驳回", description = "任意一级驳回都退回编制人；复核意见必填")
    @PutMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @Valid @RequestBody ReviewDTO dto) {
        evidenceService.reject(id, dto.getComment());
        return Result.success("已驳回", null);
    }

    @Operation(summary = "证据流转时间轴", description = "完整留痕记录，按时间正序")
    @GetMapping("/{id}/flows")
    public Result<List<FlowVO>> flows(@PathVariable Long id) {
        return Result.success(evidenceService.flowList(id));
    }

    @Log(module = "审计证据", value = "导出证据清单")
    @Operation(summary = "导出证据清单 Excel", description = "按当前查询条件导出，最多5000条")
    @GetMapping("/export")
    public void export(EvidenceQuery query, jakarta.servlet.http.HttpServletResponse response) {
        evidenceService.exportExcel(query, response);
    }

    @Log(module = "审计证据", value = "重算可靠性等级")
    @Operation(summary = "按准则 1301 号五原则重算全部证据的可靠性等级",
            description = "评级规则调整后使用。返回发生变化的记录数")
    @PostMapping("/recalculate-reliability")
    public Result<Integer> recalculateReliability() {
        return Result.success(evidenceService.recalculateAllReliability());
    }
}
