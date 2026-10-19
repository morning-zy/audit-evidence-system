package com.maning.audit.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("变量抽样引擎（细节测试）")
class VariableSamplingCalculatorTest {

    private static final double RISK_5 = 0.05;

    /** 教科书标准样本：10 项，其中 2 项各少记 100 元，其余 8 项无错报 */
    private static List<VariableSamplingCalculator.SampleItem> twoErrors() {
        List<VariableSamplingCalculator.SampleItem> items = new ArrayList<>();
        items.add(new VariableSamplingCalculator.SampleItem(1000, 900));
        items.add(new VariableSamplingCalculator.SampleItem(1000, 900));
        for (int i = 0; i < 8; i++) {
            items.add(new VariableSamplingCalculator.SampleItem(1000, 1000));
        }
        return items;
    }

    /* ==================== 样本量 ==================== */

    @Nested
    @DisplayName("样本量计算")
    class PlanSize {

        /**
         * N=1000、s=1000、z=1.6449：
         *   n₀ = (1000 × 1.6449 × 1000 / 50000)² = 1082.3
         *   n  = 1082.3 / (1 + 1082.3/1000)      = 519.8 → 520
         *
         * TM 减半后 n₀ 变成 4 倍（4329.1），但 FPC 校正把最终样本量压到 813 ——
         * 因为 520 已经抽掉总体的过半，再加倍在物理上做不到。
         */
        @Test
        @DisplayName("可容忍错报 5 万 → 520 项")
        void normalCase() {
            var plan = VariableSamplingCalculator.plan(1000, 1_000_000, 50_000, 1000.0, RISK_5);
            assertThat(plan.sampleSize()).isEqualTo(520);
            assertThat(plan.pilot()).isFalse();
            assertThat(plan.basis()).contains("有限总体校正");
        }

        @Test
        @DisplayName("可容忍错报减半，样本量随之上升")
        void tighterTolerable() {
            var plan = VariableSamplingCalculator.plan(1000, 1_000_000, 25_000, 1000.0, RISK_5);
            assertThat(plan.sampleSize()).isEqualTo(813);
            assertThat(plan.sampleSize()).isGreaterThan(520);
        }

        @Test
        @DisplayName("没有预估标准差时给 30 项试点，而不是硬凑一个数")
        void pilotWhenNoStdDev() {
            var plan = VariableSamplingCalculator.plan(1000, 1_000_000, 50_000, null, RISK_5);
            assertThat(plan.pilot()).isTrue();
            assertThat(plan.sampleSize()).isEqualTo(30);
            assertThat(plan.basis()).contains("试点样本");
        }

        @Test
        @DisplayName("标准差为 0 视同未提供")
        void zeroStdDevFallsBackToPilot() {
            var plan = VariableSamplingCalculator.plan(1000, 1_000_000, 50_000, 0.0, RISK_5);
            assertThat(plan.pilot()).isTrue();
        }

        @Test
        @DisplayName("总体比所需样本还小 → 全查，样本量等于总体")
        void census() {
            var plan = VariableSamplingCalculator.plan(20, 20_000, 5_000, 1000.0, RISK_5);
            assertThat(plan.sampleSize()).isEqualTo(20);
            assertThat(plan.basis()).contains("详细检查");
        }

        @Test
        @DisplayName("样本量下限 30：算出 8 项也不能真抽 8 项")
        void minimumSampleSize() {
            // 标准差很小、可容忍错报很大时公式给出的 n 很小
            var plan = VariableSamplingCalculator.plan(10_000, 1_000_000, 400_000, 100.0, RISK_5);
            assertThat(plan.sampleSize()).isGreaterThanOrEqualTo(30);
        }

        @Test
        @DisplayName("置信水平越高，样本量越大")
        void higherConfidenceNeedsMore() {
            var p90 = VariableSamplingCalculator.plan(5000, 5_000_000, 100_000, 3000.0, 0.10);
            var p95 = VariableSamplingCalculator.plan(5000, 5_000_000, 100_000, 3000.0, RISK_5);
            assertThat(p95.sampleSize()).isGreaterThan(p90.sampleSize());
        }
    }

