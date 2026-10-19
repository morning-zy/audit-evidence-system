package com.maning.audit.service;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.dto.EvidenceQuery;
import com.maning.audit.dto.EvidenceSaveDTO;
import com.maning.audit.vo.EvidenceVO;
import com.maning.audit.vo.FlowVO;

import java.util.List;

/**
 * 审计证据服务 —— 本项目的核心业务
 *
 * <p>状态机：
 * <pre>
 *   草稿(0) --提交--> 待复核(1) --通过--> 已确认(2)
 *                        └----驳回----> 已驳回(3) --提交--> 待复核(1)
 * </pre>
 */
public interface EvidenceService {

    PageResult<EvidenceVO> page(EvidenceQuery query);

    EvidenceVO detail(Long id);

    Long save(EvidenceSaveDTO dto);

    void update(EvidenceSaveDTO dto);

    void delete(Long id);

    /** 草稿/已驳回 -> 待复核 */
    void submit(Long id);

    /**
     * 按当前五维度重新计算全部证据的可靠性等级并落库。
     *
     * <p><b>为什么需要这个方法</b>：可靠性等级是五维度的派生值，
     * 一旦评级规则调整（比如阈值收紧），历史数据的等级就会和新规则不一致 ——
     * 表现为「同一条维度组合，老数据显示 B 级、新录入显示 C 级」，
     * 这会让复核人怀疑整个系统在乱算。
     *
     * <p>所以必须有一次性重算入口，而不是让人手工改数据库
     * （手工改库既容易漏，也没有审计轨迹）。
     *
     * @return 重算并发生变化的记录数
     */
    int recalculateAllReliability();

    /**
     * 复核通过，逐级推进。
     *
     * @return 流转后的去向描述，如「已流转至部门经理一般复核」或「三级复核已完成，证据生效」
     */
    String approve(Long id, String comment);

    /** 任意一级复核驳回，退回编制人 */
    void reject(Long id, String comment);

    /** 证据流转时间轴 */
    List<FlowVO> flowList(Long evidenceId);

    /**
     * 导出证据清单 Excel
     *
     * <p>返回值直接写进 HttpServletResponse 的输出流，
     * 所以这里传 response 进来而不是返回 byte[]：
     * 证据量大时（几万条）byte[] 会撑爆内存，直接流式写出更安全。
     */
    void exportExcel(EvidenceQuery query, jakarta.servlet.http.HttpServletResponse response);
}
