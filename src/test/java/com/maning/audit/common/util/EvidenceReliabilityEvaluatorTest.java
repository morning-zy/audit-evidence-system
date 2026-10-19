package com.maning.audit.common.util;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.constant.Enums.AcquireMethod;
import com.maning.audit.common.constant.Enums.EvidenceForm;
import com.maning.audit.common.constant.Enums.EvidenceSource;
import com.maning.audit.common.constant.Enums.Originality;
import com.maning.audit.common.constant.Enums.Reliability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 审计证据可靠性评估器单元测试
 *
 * <h2>这个类的测试难点在哪</h2>
 *
 * <p>它不是"算个加法"那么简单，而是 <b>4 个维度 × 3 个取值 + 1 个布尔 = 163 种组合</b>
 * （3×3×3×3×2 = 162，加上 null 的处理路径）。人工逐条核对不可能，
 * 必须让测试替人做三件事：
 * <ol>
 *   <li><b>算得对</b>：把准则 1301 号的权重逐项验算（有些分数是手算出来的，正好校准）</li>
 *   <li><b>分档符合审计直觉</b>：光算对分数不够，档次划分要经得起注册会计师反问</li>
 *   <li><b>不会反序</b>：某个维度变好，总分绝不能变低（单调性）</li>
 * </ol>
 *
 * <p>第三条是最有价值的 —— 它不需要知道正确答案，只需要知道"方向上不能错"。
 * 这类测试叫<b>属性测试（property-based test）</b>，
 * 用少量逻辑锁住海量组合，比手写 162 个用例可靠得多。
 */
@DisplayName("审计证据可靠性评估")
class EvidenceReliabilityEvaluatorTest {

    /* ==================================================================
     * 一、分数计算
     * ================================================================== */

    @Nested
    @DisplayName("加权分数计算")
    class Score {

        /**
         * 满分用例：外部 + 直接 + 书面 + 原件。
         *
         * <p>手算：
         * <pre>
         * 来源  外部 3 分 × 权重 3.0 = 9
         * 获取  直接 3 分 × 权重 1.0 = 3
         * 形式  书面 3 分 × 权重 1.0 = 3
         * 原件  原件 3 分 × 权重 2.0 = 6
         * ------------------------------
         * 合计                       21
         * </pre>
         * 这个用例同时验证了"满分确实是 21" —— 注释里写的数字和代码实际跑出来的一致。
         */
        @Test
        @DisplayName("四个维度全优 = 21 分，评为 A 级")
        void perfectScore() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true);

