package com.maning.audit.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("属性抽样引擎（准则 1314 号）")
class SamplingCalculatorTest {

    private static final double TDR_5 = 0.05;
    private static final double RISK_5 = 0.05;
    private static final double RISK_10 = 0.10;

    /* ==================== 样本量 ==================== */

    @Nested
    @DisplayName("样本量计算")
    class PlanSize {

        /**
         * EDR=0 时 k=0，方程退化为 (1-TDR)^n ≤ risk，可以直接手算：
         * n ≥ ln(risk) / ln(1-TDR)
         *
         *   TDR=5%,  risk=5%  → ln(0.05)/ln(0.95)  = 58.40 → 59
         *   TDR=10%, risk=5%  → ln(0.05)/ln(0.90)  = 28.43 → 29
         *   TDR=5%,  risk=10% → ln(0.10)/ln(0.95)  = 44.89 → 45
         *
         * 这三个值和准则附录的抽样表完全一致，说明精确解与表格法在 EDR=0 时是同一个答案。
         */
        @ParameterizedTest(name = "TDR={0}, 风险={1} → 样本量 {2}")
        @CsvSource({
                "0.05, 0.05, 59",
                "0.10, 0.05, 29",
                "0.05, 0.10, 45",
        })
        void zeroExpectedRateMatchesStandardTable(double tdr, double risk, int expected) {
            var plan = SamplingCalculator.plan(5000, tdr, 0.0, risk);

            assertThat(plan.sampleSize()).isEqualTo(expected);
            assertThat(plan.allowedDeviations()).as("预期 0 偏差时不允许任何偏差").isZero();
            assertThat(plan.census()).isFalse();
        }

        /**
         * EDR=1%、TDR=5%、风险 5% 时样本量 93，与准则附录的抽样表一致。
         *
         * <p>这个值的容错极小：n=93 时 P(X≤1)=0.049975，只比 0.05 低 2.5e-5；
         * n=92 时就会超过。也就是说表格和精确解在这一点上刚好吻合，
         * 任何一点手算误差都会把它推到 94。
         *
         * <p>（这条用例第一版我写的是 94，跑测试才被纠正。
         * 算法本身没问题，是我手算时把 0.95^93 算大了。）
         */
        @Test
        @DisplayName("预期偏差率 1% 时样本量 93，与准则抽样表一致")
        void expectedRateSlightlyIncreasesSample() {
            var plan = SamplingCalculator.plan(5000, TDR_5, 0.01, RISK_5);

            assertThat(plan.sampleSize()).isEqualTo(93);
            assertThat(plan.allowedDeviations()).as("93 × 1% 四舍五入 = 1，允许 1 个偏差").isEqualTo(1);
        }

        @Test
        @DisplayName("计算结果必须真的满足风险约束：P(X≤k | n, TDR) ≤ risk")
        void planActuallySatisfiesRiskConstraint() {
            double[][] cases = {
                    {0.05, 0.00, 0.05},
                    {0.05, 0.01, 0.05},
                    {0.10, 0.00, 0.05},
                    {0.03, 0.00, 0.10},
                    {0.08, 0.02, 0.05},
            };
            for (double[] c : cases) {
                var plan = SamplingCalculator.plan(10000, c[0], c[1], c[2]);
                double achieved = SamplingCalculator.binomialCdf(
                        plan.sampleSize(), plan.allowedDeviations(), c[0]);

                assertThat(achieved)
                        .as("TDR=%s EDR=%s 时，样本量 %d 的实际风险应 ≤ %s，实际 %.6f",
                                c[0], c[1], plan.sampleSize(), c[2], achieved)
                        .isLessThanOrEqualTo(c[2]);
            }
        }

        @Test
        @DisplayName("可容忍偏差率越严格，样本量越大")
        void stricterToleranceNeedsMoreSamples() {
            int strict = SamplingCalculator.plan(10000, 0.04, 0.0, RISK_5).sampleSize();
            int loose = SamplingCalculator.plan(10000, 0.08, 0.0, RISK_5).sampleSize();

            assertThat(strict).as("4% 可容忍应比 8% 需要更多样本").isGreaterThan(loose);
        }

        @Test
        @DisplayName("置信水平越高，样本量越大")
        void higherConfidenceNeedsMoreSamples() {
            int c95 = SamplingCalculator.plan(10000, TDR_5, 0.0, RISK_5).sampleSize();
            int c90 = SamplingCalculator.plan(10000, TDR_5, 0.0, RISK_10).sampleSize();

            assertThat(c95).isEqualTo(59);
            assertThat(c90).isEqualTo(45);
            assertThat(c95).as("95% 置信比 90% 需要更多样本").isGreaterThan(c90);
        }

        @Test
        @DisplayName("预期偏差率越高，样本量越大")
        void higherExpectedRateNeedsMoreSamples() {
            int e0 = SamplingCalculator.plan(10000, TDR_5, 0.00, RISK_5).sampleSize();
            int e1 = SamplingCalculator.plan(10000, TDR_5, 0.01, RISK_5).sampleSize();
            int e3 = SamplingCalculator.plan(10000, TDR_5, 0.03, RISK_5).sampleSize();

            assertThat(e0).isLessThan(e1);
            assertThat(e1).isLessThan(e3);
        }

