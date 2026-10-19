package com.maning.audit.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举一致性测试
 *
 * <h2>为什么要单独测枚举</h2>
 *
 * <p>枚举本身没有逻辑，但它<b>是前后端、数据库、业务规则三方共用的契约</b>。
 * 一个 code 重复、一个角色漏配级别映射，故障现场可能很难定位：
 * <ul>
 *   <li>code 重复 → 前端字典表里两个状态显示成同一个名字</li>
 *   <li>reviewLevelOf 漏了某个角色 → 那个人永远点不到复核按钮，但系统不报错</li>
 *   <li>pendingOf 与 EvidenceStatus 不对应 → 状态推进会推到一个"看起来正常但没法审"的状态</li>
 * </ul>
 *
 * <p>这几条都是"跑起来不报错，用起来才发现不对"的典型。
 * 用几条断言提前拦住，比上线后查数据便宜得多。
 */
@DisplayName("枚举契约")
class EnumsConsistencyTest {

    @Nested
    @DisplayName("证据状态 EvidenceStatus")
    class EvidenceStatusTest {

        @Test
        @DisplayName("状态码不重复")
        void codesAreUnique() {
            Set<Integer> codes = new HashSet<>();
            for (Enums.EvidenceStatus s : Enums.EvidenceStatus.values()) {
                assertThat(codes.add(s.getCode()))
                        .as("状态码 %d 重复（%s）", s.getCode(), s.getDesc())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("状态描述不重复（前端字典里不能出现两个同名状态）")
        void descsAreUnique() {
            Set<String> descs = new HashSet<>();
            for (Enums.EvidenceStatus s : Enums.EvidenceStatus.values()) {
                assertThat(descs.add(s.getDesc()))
                        .as("状态描述「%s」重复", s.getDesc())
                        .isTrue();
            }
        }

        /**
         * 三个待复核状态必须分别指向 1/2/3 级，且不能互相串。
         *
         * <p>这是三级复核的骨架：
         * 1=待详细复核、4=待一般复核、5=待重点复核。
         * 一旦串了，整个流程会"跳级"或"卡死"。
         */
        @Test
        @DisplayName("三个待复核状态精确对应 1/2/3 级")
        void pendingStatusesMapToOneTwoThree() {
            assertThat(Enums.EvidenceStatus.PENDING_REVIEW.getReviewLevel()).isEqualTo(1);
            assertThat(Enums.EvidenceStatus.PENDING_GENERAL.getReviewLevel()).isEqualTo(2);
            assertThat(Enums.EvidenceStatus.PENDING_KEY.getReviewLevel()).isEqualTo(3);
        }

        /**
         * levelOf 与 pendingOf 必须互为逆运算。
         *
         * <p>这一条是全类里最值钱的断言：它不需要知道具体码值，
         * 只要求"根据状态取级别"和"根据级别取状态"能对得上。
         * 以后重排状态码时，只要这条还是绿的，推进逻辑就不会错。
         */
        @Test
        @DisplayName("levelOf 与 pendingOf 互为逆运算")
        void levelOfAndPendingOfAreInverse() {
            for (int level = 1; level <= 3; level++) {
                var status = Enums.EvidenceStatus.pendingOf(level);
                assertThat(Enums.EvidenceStatus.levelOf(status.getCode()))
                        .as("pendingOf(%d)=%s 反查级别应仍为 %d", level, status.getDesc(), level)
                        .isEqualTo(level);
            }
        }

        @Test
        @DisplayName("草稿/已确认/已驳回都不在复核中")
        void terminalStatusesAreNotReviewing() {
            for (var s : new Enums.EvidenceStatus[]{
                    Enums.EvidenceStatus.DRAFT,
                    Enums.EvidenceStatus.CONFIRMED,
                    Enums.EvidenceStatus.REJECTED}) {
                assertThat(Enums.EvidenceStatus.isReviewing(s.getCode())).isFalse();
                assertThat(Enums.EvidenceStatus.levelOf(s.getCode())).isZero();
            }
        }

        @Test
        @DisplayName("非法码值返回「未知」而不是抛异常")
        void unknownCodeReturnsPlaceholder() {
            assertThat(Enums.EvidenceStatus.descOf(99)).isEqualTo("未知");
            assertThat(Enums.EvidenceStatus.descOf(null)).isEqualTo("未知");
            assertThat(Enums.EvidenceStatus.levelOf(null)).isZero();
        }
    }

    @Nested
    @DisplayName("角色 Role")
    class RoleTest {

        /**
         * 三个复核岗位必须各自对应唯一级别，且不重复。
         *
         * <p>如果 LEADER 和 MANAGER 都映射到 1 级，
         * 那"谁能审一级"就会出现两个答案，制度就乱了。
         */
        @Test
        @DisplayName("三个复核岗位各自对应唯一级别")
        void reviewRolesMapToDistinctLevels() {
            assertThat(Enums.Role.LEADER.reviewLevelOf()).isEqualTo(1);
            assertThat(Enums.Role.MANAGER.reviewLevelOf()).isEqualTo(2);
            assertThat(Enums.Role.PARTNER.reviewLevelOf()).isEqualTo(3);

            Set<Integer> levels = new HashSet<>(Arrays.asList(
                    Enums.Role.LEADER.reviewLevelOf(),
                    Enums.Role.MANAGER.reviewLevelOf(),
                    Enums.Role.PARTNER.reviewLevelOf()));
            assertThat(levels).as("三个岗位不能映射到同一个级别").hasSize(3);
        }

        @ParameterizedTest(name = "{0} 无复核级别")
        @EnumSource(value = Enums.Role.class, names = {"ADMIN", "AUDITOR", "VIEWER"})
        @DisplayName("管理/编制/只读角色本身不绑定级别")
        void nonReviewRolesHaveNoLevel(Enums.Role role) {
            assertThat(role.reviewLevelOf())
                    .as("%s 通过 canReview 里的 ADMIN 分支获得代行权，而不是靠级别绑定", role.getDesc())
                    .isNull();
        }

        @Test
        @DisplayName("角色码与描述都不重复")
        void codesAndDescsUnique() {
            Set<String> codes = new HashSet<>();
            Set<String> descs = new HashSet<>();
            for (Enums.Role r : Enums.Role.values()) {
                assertThat(codes.add(r.getCode())).isTrue();
                assertThat(descs.add(r.getDesc())).as("角色描述「%s」重复", r.getDesc()).isTrue();
            }
        }

        @Test
        @DisplayName("isValid 精确匹配")
        void isValid() {
            assertThat(Enums.Role.isValid("LEADER")).isTrue();
            assertThat(Enums.Role.isValid("leader")).as("大小写敏感").isFalse();
            assertThat(Enums.Role.isValid("BOSS")).isFalse();
            assertThat(Enums.Role.isValid(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("流转动作 FlowAction")
    class FlowActionTest {

        /**
         * 三级 × 两种动作 = 6 个编码，且全部唯一。
         */
        @Test
        @DisplayName("六个分级动作码唯一")
        void codesUnique() {
            Set<String> codes = new HashSet<>();
            for (Enums.FlowAction a : Enums.FlowAction.values()) {
                assertThat(codes.add(a.getCode())).as("动作码 %s 重复", a.getCode()).isTrue();
            }
            assertThat(codes).contains("APPROVE_L1", "APPROVE_L2", "APPROVE_L3",
                    "REJECT_L1", "REJECT_L2", "REJECT_L3");
        }

        @Test
        @DisplayName("approveOf / rejectOf 与级别一一对应")
        void actionOfLevel() {
            assertThat(Enums.FlowAction.approveOf(1)).isEqualTo(Enums.FlowAction.APPROVE_L1);
            assertThat(Enums.FlowAction.approveOf(2)).isEqualTo(Enums.FlowAction.APPROVE_L2);
            assertThat(Enums.FlowAction.approveOf(3)).isEqualTo(Enums.FlowAction.APPROVE_L3);
            assertThat(Enums.FlowAction.rejectOf(1)).isEqualTo(Enums.FlowAction.REJECT_L1);
            assertThat(Enums.FlowAction.rejectOf(2)).isEqualTo(Enums.FlowAction.REJECT_L2);
            assertThat(Enums.FlowAction.rejectOf(3)).isEqualTo(Enums.FlowAction.REJECT_L3);
        }

        /**
         * 历史旧值必须翻译成"旧版单级"，而不是露出裸英文或冒充三级。
         *
         * <p>这条守的是不可篡改原则：旧记录就是单级复核时代留下的，
         * 硬写成"重点复核通过"等于伪造底稿。
         */
        @Test
        @DisplayName("旧版 APPROVE/REJECT 翻译为「旧版单级」")
        void legacyActionsTranslated() {
            assertThat(Enums.FlowAction.descOf("APPROVE")).isEqualTo("复核通过（旧版单级）");
            assertThat(Enums.FlowAction.descOf("REJECT")).isEqualTo("复核驳回（旧版单级）");
        }

        @Test
        @DisplayName("未知动作码原样返回，不吞掉信息")
        void unknownActionReturnsAsIs() {
            assertThat(Enums.FlowAction.descOf("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
        }
    }

    @Nested
    @DisplayName("可靠性等级 Reliability")
    class ReliabilityTest {

        /**
         * atLeast 比较依赖 code 大小，所以 code 必须严格按可靠性递增。
         *
         * <p>如果哪天有人把 LOW 的 code 改成 4，整个等级比较就反了 ——
         * "低"会被判成"比高还高"。
         */
        @Test
        @DisplayName("等级码按可靠性递增，atLeast 语义正确")
        void atLeastFollowsCodeOrder() {
            assertThat(Enums.Reliability.LOW.getCode()).isLessThan(Enums.Reliability.MEDIUM.getCode());
            assertThat(Enums.Reliability.MEDIUM.getCode()).isLessThan(Enums.Reliability.HIGH.getCode());
            assertThat(Enums.Reliability.HIGH.getCode()).isLessThan(Enums.Reliability.VERY_HIGH.getCode());

            assertThat(Enums.Reliability.HIGH.atLeast(Enums.Reliability.MEDIUM)).isTrue();
            assertThat(Enums.Reliability.MEDIUM.atLeast(Enums.Reliability.HIGH)).isFalse();
            assertThat(Enums.Reliability.HIGH.atLeast(Enums.Reliability.HIGH)).as("同级别算达到").isTrue();
        }

        @Test
        @DisplayName("字母等级按 A/B/C/D 映射")
        void gradeMapping() {
            assertThat(Enums.Reliability.VERY_HIGH.getGrade()).isEqualTo("A");
            assertThat(Enums.Reliability.HIGH.getGrade()).isEqualTo("B");
            assertThat(Enums.Reliability.MEDIUM.getGrade()).isEqualTo("C");
            assertThat(Enums.Reliability.LOW.getGrade()).isEqualTo("D");
        }
    }

    @Nested
    @DisplayName("准则 1301 号五维度枚举")
    class ReliabilityDimensions {

        /**
         * 四个维度的评分都必须是 3/2/1 三档，不能有偏差。
         *
         * <p>因为 EvidenceReliabilityEvaluator 里"满分 21"这个数字
         * 是由 3×3.0 + 3×1.0 + 3×1.0 + 3×2.0 推出来的。
         * 某个维度如果改成 4 档（比如 4/3/2/1），满分就不对了，
         * 分档阈值全部失效 —— 而且不会报错，只会悄悄评错级。
         */
        @Test
        @DisplayName("四个维度的满分都必须是 3 档")
        void allDimensionsHaveThreeLevels() {
            assertThat(Arrays.stream(Enums.EvidenceSource.values()).mapToInt(Enums.EvidenceSource::getScore))
                    .containsExactlyInAnyOrder(3, 2, 1);
            assertThat(Arrays.stream(Enums.AcquireMethod.values()).mapToInt(Enums.AcquireMethod::getScore))
                    .containsExactlyInAnyOrder(3, 2, 1);
            assertThat(Arrays.stream(Enums.EvidenceForm.values()).mapToInt(Enums.EvidenceForm::getScore))
                    .containsExactlyInAnyOrder(3, 2, 1);
            assertThat(Arrays.stream(Enums.Originality.values()).mapToInt(Enums.Originality::getScore))
                    .containsExactlyInAnyOrder(3, 2, 1);
        }

        @Test
        @DisplayName("外部来源得分必须高于内部，内部高于管理层声明")
        void sourceOrderFollowsStandard() {
            assertThat(Enums.EvidenceSource.EXTERNAL.getScore())
                    .as("准则 1301 号第三条（一）：外部独立来源最可靠")
                    .isGreaterThan(Enums.EvidenceSource.INTERNAL.getScore());
            assertThat(Enums.EvidenceSource.INTERNAL.getScore())
                    .isGreaterThan(Enums.EvidenceSource.MANAGEMENT.getScore());
        }

        @Test
        @DisplayName("原件得分必须高于扫描件，扫描件高于复印件")
        void originalityOrderFollowsStandard() {
            assertThat(Enums.Originality.ORIGINAL.getScore())
                    .as("准则 1301 号第三条（五）：原件优于复印件")
                    .isGreaterThan(Enums.Originality.SCANNED.getScore());
            assertThat(Enums.Originality.SCANNED.getScore())
                    .isGreaterThan(Enums.Originality.COPY.getScore());
        }

        @Test
        @DisplayName("未填写时降级为「未填写」而不是抛异常")
        void unknownDimensionReturnsPlaceholder() {
            assertThat(Enums.EvidenceSource.descOf("NOPE")).isEqualTo("未填写");
            assertThat(Enums.AcquireMethod.descOf(null)).isEqualTo("未填写");
            assertThat(Enums.EvidenceForm.descOf("")).isEqualTo("未填写");
            assertThat(Enums.Originality.descOf("X")).isEqualTo("未填写");
        }
    }
}
