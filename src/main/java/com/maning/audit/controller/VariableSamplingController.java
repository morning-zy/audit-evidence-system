package com.maning.audit.controller;

import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.VariableSamplingItemDTO;
import com.maning.audit.dto.VariableSamplingPlanDTO;
import com.maning.audit.service.VariableSamplingService;
import com.maning.audit.vo.VariableSamplingVO;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * 变量抽样接口（细节测试）。
 *
 * <p>和属性抽样（{@code /sampling}）刻意分成两个资源，不合成一个"抽样"接口：
 * 两者的入参、结论口径、页面形态都不同 —— 属性抽样比的是偏差率，
 * 变量抽样比的是错报金额。合成一个接口只会逼着每个字段可空，
 * 前端还得靠 method 字段分支渲染，最后谁都不敢改。
 */
@Tag(name = "09-变量抽样", description = "细节测试：三种估计方法推断总体错报上限")
@RestController
@RequestMapping("/variable-sampling")
@RequiredArgsConstructor
public class VariableSamplingController {

    private final VariableSamplingService variableSamplingService;

    @Log(module = "变量抽样", value = "创建变量抽样计划")
    @Operation(summary = "创建变量抽样计划",
            description = "金额单位为元。assumedStdDev 留空时返回 30 项试点计划；"
                    + "method 可选 MEAN_PER_UNIT / DIFFERENCE / RATIO，默认差额估计")
    @PostMapping
    public Result<VariableSamplingVO> create(@Valid @RequestBody VariableSamplingPlanDTO dto) {
        return Result.success("变量抽样计划已创建", variableSamplingService.createPlan(dto));
    }

    @Operation(summary = "变量抽样详情", description = "含样本明细、错报推断结果与结论说明")
    @GetMapping("/{id}")
    public Result<VariableSamplingVO> detail(@PathVariable Long id) {
        return Result.success(variableSamplingService.detail(id));
    }

    @Operation(summary = "查询某项目下的变量抽样计划列表")
    @GetMapping("/list")
    public Result<List<VariableSamplingVO>> listByProject(@RequestParam Long projectId) {
        return Result.success(variableSamplingService.listByProject(projectId));
    }

    @Log(module = "变量抽样", value = "录入样本")
    @Operation(summary = "批量录入样本",
            description = "账面金额必填，审定金额可空（表示尚未检查）；"
                    + "审定金额与账面不一致时必须填检查说明")
    @PostMapping("/{id}/items")
    public Result<VariableSamplingVO> addItems(@PathVariable Long id,
                                               @Valid @RequestBody List<VariableSamplingItemDTO> items) {
        return Result.success("样本已录入", variableSamplingService.addItems(id, items));
    }

    @Log(module = "变量抽样", value = "回填检查结果")
    @Operation(summary = "回填单条样本的审定金额",
            description = "auditedValue 留空表示撤销检查（回到未检查状态）")
    @PutMapping("/item/{itemId}")
    public Result<VariableSamplingVO> checkItem(@PathVariable Long itemId,
                                                @RequestParam(required = false) BigDecimal auditedValue,
                                                @RequestParam(required = false) String remark) {
        return Result.success("样本已更新", variableSamplingService.checkItem(itemId, auditedValue, remark));
    }

    @Log(module = "变量抽样", value = "出具评价结论")
    @Operation(summary = "出具评价结论",
            description = "样本未检查完时拒绝，避免用不完整的样本推断总体错报")
    @PostMapping("/{id}/evaluate")
    public Result<VariableSamplingVO> evaluate(@PathVariable Long id) {
        return Result.success("评价结论已出具", variableSamplingService.evaluate(id));
    }

    @Log(module = "变量抽样", value = "删除变量抽样计划")
    @Operation(summary = "删除变量抽样计划", description = "连同样本明细一起逻辑删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        variableSamplingService.delete(id);
        return Result.success("删除成功", null);
    }
}