        @Test
        @DisplayName("总体小于所需样本量时退化为全查")
        void smallPopulationFallsBackToCensus() {
            var plan = SamplingCalculator.plan(30, TDR_5, 0.0, RISK_5);

            assertThat(plan.census()).as("只有 30 项，抽 59 项无从谈起").isTrue();
            assertThat(plan.sampleSize()).isEqualTo(30);
            assertThat(plan.basis()).contains("不构成抽样");
        }

        @Test
        @DisplayName("总体恰好等于所需样本量时，仍是抽样而非全查")
        void populationEqualsSampleSizeIsNotCensus() {
            var plan = SamplingCalculator.plan(59, TDR_5, 0.0, RISK_5);

            assertThat(plan.sampleSize()).isEqualTo(59);
            assertThat(plan.census()).as("59 是算出来的样本量，属于抽样").isFalse();
        }

        @Test
        @DisplayName("底稿依据要含全部关键参数，否则复核人无法复核")
        void basisCarriesAllParameters() {
            var plan = SamplingCalculator.plan(2000, TDR_5, 0.01, RISK_5);
            String basis = plan.basis();

            assertThat(basis)
                    .contains("2000")
                    .contains("5.00%")
                    .contains("1.00%")
                    .contains("95%")
                    .contains(String.valueOf(plan.sampleSize()));
        }
    }

    /* ==================== 偏差率上限 ==================== */

    @Nested
    @DisplayName("偏差率上限推断")
    class UpperLimit {

        /**
         * 60 个样本 0 个偏差时，(1-p)^60 = 0.05 → p = 4.87%。
         * 这个值可以手算校验，也是实务里"3/n 估算法"的来源（3/60 = 5%，略高估）。
         */
        @Test
        @DisplayName("60 项零偏差 → 上限 4.87%，低于 5% 可容忍，控制可信赖")
        void zeroDeviationInSixtyIsAcceptable() {
            var r = SamplingCalculator.evaluate(60, 0, TDR_5, RISK_5);

            assertThat(r.upperLimit()).isCloseTo(0.0487, within(0.0005));
            assertThat(r.deviationRate()).isZero();
            assertThat(r.effective()).isTrue();
            assertThat(r.conclusion()).contains("可以信赖");
        }

        @Test
        @DisplayName("60 项里有 1 个偏差 → 上限升到 7.9%，控制不可信赖")
        void oneDeviationBreaksIt() {
            var r = SamplingCalculator.evaluate(60, 1, TDR_5, RISK_5);

            assertThat(r.upperLimit()).isGreaterThan(TDR_5);
            assertThat(r.effective()).as("一个偏差就让结论翻转").isFalse();
            assertThat(r.conclusion()).contains("不能信赖").contains("扩大样本");
        }

        @Test
        @DisplayName("上限永远不低于样本点估计")
        void upperLimitNeverBelowPointEstimate() {
            for (int n = 5; n <= 200; n += 7) {
                for (int d = 0; d <= n; d += Math.max(1, n / 10)) {
                    var r = SamplingCalculator.evaluate(n, d, 0.10, RISK_5);
                    assertThat(r.upperLimit())
                            .as("n=%d d=%d 时上限应 ≥ 点估计", n, d)
                            .isGreaterThanOrEqualTo(r.deviationRate());
                }
            }
        }

        @Test
        @DisplayName("偏差数增加，上限单调不降")
        void moreDeviationsRaiseUpperLimit() {
            double prev = -1;
            for (int d = 0; d <= 10; d++) {
                double upper = SamplingCalculator.evaluate(100, d, 0.10, RISK_5).upperLimit();
                assertThat(upper).as("d=%d 时上限应不低于 d=%d", d, d - 1).isGreaterThanOrEqualTo(prev);
                prev = upper;
            }
        }

        @Test
        @DisplayName("样本全部有偏差 → 上限 1.0，无任何余地")
        void allDeviationsGivesCertainty() {
            var r = SamplingCalculator.evaluate(20, 20, 0.05, RISK_5);

            assertThat(r.upperLimit()).isEqualTo(1.0);
            assertThat(r.effective()).isFalse();
        }

        @Test
        @DisplayName("置信水平越高，上限越宽（结论更保守）")
        void higherConfidenceWidensLimit() {
            double u95 = SamplingCalculator.evaluate(50, 1, 0.10, RISK_5).upperLimit();
            double u90 = SamplingCalculator.evaluate(50, 1, 0.10, RISK_10).upperLimit();

            assertThat(u95).as("95% 置信的上限应比 90% 更宽").isGreaterThan(u90);
        }

        @Test
        @DisplayName("结论文字要带上限、可容忍率和置信水平三个数字")
        void conclusionIsSelfExplanatory() {
            var r = SamplingCalculator.evaluate(120, 2, TDR_5, RISK_5);

            assertThat(r.conclusion())
                    .contains("95%")
                    .contains(String.format("%.2f%%", r.upperLimit() * 100))
                    .contains("5.00%");
        }

