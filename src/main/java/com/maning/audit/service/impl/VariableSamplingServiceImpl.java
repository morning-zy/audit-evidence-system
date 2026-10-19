package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.common.util.VariableSamplingCalculator;
import com.maning.audit.dto.VariableSamplingItemDTO;
import com.maning.audit.dto.VariableSamplingPlanDTO;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.entity.AuditVariableSampling;
import com.maning.audit.entity.AuditVariableSamplingItem;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.AuditVariableSamplingItemMapper;
import com.maning.audit.mapper.AuditVariableSamplingMapper;
import com.maning.audit.service.VariableSamplingService;
import com.maning.audit.vo.VariableSamplingVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 变量抽样服务实现（细节测试）。
 *
 * <p><b>这里最关键的一段逻辑是"试点样本"</b>，它也是变量抽样和属性抽样在流程上的分水岭：
 *
 * <pre>
 * 属性抽样：可容忍偏差率、预期偏差率都是比例，凭经验就能填，建计划时样本量一次算定。
 * 变量抽样：公式里的 s（单笔金额标准差）没人能凭经验填 —— 存货每项几千到几十万，
 *           标准差是 3 万还是 30 万，只有抽过才知道。
 * </pre>
 *
 * <p>所以实务做法是两阶段：先抽 30 项试点算出 s，再算正式样本量。
 * 本类把这两个阶段做成自动衔接的：试点抽满时用实际 s 重算，
 * 不够就<b>扩大</b>样本量，而不是拿 30 项硬下结论。
 *
 * <p>另一个必须守住的口径：<b>未检查（审定金额为空）≠ 审定金额为零</b>。
 * 前者不计入已检查数，后者是一条全额错报。混在一起会让错报上限虚高几十倍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariableSamplingServiceImpl implements VariableSamplingService {

    private final AuditVariableSamplingMapper samplingMapper;
    private final AuditVariableSamplingItemMapper itemMapper;
    private final AuditProjectMapper projectMapper;

    private static final String NO_PREFIX = "VS";

    /** 金额统一 2 位小数，和 DECIMAL(18,2) 对齐 */
    private static final int AMOUNT_SCALE = 2;

    /* ==================== 创建 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VariableSamplingVO createPlan(VariableSamplingPlanDTO dto) {
        AuditProject project = projectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "所属审计项目不存在");
        }
        if (Enums.ProjectStatus.ARCHIVED.getCode().equals(project.getStatus())) {
            throw new BusinessException(ResultCode.PROJECT_NOT_EDITABLE, "已归档项目不能新增抽样计划");
        }

        String method = parseMethod(dto.getMethod());
        Enums.ConfidenceLevel level = parseConfidence(dto.getConfidenceLevel());

        double bookValue = dto.getPopulationBookValue().doubleValue();
        double tolerable = dto.getTolerableMisstatement().doubleValue();
        Double assumed = dto.getAssumedStdDev() == null ? null : dto.getAssumedStdDev().doubleValue();

        VariableSamplingCalculator.Plan plan;
        try {
            plan = VariableSamplingCalculator.plan(dto.getPopulationSize(), bookValue,
                    tolerable, assumed, level.getRisk());
        } catch (IllegalArgumentException e) {
            // 算法层的 IllegalArgumentException 必须转成业务异常：
            // 直接冒泡会被全局处理器当成系统错误（5000），前端只能看到"系统繁忙"，
            // 而实际上是"可容忍错报填得比账面金额还大"这种一眼能改的问题。
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL, e.getMessage());
        }

        AuditVariableSampling s = new AuditVariableSampling();
        s.setSamplingNo(generateSamplingNo());
        s.setProjectId(dto.getProjectId());
        s.setIssueId(dto.getIssueId());
        s.setAccountName(dto.getAccountName());
        s.setPopulationBookValue(dto.getPopulationBookValue());
        s.setPopulationSize(dto.getPopulationSize());
        s.setTolerableMisstatement(dto.getTolerableMisstatement());
        s.setExpectedMisstatement(dto.getExpectedMisstatement() == null
                ? BigDecimal.ZERO : dto.getExpectedMisstatement());
        s.setAssumedStdDev(dto.getAssumedStdDev());
        s.setConfidenceLevel(level.name());
        s.setMethod(method);
        s.setSampleSize(plan.sampleSize());
        s.setIsPilot(plan.pilot());
        s.setCheckedCount(0);
        s.setConclusion(Enums.VariableSamplingConclusion.PENDING.getCode());
        s.setBasis(plan.basis());
        s.setCreateBy(UserContext.getUserId());

        samplingMapper.insert(s);
        log.info("创建变量抽样计划 {}：总体 {} 项 / {} 元，样本量 {}，方法 {}",
                s.getSamplingNo(), s.getPopulationSize(), s.getPopulationBookValue(),
                s.getSampleSize(), s.getMethod());

        return toVO(s, List.of());
    }

    /* ==================== 查询 ==================== */

    @Override
    public VariableSamplingVO detail(Long id) {
        AuditVariableSampling s = requirePlan(id);
        return toVO(s, listItems(id));
    }

    @Override
    public List<VariableSamplingVO> listByProject(Long projectId) {
        return samplingMapper.selectList(Wrappers.<AuditVariableSampling>lambdaQuery()
                        .eq(AuditVariableSampling::getProjectId, projectId)
                        .orderByDesc(AuditVariableSampling::getId))
                .stream().map(s -> toVO(s, null)).toList();
    }

    /* ==================== 录入与检查 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VariableSamplingVO addItems(Long id, List<VariableSamplingItemDTO> items) {
        AuditVariableSampling s = requirePlan(id);
        assertNotEvaluated(s);

        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL, "至少录入一条样本");
        }

        long existing = itemMapper.selectCount(Wrappers.<AuditVariableSamplingItem>lambdaQuery()
                .eq(AuditVariableSamplingItem::getSamplingId, id));
        if (existing + items.size() > s.getSampleSize()) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_ITEM_EXCEED, String.format(
                    "计划抽取 %d 项，已录入 %d 项，本次 %d 项，超出计划",
                    s.getSampleSize(), existing, items.size()));
        }

        int seq = (int) existing;
        for (VariableSamplingItemDTO dto : items) {
            seq++;
            // List 参数上的 @Valid 不会逐元素校验，必填项必须在 Service 兜一层，
            // 且提示里带上序号，否则 20 条样本里漏一条，用户根本不知道该改哪条
            if (!StringUtils.hasText(dto.getVoucherNo())) {
                throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL,
                        "第 " + seq + " 条样本缺少凭证号");
            }
            if (dto.getBookValue() == null) {
                throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL,
                        "凭证 " + dto.getVoucherNo() + " 缺少账面金额");
            }
            requireRemarkIfMisstated(dto.getVoucherNo(), dto.getBookValue(),
                    dto.getAuditedValue(), dto.getRemark());

            AuditVariableSamplingItem item = new AuditVariableSamplingItem();
            item.setSamplingId(id);
            item.setSeqNo(seq);
            item.setVoucherNo(dto.getVoucherNo());
            item.setVoucherDate(dto.getVoucherDate());
            item.setBookValue(dto.getBookValue());
            item.setAuditedValue(dto.getAuditedValue());
            item.setMisstatement(misstatementOf(dto.getBookValue(), dto.getAuditedValue()));
            item.setRemark(dto.getRemark());
            item.setCheckedTime(dto.getAuditedValue() == null ? null : LocalDateTime.now());
            itemMapper.insert(item);
        }

        refresh(s);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VariableSamplingVO checkItem(Long itemId, BigDecimal auditedValue, String remark) {
        AuditVariableSamplingItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "样本不存在");
        }
        AuditVariableSampling s = requirePlan(item.getSamplingId());
        assertNotEvaluated(s);

        requireRemarkIfMisstated(item.getVoucherNo(), item.getBookValue(), auditedValue, remark);

        item.setAuditedValue(auditedValue);
        item.setMisstatement(misstatementOf(item.getBookValue(), auditedValue));
        item.setRemark(remark);
        item.setCheckedTime(auditedValue == null ? null : LocalDateTime.now());
        itemMapper.updateById(item);

        refresh(s);
        return detail(s.getId());
    }

    /* ==================== 评价 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VariableSamplingVO evaluate(Long id) {
        AuditVariableSampling s = requirePlan(id);
        assertNotEvaluated(s);
        if (s.getCheckedCount() < s.getSampleSize()) {
            throw new BusinessException(ResultCode.DATA_STATE_ERROR, String.format(
                    "样本尚未检查完成（已检查 %d / 应抽 %d），不能出具评价结论",
                    s.getCheckedCount(), s.getSampleSize()));
        }
        applyEvaluation(s, listItems(id));
        samplingMapper.updateById(s);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePlan(id);
        itemMapper.delete(Wrappers.<AuditVariableSamplingItem>lambdaQuery()
                .eq(AuditVariableSamplingItem::getSamplingId, id));
        samplingMapper.deleteById(id);
    }

    /* ==================== 核心：重算与阶段推进 ==================== */

    /**
     * 每次录入/修改样本后重算进度，并决定下一步做什么。
     *
     * <p>三条分支，顺序不能乱：
     * <ol>
     *   <li><b>试点阶段抽满</b>：用实际标准差重算正式样本量。够就转入评价，不够就扩样本。</li>
     *   <li><b>样本抽满</b>：正式评价。</li>
     *   <li><b>还没抽满</b>：只更新进度，不推任何数字。</li>
     * </ol>
     * 第 1 条必须放在第 2 条前面：试点样本量往往就是 30，抽满 30 项时两个条件同时成立，
     * 若先判"抽满"，就会拿 30 项直接评价 —— 这正是"抽了几十项就下结论"的错误做法。
     */
    private void refresh(AuditVariableSampling s) {
        List<AuditVariableSamplingItem> items = listItems(s.getId());
        int checked = 0;
        for (AuditVariableSamplingItem item : items) {
            if (item.getAuditedValue() != null) {
                checked++;
            }
        }
        s.setCheckedCount(checked);

        if (Boolean.TRUE.equals(s.getIsPilot()) && checked >= s.getSampleSize()
                && s.getSampleSize() < s.getPopulationSize()) {
            expandAfterPilot(s, items);
            return;
        }

        if (checked >= s.getSampleSize()) {
            applyEvaluation(s, items);
        } else {
            // 样本没抽满时把结论退回未评价，并清掉可能残留的旧数字。
            // 不清的话，用户撤销一条检查后，页面会继续显示上一次算出来的错报上限，
            // 看起来像是"基于当前样本算的"，实际上样本已经变了。
            s.setConclusion(Enums.VariableSamplingConclusion.PENDING.getCode());
            s.setProjectedMisstatement(null);
            s.setPrecisionAmount(null);
            s.setUpperMisstatement(null);
            s.setSampleStdDev(null);
            s.setConclusionText(null);
        }

        samplingMapper.updateById(s);
    }

    /**
     * 试点结束：用实际标准差重算正式样本量。
     *
     * <p>如果重算结果比已抽的多，就扩大样本量并保持"未评价"状态 ——
     * 这不是系统在刁难用户，而是 30 项试点证明不了 300 项总体。
     * 如果重算结果小于等于已抽的，说明试点样本已经够了，直接进入评价。
     */
    private void expandAfterPilot(AuditVariableSampling s, List<AuditVariableSamplingItem> items) {
        List<VariableSamplingCalculator.SampleItem> checkedItems = toSampleItems(items);
        VariableSamplingCalculator.Method method = calculatorMethodOf(s.getMethod());
        double stdDev = VariableSamplingCalculator.stdDevFor(checkedItems, method);

        VariableSamplingCalculator.Plan replan = VariableSamplingCalculator.replan(
                s.getPopulationSize(), s.getPopulationBookValue().doubleValue(),
                s.getTolerableMisstatement().doubleValue(), stdDev,
                Enums.ConfidenceLevel.of(s.getConfidenceLevel()).getRisk());

        s.setIsPilot(false);
        s.setSampleStdDev(scale(stdDev));

        if (replan.sampleSize() > s.getCheckedCount()) {
            s.setSampleSize(replan.sampleSize());
            s.setConclusion(Enums.VariableSamplingConclusion.PENDING.getCode());
            s.setProjectedMisstatement(null);
            s.setPrecisionAmount(null);
            s.setUpperMisstatement(null);
            s.setBasis(replan.basis());
            s.setConclusionText(String.format(
                    "试点样本 %d 项检查完成，实际标准差 %.2f 元，据此重算正式样本量为 %d 项，"
                            + "需继续抽取 %d 项后方可评价。",
                    s.getCheckedCount(), stdDev, replan.sampleSize(),
                    replan.sampleSize() - s.getCheckedCount()));
            log.info("变量抽样 {} 试点结束：s={}，样本量 30 → {}",
                    s.getSamplingNo(), stdDev, replan.sampleSize());
        } else {
            // 试点样本已足够，不必再抽；样本量收敛到实际检查数，避免出现"应抽 30、实抽 30、还差 0"
            s.setSampleSize(Math.max(replan.sampleSize(), s.getCheckedCount()));
            s.setBasis(replan.basis());
            applyEvaluation(s, items);
        }

        samplingMapper.updateById(s);
    }

    /**
     * 执行统计推断并落库。
     *
     * <p>只把<b>已检查</b>的样本交给算法层。未检查的样本传进去会被当成审定金额 0，
     * 每一条都会变成全额错报，错报上限直接爆表。
     */
    private void applyEvaluation(AuditVariableSampling s, List<AuditVariableSamplingItem> items) {
        List<VariableSamplingCalculator.SampleItem> checkedItems = toSampleItems(items);
        if (checkedItems.size() < 2) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_ITEM_INCOMPLETE,
                    "已检查样本不足 2 项，标准差无定义，无法评价");
        }

        VariableSamplingCalculator.Result r;
        try {
            r = VariableSamplingCalculator.evaluate(checkedItems, s.getPopulationSize(),
                    s.getPopulationBookValue().doubleValue(),
                    s.getTolerableMisstatement().doubleValue(),
                    calculatorMethodOf(s.getMethod()),
                    Enums.ConfidenceLevel.of(s.getConfidenceLevel()).getRisk());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL, e.getMessage());
        }

        s.setCheckedCount(r.sampleSize());
        s.setSampleStdDev(scale(r.stdDev()));
        s.setProjectedMisstatement(scale(r.projectedMisstatement()));
        s.setPrecisionAmount(scale(r.precision()));
        s.setUpperMisstatement(scale(r.upperMisstatement()));
        s.setConclusion(r.acceptable()
                ? Enums.VariableSamplingConclusion.ACCEPTABLE.getCode()
                : Enums.VariableSamplingConclusion.NOT_ACCEPTABLE.getCode());
        s.setConclusionText(r.conclusion());
    }

    /* ==================== 辅助 ==================== */

    private AuditVariableSampling requirePlan(Long id) {
        AuditVariableSampling s = samplingMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_NOT_FOUND, "变量抽样计划不存在或已删除");
        }
        return s;
    }

    private void assertNotEvaluated(AuditVariableSampling s) {
        if (!Enums.VariableSamplingConclusion.PENDING.getCode().equals(s.getConclusion())) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_ALREADY_CONCLUDED,
                    "该抽样已出具评价结论，如需继续检查请新建计划");
        }
    }

    /**
     * 有错报就必须写明原因。
     *
     * <p>这条规则不是形式主义：错报金额本身只说明"差了多少"，
     * 复核人和报告使用人真正要知道的是"为什么差"——
     * 是计价错误、截止性错误，还是单纯的录入笔误。
     * 没有原因的错报在底稿里等于没有证据。
     */
    private void requireRemarkIfMisstated(String voucherNo, BigDecimal bookValue,
                                          BigDecimal auditedValue, String remark) {
        if (auditedValue == null) {
            return;
        }
        BigDecimal diff = bookValue.subtract(auditedValue);
        if (diff.compareTo(BigDecimal.ZERO) != 0 && !StringUtils.hasText(remark)) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL,
                    "凭证 " + voucherNo + " 审定金额与账面不一致（错报 " + diff.toPlainString()
                            + " 元），必须填写检查说明");
        }
    }

    private BigDecimal misstatementOf(BigDecimal bookValue, BigDecimal auditedValue) {
        if (auditedValue == null) {
            return null;
        }
        return bookValue.subtract(auditedValue).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private List<AuditVariableSamplingItem> listItems(Long samplingId) {
        return itemMapper.selectList(Wrappers.<AuditVariableSamplingItem>lambdaQuery()
                .eq(AuditVariableSamplingItem::getSamplingId, samplingId)
                .orderByAsc(AuditVariableSamplingItem::getSeqNo));
    }

    private List<VariableSamplingCalculator.SampleItem> toSampleItems(
            List<AuditVariableSamplingItem> items) {
        List<VariableSamplingCalculator.SampleItem> list = new ArrayList<>();
        for (AuditVariableSamplingItem item : items) {
            if (item.getAuditedValue() != null) {
                list.add(new VariableSamplingCalculator.SampleItem(
                        item.getBookValue().doubleValue(), item.getAuditedValue().doubleValue()));
            }
        }
        return list;
    }

    private VariableSamplingCalculator.Method calculatorMethodOf(String method) {
        return switch (method) {
            case "MEAN_PER_UNIT" -> VariableSamplingCalculator.Method.MEAN_PER_UNIT;
            case "RATIO" -> VariableSamplingCalculator.Method.RATIO;
            default -> VariableSamplingCalculator.Method.DIFFERENCE;
        };
    }

    private String parseMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return Enums.VariableSamplingMethod.DIFFERENCE.getCode();
        }
        if (!Enums.VariableSamplingMethod.isValid(method)) {
            throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL,
                    "估计方法只能是 MEAN_PER_UNIT（均值估计）、DIFFERENCE（差额估计）或 RATIO（比率估计）");
        }
        return method;
    }

    /**
     * 严格解析置信水平，不用 {@code Enums.ConfidenceLevel.of()} 的静默兜底：
     * 前端传 "95" 会被当成 95%，看起来能用，实际上参数没被理解 ——
     * 审计参数不接受"猜一个默认值"。
     */
    private Enums.ConfidenceLevel parseConfidence(String name) {
        if (!StringUtils.hasText(name)) {
            return Enums.ConfidenceLevel.NINETY_FIVE;
        }
        for (Enums.ConfidenceLevel c : Enums.ConfidenceLevel.values()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new BusinessException(ResultCode.VARIABLE_SAMPLING_PARAM_ILLEGAL,
                "置信水平只能是 NINETY_FIVE（95%）或 NINETY（90%）");
    }

    private String generateSamplingNo() {
        String prefix = NO_PREFIX + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String max = samplingMapper.selectMaxSamplingNo(prefix);
        long next = 1L;
        if (StringUtils.hasText(max) && max.length() > prefix.length()) {
            try {
                next = Long.parseLong(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException e) {
                log.warn("变量抽样编号解析失败，从 1 重新开始: {}", max);
            }
        }
        return prefix + String.format("%04d", next);
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private VariableSamplingVO toVO(AuditVariableSampling s, List<AuditVariableSamplingItem> items) {
        VariableSamplingVO vo = new VariableSamplingVO();
        BeanUtils.copyProperties(s, vo);

        vo.setConfidenceDesc(Enums.ConfidenceLevel.of(s.getConfidenceLevel()).getDesc());
        vo.setMethodDesc(Enums.VariableSamplingMethod.descOf(s.getMethod()));
        vo.setMethodNote(Enums.VariableSamplingMethod.noteOf(s.getMethod()));
        vo.setConclusionDesc(Enums.VariableSamplingConclusion.descOf(s.getConclusion()));

        vo.setRemaining(Math.max(0, s.getSampleSize() - s.getCheckedCount()));
        vo.setReadyToConclude(s.getCheckedCount() >= s.getSampleSize());

        if (items != null) {
            int misstated = 0;
            for (AuditVariableSamplingItem item : items) {
                if (item.getMisstatement() != null && item.getMisstatement().compareTo(BigDecimal.ZERO) != 0) {
                    misstated++;
                }
            }
            vo.setMisstatementCount(misstated);
            vo.setItems(items.stream().map(i -> {
                VariableSamplingVO.ItemVO iv = new VariableSamplingVO.ItemVO();
                BeanUtils.copyProperties(i, iv);
                return iv;
            }).toList());
        } else {
            vo.setMisstatementCount(0);
        }
        return vo;
    }
}
