package com.maning.audit.common.util;

import com.maning.audit.common.constant.Enums;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计证据可靠性计算器
 *
 * <p><b>这不是一个"随手写的加减分函数"，它的每一条权重都有准则出处。</b>
 *
 * <p>依据《中国注册会计师审计准则第 1301 号——审计证据》第三条：
 * <pre>
 * （一）外部独立来源 &gt; 其他来源
 * （二）相关控制有效时的内部生成 &gt; 控制薄弱时的内部生成
 * （三）直接获取 &gt; 间接获取或推论
 * （四）文件记录形式 &gt; 口头形式
 * （五）原件 &gt; 复印、传真、拍摄或数字化转化的电子件
 * </pre>
 *
 * <p><b>为什么是加权求和而不是"只要有一条不合格就降级"</b>：
 * 真实审计中证据的五个维度经常是混合的。比如"审计人员亲自去银行取的
 * 银行对账单原件"——外部、直接、书面、原件全优；
 * 而"被审计单位发来的ERP明细账截图"——内部、间接、电子、非原件全差。
 * 用一票否决制，后者会直接判死，但它其实是可用的 B 级证据（控制有效时）。
 * 加权能反映"部分维度好、部分维度差"的真实情况。
 *
 * <p><b>权重设计理由</b>：
 * 来源权重最高（3.0），因为准则把它放在第一位，且它是决定证据性质的根本因素；
 * 原件性权重次高（2.0），因为复印件的可篡改风险直接影响证明力；
 * 形式（1.0）和获取方式（1.0）权重较低，因为它们更多影响"获取过程的可靠性"，
 * 而不是"证据本身的信息含量"。
 * 这些权重可以调整 —— 不同事务所的底稿标准有差异，所以抽成了常量并写清依据。
 */
public final class EvidenceReliabilityEvaluator {

    /* ---------------- 权重（可调，但要说明依据） ---------------- */

    /** 来源权重 3.0：准则 1301 号 第三条（一）排在首位，是决定证据性质的根本因素 */
    private static final double W_SOURCE = 3.0;

    /** 原件性权重 2.0：复印件的篡改风险直接削弱证明力 */
    private static final double W_ORIGINALITY = 2.0;

    /** 获取方式权重 1.0：影响的是获取过程，不是信息本身 */
    private static final double W_ACQUIRE = 1.0;

    /** 形式权重 1.0：与获取方式同级 */
    private static final double W_FORM = 1.0;

    /** 内部证据在控制薄弱时的额外扣分：准则 1301 号 第三条（二） */
    private static final double PENALTY_WEAK_CONTROL = 4.0;

    private EvidenceReliabilityEvaluator() {
    }

    /**
     * 计算结果
     *
     * <p>不只返回一个等级，还返回每一项的得分和判定说明 ——
     * 因为审计底稿要求"可追溯"：复核人点开证据必须能看到
     * "为什么这条是 B 级"，而不是只看到一个孤零零的字母。
     */
    @Getter
    public static class Result {
        /** 可靠性等级 */
        private final Enums.Reliability level;
        /** 加权总分，满分 27（来源9 + 原件6 + 获取3 + 形式3，控制扣分另算） */
        private final double score;
        /** 逐项得分明细，用于前端展示判定依据 */
        private final List<Item> items;
        /** 一条人类可读的判定说明，直接写进审计底稿 */
        private final String explanation;

        Result(Enums.Reliability level, double score, List<Item> items, String explanation) {
            this.level = level;
            this.score = score;
            this.items = items;
            this.explanation = explanation;
        }
    }

    /** 单项得分明细 */
    @Getter
    public static class Item {
        /** 维度名称，如"来源" */
        private final String dimension;
        /** 取值描述，如"被审计单位内部生成" */
        private final String value;
        /** 该项得分 */
        private final double score;
        /** 该项满分 */
        private final double max;
        /** 准则依据，如"准则 1301 号 第三条（一）" */
        private final String basis;

        Item(String dimension, String value, double score, double max, String basis) {
            this.dimension = dimension;
            this.value = value;
            this.score = score;
            this.max = max;
            this.basis = basis;
        }
    }