        @Test
        @DisplayName("样本量越大，同样偏差率下上限越窄")
        void largerSampleTightensLimit() {
            double small = SamplingCalculator.evaluate(50, 1, 0.10, RISK_5).upperLimit();
            double large = SamplingCalculator.evaluate(500, 10, 0.10, RISK_5).upperLimit();

            assertThat(large).as("同为 2% 偏差率，500 项的推断比 50 项精确").isLessThan(small);
        }
    }

    /* ==================== 二项分布 ==================== */

    @Nested
    @DisplayName("二项分布累积概率")
    class Binomial {

        @Test
        @DisplayName("n=5, k=2, p=0.5 → (1+5+10)/32 = 0.5")
        void matchesHandCalculation() {
            assertThat(SamplingCalculator.binomialCdf(5, 2, 0.5)).isCloseTo(0.5, within(1e-12));
        }

        @Test
        @DisplayName("k ≥ n 时累积概率为 1，k < 0 时为 0")
        void boundaryCases() {
            assertThat(SamplingCalculator.binomialCdf(10, 10, 0.3)).isEqualTo(1.0);
            assertThat(SamplingCalculator.binomialCdf(10, 99, 0.3)).isEqualTo(1.0);
            assertThat(SamplingCalculator.binomialCdf(10, -1, 0.3)).isZero();
        }

        @Test
        @DisplayName("p=0 时恒为 1，p=1 且 k<n 时为 0")
        void degenerateProbabilities() {
            assertThat(SamplingCalculator.binomialCdf(10, 5, 0.0)).isEqualTo(1.0);
            assertThat(SamplingCalculator.binomialCdf(10, 5, 1.0)).isZero();
        }

        @Test
        @DisplayName("p=0.5 时分布对称：P(X≤k) + P(X≤n-k-1) = 1")
        void symmetricAtHalf() {
            for (int n = 3; n <= 30; n += 3) {
                for (int k = 0; k < n; k++) {
                    double a = SamplingCalculator.binomialCdf(n, k, 0.5);
                    double b = SamplingCalculator.binomialCdf(n, n - k - 1, 0.5);
                    assertThat(a + b).as("n=%d k=%d", n, k).isCloseTo(1.0, within(1e-9));
                }
            }
        }

        @Test
        @DisplayName("大样本下结果仍在 [0,1] 内且单调递增")
        void largeSampleStaysWellBehaved() {
            double prev = -1;
            for (int k = 0; k <= 200; k++) {
                double cdf = SamplingCalculator.binomialCdf(1000, k, 0.05);
                assertThat(cdf).isBetween(0.0, 1.0).isGreaterThanOrEqualTo(prev);
                prev = cdf;
            }
        }
    }

    /* ==================== 业务规则与参数校验 ==================== */

    @Nested
    @DisplayName("业务规则与参数校验")
    class Guards {

        @Test
        @DisplayName("预期偏差率达到可容忍水平 → 拒绝抽样，应先判控制不可信赖")
        void expectedReachingTolerableIsRejected() {
            assertThatThrownBy(() -> SamplingCalculator.plan(1000, 0.05, 0.05, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不应实施控制测试");
        }

        @Test
        @DisplayName("预期偏差率超过可容忍水平同样拒绝")
        void expectedAboveTolerableIsRejected() {
            assertThatThrownBy(() -> SamplingCalculator.plan(1000, 0.05, 0.08, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("预期不可信赖");
        }

        @Test
        @DisplayName("预期偏差率与可容忍率极其接近 → 拒绝给出荒谬的样本量")
        void tooCloseRatesAreRejected() {
            assertThatThrownBy(() -> SamplingCalculator.plan(1000000, 0.05, 0.0499, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("过于接近");
        }

        @ParameterizedTest(name = "总体规模 {0} 非法")
        @CsvSource({"0", "-1"})
        void invalidPopulation(int population) {
            assertThatThrownBy(() -> SamplingCalculator.plan(population, TDR_5, 0.0, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("总体规模");
        }

        @ParameterizedTest(name = "可容忍偏差率 {0} 非法")
        @CsvSource({"0", "1", "-0.1", "1.5"})
        void invalidTolerableRate(double tdr) {
            assertThatThrownBy(() -> SamplingCalculator.plan(1000, tdr, 0.0, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("偏差数超出样本量 → 拒绝（脏数据不能算出结论）")
        void deviationExceedingSampleIsRejected() {
            assertThatThrownBy(() -> SamplingCalculator.evaluate(20, 21, TDR_5, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("偏差数");
        }

        @ParameterizedTest(name = "预期偏差率 {0} 非法")
        @CsvSource({"-0.1", "1.0", "1.5"})
        @DisplayName("预期偏差率必须在 [0,1) 区间")
        void invalidExpectedRate(double edr) {
            assertThatThrownBy(() -> SamplingCalculator.plan(1000, TDR_5, edr, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("预期偏差率");
        }

        @Test
        @DisplayName("样本量为 0 → 拒绝")
        void zeroSampleIsRejected() {
            assertThatThrownBy(() -> SamplingCalculator.evaluate(0, 0, TDR_5, RISK_5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("样本量");
        }
    }
}
