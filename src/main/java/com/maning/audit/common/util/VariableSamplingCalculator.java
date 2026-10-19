package com.maning.audit.common.util;

import java.util.List;
import java.util.Locale;

/**
 * 变量抽样引擎 —— 细节测试中用于推断<b>总体错报金额</b>
 *
 * <p><b>它和属性抽样（{@link SamplingCalculator}）解决的不是同一个问题</b>，这一点必须先分清：
 * <pre>
 *   属性抽样（控制测试）：这张凭证有没有按流程审批？→ 推断"偏差率"，答案是百分比
 *   变量抽样（细节测试）：这批存货金额对不对？      → 推断"错报金额"，答案是钱
 * </pre>
 * 控制有效，不代表金额一定对（审批签了字也可能批错）；控制无效，更不能推出金额错了多少。
 * 所以准则要求两者分开做，结论也分开写 —— 把"控制可信赖"直接当成"金额无重大错报"，
 * 是审计实务里最典型的逻辑跳跃。
 *
 * <p><b>三种估计方法的区别（本类的核心）</b>：
 * <pre>
 *   均值估计 MEAN_PER_UNIT：用样本审定额的均值 × 总体项数，推出总体审定金额。
 *       不依赖样本与总体的账面结构关系，最稳健，但方差最大（要求样本量大）。
 *
 *   差额估计 DIFFERENCE：用样本"单笔错报"的平均值 × 总体项数。
 *       要求错报与项目金额无关（比如每笔都少记 100 元），否则会高估或低估。
 *
 *   比率估计 RATIO：用样本"错报/账面"的比率 × 总体账面金额。
 *       要求错报与金额成比例（比如每笔都按 10% 多记）。比率估计通常最精确，
 *       但样本账面金额太集中时不稳定。
 * </pre>
 * 三种方法算出来的错报上限不会相同。选错方法不是"算得不准"，而是<b>前提不成立</b>：
 * 错报明明和金额成比例，却用差额估计，会低估大额项目的错报影响。
 *
 * <p><b>为什么用 t 分布而不是正态分布</b>：
 * 审计样本通常三五十到一两百项，σ 未知、只能用样本标准差 s 代替。
 * 这在统计上就是 t 分布的场景。n=30、95% 单侧时 t=1.699，正态 z=1.645，
 * 差 3%，落到几十万的金额上就是上万块的精度差异 —— 而精度直接决定
 * "错报上限是否超过可容忍错报"，也就是结论翻不翻转。
 *
 * <p>本类是纯函数：不碰数据库、不依赖 Spring，输入输出都是基本类型，
 * 因此可以完全用单元测试覆盖（{@code VariableSamplingCalculatorTest}）。
 */
public final class VariableSamplingCalculator {

    /** 实务惯例：变量抽样样本量一般不少于 30 项，否则样本标准差不可靠 */
    private static final int MIN_SAMPLE_SIZE = 30;

    /** 二分法迭代次数：200 次足以把区间压到 double 精度以下 */
    private static final int BISECTION_STEPS = 200;

    private VariableSamplingCalculator() {
    }

    /* ==================== 值对象 ==================== */

    /**
     * 估计方法。刻意定义在算法层而不是复用业务枚举：
     * {@code common.util} 不应该反向依赖 {@code common.constant}，
     * 否则算法类就被业务枚举绑架，想单独跑个数学测试都得带上整个枚举。
     */
    public enum Method {
        MEAN_PER_UNIT("均值估计"),
        DIFFERENCE("差额估计"),
        RATIO("比率估计");

        private final String desc;

