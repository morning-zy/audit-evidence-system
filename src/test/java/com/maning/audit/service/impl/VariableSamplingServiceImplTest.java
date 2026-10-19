package com.maning.audit.service.impl;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.dto.VariableSamplingItemDTO;
import com.maning.audit.dto.VariableSamplingPlanDTO;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.entity.AuditVariableSampling;
import com.maning.audit.entity.AuditVariableSamplingItem;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.AuditVariableSamplingItemMapper;
import com.maning.audit.mapper.AuditVariableSamplingMapper;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("变量抽样服务（细节测试）")
class VariableSamplingServiceImplTest {

    @Mock
    private AuditVariableSamplingMapper samplingMapper;

    @Mock
    private AuditVariableSamplingItemMapper itemMapper;

    @Mock
    private AuditProjectMapper projectMapper;

    @InjectMocks
    private VariableSamplingServiceImpl service;

    /* ==================== 建计划 ==================== */

    @Nested
    @DisplayName("创建计划")
    class Create {

        @Test
        @DisplayName("提供标准差 → 直接算出正式样本量，非试点")
        void planWithStdDev() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));
            when(samplingMapper.selectMaxSamplingNo(anyString())).thenReturn(null);

            var dto = planDto();
            dto.setAssumedStdDev(new BigDecimal("1000"));
            var vo = service.createPlan(dto);

            assertThat(vo.getSampleSize()).isEqualTo(520);
            assertThat(vo.getIsPilot()).isFalse();
            assertThat(vo.getBasis()).contains("有限总体校正");
            assertThat(vo.getSamplingNo()).startsWith("VS");
        }

        @Test
        @DisplayName("不提供标准差 → 返回 30 项试点计划")
        void pilotWhenNoStdDev() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));
            when(samplingMapper.selectMaxSamplingNo(anyString())).thenReturn(null);

            var vo = service.createPlan(planDto());

            assertThat(vo.getIsPilot()).isTrue();
            assertThat(vo.getSampleSize()).isEqualTo(30);
            assertThat(vo.getBasis()).contains("试点样本");
        }

        @Test
        @DisplayName("可容忍错报大于账面金额 → 业务异常 3014，而不是系统错误 5000")
        void illegalParamsBecomeBusinessException() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));
            var dto = planDto();
            dto.setTolerableMisstatement(new BigDecimal("2000000"));

            assertThatThrownBy(() -> service.createPlan(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能大于总体账面金额");
        }

        @Test
        @DisplayName("已归档项目不能新增抽样计划")
        void archivedProjectRejected() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.ARCHIVED.getCode()));

            assertThatThrownBy(() -> service.createPlan(planDto()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("非法估计方法被拒绝，不做静默兜底")
        void unknownMethodRejected() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));
            var dto = planDto();
            dto.setMethod("AVERAGE");

            assertThatThrownBy(() -> service.createPlan(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("估计方法");
        }
    }

    /* ==================== 录入 ==================== */

    @Nested
    @DisplayName("录入样本")
    class Entry {

        @Test
        @DisplayName("有错报但没写检查说明 → 拒绝，并指出是哪张凭证")
        void remarkRequiredForMisstatement() {
            AuditVariableSampling plan = plan(10);
            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectCount(any())).thenReturn(0L);

            VariableSamplingItemDTO dto = new VariableSamplingItemDTO();
            dto.setVoucherNo("记-0001");
            dto.setBookValue(new BigDecimal("1000"));
            dto.setAuditedValue(new BigDecimal("900"));

            assertThatThrownBy(() -> service.addItems(10L, List.of(dto)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("记-0001")
                    .hasMessageContaining("检查说明");
        }

        @Test
        @DisplayName("超出计划样本量 → 拒绝")
        void exceedSampleSize() {
            AuditVariableSampling plan = plan(2);
            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectCount(any())).thenReturn(2L);

            assertThatThrownBy(() -> service.addItems(10L, List.of(itemDto("记-0003", "1000", null))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超出计划");
        }

        @Test
        @DisplayName("审定金额留空 = 未检查，不计入已检查数")
        void nullAuditedMeansUnchecked() {
            AuditVariableSampling plan = plan(2);
            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            when(itemMapper.selectList(any())).thenReturn(new ArrayList<>());

            var vo = service.addItems(10L, List.of(itemDto("记-0001", "1000", null)));

            assertThat(vo.getCheckedCount()).isZero();
            assertThat(vo.getConclusion()).isZero();
        }
    }

    /* ==================== 评价 ==================== */

    @Nested
    @DisplayName("评价与阶段推进")
    class Evaluation {

        /**
         * 10 项样本（2 项各少记 100），N=1000、TM=5 万：
         *   估计错报 = 1000 × 20 = 20,000
         *   精度 ≈ 24,320，上限 ≈ 44,320 ≤ 50,000 → 可接受
         */
        @Test
        @DisplayName("抽满后自动评价：错报上限未超可容忍 → 可接受")
        void autoEvaluateWhenComplete() {
            AuditVariableSampling plan = plan(10);
            List<AuditVariableSamplingItem> items = new ArrayList<>();
            items.add(item(1, "1000", "900"));
            items.add(item(2, "1000", "900"));
            for (int i = 3; i <= 10; i++) {
                items.add(item(i, "1000", "1000"));
            }

            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectById(1L)).thenReturn(items.get(0));
            when(itemMapper.selectList(any())).thenReturn(items);

            var vo = service.checkItem(1L, new BigDecimal("900"), "单价多记");

            assertThat(vo.getCheckedCount()).isEqualTo(10);
            assertThat(vo.getConclusion()).isEqualTo(Enums.VariableSamplingConclusion.ACCEPTABLE.getCode());
            assertThat(vo.getUpperMisstatement().doubleValue()).isCloseTo(44_320, within(2.0));
            assertThat(vo.getProjectedMisstatement().doubleValue()).isCloseTo(20_000, within(0.01));
            assertThat(vo.getConclusionText()).contains("可以接受该总体");
        }

        @Test
        @DisplayName("错报上限超过可容忍 → 不可接受")
        void notAcceptable() {
            AuditVariableSampling plan = plan(10);
            plan.setTolerableMisstatement(new BigDecimal("30000"));
            List<AuditVariableSamplingItem> items = new ArrayList<>();
            items.add(item(1, "1000", "900"));
            items.add(item(2, "1000", "900"));
            for (int i = 3; i <= 10; i++) {
                items.add(item(i, "1000", "1000"));
            }

            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectById(1L)).thenReturn(items.get(0));
            when(itemMapper.selectList(any())).thenReturn(items);

            var vo = service.checkItem(1L, new BigDecimal("900"), "单价多记");

            assertThat(vo.getConclusion()).isEqualTo(Enums.VariableSamplingConclusion.NOT_ACCEPTABLE.getCode());
            assertThat(vo.getConclusionText()).contains("不能接受该总体");
        }

        /**
         * 试点 30 项，实际标准差约 203.4：
         *   重算所需样本量 = 43 > 30 → 扩样本，结论保持未评价。
         * 如果这里直接评价，就是拿 30 项去代表 1000 项总体 —— 整个模块的意义就没了。
         */
        @Test
        @DisplayName("试点抽满后自动扩大样本量，而不是直接下结论")
        void pilotExpandsSampleSize() {
            AuditVariableSampling plan = plan(30);
            plan.setIsPilot(true);
            plan.setSampleSize(30);

            List<AuditVariableSamplingItem> items = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                // 300 元上下的波动，制造出约 203 的标准差
                items.add(item(i, "1000", i % 2 == 0 ? "800" : "1200"));
            }

            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectById(1L)).thenReturn(items.get(0));
            when(itemMapper.selectList(any())).thenReturn(items);

            var vo = service.checkItem(1L, new BigDecimal("1200"), "重算单价");

            assertThat(vo.getIsPilot()).isFalse();
            assertThat(vo.getSampleSize()).isEqualTo(43);
            assertThat(vo.getConclusion()).isZero();
            assertThat(vo.getConclusionText()).contains("需继续抽取");
        }

        @Test
        @DisplayName("样本没抽满时拒绝出具结论")
        void rejectEvaluateWhenIncomplete() {
            AuditVariableSampling plan = plan(10);
            plan.setCheckedCount(4);
            when(samplingMapper.selectById(10L)).thenReturn(plan);

            assertThatThrownBy(() -> service.evaluate(10L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("尚未检查完成");
        }

        @Test
        @DisplayName("已出结论后不能再改样本")
        void lockedAfterConclusion() {
            AuditVariableSampling plan = plan(10);
            plan.setConclusion(Enums.VariableSamplingConclusion.ACCEPTABLE.getCode());
            when(samplingMapper.selectById(10L)).thenReturn(plan);

            assertThatThrownBy(() -> service.evaluate(10L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已出具评价结论");
        }

        @Test
        @DisplayName("撤销检查后进度回退，且不会残留上一次的推断数字")
        void undoKeepsPending() {
            AuditVariableSampling plan = plan(11);

            List<AuditVariableSamplingItem> items = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                items.add(item(i, "1000", "1000"));
            }

            when(samplingMapper.selectById(10L)).thenReturn(plan);
            when(itemMapper.selectById(1L)).thenReturn(items.get(0));
            when(itemMapper.selectList(any())).thenReturn(items);

            var vo = service.checkItem(1L, null, null);

            assertThat(vo.getCheckedCount()).isEqualTo(9);
            assertThat(vo.getConclusion()).isZero();
            assertThat(vo.getUpperMisstatement()).isNull();
            assertThat(vo.getConclusionText()).isNull();
        }
    }

    /* ==================== 构造数据 ==================== */

    private static AuditProject project(Integer status) {
        AuditProject p = new AuditProject();
        p.setId(1L);
        p.setStatus(status);
        return p;
    }

    private static VariableSamplingPlanDTO planDto() {
        VariableSamplingPlanDTO dto = new VariableSamplingPlanDTO();
        dto.setProjectId(1L);
        dto.setAccountName("存货—原材料");
        dto.setPopulationBookValue(new BigDecimal("1000000"));
        dto.setPopulationSize(1000);
        dto.setTolerableMisstatement(new BigDecimal("50000"));
        dto.setExpectedMisstatement(BigDecimal.ZERO);
        dto.setConfidenceLevel("NINETY_FIVE");
        dto.setMethod("DIFFERENCE");
        return dto;
    }

    private static AuditVariableSampling plan(int sampleSize) {
        AuditVariableSampling s = new AuditVariableSampling();
        s.setId(10L);
        s.setSamplingNo("VS20260001");
        s.setProjectId(1L);
        s.setAccountName("存货—原材料");
        s.setPopulationBookValue(new BigDecimal("1000000"));
        s.setPopulationSize(1000);
        s.setTolerableMisstatement(new BigDecimal("50000"));
        s.setExpectedMisstatement(BigDecimal.ZERO);
        s.setConfidenceLevel("NINETY_FIVE");
        s.setMethod("DIFFERENCE");
        s.setSampleSize(sampleSize);
        s.setIsPilot(false);
        s.setCheckedCount(0);
        s.setConclusion(Enums.VariableSamplingConclusion.PENDING.getCode());
        return s;
    }

    private static AuditVariableSamplingItem item(int seq, String book, String audited) {
        AuditVariableSamplingItem it = new AuditVariableSamplingItem();
        it.setId((long) seq);
        it.setSamplingId(10L);
        it.setSeqNo(seq);
        it.setVoucherNo("记-" + String.format("%04d", seq));
        it.setBookValue(new BigDecimal(book));
        if (audited != null) {
            it.setAuditedValue(new BigDecimal(audited));
            it.setMisstatement(new BigDecimal(book).subtract(new BigDecimal(audited)));
        }
        return it;
    }

    private static VariableSamplingItemDTO itemDto(String voucherNo, String book, String audited) {
        VariableSamplingItemDTO dto = new VariableSamplingItemDTO();
        dto.setVoucherNo(voucherNo);
        dto.setBookValue(new BigDecimal(book));
        dto.setAuditedValue(audited == null ? null : new BigDecimal(audited));
        return dto;
    }
}
