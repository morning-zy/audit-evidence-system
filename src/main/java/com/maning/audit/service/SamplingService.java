package com.maning.audit.service;

import com.maning.audit.dto.SamplingItemDTO;
import com.maning.audit.dto.SamplingPlanDTO;
import com.maning.audit.vo.SamplingVO;

import java.util.List;

/**
 * 审计抽样服务
 *
 * <p>完整流程：建计划（算样本量）→ 录入样本 → 出结论。
 * 样本量的计算和结论的推断都在 {@code SamplingCalculator} 里，
 * 本层只负责：参数校验、持久化、状态约束、以及三种抽样方式的差异化处理。
 */
public interface SamplingService {

    /** 创建抽样计划，创建时即按二项分布算出样本量 */
    SamplingVO createPlan(SamplingPlanDTO dto);

    /** 详情（含样本明细） */
    SamplingVO detail(Long id);

    /** 某项目下的全部抽样计划 */
    List<SamplingVO> listByProject(Long projectId);

    /** 批量录入样本检查结果；检查完成或偏差超标时自动出结论 */
    SamplingVO addItems(Long id, List<SamplingItemDTO> items);

    /** 修改单个样本的偏差判定（事后发现偏差时用） */
    SamplingVO markItem(Long itemId, Boolean deviation, String deviationDesc);

    /** 手动出具结论；样本未抽满且偏差未超标时拒绝 */
    SamplingVO conclude(Long id);

    /** 删除抽样计划及其样本明细 */
    void delete(Long id);
}