        Method(String desc) {
            this.desc = desc;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * 样本项目：账面金额与审定金额。
     *
     * <p>错报 = 账面 - 审定。正数表示多记（高估），负数表示少记（低估）。
     * 两种方向都要保留，不能取绝对值 —— 高估和低估混在一起取绝对值，
     * 会互相抵消或互相叠加，两种错法都会让结论失真。
     */
    public record SampleItem(double bookValue, double auditedValue) {
    }

    /**
     * 抽样计划。
     *
     * @param sampleSize 应抽取的样本量
     * @param pilot      是否为试点样本（未提供预估标准差时的第一阶段）
     * @param basis      计算依据，直接打印进底稿
     */
    public record Plan(int sampleSize, boolean pilot, String basis) {
    }

    /**
     * 评价结果。
     *
     * @param projectedMisstatement 估计总体错报（点估计）
     * @param precision             抽样风险允许度（精度），即点估计的误差范围
     * @param upperMisstatement     错报上限 = 点估计 + 精度，这才是判断依据
     * @param zeroErrorFallback     是否因样本零错报而改用泊松基本精度
     */
    public record Result(int sampleSize, String methodDesc,
                         double sampleBookTotal, double sampleAuditedTotal, double sampleMisstatement,
                         double meanMisstatement, double stdDev,
                         double projectedMisstatement, double precision, double upperMisstatement,
                         double tolerableMisstatement, double tValue, int degreesOfFreedom,
                         boolean acceptable, boolean zeroErrorFallback, String conclusion) {
    }

    /* ==================== 一、样本量 ==================== */

    /**
     * 计算变量抽样所需样本量。
     *
     * <p>公式（经典变量抽样，带有限总体校正）：
     * <pre>
     *   n₀ = (N × z × s / TM)²
     *   n  = n₀ / (1 + n₀ / N)
     * </pre>
     * 其中 N 是总体项数，z 是置信系数，s 是单笔金额的预估标准差，TM 是可容忍错报。
     *
     * <p><b>为什么"错报越小、样本越大"是反直觉但正确的</b>：
     * 要证明"总体错报不超过 5 万"，比证明"不超过 50 万"难得多 ——
     * 前者要求精度必须收窄到 5 万以内。TM 在分母上且被平方，
     * 可容忍错报减半，样本量要翻四倍。
     *
     * <p><b>没有标准差怎么办（试点样本）</b>：
     * 实务上很少能事先知道总体标准差。标准做法是先抽一个小样本（常取 30 项）
     * 算出 s，再据此算正式样本量 —— 这就是"试点样本（pilot sample）"。
     * 本方法在 {@code assumedStdDev} 为空或 ≤0 时返回 30 项的试点计划，
     * 抽满后由 Service 用实际标准差重算所需样本量。
     *
     * <p>用已知值校验（N=1000，s=1000，z=1.6449）：
     * <pre>
     *   TM=50000 → n₀=1082.3，FPC 校正后 519.8 → 520
     *   TM=25000 → n₀=4329.1，FPC 校正后 812.4 → 813
     * </pre>
     * 可容忍错报减半，n₀ 变成 4 倍；经有限总体校正后增幅被压低到 1.56 倍，
     * 因为 520 已经接近总体的一半，"再抽一倍"在物理上做不到。
     *
     * @param populationSize        总体项数
     * @param populationBookValue   总体账面金额
     * @param tolerableMisstatement 可容忍错报
     * @param assumedStdDev         预估单笔标准差；为 null 或 ≤0 时按试点样本处理
     * @param risk                  误拒风险（1 - 置信水平），如 0.05
     */
    public static Plan plan(int populationSize, double populationBookValue,
                            double tolerableMisstatement, Double assumedStdDev, double risk) {
        validateBase(populationSize, populationBookValue, tolerableMisstatement, risk);

        if (assumedStdDev == null || assumedStdDev <= 0) {
            int n = Math.min(MIN_SAMPLE_SIZE, populationSize);
            return new Plan(n, true, String.format(Locale.ROOT,
                    "未提供预估标准差 → 按试点样本抽取 %d 项；"
                            + "取得实际标准差后系统重算正式样本量。总体 %d 项，可容忍错报 %.2f 元，置信水平 %.0f%%",
                    n, populationSize, tolerableMisstatement, (1 - risk) * 100));
        }

        double z = normalQuantile(1 - risk);
        double n0 = Math.pow(populationSize * z * assumedStdDev / tolerableMisstatement, 2);
        // 有限总体校正：n/N 不可忽略时必须缩，否则"抽 1200 项"可能比总体还大
        double n = n0 / (1 + n0 / populationSize);
        int need = (int) Math.ceil(n);
        // 下限 30：算出来只要 8 项也不能真抽 8 项，样本太小算出的 s 不可靠
        need = Math.max(need, Math.min(MIN_SAMPLE_SIZE, populationSize));
        boolean census = need >= populationSize;
        if (census) {
            need = populationSize;
        }

        String basis = census
                ? String.format(Locale.ROOT,
                "总体 %d 项，计算所需样本量已达总体规模，应对全部项目执行检查（详细检查，不构成抽样）；"
                        + "可容忍错报 %.2f 元，预估标准差 %.2f 元，置信水平 %.0f%%",
                populationSize, tolerableMisstatement, assumedStdDev, (1 - risk) * 100)
                : String.format(Locale.ROOT,
                "总体 %d 项 · 可容忍错报 %.2f 元 · 预估标准差 %.2f 元 · 置信水平 %.0f%% "
                        + "→ 样本量 %d 项（n₀=%.1f，有限总体校正后 %.1f）",
                populationSize, tolerableMisstatement, assumedStdDev, (1 - risk) * 100,
                need, n0, n);

        return new Plan(need, false, basis);
    }

    /* ==================== 二、错报推断 ==================== */

    /**
     * 根据样本检查结果推断总体错报上限，并给出能否接受的结论。
     *
     * <p>统一框架：<b>错报上限 = 估计总体错报 + 抽样风险允许度</b>。
     * 点估计本身不能作为判断依据 —— 样本里恰好没抽到错报，不等于总体没有错报。
     * 精度这一项就是为"没抽到"留的余地，它的宽度由样本标准差、样本量和置信水平共同决定。
     *
     * <p>判断规则：错报上限 ≤ 可容忍错报 → 可以接受该总体。
     * 注意比的是<b>上限</b>，不是点估计 —— 这和属性抽样里比偏差率上限是同一个逻辑。
     *
     * @param items                 已检查的样本（账面/审定金额）
     * @param populationSize        总体项数
     * @param populationBookValue   总体账面金额
     * @param tolerableMisstatement 可容忍错报
     * @param method                估计方法
     * @param risk                  误拒风险
     */
    public static Result evaluate(List<SampleItem> items, int populationSize,
                                  double populationBookValue, double tolerableMisstatement,
                                  Method method, double risk) {
        validateBase(populationSize, populationBookValue, tolerableMisstatement, risk);
        if (items == null || items.size() < 2) {
            throw new IllegalArgumentException("变量抽样至少需要 2 个已检查样本，否则标准差无定义，当前: "
                    + (items == null ? 0 : items.size()));
        }
        if (items.size() > populationSize) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "样本量 %d 不能大于总体规模 %d", items.size(), populationSize));
        }
        if (method == null) {
            throw new IllegalArgumentException("必须指定估计方法");
        }