    /**
     * 计算可靠性
     *
     * @param source        来源维度，为空时按最差处理（不填就默认不可靠，防止漏填占便宜）
     * @param acquireMethod 获取方式
     * @param form          存在形式
     * @param originality   原件性
     * @param controlEffective 内部控制是否有效。仅对内部来源生效：
     *                        准则明确"控制有效时内部生成的证据比控制薄弱时更可靠"
     */
    public static Result evaluate(Enums.EvidenceSource source,
                                  Enums.AcquireMethod acquireMethod,
                                  Enums.EvidenceForm form,
                                  Enums.Originality originality,
                                  Boolean controlEffective) {
        List<Item> items = new ArrayList<>();
        double total = 0;

        // ---- 维度一：来源 ----
        Enums.EvidenceSource s = source == null ? Enums.EvidenceSource.MANAGEMENT : source;
        double sourceScore = s.getScore() * W_SOURCE;
        items.add(new Item("来源", s.getDesc(), sourceScore, 3 * W_SOURCE, "准则 1301 号 第三条（一）"));
        total += sourceScore;

        // ---- 维度二：获取方式 ----
        Enums.AcquireMethod a = acquireMethod == null ? Enums.AcquireMethod.INDIRECT : acquireMethod;
        double acquireScore = a.getScore() * W_ACQUIRE;
        items.add(new Item("获取方式", a.getDesc(), acquireScore, 3 * W_ACQUIRE, "准则 1301 号 第三条（三）"));
        total += acquireScore;

        // ---- 维度三：存在形式 ----
        Enums.EvidenceForm f = form == null ? Enums.EvidenceForm.ORAL : form;
        double formScore = f.getScore() * W_FORM;
        items.add(new Item("存在形式", f.getDesc(), formScore, 3 * W_FORM, "准则 1301 号 第三条（四）"));
        total += formScore;

        // ---- 维度四：原件性 ----
        Enums.Originality o = originality == null ? Enums.Originality.COPY : originality;
        double origScore = o.getScore() * W_ORIGINALITY;
        items.add(new Item("原件性", o.getDesc(), origScore, 3 * W_ORIGINALITY, "准则 1301 号 第三条（五）"));
        total += origScore;

        // ---- 维度五：控制有效性（仅对内部来源有意义）----
        // 准则原文是"相关控制有效时，内部生成的证据比控制薄弱时更可靠"，
        // 所以这个条件只作用在内部证据上。外部证据不受被审计单位控制影响。
        boolean controlPenaltyApplied = false;
        if (s == Enums.EvidenceSource.INTERNAL && Boolean.FALSE.equals(controlEffective)) {
            total -= PENALTY_WEAK_CONTROL;
            controlPenaltyApplied = true;
            items.add(new Item("控制有效性", "控制薄弱", -PENALTY_WEAK_CONTROL, 0,
                    "准则 1301 号 第三条（二）"));
        } else if (s == Enums.EvidenceSource.INTERNAL) {
            items.add(new Item("控制有效性", "控制有效", 0, 0, "准则 1301 号 第三条（二）"));
        }

        total = Math.max(0, total);

        Enums.Reliability level = gradeOf(total, s, o);
        String explanation = buildExplanation(level, total, controlPenaltyApplied, s, o);

        return new Result(level, total, items, explanation);
    }

    /**
     * 分数 → 等级
     *
     * <p>满分 27 分（来源 9 + 获取 3 + 形式 3 + 原件 6 = 21？不，是下面的算法）：
     * <pre>
     * 来源  3×3.0 = 9
     * 获取  3×1.0 = 3
     * 形式  3×1.0 = 3
     * 原件  3×2.0 = 6
     * ----------------
     * 合计        21
     * </pre>
     *
     * <p>分档按"必须达到几个维度全优"来设，不按百分比切 ——
     * 因为审计上"外部+原件"这两个维度同时满足才是质变，
     * 用线性百分比会把这个质变抹平。
     */
    /**
     * 分数 → 等级
     *
     * <p>满分 21 分：
     * <pre>
     * 来源  3×3.0 = 9
     * 获取  3×1.0 = 3
     * 形式  3×1.0 = 3
     * 原件  3×2.0 = 6
     * ----------------
     * 合计        21
     * </pre>
     *
     * <p><b>分档不能简单按百分比切</b>，否则会出现「内部生成的扫描件评成 B 级」
     * 这种审计上站不住的结论。准则的逻辑是「外部独立来源」和「原件」
     * 这两个维度才对证明力产生质变，其余维度只是辅助。
     *
     * <p>所以这里改成<b>先看关键维度是否达标，再看总分</b>：
     * <ul>
     *   <li><b>A 级（很高）</b>：来源必须是外部独立，且至少是扫描件以上。
     *       只有「外部来源」这一条就能把总分推到 18 以上，但反过来
     *       内部证据无论其他维度多好，总分也到不了 18（内部来源满分 6）
     *       —— 所以 A 级天然只有外部证据能拿到，不需要额外判断</li>
     *   <li><b>B 级（高）</b>：必须同时满足「原件」或「直接获取」，
     *       且总分 ≥ 15。内部 + 间接 + 扫描件这种组合
     *       （6+2+2+4=14）拿不到 B 级，符合审计直觉</li>
     *   <li><b>C 级（中）</b>：总分 ≥ 8，可用但证明力有限，需补充佐证</li>
     *   <li><b>D 级（低）</b>：总分 &lt; 8，仅能作线索，不能作结论依据</li>
     * </ul>
     */
    private static Enums.Reliability gradeOf(double score, Enums.EvidenceSource source, Enums.Originality originality) {
        // ================================================================
        // 分档规则（第三版，经全组合校验后定型）
        //
        // 前两版都栽在同一个地方：用加权总分切档，档位会跟着分数漂移。
        // 第三版改成「性质门槛 + 分数」双条件：A 级和 B 级都先看证据性质，
        // 性质不达标，分数再高也上不去。理由来自准则 1301 号第三条的排序 ——
        // 来源是（一），原件性排在（五）；形式要件补不回来源性质的差距。
        // ================================================================

        // A 级（很高）：必须是外部独立来源，且原件或经审计人员核验的扫描件。
        //
        // 这里为什么不能用 score >= 18 兜住：
        // 内部来源全优是 6+3+3+6 = 18 分，正好撞到门槛，会把内部证据评成 A。
        // 而准则把「外部独立来源」排第一项，说明它产生的是质变，不是加分项。
        if (source == Enums.EvidenceSource.EXTERNAL && originality != Enums.Originality.COPY) {
            return Enums.Reliability.VERY_HIGH;
        }

        // B 级（高）：必须是原件，且总分 ≥ 15。
        //
        // 以前这里只看分数，结果「管理层声明 + 原件 + 直接 + 书面」= 15 分混进了 B 级。
        // 但准则 1301 号应用指南明确：管理层声明不能替代审计人员本应获取的其他证据，
        // 它只是「补充」而非「来源」。所以管理层声明单独成档，最高只到 C。
        if (originality == Enums.Originality.ORIGINAL
                && source != Enums.EvidenceSource.MANAGEMENT
                && score >= 15) {
            return Enums.Reliability.HIGH;
        }

        // C 级（中）：存在形式缺陷或来源较弱，但仍有实质证明力，需补充佐证后使用。
        // 外部复印件（17 分）、内部扫描件（14 分）、管理层声明原件（15 分）都落在这里，
        // 这正是它们在审计实务中的真实地位。
        if (score >= 8) {
            return Enums.Reliability.MEDIUM;
        }

        // D 级（低）：多重缺陷叠加，单独不能使用。
        return Enums.Reliability.LOW;
    }

