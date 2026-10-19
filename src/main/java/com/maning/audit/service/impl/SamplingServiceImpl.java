package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.common.util.SamplingCalculator;
import com.maning.audit.dto.SamplingItemDTO;
import com.maning.audit.dto.SamplingPlanDTO;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.entity.AuditSampling;
import com.maning.audit.entity.AuditSamplingItem;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.AuditSamplingItemMapper;
import com.maning.audit.mapper.AuditSamplingMapper;
import com.maning.audit.service.SamplingService;
import com.maning.audit.vo.SamplingVO;
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
import java.util.List;

/**
 * 审计抽样服务实现。
 *
 * <p>本类最需要注意的是<b>三种抽样路径的区别</b>，它们的结论口径完全不同，
 * 混用会直接导致底稿站不住：
 *
 * <pre>
 * ① 统计抽样（STATISTICAL）
 *    有抽样设计，能算偏差率上限，结论可以推断总体。
 *
 * ② 判断抽样（JUDGMENTAL）
 *    没有概率抽样设计，<b>不能</b>给置信区间。仍然可以给"可信赖/不可信赖"的结论，
 *    但依据是职业判断和偏差数是否超标，不是统计推断。
 *    给它套一个"95% 置信"是伪造统计基础，复核时会被打回。
 *
 * ③ 全查（is_census = true）
 *    总体太小，全部检查了。此时偏差率就是总体真实偏差率，
 *    不存在"推断"这回事，也不该出现"95% 置信水平"的表述。
 * </pre>
 *
 * <p>这三种情况在代码里必须分开处理，不能用一个公式套到底 ——
 * 这也是这个模块比"填个样本量然后数偏差"复杂的地方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SamplingServiceImpl implements SamplingService {

    private final AuditSamplingMapper samplingMapper;
    private final AuditSamplingItemMapper itemMapper;
    private final AuditProjectMapper projectMapper;

    private static final String NO_PREFIX = "SP";

    /** 比率统一保留 4 位，和 DECIMAL(6,4) 对齐；四舍五入而不是截断，避免 5.00% 显示成 4.99% */
    private static final int RATE_SCALE = 4;

    /* ==================== 创建 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SamplingVO createPlan(SamplingPlanDTO dto) {
        AuditProject project = projectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "所属审计项目不存在");
        }
        if (Enums.ProjectStatus.ARCHIVED.getCode().equals(project.getStatus())) {
            throw new BusinessException(ResultCode.PROJECT_NOT_EDITABLE, "已归档项目不能新增抽样计划");
        }

        String method = parseMethod(dto.getMethod());
        Enums.ConfidenceLevel level = parseConfidence(dto.getConfidenceLevel());
        double tolerable = dto.getTolerableRate().doubleValue();
        double expected = dto.getExpectedRate() == null ? 0.0 : dto.getExpectedRate().doubleValue();

        SamplingCalculator.SamplingPlan plan;
        try {
            plan = SamplingCalculator.plan(dto.getPopulationSize(), tolerable, expected, level.getRisk());
        } catch (IllegalArgumentException e) {
            // 算法层抛的是 IllegalArgumentException，直接冒泡会被全局处理器当成系统错误(5000)。
            // 这些其实都是"用户填的参数有问题"，必须转成 3009，前端才能把提示显示在对应输入框旁。
            throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL, e.getMessage());
        }

        AuditSampling s = new AuditSampling();
        s.setSamplingNo(generateSamplingNo());
        s.setProjectId(dto.getProjectId());
        s.setIssueId(dto.getIssueId());
        s.setControlName(dto.getControlName());
        s.setPopulationSize(dto.getPopulationSize());
        s.setTolerableRate(dto.getTolerableRate());
        s.setExpectedRate(dto.getExpectedRate() == null ? BigDecimal.ZERO : dto.getExpectedRate());
        s.setConfidenceLevel(level.name());
        s.setMethod(method);
        s.setSampleSize(plan.sampleSize());
        s.setAllowedDeviations(plan.allowedDeviations());
        s.setIsCensus(plan.census());
        s.setCheckedCount(0);
        s.setDeviationCount(0);
        s.setConclusion(Enums.SamplingConclusion.PENDING.getCode());
        s.setBasis(plan.basis());
        s.setCreateBy(UserContext.getUserId());

        samplingMapper.insert(s);
        log.info("创建抽样计划 {}：总体 {} 项，样本量 {}，允许偏差 {}",
                s.getSamplingNo(), s.getPopulationSize(), s.getSampleSize(), s.getAllowedDeviations());

        return toVO(s, List.of());
    }

    /* ==================== 查询 ==================== */

    @Override
    public SamplingVO detail(Long id) {
        AuditSampling s = requirePlan(id);
        return toVO(s, listItems(id));
    }

    @Override
    public List<SamplingVO> listByProject(Long projectId) {
        List<AuditSampling> list = samplingMapper.selectList(
                Wrappers.<AuditSampling>lambdaQuery()
                        .eq(AuditSampling::getProjectId, projectId)
                        .orderByDesc(AuditSampling::getId));
        return list.stream().map(s -> toVO(s, null)).toList();
    }

    /* ==================== 录入样本 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SamplingVO addItems(Long id, List<SamplingItemDTO> items) {
        AuditSampling s = requirePlan(id);
        assertNotConcluded(s);

        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL, "至少录入一条样本");
        }

        long existing = itemMapper.selectCount(Wrappers.<AuditSamplingItem>lambdaQuery()
                .eq(AuditSamplingItem::getSamplingId, id));

        if (existing + items.size() > s.getSampleSize()) {
            throw new BusinessException(ResultCode.SAMPLING_ITEM_EXCEED, String.format(
                    "计划抽取 %d 项，已录入 %d 项，本次 %d 项，超出计划",
                    s.getSampleSize(), existing, items.size()));
        }

        int seq = (int) existing;
        for (SamplingItemDTO dto : items) {
            // @Valid 写在 List 参数上时，Spring 默认不会逐个校验元素，
            // 所以凭证号这类必填项必须在 Service 里兜一层，否则会以数据库非空约束报错，
            // 前端只能看到 5000，定位不到是哪一条样本的问题。
            if (!StringUtils.hasText(dto.getVoucherNo())) {
                throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL,
                        "第 " + (seq + 1) + " 条样本缺少凭证号");
            }

            // 偏差不能只有结论没有依据。这条校验放在 Service 而不是 DTO 注解，
            // 是为了在提示里带上凭证号 —— 否则用户面对 20 条样本不知道是哪条漏填了。
            if (Boolean.TRUE.equals(dto.getDeviation()) && !StringUtils.hasText(dto.getDeviationDesc())) {
                throw new BusinessException(ResultCode.SAMPLING_DEVIATION_DESC_REQUIRED,
                        "凭证 " + dto.getVoucherNo() + " 判定为偏差，必须填写偏差描述");
            }

            AuditSamplingItem item = new AuditSamplingItem();
            item.setSamplingId(id);
            item.setSeqNo(++seq);
            item.setVoucherNo(dto.getVoucherNo());
            item.setVoucherDate(dto.getVoucherDate());
            item.setAmount(dto.getAmount());
            item.setDeviation(dto.getDeviation());
            item.setDeviationDesc(dto.getDeviationDesc());
            // null 表示"抽到了但还没看"，不算已检查 —— 这是 checkedCount 的统计口径
            item.setCheckedTime(dto.getDeviation() == null ? null : LocalDateTime.now());
            itemMapper.insert(item);
        }

        refresh(s);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SamplingVO markItem(Long itemId, Boolean deviation, String deviationDesc) {
        AuditSamplingItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "样本不存在");
        }
        AuditSampling s = requirePlan(item.getSamplingId());
        assertNotConcluded(s);

        if (Boolean.TRUE.equals(deviation) && !StringUtils.hasText(deviationDesc)) {
            throw new BusinessException(ResultCode.SAMPLING_DEVIATION_DESC_REQUIRED,
                    "凭证 " + item.getVoucherNo() + " 判定为偏差，必须填写偏差描述");
        }

        item.setDeviation(deviation);
        item.setDeviationDesc(deviationDesc);
        item.setCheckedTime(deviation == null ? null : LocalDateTime.now());
        itemMapper.updateById(item);

        refresh(s);
        return detail(s.getId());
    }

    /* ==================== 出结论 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SamplingVO conclude(Long id) {
        AuditSampling s = requirePlan(id);
        assertNotConcluded(s);

        if (s.getCheckedCount() < s.getSampleSize()) {
            throw new BusinessException(ResultCode.DATA_STATE_ERROR, String.format(
                    "样本尚未检查完成（已检查 %d / 应抽 %d），不能出具结论",
                    s.getCheckedCount(), s.getSampleSize()));
        }

        applyConclusion(s);
        samplingMapper.updateById(s);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePlan(id);
        itemMapper.delete(Wrappers.<AuditSamplingItem>lambdaQuery()
                .eq(AuditSamplingItem::getSamplingId, id));
        samplingMapper.deleteById(id);
    }

    /* ==================== 统计与结论 ==================== */

    /**
     * 重算统计并判断是否可以出结论。
     *
     * <p>这里有两条触发路径：
     * <ol>
     *   <li><b>偏差数超过允许值</b> → 立即终止。这就是"停止-前进抽样（stop-or-go）"：
     *       已经证明控制不可信赖，再抽下去结论不会翻转，继续抽是浪费审计资源。
     *       真实的控制测试就是这么做的，不是必须抽满才能下结论。</li>
     *   <li><b>检查数达到样本量</b> → 正常出结论。</li>
     * </ol>
     */
    private void refresh(AuditSampling s) {
        // 一次查出全部样本在内存里统计，而不是发两条 count(*)。
        // 抽样场景样本量最多几百条，全量查的成本可以忽略；
        // 换来的是"已检查数"和"偏差数"必然来自同一份数据快照 ——
        // 两次 count 之间若有并发写入，两个数字会对不上，底稿就会自相矛盾。
        List<AuditSamplingItem> items = listItems(s.getId());
        int checked = 0;
        int deviations = 0;
        for (AuditSamplingItem item : items) {
            if (item.getDeviation() != null) {
                checked++;
                if (Boolean.TRUE.equals(item.getDeviation())) {
                    deviations++;
                }
            }
        }

        s.setCheckedCount(checked);
        s.setDeviationCount(deviations);

        // 顺序不能反：先判"抽满了没有"，再判"偏差超没超"。
        // 如果先判偏差，那么抽满且偏差超标时会走进"提前终止"分支，
        // 于是全查场景下明明是总体真实偏差率，却被写成"样本未完成、不推断上限"——
        // 结论就从"实际 10%"退化成"不知道"，等于白查了 30 张凭证。
        // 抽满时一律按完整样本推断；只有没抽满且偏差已超标，才是真正的提前终止。
        if (s.getCheckedCount() >= s.getSampleSize()) {
            applyConclusion(s);
        } else if (s.getDeviationCount() > s.getAllowedDeviations()) {
            applyStopAndGo(s);
        }

        samplingMapper.updateById(s);
    }

    /**
     * 停止-前进：偏差已超出允许数，提前终止。
     *
     * <p>注意这里<b>不推算偏差率上限</b>。样本没有按计划抽完，
     * 用当前样本量算出来的置信区间没有意义 —— 硬算会得到一个
     * "看起来有统计依据、实际上不成立"的数字，这比不给数字更危险。
     * 底稿里宁可写"提前终止，不再推断上限"。
     */
    private void applyStopAndGo(AuditSampling s) {
        s.setConclusion(Enums.SamplingConclusion.INEFFECTIVE.getCode());
        s.setDeviationRate(scale(safeRate(s.getDeviationCount(), s.getCheckedCount())));
        s.setUpperLimit(null);
        s.setConclusionText(String.format(
                "已检查 %d 项，发现偏差 %d 项，超过允许的 %d 项，按停止-前进规则提前终止。"
                        + "该控制不可信赖；因样本未按计划完成，不再推断偏差率上限。",
                s.getCheckedCount(), s.getDeviationCount(), s.getAllowedDeviations()));
    }

    /**
     * 按抽样类型给出结论。
     *
     * <p>三个分支的措辞差异是刻意的，不是文案随意性：
     * 统计抽样说"置信水平"，判断抽样必须说"不能推断总体"，全查说"全部已检查"。
     * 措辞错了，底稿的证明力就不成立。
     */
    private void applyConclusion(AuditSampling s) {
        int n = s.getCheckedCount();
        int d = s.getDeviationCount();
        double tolerable = s.getTolerableRate().doubleValue();

        if (Boolean.TRUE.equals(s.getIsCensus())) {
            double rate = safeRate(d, n);
            boolean ok = rate <= tolerable;
            s.setDeviationRate(scale(rate));
            s.setUpperLimit(scale(rate));
            s.setConclusion(ok ? Enums.SamplingConclusion.EFFECTIVE.getCode()
                    : Enums.SamplingConclusion.INEFFECTIVE.getCode());
            s.setConclusionText(String.format(
                    "总体仅 %d 项，已全部检查，发现偏差 %d 项，实际偏差率 %.2f%%，%s可容忍偏差率 %.2f%%，%s",
                    n, d, rate * 100, ok ? "未超过" : "超过", tolerable * 100,
                    ok ? "控制可信赖" : "控制不可信赖"));
            return;
        }

        if (Enums.SamplingMethod.JUDGMENTAL.getCode().equals(s.getMethod())) {
            boolean ok = d <= s.getAllowedDeviations();
            s.setDeviationRate(scale(safeRate(d, n)));
            s.setUpperLimit(null);
            s.setConclusion(ok ? Enums.SamplingConclusion.EFFECTIVE.getCode()
                    : Enums.SamplingConclusion.INEFFECTIVE.getCode());
            s.setConclusionText(String.format(
                    "判断抽样：检查 %d 项，发现偏差 %d 项（允许 %d 项）。"
                            + "结论基于审计人员职业判断，不能推断总体，不具备统计置信水平。",
                    n, d, s.getAllowedDeviations()));
            return;
        }

        Enums.ConfidenceLevel level = Enums.ConfidenceLevel.of(s.getConfidenceLevel());
        SamplingCalculator.SamplingResult result;
        try {
            result = SamplingCalculator.evaluate(n, d, tolerable, level.getRisk());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL, e.getMessage());
        }

        s.setDeviationRate(scale(result.deviationRate()));
        s.setUpperLimit(scale(result.upperLimit()));
        s.setConclusion(result.effective() ? Enums.SamplingConclusion.EFFECTIVE.getCode()
                : Enums.SamplingConclusion.INEFFECTIVE.getCode());
        s.setConclusionText(result.conclusion());
    }

    /* ==================== 辅助 ==================== */

    private AuditSampling requirePlan(Long id) {
        AuditSampling s = samplingMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.SAMPLING_NOT_FOUND, "抽样计划不存在或已删除");
        }
        return s;
    }

    private void assertNotConcluded(AuditSampling s) {
        if (!Enums.SamplingConclusion.PENDING.getCode().equals(s.getConclusion())) {
            throw new BusinessException(ResultCode.SAMPLING_ALREADY_CONCLUDED,
                    "该抽样已出具结论，如需继续检查请新建计划");
        }
    }

    private List<AuditSamplingItem> listItems(Long samplingId) {
        return itemMapper.selectList(Wrappers.<AuditSamplingItem>lambdaQuery()
                .eq(AuditSamplingItem::getSamplingId, samplingId)
                .orderByAsc(AuditSamplingItem::getSeqNo));
    }

    private String parseMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return Enums.SamplingMethod.STATISTICAL.getCode();
        }
        if (!Enums.SamplingMethod.isValid(method)) {
            throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL,
                    "抽样方法只能是 STATISTICAL（统计抽样）或 JUDGMENTAL（判断抽样）");
        }
        return method;
    }

    /**
     * 严格解析置信水平。
     *
     * <p>不用 {@code Enums.ConfidenceLevel.of()}，因为那个方法对未知值返回默认的 95% ——
     * 前端传了 "95"（而不是 "NINETY_FIVE"）会被静默当成 95%，
     * 看起来能用，实际上参数根本没被正确理解。这类"静默兜底"在审计系统里必须避免。
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
        throw new BusinessException(ResultCode.SAMPLING_PARAM_ILLEGAL,
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
                log.warn("抽样编号解析失败，从 1 重新开始: {}", max);
            }
        }
        return prefix + String.format("%04d", next);
    }

    private static double safeRate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private SamplingVO toVO(AuditSampling s, List<AuditSamplingItem> items) {
        SamplingVO vo = new SamplingVO();
        BeanUtils.copyProperties(s, vo);

        Enums.ConfidenceLevel level = Enums.ConfidenceLevel.of(s.getConfidenceLevel());
        vo.setConfidenceDesc(level.getDesc());
        vo.setMethodDesc(Enums.SamplingMethod.descOf(s.getMethod()));
        vo.setConclusionDesc(Enums.SamplingConclusion.descOf(s.getConclusion()));

        int remaining = Math.max(0, s.getSampleSize() - s.getCheckedCount());
        vo.setRemaining(remaining);
        vo.setReadyToConclude(s.getCheckedCount() >= s.getSampleSize()
                || s.getDeviationCount() > s.getAllowedDeviations());

        if (items != null) {
            vo.setItems(items.stream().map(this::toItemVO).toList());
        }
        return vo;
    }

    private SamplingVO.ItemVO toItemVO(AuditSamplingItem item) {
        SamplingVO.ItemVO vo = new SamplingVO.ItemVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }
}
