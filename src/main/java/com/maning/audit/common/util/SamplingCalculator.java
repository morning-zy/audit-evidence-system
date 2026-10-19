package com.maning.audit.common.util;

import java.util.Locale;

/**
 * 属性抽样引擎 —— 依据《中国注册会计师审计准则第 1314 号——审计抽样》
 *
 * <p><b>这个类解决的问题</b>：
 * 审计师说"我抽了 30 张凭证，都没问题，所以这个控制有效"——这句话在准则上是站不住的。
 * 30 张是怎么来的？如果总体有 5000 张，抽 30 张发现 0 个偏差，
 * 真实的总体偏差率上限可能是 9.5%，远超可容忍的 5%。
 * 也就是说：<b>凭感觉抽的样本，什么都不能证明。</b>
 *
 * <p>本类做两件事，都是纯数学，不碰数据库、不碰 Spring：
 * <ol>
 *   <li><b>算样本量</b>：给定可容忍偏差率、预期偏差率、置信水平，算出至少抽多少张；
 *       这个数字不是拍脑袋，是从二项分布反解出来的。</li>
 *   <li><b>推上限</b>：抽完之后，用实际发现的偏差数，推断"总体偏差率最高可能是多少"，
 *       再和可容忍偏差率比较，得出"能不能信赖这个控制"。</li>
 * </ol>
 *
 * <p><b>为什么用二项分布</b>：
 * 属性抽样的每个样本只有两种结果——有偏差/没偏差，且从总体中抽取时
 * 每个样本的偏差概率相同。这正是二项分布的适用条件。
 * 严格来说不放回抽样应服从超几何分布，但当总体规模远大于样本量
 * （审计实务中总体通常成百上千，样本几十到几百）时，
 * 二项分布是超几何分布的精确近似，这也是准则附录抽样表的编制基础。
 *
 * <p><b>为什么用精确计算而不是查表</b>：
 * 教科书上的抽样表（AICPA 表）编制于计算机普及之前，用泊松近似简化手算，
 * 只有几十个离散格子（TDR 通常只给 2%、3%、4%、5%、6%…）。
 * 在本项目关心的取值范围内，精确二项解与表格高度吻合：
 * <pre>
 *   EDR=0%, TDR=5%, 5% 风险 → 59   （表格 59）
 *   EDR=1%, TDR=5%, 5% 风险 → 93   （表格 93，P(X≤1)=0.049975，只差 2.5e-5）
 * </pre>
 * 用精确解的价值不在"给出不同的数"，而在<b>任意参数组合都成立</b>：
 * 可容忍偏差率填 3.5%、置信水平填 92% 时，表格查不到，公式照样能算。
 */
public final class SamplingCalculator {

    /**
     * 样本量搜索的硬上限。
     *
     * <p>能触到这个上限只有一种情况：预期偏差率离可容忍偏差率太近
     * （比如 4.9% vs 5.0%）。这时要证明"没超标"需要极大样本，
     * 实务上的处理不是硬抽，而是直接判定该控制不可信赖。
     * 所以这里抛异常，逼调用方回到业务判断，而不是给出一个几万张的荒谬样本量。
     */
    private static final int MAX_SAMPLE_SIZE = 5000;

    /** 二分法迭代次数：80 次可把区间压到 2^-80，远超 double 精度需求 */
    private static final int BISECTION_STEPS = 80;

    private SamplingCalculator() {
    }

    /* ==================== 返回值 ==================== */

    /**
     * 抽样计划。
     *
     * <p>用 record 而不是 Lombok 的 @Data：这是计算结果值对象，
     * 一旦算出来就不该被修改。没有 setter，就不存在"算完被人改一个字段"的可能。
     *
     * @param sampleSize        应抽取的样本量
     * @param allowedDeviations 允许出现的偏差数，超过即判定控制不可信赖
     * @param census            是否退化为全查（总体太小，抽不如全看）
     * @param basis             计算依据，直接写进审计底稿
     */
    public record SamplingPlan(int sampleSize, int allowedDeviations, boolean census, String basis) {
    }

