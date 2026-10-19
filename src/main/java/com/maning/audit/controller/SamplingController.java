package com.maning.audit.controller;

import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.SamplingItemDTO;
import com.maning.audit.dto.SamplingPlanDTO;
import com.maning.audit.service.SamplingService;
import com.maning.audit.vo.SamplingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计抽样接口
 *
 * <p>使用顺序是固定的三步，接口也按这个顺序排列：
 * ① 建计划 → 系统算出样本量；② 录样本 → 逐条记录检查结果；③ 出结论。
 * 前两步之间可以反复补录，第二步和第三步之间不能回头 ——
 * 一旦出了结论，样本就锁死了，要改只能新建计划。
 * 这是审计留痕的要求：结论对应的样本集合必须固定，不能事后替换。
 */
@Tag(name = "08-审计抽样", description = "控制测试抽样：样本量计算与偏差率推断")
@RestController
@RequestMapping("/sampling")
@RequiredArgsConstructor
public class SamplingController {

    private final SamplingService samplingService;

    @Log(module = "审计抽样", value = "创建抽样计划")
    @Operation(summary = "创建抽样计划",
            description = "按二项分布计算样本量。比率用小数：5% 传 0.05。"
                    + "预期偏差率留空按 0 处理（最保守）。")
    @PostMapping
    public Result<SamplingVO> create(@Valid @RequestBody SamplingPlanDTO dto) {
        return Result.success("抽样计划已创建", samplingService.createPlan(dto));
    }

    @Operation(summary = "抽样计划详情", description = "含样本明细、统计进度和结论")
    @GetMapping("/{id}")
    public Result<SamplingVO> detail(@PathVariable Long id) {
        return Result.success(samplingService.detail(id));
    }

    @Operation(summary = "查询某项目下的抽样计划列表")
    @GetMapping("/list")
    public Result<List<SamplingVO>> listByProject(@RequestParam Long projectId) {
        return Result.success(samplingService.listByProject(projectId));
    }

    @Log(module = "审计抽样", value = "录入样本")
    @Operation(summary = "批量录入样本检查结果",
            description = "deviation=true 时 deviationDesc 必填。"
                    + "检查数达到样本量或偏差数超过允许值时会自动出结论。")
    @PostMapping("/{id}/items")
    public Result<SamplingVO> addItems(@PathVariable Long id,
                                       @Valid @RequestBody List<SamplingItemDTO> items) {
        return Result.success("样本已录入", samplingService.addItems(id, items));
    }

    @Log(module = "审计抽样", value = "修改样本判定")
    @Operation(summary = "修改单个样本的偏差判定",
            description = "用于事后复核时发现的新偏差；把 deviation 置空可撤销检查")
    @PutMapping("/item/{itemId}")
    public Result<SamplingVO> markItem(@PathVariable Long itemId,
                                       @RequestParam(required = false) Boolean deviation,
                                       @RequestParam(required = false) String deviationDesc) {
        return Result.success("样本已更新", samplingService.markItem(itemId, deviation, deviationDesc));
    }

    @Log(module = "审计抽样", value = "出具抽样结论")
    @Operation(summary = "手动出具结论",
            description = "样本未抽满且偏差未超标时拒绝，避免用不完整的样本推断总体")
    @PostMapping("/{id}/conclude")
    public Result<SamplingVO> conclude(@PathVariable Long id) {
        return Result.success("结论已出具", samplingService.conclude(id));
    }

    @Log(module = "审计抽样", value = "删除抽样计划")
    @Operation(summary = "删除抽样计划", description = "连同样本明细一起逻辑删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        samplingService.delete(id);
        return Result.success("删除成功", null);
    }
}