        int n = items.size();
        Sums sums = sumOf(items);
        double bookSum = sums.book();
        double auditSum = sums.audit();
        double diffSum = sums.diff();

        double fpc = n >= populationSize ? 0.0 : Math.sqrt(1.0 - (double) n / populationSize);
        int df = n - 1;
        double t = tQuantile(1 - risk, df);

        // 标准差的口径随方法而变：均值法看审定金额的离散度，差额法看单笔错报的离散度，
        // 比率法看残差。三者混用会直接算错精度，所以统一由 stdDevFor 决定。
        double stdDev = stdDevFor(items, method);

        double projected;
        double precision;

        switch (method) {
            case MEAN_PER_UNIT -> {
                // 估计总体审定金额 = N × 样本均值；错报 = 账面总额 - 估计审定总额
                projected = populationBookValue - populationSize * (auditSum / n);
                precision = t * populationSize * stdDev / Math.sqrt(n) * fpc;
            }
            case DIFFERENCE -> {
                projected = populationSize * (diffSum / n);
                precision = t * populationSize * stdDev / Math.sqrt(n) * fpc;
            }
            case RATIO -> {
                if (bookSum <= 0) {
                    throw new IllegalArgumentException("比率估计要求样本账面金额合计大于 0");
                }
                double meanBook = bookSum / n;
                projected = populationBookValue * (diffSum / bookSum);
                // SE(r) = sₑ/(b̄·√n)，再乘以总体账面金额换成金额口径
                precision = t * populationBookValue * stdDev / (meanBook * Math.sqrt(n)) * fpc;
            }
            default -> throw new IllegalArgumentException("不支持的估计方法: " + method);
        }