    /* ==================== 三种估计方法 ==================== */

    @Nested
    @DisplayName("错报推断")
    class Evaluate {

        /**
         * 差额估计手算：
         *   d̄ = 200/10 = 20，s_d = √(16000/9) = 42.166
         *   估计错报 = 1000 × 20 = 20,000
         *   FPC = √(1-10/1000) = 0.99499
         *   t(0.95, 9) = 1.8331
         *   精度 = 1.8331 × 1000 × 42.166/√10 × 0.99499 = 24,320
         *   上限 = 44,320 ≤ 50,000 → 可接受
         */
        @Test
        @DisplayName("差额估计：估计错报 2 万，上限 4.4 万，可接受")
        void difference() {
            var r = VariableSamplingCalculator.evaluate(twoErrors(), 1000, 1_000_000,
                    50_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(r.sampleSize()).isEqualTo(10);
            assertThat(r.meanMisstatement()).isCloseTo(20.0, within(1e-9));
            assertThat(r.stdDev()).isCloseTo(42.166, within(0.01));
            assertThat(r.projectedMisstatement()).isCloseTo(20_000, within(0.01));
            assertThat(r.precision()).isCloseTo(24_320, within(2.0));
            assertThat(r.upperMisstatement()).isCloseTo(44_320, within(2.0));
            assertThat(r.tValue()).isCloseTo(1.8331, within(1e-3));
            assertThat(r.acceptable()).isTrue();
            assertThat(r.conclusion()).contains("可以接受该总体");
        }

        /**
         * 账面金额全部相同（1000）时，审定金额的标准差等于错报的标准差，
         * 所以均值估计与差额估计必然给出同一个结果 —— 这是两种方法的一致性检验。
         * 如果这里对不上，说明其中一个公式写错了。
         */
        @Test
        @DisplayName("账面金额相等时，均值估计与差额估计结果一致")
        void meanEqualsDifferenceWhenBookConstant() {
            var mp = VariableSamplingCalculator.evaluate(twoErrors(), 1000, 1_000_000,
                    50_000, VariableSamplingCalculator.Method.MEAN_PER_UNIT, RISK_5);
            var df = VariableSamplingCalculator.evaluate(twoErrors(), 1000, 1_000_000,
                    50_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(mp.projectedMisstatement()).isCloseTo(df.projectedMisstatement(), within(1e-6));
            assertThat(mp.upperMisstatement()).isCloseTo(df.upperMisstatement(), within(1e-6));
        }

        /**
         * 错报大体按账面金额成比例（约 10%，其中一项 15%）时：
         *   比率估计 → 100,000 × (175/1500) = 11,666.67（接近真实）
         *   差额估计 → 平均错报 35 × 100 项 = 3,500（严重低估）
         * 因为样本里都是小额项目，而总体均值是 1,000 元/项。
         * 这就是"错报与金额成比例时必须用比率估计"的直观证明。
         */
        @Test
        @DisplayName("错报与金额成比例时，比率估计才准")
        void ratioVsDifference() {
            List<VariableSamplingCalculator.SampleItem> items = List.of(
                    new VariableSamplingCalculator.SampleItem(100, 90),
                    new VariableSamplingCalculator.SampleItem(200, 180),
                    new VariableSamplingCalculator.SampleItem(300, 270),
                    new VariableSamplingCalculator.SampleItem(400, 360),
                    new VariableSamplingCalculator.SampleItem(500, 425));

            var ratio = VariableSamplingCalculator.evaluate(items, 100, 100_000,
                    20_000, VariableSamplingCalculator.Method.RATIO, RISK_5);
            var diff = VariableSamplingCalculator.evaluate(items, 100, 100_000,
                    20_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(ratio.projectedMisstatement()).isCloseTo(11_666.67, within(0.01));
            assertThat(diff.projectedMisstatement()).isCloseTo(3_500, within(0.01));
            assertThat(ratio.zeroErrorFallback()).isFalse();
            assertThat(ratio.precision()).isGreaterThan(0);
        }

        @Test
        @DisplayName("错报超过可容忍金额 → 不可接受")
        void notAcceptable() {
            var r = VariableSamplingCalculator.evaluate(twoErrors(), 1000, 1_000_000,
                    30_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);
            assertThat(r.acceptable()).isFalse();
            assertThat(r.conclusion()).contains("不能接受该总体");
        }

        /**
         * 零错报不是"零风险"：50 项全部干净，s=0，t 区间退化为 0，
         * 若直接输出"上限 0"等于宣称总体绝对没错 —— 这显然是假的。
         * 兜底后上限 = -ln(0.05) × 1,000,000 / 50 = 59,913 元，
         * 超过可容忍错报 50,000 元，所以结论仍然是"不能接受"。
         * 这正是"抽 50 项没发现问题"不能证明"没问题"的金额版本。
         */
        @Test
        @DisplayName("样本无错报时用泊松基本精度兜底，不会得出上限为零")
        void zeroErrorsUsePoissonFallback() {
            List<VariableSamplingCalculator.SampleItem> items = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                items.add(new VariableSamplingCalculator.SampleItem(1000, 1000));
            }
            var r = VariableSamplingCalculator.evaluate(items, 1000, 1_000_000,
                    50_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(r.zeroErrorFallback()).isTrue();
            assertThat(r.upperMisstatement()).isCloseTo(59_913, within(5.0));
            assertThat(r.acceptable()).isFalse();
            assertThat(r.conclusion()).contains("泊松基本精度");
        }

        @Test
        @DisplayName("高估与低估方向相反，不能取绝对值互相抵消")
        void overAndUnderDoNotCancel() {
            // 一项多记 100、一项少记 100，净错报为 0，但两项都是错报
            List<VariableSamplingCalculator.SampleItem> items = List.of(
                    new VariableSamplingCalculator.SampleItem(1000, 1100),
                    new VariableSamplingCalculator.SampleItem(1000, 900),
                    new VariableSamplingCalculator.SampleItem(1000, 1000),
                    new VariableSamplingCalculator.SampleItem(1000, 1000));
            var r = VariableSamplingCalculator.evaluate(items, 1000, 1_000_000,
                    50_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(r.projectedMisstatement()).isCloseTo(0.0, within(1e-9));
            assertThat(r.precision()).isGreaterThan(0);
            assertThat(r.meanMisstatement()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("样本等于总体时 FPC 归零，不存在抽样风险")
        void censusHasNoSamplingRisk() {
            List<VariableSamplingCalculator.SampleItem> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                items.add(new VariableSamplingCalculator.SampleItem(1000, 950));
            }
            var r = VariableSamplingCalculator.evaluate(items, 10, 10_000,
                    5_000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5);

            assertThat(r.precision()).isCloseTo(0.0, within(1e-9));
            assertThat(r.projectedMisstatement()).isCloseTo(500, within(1e-9));
            assertThat(r.acceptable()).isTrue();
        }
    }

    /* ==================== 统计工具 ==================== */

    @Nested
    @DisplayName("统计工具")
    class Stats {

        /** 与标准 t 表对照，误差应小于 0.001 */
        @ParameterizedTest(name = "t({0} 分位, df={1}) = {2}")
        @CsvSource({
                "0.95,  1,    6.3138",
                "0.95,  9,    1.8331",
                "0.95,  29,   1.6991",
                "0.95,  59,   1.6711",
                "0.99,  30,   2.4573",
                "0.975, 10,   2.2281"
        })
        void tQuantileMatchesTable(double p, int df, double expected) {
            assertThat(VariableSamplingCalculator.tQuantile(p, df))
                    .isCloseTo(expected, within(1e-3));
        }

        @Test
        @DisplayName("t 分布对称性")
        void tSymmetry() {
            assertThat(VariableSamplingCalculator.tQuantile(0.05, 20))
                    .isCloseTo(-VariableSamplingCalculator.tQuantile(0.95, 20), within(1e-9));
        }

        @Test
        @DisplayName("自由度越大越接近正态 1.6449")
        void tApproachesNormal() {
            assertThat(VariableSamplingCalculator.tQuantile(0.95, 100000))
                    .isCloseTo(1.6449, within(1e-3));
        }

        @Test
        @DisplayName("标准正态分位数")
        void normalQuantile() {
            assertThat(VariableSamplingCalculator.normalQuantile(0.95)).isCloseTo(1.6449, within(1e-4));
            assertThat(VariableSamplingCalculator.normalQuantile(0.90)).isCloseTo(1.2816, within(1e-4));
            assertThat(VariableSamplingCalculator.normalQuantile(0.50)).isCloseTo(0.0, within(1e-6));
        }

        @Test
        @DisplayName("误差函数")
        void erf() {
            assertThat(VariableSamplingCalculator.erf(0)).isCloseTo(0.0, within(1e-12));
            assertThat(VariableSamplingCalculator.erf(1)).isCloseTo(0.8427, within(1e-4));
            assertThat(VariableSamplingCalculator.erf(-1)).isCloseTo(-0.8427, within(1e-4));
        }

        @Test
        @DisplayName("样本标准差用 n-1 分母")
        void sampleStdDev() {
            double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
            // 均值 5，平方和 32，总体标准差 2；样本标准差 √(32/7) = 2.1381
            assertThat(VariableSamplingCalculator.stdDevOf(v, 5.0)).isCloseTo(2.1381, within(1e-4));
        }

        @Test
        @DisplayName("lnΓ(5) = ln24")
        void logGamma() {
            assertThat(VariableSamplingCalculator.logGamma(5)).isCloseTo(Math.log(24), within(1e-9));
            assertThat(VariableSamplingCalculator.logGamma(0.5))
                    .isCloseTo(Math.log(Math.sqrt(Math.PI)), within(1e-9));
        }
    }

    /* ==================== 参数校验 ==================== */

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("总体项数必须为正")
        void populationPositive() {
            assertThatThrownBy(() -> VariableSamplingCalculator.plan(0, 1000, 100, 10.0, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("总体项数");
        }

        @Test
        @DisplayName("可容忍错报不能超过总体账面金额")
        void tolerableNotExceedBook() {
            assertThatThrownBy(() -> VariableSamplingCalculator.plan(100, 1000, 2000, 10.0, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能大于总体账面金额");
        }

        @Test
        @DisplayName("风险必须在 (0,1)")
        void riskRange() {
            assertThatThrownBy(() -> VariableSamplingCalculator.plan(100, 1000, 100, 10.0, 1.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VariableSamplingCalculator.tQuantile(0.95, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("自由度");
        }

        @Test
        @DisplayName("样本少于 2 项时标准差无定义")
        void tooFewItems() {
            assertThatThrownBy(() -> VariableSamplingCalculator.evaluate(
                    List.of(new VariableSamplingCalculator.SampleItem(100, 90)),
                    100, 10_000, 1000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少需要 2");
        }

        @Test
        @DisplayName("样本量不能大于总体")
        void sampleNotExceedPopulation() {
            assertThatThrownBy(() -> VariableSamplingCalculator.evaluate(
                    twoErrors(), 5, 10_000, 1000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能大于总体");
        }

        @Test
        @DisplayName("账面金额不能为负")
        void negativeBook() {
            assertThatThrownBy(() -> VariableSamplingCalculator.evaluate(
                    List.of(new VariableSamplingCalculator.SampleItem(-100, 90),
                            new VariableSamplingCalculator.SampleItem(100, 100)),
                    100, 10_000, 1000, VariableSamplingCalculator.Method.DIFFERENCE, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为负数");
        }
    }
}