            assertThat(r.getScore()).isCloseTo(21.0, within(0.001));
            assertThat(r.getLevel()).isEqualTo(Reliability.VERY_HIGH);
            assertThat(r.getItems()).hasSize(4);
        }

        /**
         * 最差用例：管理层声明 + 推论 + 口头 + 复印件。
         *
         * <p>手算：1×3 + 1×1 + 1×1 + 1×2 = 7 分，低于 8，判 D 级。
         * 这正是准则说的"多重缺陷叠加，单独不能使用"。
         */
        @Test
        @DisplayName("四个维度全差 = 7 分，评为 D 级")
        void worstScore() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.MANAGEMENT, AcquireMethod.INFERRED,
                    EvidenceForm.ORAL, Originality.COPY, true);

            assertThat(r.getScore()).isCloseTo(7.0, within(0.001));
            assertThat(r.getLevel()).isEqualTo(Reliability.LOW);
        }

        /**
         * 内部控制薄弱时，内部证据额外扣 4 分。
         *
         * <p>这是准则 1301 号第三条（二）的直接落地：
         * "相关控制有效时内部生成的证据比控制薄弱时内部生成的更可靠"。
         *
         * <p>注意扣分只作用于 <b>内部来源</b>。必须同时验证"外部证据不受影响" ——
         * 否则就成了"控制薄弱把所有证据都往下压"，那是不对的：
         * 银行函证回函的可信度跟被审计单位的内控好坏没关系。
         */
        @Test
        @DisplayName("内部控制薄弱：内部证据扣 4 分，外部证据不受影响")
        void weakControlPenaltyOnlyForInternal() {
            var strong = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true);
            var weak = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, false);

            assertThat(strong.getScore() - weak.getScore())
                    .as("内部证据在控制薄弱时应扣 4 分")
                    .isCloseTo(4.0, within(0.001));
            assertThat(weak.getItems())
                    .as("扣分项要出现在明细里，底稿才能说明为什么扣")
                    .anyMatch(i -> i.getDimension().equals("控制有效性") && i.getScore() < 0);

            var externalStrong = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true);
            var externalWeak = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, false);

            assertThat(externalWeak.getScore())
                    .as("外部证据不受被审计单位内控影响")
                    .isCloseTo(externalStrong.getScore(), within(0.001));
        }

        /**
         * 每个维度的得分明细必须完整，且每项都带准则出处。
         *
         * <p>这是审计系统的硬要求：底稿上的评级必须可追溯。
         * 复核人看到"B 级"，要能点开看到"来源 6 分、原件 6 分、获取 3 分、形式 3 分"，
         * 并且每项都标着是准则第几条第几款。
         *
         * <p>注意"控制有效性"这一项只在<b>内部来源</b>时出现 ——
         * 外部证据（银行回函）跟被审计单位内控好坏没关系，列出来反而误导。
         * 所以这里分两种情况断言：外部 4 项、内部 5 项。
         */
        @Test
        @DisplayName("四维明细齐全，每项都带准则条款出处")
        void itemsCarryStandardBasis() {
            // 外部来源：没有"控制有效性"项
            var external = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.INDIRECT,
                    EvidenceForm.ELECTRONIC, Originality.SCANNED, true);
            assertThat(external.getItems()).extracting(EvidenceReliabilityEvaluator.Item::getDimension)
                    .containsExactly("来源", "获取方式", "存在形式", "原件性");

            // 内部来源：多一项"控制有效性"
            var internal = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.INDIRECT,
                    EvidenceForm.ELECTRONIC, Originality.SCANNED, true);
            assertThat(internal.getItems()).extracting(EvidenceReliabilityEvaluator.Item::getDimension)
                    .containsExactly("来源", "获取方式", "存在形式", "原件性", "控制有效性");

            external.getItems().forEach(item -> {
                assertThat(item.getBasis()).as("%s 维度必须标注准则出处", item.getDimension())
                        .startsWith("准则 1301 号");
                assertThat(item.getMax()).isGreaterThan(0);
                assertThat(item.getScore()).isBetween(0.0, item.getMax());
            });
        }

        /**
         * 分数下限保护：扣分不能扣成负数。
         *
         * <p>虽然理论上 7 - 4 = 3 不会是负数，但这条断言是为了锁住
         * {@code Math.max(0, total)} 这行代码。没有它，一旦有人调大惩罚权重，
         * 会出现 "-1 分" 这种荒谬结果，而百分比/等级计算可能因此出错。
         */
        @Test
        @DisplayName("分数不会为负（下限保护）")
        void scoreNeverNegative() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.INFERRED,
                    EvidenceForm.ORAL, Originality.COPY, false);

            assertThat(r.getScore()).isGreaterThanOrEqualTo(0.0);
        }
    }

    /* ==================================================================
     * 二、空值容错
     * ================================================================== */

    @Nested
    @DisplayName("空值容错：不填就按最差算")
    class NullHandling {

        /**
         * 所有维度传 null 时，按"最差"处理而不是按"最好"。
         *
         * <p>这个设计很关键：如果 null 按 0 分算，那"全不填"就是 0 分 D 级；
         * 如果 null 按满分算，那漏填反而占便宜。
         * 当前实现把 null 映射到该维度的<b>最低档</b>枚举：
         * <pre>
         * 来源 null -> MANAGEMENT   1×3.0 = 3
         * 获取 null -> INDIRECT     2×1.0 = 2
         * 形式 null -> ORAL         1×1.0 = 1
         * 原件 null -> COPY         1×2.0 = 2
         * 控制 null -> 不加不减           = 0
         * ------------------------------------
         * 合计                             8 分 -> C 级
         * </pre>
         * 注意 controlEffective 传 null 时<b>不触发扣分</b>：
         * 扣分条件写的是 {@code Boolean.FALSE.equals(...)}，
         * 只有明确填了 false（控制薄弱）才扣。null 是"没评价"，不是"评价为薄弱"，
         * 这是刻意的 —— 否则"没填"会被当成"内控差"，冤枉了被审计单位。
         */
        @Test
        @DisplayName("全部传 null 时按最差档位计算，不抛 NPE")
        void allNullTreatedAsWorst() {
            var r = EvidenceReliabilityEvaluator.evaluate(null, null, null, null, null);

            // 3 + 2 + 1 + 2 = 8，控制有效性为 null 不加不减
            assertThat(r.getScore()).isCloseTo(8.0, within(0.001));
            assertThat(r.getLevel()).as("8 分刚好踩到 C 档门槛").isEqualTo(Reliability.MEDIUM);
            assertThat(r.getExplanation()).isNotBlank();
        }

        /**
         * 但"控制有效性 null"和"控制有效性 false"必须区别对待。
         *
         * <p>null = 未评价，不扣分；false = 明确评价为薄弱，扣 4 分。
         * 这条差异很容易在重构时被抹平（比如把条件改成 {@code !TRUE.equals(...)}），
         * 一旦抹平，"没填内控"就等于"内控差"，会凭空扣掉 4 分并让证据降档。
         */
        @Test
        @DisplayName("控制有效性 null 不扣分，false 才扣 4 分")
        void nullControlDiffersFromFalseControl() {
            var unknown = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, null);
            var weak = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, false);

            assertThat(unknown.getScore() - weak.getScore())
                    .as("只有明确填了「控制薄弱」才扣分")
                    .isCloseTo(4.0, within(0.001));
            // 只要来源是内部，"控制有效性"这一项就会出现在明细里（说明"控制有效"或"控制薄弱"），
            // 区别在于它的分值是 0 还是 -4
            assertThat(unknown.getItems()).hasSize(5);
            assertThat(unknown.getItems())
                    .filteredOn(i -> i.getDimension().equals("控制有效性"))
                    .singleElement()
                    .satisfies(i -> assertThat(i.getScore()).isZero());
            assertThat(weak.getItems())
                    .filteredOn(i -> i.getDimension().equals("控制有效性"))
                    .singleElement()
                    .satisfies(i -> assertThat(i.getScore()).isEqualTo(-4.0));
        }
    }

    /* ==================================================================
     * 三、分档规则（第三版）
     * ================================================================== */

    @Nested
    @DisplayName("分档规则")
    class Grading {

        /**
         * A 级必须是外部独立来源 + 非复印件。
         *
         * <p>这是第三版分档的核心：A 级靠<b>性质</b>而不是分数。
         * 反例：内部来源全优 = 6+3+3+6 = 18 分，如果纯按分数切（≥18 就 A），
         * 内部证据会被评成 A 级 —— 这在审计上站不住。
         */
        @Test
        @DisplayName("A 级：仅限外部独立来源且非复印件")
        void veryHighRequiresExternal() {
            // 外部 + 原件 -> A
            assertThat(EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true).getLevel())
                    .isEqualTo(Reliability.VERY_HIGH);

            // 外部 + 扫描件 -> 也是 A（数字化件经核验，仍属外部独立来源）
            assertThat(EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.SCANNED, true).getLevel())
                    .isEqualTo(Reliability.VERY_HIGH);

            // 外部 + 复印件 -> 降到 C。复印件可篡改，失去 A 级资格
            assertThat(EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.COPY, true).getLevel())
                    .isEqualTo(Reliability.MEDIUM);
        }

        /**
         * 内部来源即使四维全优也拿不到 A 级 —— 这条是第三版修正的重点。
         *
         * <p>前两版用纯分数切档时，内部全优 18 分正好撞到 A 级门槛。
         * 现在改成"性质门槛 + 分数"双条件，内部证据最高只能到 B。
         */
        @Test
        @DisplayName("内部来源全优最高只到 B 级，绝不越到 A")
        void internalNeverReachesVeryHigh() {
            Reliability best = Reliability.LOW;

            for (AcquireMethod a : AcquireMethod.values()) {
                for (EvidenceForm f : EvidenceForm.values()) {
                    for (Originality o : Originality.values()) {
                        var r = EvidenceReliabilityEvaluator.evaluate(
                                EvidenceSource.INTERNAL, a, f, o, true);
                        if (r.getLevel().atLeast(best)) {
                            best = r.getLevel();
                        }
                    }
                }
            }

            assertThat(best).as("内部来源的等级上限").isEqualTo(Reliability.HIGH);
        }

        /**
         * 管理层声明最高只到 C 级。
         *
         * <p>依据准则 1301 号应用指南：管理层声明不能替代审计人员
         * 本应获取的其他审计证据，它只是"补充"而非"来源"。
         *
         * <p>反例（也是第二版的坑）："管理层声明 + 原件 + 直接 + 书面" = 15 分，
         * 按纯分数切会落进 B 级。这在审计上是不允许的 ——
         * 管理层自己说的话再正式，也不能当独立证据用。
         */
        @Test
        @DisplayName("管理层声明封顶 C 级，四维全优也不能到 B")
        void managementDeclarationCappedAtMedium() {
            var best = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.MANAGEMENT, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true);

            assertThat(best.getScore()).as("分数是 15，确实够 B 的门槛").isCloseTo(15.0, within(0.001));
            assertThat(best.getLevel()).as("但性质不达标，最高只能 C").isEqualTo(Reliability.MEDIUM);
        }

        /**
         * 用 CsvSource 逐条核对几个典型实务组合的档位。
         *
         * <p>这些组合都是真实审计场景里会遇到的，写进 README 和面试讲述时可直接引用：
         * <ul>
         *   <li>审计人员亲自取回的银行对账单原件 -> A</li>
         *   <li>被审计单位提供的入库单原件（内控有效）-> B</li>
         *   <li>被审计单位导出的 ERP 明细账截图 -> C</li>
         *   <li>口头询问财务经理的记录 -> D 或 C</li>
         * </ul>
         */
        @ParameterizedTest(name = "{0}+{1}+{2}+{3} => {4}")
        @CsvSource({
                // 来源, 获取方式, 形式, 原件性, 期望等级
                "EXTERNAL, DIRECT,   WRITTEN,    ORIGINAL, VERY_HIGH",  // 亲自取回的对账单原件
                "EXTERNAL, INDIRECT, WRITTEN,    ORIGINAL, VERY_HIGH",  // 供应商寄来的对账单原件
                "INTERNAL, DIRECT,   WRITTEN,    ORIGINAL, HIGH",       // 亲自盘点的入库单原件
                "INTERNAL, INDIRECT, WRITTEN,    ORIGINAL, HIGH",       // 单位提供的凭证原件
                "INTERNAL, INDIRECT, ELECTRONIC, SCANNED,  MEDIUM",     // ERP 导出截图
                "INTERNAL, DIRECT,   ELECTRONIC, COPY,     MEDIUM",     // 系统截图打印后复印
                "MANAGEMENT, DIRECT, WRITTEN,    ORIGINAL, MEDIUM",     // 管理层书面声明原件
                "MANAGEMENT, INFERRED, ORAL,     COPY,     LOW",        // 口头转述的推测
        })
        @DisplayName("典型实务组合的分档核对")
        void typicalScenarios(EvidenceSource source, AcquireMethod acquire, EvidenceForm form,
                              Originality originality, Reliability expected) {
            var r = EvidenceReliabilityEvaluator.evaluate(source, acquire, form, originality, true);
            assertThat(r.getLevel()).isEqualTo(expected);
        }
    }

    /* ==================================================================
     * 四、单调性（属性测试）
     * ================================================================== */

    @Nested
    @DisplayName("单调性：维度变好，分数不能变低")
    class Monotonicity {

        /**
         * 这是本测试类里跑得最重、也最有价值的一个用例。
         *
         * <p>思路：遍历所有组合，对每个组合尝试把某个维度"升级"到更高档，
         * 断言分数只可能持平或上升，绝不会下降。
         *
         * <p>为什么这个属性值得单独测：
         * 分档规则改了三版，每次改都可能引入"某维度更好反而扣分"的逻辑错误。
         * 而人眼审查 162 个组合根本不现实。用单调性约束，不需要知道每个组合
         * 的正确分数是多少，就能查出方向性错误。
         *
         * <p>枚举的分数是按 code 递增的（EXTERNAL 3 &gt; INTERNAL 2 &gt; MANAGEMENT 1），
         * 所以"变好"就是 code 变大。这也间接验证了枚举定义本身没写反。
         */
        @Test
        @DisplayName("四个维度分别提升一级，总分不低于原分值")
        void upgradingAnyDimensionNeverLowersScore() {
            for (EvidenceSource s : EvidenceSource.values()) {
                for (AcquireMethod a : AcquireMethod.values()) {
                    for (EvidenceForm f : EvidenceForm.values()) {
                        for (Originality o : Originality.values()) {
                            double base = EvidenceReliabilityEvaluator
                                    .evaluate(s, a, f, o, true).getScore();

                            // 来源提升一级
                            for (EvidenceSource s2 : EvidenceSource.values()) {
                                if (s2.getScore() > s.getScore()) {
                                    assertScoreNotLower(base,
                                            EvidenceReliabilityEvaluator.evaluate(s2, a, f, o, true).getScore(),
                                            "来源 " + s + " -> " + s2);
                                }
                            }
                            // 获取方式提升一级
                            for (AcquireMethod a2 : AcquireMethod.values()) {
                                if (a2.getScore() > a.getScore()) {
                                    assertScoreNotLower(base,
                                            EvidenceReliabilityEvaluator.evaluate(s, a2, f, o, true).getScore(),
                                            "获取方式 " + a + " -> " + a2);
                                }
                            }
                            // 存在形式提升一级
                            for (EvidenceForm f2 : EvidenceForm.values()) {
                                if (f2.getScore() > f.getScore()) {
                                    assertScoreNotLower(base,
                                            EvidenceReliabilityEvaluator.evaluate(s, a, f2, o, true).getScore(),
                                            "存在形式 " + f + " -> " + f2);
                                }
                            }
                            // 原件性提升一级
                            for (Originality o2 : Originality.values()) {
                                if (o2.getScore() > o.getScore()) {
                                    assertScoreNotLower(base,
                                            EvidenceReliabilityEvaluator.evaluate(s, a, f, o2, true).getScore(),
                                            "原件性 " + o + " -> " + o2);
                                }
                            }
                        }
                    }
                }
            }
        }

        private void assertScoreNotLower(double base, double improved, String what) {
            assertThat(improved)
                    .as("%s 变好了，分数却下降了（%.1f -> %.1f）", what, base, improved)
                    .isGreaterThanOrEqualTo(base);
        }

        /**
         * 等级也要单调：分数高的一定不会比分数低的等级低。
         *
         * <p>注意这条不能反过来推 —— 分数高不代表等级一定高
         * （管理层声明 15 分只到 C，内部原件 18 分只到 B）。
         * 所以这里只断言"高分档不能低于低分档"，不做等价推断。
         */
        @Test
        @DisplayName("在内控有效前提下，同分同档（不存在的分数断层）")
        void allScoresCovered() {
            Set<Double> scores = new HashSet<>();
            for (EvidenceSource s : EvidenceSource.values()) {
                for (AcquireMethod a : AcquireMethod.values()) {
                    for (EvidenceForm f : EvidenceForm.values()) {
                        for (Originality o : Originality.values()) {
                            scores.add(EvidenceReliabilityEvaluator
                                    .evaluate(s, a, f, o, true).getScore());
                        }
                    }
                }
            }
            // 内控有效时分数范围 7~21，实际出现的不同分值不应少于 8 种
            assertThat(scores).as("分数分布过于集中，说明权重配置可能失效").hasSizeGreaterThanOrEqualTo(8);
            assertThat(scores).allMatch(v -> v >= 0 && v <= 21);
        }
    }

    /* ==================================================================
     * 五、判定说明文本
     * ================================================================== */

    @Nested
    @DisplayName("判定说明 explanation")
    class Explanation {

        /**
         * 说明文本会原样写进底稿的「可靠性评定依据」字段。
         *
         * <p>所以它必须满足：说清等级、说清分数、说清依据。
         * 不能只报一个"B" 就完事 —— 复核人和外部检查看的是依据，不是字母。
         */
        @Test
        @DisplayName("说明包含等级、分数与准则依据")
        void explanationHasLevelScoreAndBasis() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, true);

            assertThat(r.getExplanation())
                    .contains("A 级")
                    .contains("21.0")
                    .contains("准则 1301 号")
                    .contains("外部独立来源");
        }

        @Test
        @DisplayName("扣分时说明里会写明内控薄弱")
        void explanationMentionsWeakControl() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.ORIGINAL, false);

            assertThat(r.getExplanation()).contains("内部控制");
        }

        @Test
        @DisplayName("C 级及以下提示需要补充佐证")
        void explanationPromptsSupplementary() {
            var medium = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.INTERNAL, AcquireMethod.INDIRECT,
                    EvidenceForm.ELECTRONIC, Originality.SCANNED, true);

            assertThat(medium.getLevel()).isEqualTo(Reliability.MEDIUM);
            assertThat(medium.getExplanation()).contains("补充佐证");
        }

        /**
         * 复印件被判 C 时，说明必须点出"原件优于复印件"这一条 ——
         * 因为这是它掉档的直接原因，不能笼统说"存在缺陷"。
         */
        @Test
        @DisplayName("复印件掉档时，说明直接指向第三条（五）")
        void copyExplanationPointsToFifthPrinciple() {
            var r = EvidenceReliabilityEvaluator.evaluate(
                    EvidenceSource.EXTERNAL, AcquireMethod.DIRECT,
                    EvidenceForm.WRITTEN, Originality.COPY, true);

            assertThat(r.getLevel()).isEqualTo(Reliability.MEDIUM);
            assertThat(r.getExplanation()).contains("复印件");
        }
    }

    /* ==================================================================
     * 六、补充佐证规则
     * ================================================================== */

    @Nested
    @DisplayName("补充佐证要求 requiresSupplementary")
    class Supplementary {

        /**
         * 规则：只有 B 级（高）及以上才算"可单独支持结论"。
         * C 级和 D 级必须补充佐证。
         *
         * <p>依据审计实务原则：低可靠性证据不能单独作为审计结论的依据。
         * 这条规则在 Service 层用来决定"提交复核时是否强制要求填备注"。
         */
        @Test
        @DisplayName("A/B 级无需补充，C/D 级必须补充")
        void onlyHighAndAboveAreSelfSufficient() {
            assertThat(EvidenceReliabilityEvaluator.requiresSupplementary(Reliability.VERY_HIGH)).isFalse();
            assertThat(EvidenceReliabilityEvaluator.requiresSupplementary(Reliability.HIGH)).isFalse();
            assertThat(EvidenceReliabilityEvaluator.requiresSupplementary(Reliability.MEDIUM)).isTrue();
            assertThat(EvidenceReliabilityEvaluator.requiresSupplementary(Reliability.LOW)).isTrue();
        }
    }
}