        /*
         * 零错报陷阱：样本里一个错报都没有时，s=0，t 区间宽度变成 0，
         * 于是"错报上限 = 0"——等于宣称总体绝对没有错报。这显然是假的：
         * 只是这几十项里恰好没有，不代表剩下的几千项也没有。
         *
         * 这不是实现缺陷，而是经典变量抽样在"零错报"时的固有限制，
         * 所以实务上小额错报的总体通常改用 MUS（货币单位抽样）。
         * 这里不做静默处理，改用泊松基本精度兜底：
         *   上限 = -ln(风险) × 总体账面金额 ÷ 样本量
         * 风险 5% 时系数为 2.996（约等于 3），正好对上审计里"零偏差时上限约等于 3/n"的经验法则。
         *
         * 仅在抽样场景生效；n = N 是详细检查，不存在抽样风险，精度就该是 0。
         */
        boolean zeroErrorFallback = false;
        if (stdDev == 0.0 && n < populationSize) {
            precision = -Math.log(risk) * populationBookValue / n;
            zeroErrorFallback = true;
        }

        double upper = projected + precision;
        boolean acceptable = upper <= tolerableMisstatement;
        String methodDesc = method.getDesc();

        String conclusion;
        if (zeroErrorFallback) {
            conclusion = String.format(Locale.ROOT,
                    "%s：检查 %d 项（总体 %d 项），样本未发现错报。"
                            + "样本标准差为 0，t 区间退化，改用泊松基本精度兜底："
                            + "错报上限 %.2f 元（风险系数 %.3f × 总体账面 %.2f 元 ÷ 样本量 %d），"
                            + "%s可容忍错报 %.2f 元，%s",
                    methodDesc, n, populationSize, upper, -Math.log(risk),
                    populationBookValue, n,
                    acceptable ? "未超过" : "超过", tolerableMisstatement,
                    acceptable ? "可以接受该总体" : "不能接受该总体，应扩大样本量或提请管理层调整");
        } else {
            conclusion = String.format(Locale.ROOT,
                    "%s：检查 %d 项（总体 %d 项），估计总体错报 %.2f 元，"
                            + "抽样风险允许度 %.2f 元（t=%.4f，自由度 %d，置信水平 %.0f%%），"
                            + "错报上限 %.2f 元，%s可容忍错报 %.2f 元，%s",
                    methodDesc, n, populationSize, projected, precision,
                    t, df, (1 - risk) * 100, upper,
                    acceptable ? "未超过" : "超过", tolerableMisstatement,
                    acceptable ? "可以接受该总体" : "不能接受该总体，应扩大样本量或提请管理层调整");
        }