    /**
     * 生成判定说明。
     *
     * <p>这段文字会原样写进底稿的「可靠性评定依据」字段，所以不能只报分数，
     * 必须说清「凭什么落在这一档」—— 复核人和外部检查看的是依据，不是数字。
     * 因此按等级分支给出不同的准则理由，而不是套一个统一模板。
     */
    private static String buildExplanation(Enums.Reliability level, double score, boolean controlPenalty,
                                           Enums.EvidenceSource source, Enums.Originality originality) {
        StringBuilder sb = new StringBuilder();
        sb.append("按准则 1301 号第三条五原则评定为 ")
                .append(level.getGrade()).append(" 级（").append(level.getDesc())
                .append("），加权得分 ").append(String.format("%.1f", score)).append("/21。");

        switch (level) {
            case VERY_HIGH -> sb.append("证据取自外部独立来源且保持原件状态，符合第三条（一）（五），")
                    .append("可直接支持审计结论。");
            case HIGH -> sb.append("证据系被审计单位内部生成但为原件，且控制运行有效，符合第三条（二）（五），")
                    .append("可单独支持结论，建议配合外部证据交叉印证。");
            case MEDIUM -> {
                if (originality == Enums.Originality.COPY) {
                    sb.append("证据为复印件/传真件/照片，不符合第三条（五）原件优于复印件的要求，篡改风险未能排除；");
                } else if (source == Enums.EvidenceSource.MANAGEMENT) {
                    sb.append("证据为管理层声明，按准则应用指南不能替代审计人员本应获取的其他审计证据；");
                } else {
                    sb.append("证据在来源、获取方式或存在形式上存在一项以上缺陷（第三条（一）（三）（四））；");
                }
                sb.append("证明力有限，须补充佐证后方可作为结论依据。");
            }
            case LOW -> sb.append("证据在来源、形式、原件性上多重缺陷叠加，单独不具备证明力，")
                    .append("须取得替代性证据或追加审计程序。");
        }

        if (controlPenalty) {
            sb.append("另因内部控制被评价为薄弱，按第三条（二）已扣减证明力。");
        }
        if (requiresSupplementary(level)) {
            sb.append("提交复核时须在备注中说明补充佐证方案。");
        }
        return sb.toString();
    }

    /**
     * 判断该等级是否需要在提交复核时强制提供补充说明。
     *
     * <p>C 级（中）及以下：单一证据不足以支持结论，必须补充佐证或说明。
     * 这条规则来自审计实务：低可靠性证据不能单独作为审计结论的依据。
     */
    public static boolean requiresSupplementary(Enums.Reliability level) {
        return !level.atLeast(Enums.Reliability.HIGH);
    }
}
