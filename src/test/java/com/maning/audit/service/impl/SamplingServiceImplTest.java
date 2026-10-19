package com.maning.audit.service.impl;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.dto.SamplingItemDTO;
import com.maning.audit.dto.SamplingPlanDTO;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.entity.AuditSampling;
import com.maning.audit.entity.AuditSamplingItem;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.AuditSamplingItemMapper;
import com.maning.audit.mapper.AuditSamplingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("审计抽样服务")
class SamplingServiceImplTest {

    @Mock
    private AuditSamplingMapper samplingMapper;

    @Mock
    private AuditSamplingItemMapper itemMapper;

    @Mock
    private AuditProjectMapper projectMapper;

    @InjectMocks
    private SamplingServiceImpl service;

    /* ==================== 创建计划 ==================== */

    @Nested
    @DisplayName("创建抽样计划")
    class Create {

        @Test
        @DisplayName("2000 笔总体、5% 可容忍、零预期 → 样本量 59，依据落库")
        void computesSampleSizeAndPersistsBasis() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));
            when(samplingMapper.selectMaxSamplingNo(anyString())).thenReturn(null);

            var vo = service.createPlan(dto("0.05", "0"));

            assertThat(vo.getSampleSize()).isEqualTo(59);
            assertThat(vo.getAllowedDeviations()).isZero();
            assertThat(vo.getBasis()).as("依据要写进底稿，复核人靠它复核")
                    .contains("2000").contains("5.00%").contains("95%");
            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.PENDING.getCode());
            assertThat(vo.getSamplingNo()).startsWith("SP");

            verify(samplingMapper).insert(any(AuditSampling.class));
        }

        @Test
        @DisplayName("预期偏差率达到可容忍水平 → 3009，先判控制不可信赖而不是硬抽")
        void rejectsWhenExpectedReachesTolerable() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));

            assertThatThrownBy(() -> service.createPlan(dto("0.05", "0.05")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.SAMPLING_PARAM_ILLEGAL.getCode())
                    .hasMessageContaining("不应实施控制测试");
        }

        @Test
        @DisplayName("算不出合理样本量 → 转成 3009，不能变成系统错误")
        void illegalArgumentBecomesBusinessError() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));

            // 总体 100 万、预期 4.99% 紧贴可容忍 5%，所需样本量突破 5000 上限。
            // 注意总体只有 2000 时不会抛异常 —— 那会退化成全查，本身是合理结果。
            SamplingPlanDTO dto = dto("0.05", "0.0499");
            dto.setPopulationSize(1_000_000);

            assertThatThrownBy(() -> service.createPlan(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.SAMPLING_PARAM_ILLEGAL.getCode())
                    .hasMessageContaining("过于接近");
        }
        @Test
        @DisplayName("已归档项目不能新增抽样")
        void archivedProjectIsRejected() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.ARCHIVED.getCode()));

            assertThatThrownBy(() -> service.createPlan(dto("0.05", "0")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PROJECT_NOT_EDITABLE.getCode());
        }
    }

    /* ==================== 录入样本 ==================== */

    @Nested
    @DisplayName("录入样本")
    class AddItems {

        @Test
        @DisplayName("超出计划抽取数 → 3011")
        void exceedingPlannedSizeIsRejected() {
            when(samplingMapper.selectById(1L)).thenReturn(pendingPlan(59, 0, "STATISTICAL"));
            when(itemMapper.selectCount(any())).thenReturn(59L);

            assertThatThrownBy(() -> service.addItems(1L, List.of(item("V001", false))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.SAMPLING_ITEM_EXCEED.getCode())
                    .hasMessageContaining("超出计划");

            verify(itemMapper, never()).insert(any(AuditSamplingItem.class));
        }

        @Test
        @DisplayName("判为偏差却没写描述 → 3013，提示里要带凭证号")
        void deviationWithoutDescriptionIsRejected() {
            when(samplingMapper.selectById(1L)).thenReturn(pendingPlan(59, 0, "STATISTICAL"));
            when(itemMapper.selectCount(any())).thenReturn(0L);

            var bad = item("V002", true);
            bad.setDeviationDesc(null);

            assertThatThrownBy(() -> service.addItems(1L, List.of(bad)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.SAMPLING_DEVIATION_DESC_REQUIRED.getCode())
                    .hasMessageContaining("V002");
        }

        @Test
        @DisplayName("已出结论的抽样不能再录样本")
        void concludedSamplingIsLocked() {
            AuditSampling concluded = pendingPlan(59, 0, "STATISTICAL");
            concluded.setConclusion(Enums.SamplingConclusion.EFFECTIVE.getCode());
            when(samplingMapper.selectById(1L)).thenReturn(concluded);

            assertThatThrownBy(() -> service.addItems(1L, List.of(item("V003", false))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.SAMPLING_ALREADY_CONCLUDED.getCode());
        }
    }

    /* ==================== 三种结论路径 ==================== */

    @Nested
    @DisplayName("结论：统计抽样")
    class StatisticalConclusion {

        @Test
        @DisplayName("抽满 59 项、零偏差 → 可信赖，上限 4.95%")
        void zeroDeviationIsEffective() {
            when(samplingMapper.selectById(1L)).thenReturn(pendingPlan(59, 0, "STATISTICAL"));
            when(itemMapper.selectCount(any())).thenReturn(58L);
            when(itemMapper.selectList(any())).thenReturn(items(59, 0));

            var vo = service.addItems(1L, List.of(item("V059", false)));

            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.EFFECTIVE.getCode());
            // 59 项零偏差的上限是 4.95%，离 5% 只差 0.05 个百分点 ——
            // 这说明 59 就是"刚好够"的样本量，任何一点算法偏差都会让结论翻盘。
            assertThat(vo.getUpperLimit().doubleValue()).isCloseTo(0.0495, within(0.0005));
            assertThat(vo.getConclusionText()).contains("可以信赖").contains("95%");
        }

        @Test
        @DisplayName("抽满 59 项、出现 1 个偏差 → 上限超 5%，不可信赖")
        void oneDeviationIsIneffective() {
            when(samplingMapper.selectById(1L)).thenReturn(pendingPlan(59, 0, "STATISTICAL"));
            when(itemMapper.selectCount(any())).thenReturn(58L);
            when(itemMapper.selectList(any())).thenReturn(items(59, 1));

            var vo = service.addItems(1L, List.of(item("V059", true)));

            // 样本已抽满，走的是完整推断而不是提前终止：
            // 1 个偏差把上限推到 7.6%，超过可容忍的 5%，结论翻转为不可信赖。
            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.INEFFECTIVE.getCode());
            assertThat(vo.getUpperLimit().doubleValue()).isGreaterThan(0.05);
            assertThat(vo.getConclusionText()).contains("不能信赖").contains("扩大样本");
        }

        @Test
        @DisplayName("偏差数超过允许值 → 立即终止，且不给偏差率上限")
        void stopAndGoTerminatesEarly() {
            AuditSampling plan = pendingPlan(59, 0, "STATISTICAL");
            when(samplingMapper.selectById(1L)).thenReturn(plan);
            when(itemMapper.selectCount(any())).thenReturn(19L);
            when(itemMapper.selectList(any())).thenReturn(items(20, 1));

            var vo = service.addItems(1L, List.of(item("V020", true)));

            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.INEFFECTIVE.getCode());
            assertThat(vo.getUpperLimit())
                    .as("样本没抽完，此时算上限会得到一个不成立的数字")
                    .isNull();
            assertThat(vo.getConclusionText())
                    .contains("提前终止")
                    .contains("不再推断偏差率上限");
            assertThat(vo.getCheckedCount()).isEqualTo(20);
            assertThat(vo.getRemaining()).isEqualTo(39);
        }
    }

    @Nested
    @DisplayName("结论：判断抽样")
    class JudgmentalConclusion {

        @Test
        @DisplayName("判断抽样不出置信区间，文案必须写明不可推断总体")
        void noConfidenceIntervalForJudgmental() {
            when(samplingMapper.selectById(1L)).thenReturn(pendingPlan(59, 0, "JUDGMENTAL"));
            when(itemMapper.selectCount(any())).thenReturn(58L);
            when(itemMapper.selectList(any())).thenReturn(items(59, 0));

            var vo = service.addItems(1L, List.of(item("V059", false)));

            assertThat(vo.getUpperLimit()).as("判断抽样没有统计基础").isNull();
            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.EFFECTIVE.getCode());
            assertThat(vo.getConclusionText())
                    .contains("不能推断总体")
                    .doesNotContain("95%");
        }
    }

    @Nested
    @DisplayName("结论：全查")
    class CensusConclusion {

        @Test
        @DisplayName("全查时偏差率就是总体真实值，措辞不能说推断")
        void censusUsesActualRate() {
            AuditSampling plan = pendingPlan(30, 0, "STATISTICAL");
            plan.setIsCensus(true);
            plan.setPopulationSize(30);
            when(samplingMapper.selectById(1L)).thenReturn(plan);
            when(itemMapper.selectCount(any())).thenReturn(29L);
            when(itemMapper.selectList(any())).thenReturn(items(30, 3));

            var vo = service.addItems(1L, List.of(item("V030", true)));

            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.INEFFECTIVE.getCode());
            assertThat(vo.getUpperLimit().doubleValue()).as("全查时上限=实际值 10%").isCloseTo(0.10, within(0.0001));
            assertThat(vo.getConclusionText()).contains("全部检查").contains("实际偏差率");
        }
    }

    /* ==================== 手动出结论 ==================== */

    @Nested
    @DisplayName("手动出具结论")
    class Conclude {

        @Test
        @DisplayName("样本没抽满且偏差未超标 → 拒绝，不拿半截样本推断总体")
        void incompleteSampleIsRejected() {
            AuditSampling plan = pendingPlan(59, 0, "STATISTICAL");
            plan.setCheckedCount(10);
            when(samplingMapper.selectById(1L)).thenReturn(plan);

            assertThatThrownBy(() -> service.conclude(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.DATA_STATE_ERROR.getCode())
                    .hasMessageContaining("尚未检查完成");

            verify(samplingMapper, never()).updateById(any(AuditSampling.class));
        }

        @Test
        @DisplayName("抽满后手动出结论 → 正常推断")
        void completeSampleConcludes() {
            AuditSampling plan = pendingPlan(59, 0, "STATISTICAL");
            plan.setCheckedCount(59);
            plan.setDeviationCount(0);
            when(samplingMapper.selectById(1L)).thenReturn(plan);
            when(itemMapper.selectList(any())).thenReturn(items(59, 0));

            var vo = service.conclude(1L);

            assertThat(vo.getConclusion()).isEqualTo(Enums.SamplingConclusion.EFFECTIVE.getCode());
            assertThat(vo.getUpperLimit()).isNotNull();
        }
    }

    /* ==================== 辅助 ==================== */

    private AuditProject project(Integer status) {
        AuditProject p = new AuditProject();
        p.setId(1L);
        p.setProjectName("2026年度财务报表审计");
        p.setStatus(status);
        return p;
    }

    private SamplingPlanDTO dto(String tolerable, String expected) {
        SamplingPlanDTO dto = new SamplingPlanDTO();
        dto.setProjectId(1L);
        dto.setControlName("采购付款审批");
        dto.setPopulationSize(2000);
        dto.setTolerableRate(new BigDecimal(tolerable));
        dto.setExpectedRate(new BigDecimal(expected));
        return dto;
    }

    private AuditSampling pendingPlan(int sampleSize, int allowed, String method) {
        AuditSampling s = new AuditSampling();
        s.setId(1L);
        s.setSamplingNo("SP20260001");
        s.setProjectId(1L);
        s.setControlName("采购付款审批");
        s.setPopulationSize(2000);
        s.setTolerableRate(new BigDecimal("0.0500"));
        s.setExpectedRate(BigDecimal.ZERO);
        s.setConfidenceLevel("NINETY_FIVE");
        s.setMethod(method);
        s.setSampleSize(sampleSize);
        s.setAllowedDeviations(allowed);
        s.setIsCensus(false);
        s.setCheckedCount(0);
        s.setDeviationCount(0);
        s.setConclusion(Enums.SamplingConclusion.PENDING.getCode());
        s.setBasis("测试依据");
        return s;
    }

    private SamplingItemDTO item(String voucherNo, boolean deviation) {
        SamplingItemDTO dto = new SamplingItemDTO();
        dto.setVoucherNo(voucherNo);
        dto.setDeviation(deviation);
        dto.setDeviationDesc(deviation ? "缺少财务总监签字" : null);
        return dto;
    }

    /** 构造 total 条样本，前 deviations 条为偏差 */
    private List<AuditSamplingItem> items(int total, int deviations) {
        List<AuditSamplingItem> list = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            AuditSamplingItem it = new AuditSamplingItem();
            it.setId((long) i);
            it.setSamplingId(1L);
            it.setSeqNo(i);
            it.setVoucherNo("V" + String.format("%03d", i));
            it.setDeviation(i <= deviations);
            list.add(it);
        }
        return list;
    }
}