        return new Result(n, methodDesc, bookSum, auditSum, diffSum,
                diffSum / n, stdDev, projected, precision, upper,
                tolerableMisstatement, t, df, acceptable, zeroErrorFallback, conclusion);
    }

    /**
     * 按估计方法取标准差口径。
     *
     * <p>这不是"实现细节"，而是三种方法在统计上的身份差异：
     * <pre>
     *   均值估计 → sd(审定金额)      —— 关心的是审定金额本身的离散
     *   差额估计 → sd(单笔错报)      —— 关心的是错报额的离散
     *   比率估计 → sd(dᵢ - r·bᵢ)    —— 关心的是错报偏离"按比例"的程度
     * </pre>
     * 试点样本抽满后重算样本量时也要用这个口径，否则拿一套方差去配另一套公式，
     * 算出来的样本量看着正常、实际是错的。
     */
    public static double stdDevFor(List<SampleItem> items, Method method) {
        if (items == null || items.size() < 2) {
            throw new IllegalArgumentException("计算标准差至少需要 2 个样本");
        }
        if (method == null) {
            throw new IllegalArgumentException("必须指定估计方法");
        }
        int n = items.size();
        Sums sums = sumOf(items);

        return switch (method) {
            case MEAN_PER_UNIT -> stdDevOf(
                    items.stream().mapToDouble(SampleItem::auditedValue).toArray(), sums.audit() / n);
            case DIFFERENCE -> stdDevOf(
                    items.stream().mapToDouble(x -> x.bookValue() - x.auditedValue()).toArray(),
                    sums.diff() / n);
            case RATIO -> {
                if (sums.book() <= 0) {
                    throw new IllegalArgumentException("比率估计要求样本账面金额合计大于 0");
                }
                double ratio = sums.diff() / sums.book();
                yield stdDevOf(items.stream()
                        .mapToDouble(x -> (x.bookValue() - x.auditedValue()) - ratio * x.bookValue())
                        .toArray(), 0.0);
            }
        };
    }

    /** 样本三项合计，评价与标准差计算共用同一份遍历逻辑 */
    private record Sums(double book, double audit, double diff) {
    }

    private static Sums sumOf(List<SampleItem> items) {
        double book = 0, audit = 0, diff = 0;
        for (SampleItem it : items) {
            if (it == null || Double.isNaN(it.bookValue()) || Double.isNaN(it.auditedValue())) {
                throw new IllegalArgumentException("样本金额不能为空");
            }
            if (it.bookValue() < 0) {
                throw new IllegalArgumentException("样本账面金额不能为负数，当前: " + it.bookValue());
            }
            book += it.bookValue();
            audit += it.auditedValue();
            diff += it.bookValue() - it.auditedValue();
        }
        return new Sums(book, audit, diff);
    }

    /**
     * 用试点样本的实际标准差重算正式所需样本量。
     *
     * <p>试点阶段的意义就在这里：如果实际标准差比预估大，
     * 30 项根本不够，必须扩样本 —— 而不是"抽了 30 项就下结论"。
     */
    public static Plan replan(int populationSize, double populationBookValue,
                              double tolerableMisstatement, double actualStdDev, double risk) {
        return plan(populationSize, populationBookValue, tolerableMisstatement, actualStdDev, risk);
    }

    /* ==================== 三、统计工具 ==================== */

    /**
     * 样本标准差（n-1 分母，无偏估计）。
     *
     * <p>用 n-1 而不是 n：样本标准差是总体标准差的有偏估计，n-1 才是无偏的
     * （贝塞尔校正）。审计样本普遍只有几十项，用 n 会系统性低估方差，
     * 进而低估精度、低估错报上限，最后把"该拒绝的总体"判成"可接受"——
     * 这是方向性错误，宁可保守。
     */
    static double stdDevOf(double[] values, double mean) {
        int n = values.length;
        if (n < 2) {
            throw new IllegalArgumentException("计算标准差至少需要 2 个观测值");
        }
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (n - 1));
    }

    /**
     * 标准正态分位数（用于样本量规划）。
     *
     * <p>用二分法反解 CDF：正态 CDF 严格单调，必然收敛。
     * 没有引入 Acklam 那类有理逼近，是因为这里只需 1e-7 精度，
     * 而二分法的逻辑一眼能看懂、不会写错 —— 在审计系统里，
     * "能被人复核的代码"比"少迭代几次的代码"更重要。
     */
    static double normalQuantile(double p) {
        if (Double.isNaN(p) || p <= 0 || p >= 1) {
            throw new IllegalArgumentException("分位点必须在 (0,1) 开区间，当前: " + p);
        }
        double lo = -10, hi = 10;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (lo + hi) / 2;
            if (normalCdf(mid) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    private static double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /**
     * 误差函数近似（Abramowitz & Stegun 7.1.26），最大绝对误差 1.5e-7。
     * 这个精度对"算样本量"完全够用：样本量本身就要向上取整。
     */
    static double erf(double x) {
        // 近似式在 0 点会留下 1e-9 的残差，而 erf(0)=0 是精确值，单独返回
        if (x == 0.0) {
            return 0.0;
        }
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }

    /**
     * t 分布分位数（单侧）。
     *
     * <p>关系式：t ≥ 0 时 P(T ≤ t) = 1 - ½·I_{v/(v+t²)}(v/2, ½)，
     * 其中 I 是正则化不完全 Beta 函数。反解同样用二分法。
     *
     * <p>用已知表值校验：
     * <pre>
     *   t(0.95, 1)  = 6.3138   t(0.95, 9)  = 1.8331
     *   t(0.95, 29) = 1.6991   t(0.99, 30) = 2.4573
     * </pre>
     */
    static double tQuantile(double p, int df) {
        if (df <= 0) {
            throw new IllegalArgumentException("自由度必须大于 0，当前: " + df);
        }
        if (Double.isNaN(p) || p <= 0 || p >= 1) {
            throw new IllegalArgumentException("分位点必须在 (0,1) 开区间，当前: " + p);
        }
        if (Math.abs(p - 0.5) < 1e-12) {
            return 0.0;
        }
        if (p < 0.5) {
            return -tQuantile(1 - p, df);
        }
        double lo = 0, hi = 1e6;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (lo + hi) / 2;
            if (tCdf(mid, df) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    static double tCdf(double t, int df) {
        if (t == 0) {
            return 0.5;
        }
        double x = df / (df + t * t);
        double ib = incompleteBeta(x, df / 2.0, 0.5);
        return t > 0 ? 1.0 - 0.5 * ib : 0.5 * ib;
    }

    /**
     * 正则化不完全 Beta 函数 I_x(a,b)。
     *
     * <p>用连分式（Lentz 算法）而不是数值积分：连分式在 a、b 较大时收敛快，
     * 而这里的自由度动辄几十上百。x 在 0.5 左右时两侧精度对称，
     * 所以按 "(a+1)/(a+b+2)" 分界切换成 1 - I_{1-x}(b,a)，避免在 x→1 附近丢精度。
     */
    static double incompleteBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0.0;
        }
        if (x >= 1) {
            return 1.0;
        }
        double logFront = logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log1p(-x);
        double front = Math.exp(logFront);
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(x, a, b) / a;
        }
        return 1.0 - front * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        final double tiny = 1e-30;
        double c = 1.0;
        double d = 1.0 - (a + b) * x / (a + 1.0);
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1e-15) {
                break;
            }
        }
        return h;
    }

    /** Lanczos 近似计算 lnΓ(x)，用于不完全 Beta 的前置系数 */
    static double logGamma(double x) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += c[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /* ==================== 辅助 ==================== */

    private static void validateBase(int populationSize, double populationBookValue,
                                     double tolerableMisstatement, double risk) {
        if (populationSize <= 0) {
            throw new IllegalArgumentException("总体项数必须大于 0，当前: " + populationSize);
        }
        if (Double.isNaN(populationBookValue) || populationBookValue <= 0) {
            throw new IllegalArgumentException("总体账面金额必须大于 0，当前: " + populationBookValue);
        }
        if (Double.isNaN(tolerableMisstatement) || tolerableMisstatement <= 0) {
            throw new IllegalArgumentException("可容忍错报必须大于 0，当前: " + tolerableMisstatement);
        }
        if (tolerableMisstatement > populationBookValue) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "可容忍错报 %.2f 元 不能大于总体账面金额 %.2f 元",
                    tolerableMisstatement, populationBookValue));
        }
        if (Double.isNaN(risk) || risk <= 0 || risk >= 1) {
            throw new IllegalArgumentException("误拒风险必须在 (0,1) 开区间，当前: " + risk);
        }
    }
}
