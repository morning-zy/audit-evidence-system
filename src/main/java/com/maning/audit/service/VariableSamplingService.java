package com.maning.audit.service;

import com.maning.audit.dto.VariableSamplingItemDTO;
import com.maning.audit.dto.VariableSamplingPlanDTO;
import com.maning.audit.vo.VariableSamplingVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 变量抽样服务（细节测试）。
 *
 * <p>流程比属性抽样多一步"试点"：
 * <pre>
 *   ① 建计划 —— 有预估标准差就直接算样本量；没有就先给 30 项试点
 *   ② 录入样本 —— 账面金额必填，审定金额可后补
 *   ③ 试点抽满后，用实际标准差重算样本量（可能要求扩大）
 *   ④ 全部检查完成后自动评价：错报上限 vs 可容忍错报
 * </pre>
 * 第③步是这个模块和属性抽样最本质的流程差异，也是实务中真正会发生的：
 * 先抽 30 项看看总体有多"散"，再决定要抽多少 —— 而不是拍一个数抽到底。
 */
public interface VariableSamplingService {

    /** 创建计划；未提供标准差时返回 30 项试点计划 */
    VariableSamplingVO createPlan(VariableSamplingPlanDTO dto);

    VariableSamplingVO detail(Long id);

    List<VariableSamplingVO> listByProject(Long projectId);

    /** 批量录入样本（账面必填、审定可空） */
    VariableSamplingVO addItems(Long id, List<VariableSamplingItemDTO> items);

    /** 回填/修改单条样本的审定金额；传 null 表示撤销检查 */
    VariableSamplingVO checkItem(Long itemId, BigDecimal auditedValue, String remark);

    /** 手动出具评价结论；未检查完时拒绝 */
    VariableSamplingVO evaluate(Long id);

    void delete(Long id);
}