    /**
     * 抽样评价结果。
     *
     * @param sampleSize    实际检查的样本量
     * @param deviationCount 发现的偏差数
     * @param deviationRate 样本偏差率（点估计）
     * @param upperLimit    总体偏差率上限（单侧置信上限）
     * @param tolerableRate 可容忍偏差率
     * @param risk          信赖过度风险
     * @param effective     控制是否可信赖
     * @param conclusion    结论文字，可直接贴进底稿
     */
    public record SamplingResult(int sampleSize, int deviationCount, double deviationRate,
                                 double upperLimit, double tolerableRate, double risk,
                                 boolean effective, String conclusion) {
    }

    /* ==================== 一、样本量 ==================== */

    /**
     * 计算属性抽样所需的最小样本量。
     *
     * <p>求解思路：找到最小的 n，使得"当总体真实偏差率恰好等于可容忍偏差率时，
     * 样本中出现不超过 k 个偏差的概率"小于等于可接受风险。
     * 换句话说：如果这个控制在临界水平上，我们有 1-risk 的把握能发现它有问题。
     *
     * <p>其中 k = round(n × 预期偏差率)，即按预期偏差率预留的"允许偏差额度"。
     * 为什么允许 k 个而不是 0 个：如果预期有 1% 的偏差，那么样本里出现一两个
     * 偏差是正常波动，不能因为一两个偏差就判死。预留额度后，
     * 只有偏差数显著超出预期，才判定失控。
     *
     * <p>用已知值校验（EDR=0 时 k=0，可直接手算 n ≥ ln(risk)/ln(1-TDR)）：
     * <pre>
     *   TDR=5%,  risk=5%,  EDR=0  → n=59   （(1-0.05)^59 = 0.0485 ≤ 0.05）
     *   TDR=10%, risk=5%,  EDR=0  → n=29
     *   TDR=5%,  risk=10%, EDR=0  → n=45
     * </pre>
     * 与准则附录的抽样表一致。
     *
     * @param populationSize 总体规模（本期该控制涉及的凭证/交易笔数）
     * @param tolerableRate  可容忍偏差率，如 0.05 表示 5%
     * @param expectedRate   预期偏差率，如 0.01 表示 1%；无历史数据时填 0
     * @param risk           可接受的信赖过度风险，如 0.05
     * @return 抽样计划
     * @throws IllegalArgumentException 参数非法，或预期偏差率已达到可容忍水平
     */
    public static SamplingPlan plan(int populationSize, double tolerableRate,
                                    double expectedRate, double risk) {
        if (populationSize <= 0) {
            throw new IllegalArgumentException("总体规模必须大于 0，当前: " + populationSize);
        }
        requireOpen("可容忍偏差率", tolerableRate);
        requireOpen("信赖过度风险", risk);
        if (Double.isNaN(expectedRate) || expectedRate < 0 || expectedRate >= 1) {
            throw new IllegalArgumentException("预期偏差率必须在 [0,1) 区间，当前: " + expectedRate);
        }

        // 这条不是防御性校验，是审计判断：
        // 如果历史数据表明偏差率已经达到可容忍水平，那么该控制在抽样之前就应该被判为不可信赖，
        // 继续抽样的意义是"证明它不行"，而抽样只能证明"它可能不行"，不如直接下结论。
        // 准则的做法是：预期偏差率高于可容忍偏差率时，不实施控制测试，改由实质性程序获取保证。
        if (expectedRate >= tolerableRate) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "预期偏差率 %.2f%% 已达到或超过可容忍偏差率 %.2f%%，该控制预期不可信赖，不应实施控制测试",
                    expectedRate * 100, tolerableRate * 100));
        }

        int limit = Math.min(populationSize, MAX_SAMPLE_SIZE);
        for (int n = 1; n <= limit; n++) {
            int k = allowedDeviations(n, expectedRate);
            if (binomialCdf(n, k, tolerableRate) <= risk) {
                return new SamplingPlan(n, k, false,
                        basisOf(populationSize, tolerableRate, expectedRate, risk, n, k));
            }
        }

        // 循环走完说明：总体本身已达到所需样本量（全查），或者参数极端到算不出合理样本量
        if (populationSize <= MAX_SAMPLE_SIZE) {
            int k = allowedDeviations(populationSize, expectedRate);
            return new SamplingPlan(populationSize, k, true, String.format(Locale.ROOT,
                    "总体仅 %d 项，小于计算所需样本量，应对全部项目执行检查（属于详细检查，不构成抽样）；"
                            + "可容忍偏差率 %.2f%%、预期偏差率 %.2f%%、置信水平 %.0f%%",
                    populationSize, tolerableRate * 100, expectedRate * 100,
                    (1 - risk) * 100));
        }

        throw new IllegalArgumentException(String.format(Locale.ROOT,
                "计算所需样本量超过 %d，预期偏差率(%.2f%%)与可容忍偏差率(%.2f%%)过于接近；"
                        + "实务处理：直接判定该控制不可信赖，改由实质性程序获取保证",
                MAX_SAMPLE_SIZE, expectedRate * 100, tolerableRate * 100));
    }

    /* ==================== 二、偏差率上限推断 ==================== */

    /**
     * 根据实际检查结果推断总体偏差率上限，并给出结论。
     *
     * <p>核心是求单侧置信上限（Clopper-Pearson 精确区间）：
     * 找到那个 p，使得"总体真实偏差率为 p 时，观察到本次这么少偏差的概率"
     * 恰好等于风险水平。这个 p 就是上限——总体偏差率超过它的可能性小于 risk。
     *
     * <p>举个具体例子理解为什么要"上限"：
     * 抽 60 张，发现 0 个偏差。点估计偏差率是 0%，但显然不能说
     * "总体偏差率就是 0"——只是这 60 张里没遇到而已。
     * 精确计算得到上限 4.9%（95% 置信），意思是：
     * 总体偏差率高于 4.9% 的可能性不到 5%。
     * 而可容忍是 5%，4.9% &lt; 5%，所以可以信赖。哪怕多一个偏差，结论就会翻转。
     *
     * <p>这个"0 个偏差时上限约等于 3/n"的经验规律，是审计实务里估算样本量时常用的心算方法
     * （n=60 时 3/60=5%），它来自二项分布的近似，可作为结果合理性的快速校验。
     *
     * @param sampleSize     实际检查的样本量
     * @param deviationCount 发现的偏差数
     * @param tolerableRate  可容忍偏差率
     * @param risk           可接受的信赖过度风险
     * @return 评价结果
     */
    public static SamplingResult evaluate(int sampleSize, int deviationCount,
                                          double tolerableRate, double risk) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("样本量必须大于 0，当前: " + sampleSize);
        }
        if (deviationCount < 0 || deviationCount > sampleSize) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "偏差数必须在 [0, %d] 区间，当前: %d", sampleSize, deviationCount));
        }
        requireOpen("可容忍偏差率", tolerableRate);
        requireOpen("信赖过度风险", risk);

        double deviationRate = (double) deviationCount / sampleSize;
        double upperLimit = upperLimit(sampleSize, deviationCount, risk);
        boolean effective = upperLimit <= tolerableRate;

        String conclusion;
        if (effective) {
            conclusion = String.format(Locale.ROOT,
                    "在 %.0f%% 置信水平下，总体偏差率上限为 %.2f%%，未超过可容忍偏差率 %.2f%%，可以信赖该控制",
                    (1 - risk) * 100, upperLimit * 100, tolerableRate * 100);
        } else {
            conclusion = String.format(Locale.ROOT,
                    "在 %.0f%% 置信水平下，总体偏差率上限为 %.2f%%，超过可容忍偏差率 %.2f%%，"
                            + "不能信赖该控制；应扩大样本量，或改由实质性程序获取审计保证",
                    (1 - risk) * 100, upperLimit * 100, tolerableRate * 100);
        }

        return new SamplingResult(sampleSize, deviationCount, deviationRate,
                upperLimit, tolerableRate, risk, effective, conclusion);
    }

    /**
     * 精确单侧置信上限：解 P(X ≤ d | n, p) = risk。
     *
     * <p>用二分法而不是查表或正态近似，原因：
     * 样本量小时正态近似误差很大（n=20、d=0 时近似给出上限 0，实际是 14%），
     * 而小样本恰恰是控制测试的常见情况。二分法 80 次迭代误差小于 1e-20，是可以忽略的。
     *
     * <p>单调性说明：p 增大时，"观察到不超过 d 个偏差"的概率单调递减，
     * 所以方程在 [d/n, 1] 上有唯一解，二分法必然收敛。
     * 下界取 d/n 而不是 0，是因为上限不可能低于样本点估计。
     */
    private static double upperLimit(int n, int d, double risk) {
        if (d >= n) {
            // 全部样本都有偏差，上限就是 1，没有任何余地
            return 1.0;
        }
        double lo = (double) d / n;
        double hi = 1.0;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (lo + hi) / 2.0;
            if (binomialCdf(n, d, mid) > risk) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /* ==================== 三、二项分布 ==================== */

    /**
     * 二项分布累积概率 P(X ≤ k)：n 次试验、单次成功概率 p 时，成功次数不超过 k 的概率。
     *
     * <p>不用阶乘直接算 C(n,k)，因为 n 稍大就会溢出（21! 已经超过 long）。
     * 这里用递推：P(X=i) = P(X=i-1) × (n-i+1)/i × p/(1-p)，
     * 每一项都从上一项推出，全程无大数运算。
     *
     * <p>P(X=0) 用 exp(n·ln(1-p)) 计算而不是 Math.pow(1-p, n)，
     * 因为 p 很小时 1-p 接近 1，直接 pow 会损失精度，log1p(-p) 在 p→0 时仍精确。
     */
    public static double binomialCdf(int n, int k, double p) {
        if (k < 0) {
            return 0.0;
        }
        if (k >= n) {
            return 1.0;
        }
        if (p <= 0.0) {
            return 1.0;
        }
        if (p >= 1.0) {
            return 0.0;
        }

        double term = Math.exp(n * Math.log1p(-p));
        double sum = term;
        double odds = p / (1.0 - p);
        for (int i = 1; i <= k; i++) {
            term *= (double) (n - i + 1) / i * odds;
            sum += term;
        }
        return Math.min(1.0, sum);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 允许的偏差数。
     *
     * <p>用四舍五入而不是向下取整：n=93、预期 1% 时应允许 1 个偏差，
     * 向下取整会得到 0，等于要求样本绝对干净，这不符合"预期偏差率"的本意。
     */
    private static int allowedDeviations(int n, double expectedRate) {
        return (int) Math.round(n * expectedRate);
    }

    private static void requireOpen(String name, double v) {
        if (Double.isNaN(v) || v <= 0.0 || v >= 1.0) {
            throw new IllegalArgumentException(name + " 必须在 (0,1) 开区间，当前: " + v);
        }
    }

    private static String basisOf(int populationSize, double tolerableRate, double expectedRate,
                                  double risk, int sampleSize, int allowed) {
        return String.format(Locale.ROOT,
                "总体 %d 项 · 可容忍偏差率 %.2f%% · 预期偏差率 %.2f%% · 置信水平 %.0f%% "
                        + "→ 样本量 %d 项（允许偏差 %d 项，超出则判定控制不可信赖）",
                populationSize, tolerableRate * 100, expectedRate * 100, (1 - risk) * 100,
                sampleSize, allowed);
    }
}
