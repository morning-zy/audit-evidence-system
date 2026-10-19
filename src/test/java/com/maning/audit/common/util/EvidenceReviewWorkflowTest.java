package com.maning.audit.common.util;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.constant.Enums.EvidenceStatus;
import com.maning.audit.common.constant.Enums.FlowAction;
import com.maning.audit.common.constant.Enums.Role;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三级复核状态机单元测试
 *
 * <h2>为什么先测这个类</h2>
 *
 * <p>{@link EvidenceReviewWorkflow} 是全项目唯一一个"不依赖数据库、不依赖 ThreadLocal"
 * 的业务规则类。它的每个方法都是纯函数：给同样的输入永远得到同样的输出。
 * 这类代码最适合写单元测试 —— 不用起 Spring 容器，跑一次不到 1 秒，
 * 而且能<b>穷举</b>状态 × 角色 × 级别的全部组合，
 * 这是集成测试（冒烟测试、Postman）做不到的。
 *
 * <h2>测试组织方式</h2>
 *
 * <p>用 {@code @Nested} 按被测方法分组，而不是按"第 1 个用例、第 2 个用例"排下来。
 * 理由是：半年后回来加需求时，能在 3 秒内找到"推进规则的测试在哪一组"。
 * 组名就是需求的名字。
 *
 * <p>断言统一用 AssertJ 的 {@code assertThat(...)}，不用 JUnit 自带的 assertEquals。
 * AssertJ 的失败信息是可读的自然语言（"expected 2 but was 4"），
 * 而且有 {@code assertThatThrownBy(...).isInstanceOf(...).hasFieldOrPropertyWithValue(...)}
 * 这种链式断言，一个异常能同时验证类型 + 错误码 + 文案。
 */
@DisplayName("三级复核状态机")
class EvidenceReviewWorkflowTest {

    /* ==================================================================
     * 一、级别判定
     * ================================================================== */

    @Nested
    @DisplayName("级别判定 currentReviewLevel")
    class LevelOf {

        /**
         * 用 @EnumSource 遍历整个枚举，而不是手写 6 个 case。
         *
         * <p>好处：以后 EvidenceStatus 加了第 7 个状态，这个测试会自动覆盖它 ——
         * 如果新状态忘了配 reviewLevel，测试立刻红。
         * 这就是"测试跟着枚举走"，而不是"测试跟着人记忆走"。
         */
        @ParameterizedTest(name = "状态 {0} 应归属级别 {0} 的 reviewLevel")
        @EnumSource(EvidenceStatus.class)
        @DisplayName("每个状态返回的级别与枚举定义一致")
        void levelMatchesEnumDefinition(EvidenceStatus status) {
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(status.getCode()))
                    .isEqualTo(status.getReviewLevel())
                    .isEqualTo(EvidenceStatus.levelOf(status.getCode()));
        }

        @Test
        @DisplayName("三个待复核状态分别指向 1/2/3 级")
        void pendingStatusesMapToThreeLevels() {
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.PENDING_REVIEW.getCode())).isEqualTo(1);
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.PENDING_GENERAL.getCode())).isEqualTo(2);
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.PENDING_KEY.getCode())).isEqualTo(3);
        }

        @Test
        @DisplayName("草稿/已确认/已驳回都不在复核流程中，返回 0")
        void nonReviewingStatusesReturnZero() {
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.DRAFT.getCode())).isZero();
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.CONFIRMED.getCode())).isZero();
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(EvidenceStatus.REJECTED.getCode())).isZero();
        }

        /**
         * 防御性测试：null 和越界值不能让程序 NPE 崩掉。
         *
         * <p>真实场景：前端漏传 status、或者数据库里有个脏数据 99。
         * 这时候返回 0（当作不在复核中）比抛 NPE 安全 ——
         * 上层会走"当前状态不允许复核"的业务分支，用户看到的是友好提示。
         */
        @Test
        @DisplayName("null 与非法状态码一律返回 0，不抛 NPE")
        void nullAndIllegalStatusReturnZero() {
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(null)).isZero();
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(99)).isZero();
            assertThat(EvidenceReviewWorkflow.currentReviewLevel(-1)).isZero();
        }
    }

    /* ==================================================================
     * 二、权限匹配矩阵
     * ================================================================== */

    @Nested
    @DisplayName("权限矩阵 canReview")
    class PermissionMatrix {

        /**
         * 权限矩阵的全表验证 —— 这是本测试类里最有价值的一个用例。
         *
         * <p>写成一个 6×3 的二维表，人眼扫一遍就能确认"谁该做什么"是否符合制度：
         * <pre>
         *          一级  二级  三级
         * ADMIN     ✓    ✓    ✓    系统管理角色可代行
         * AUDITOR   ✗    ✗    ✗    编制者无复核权
         * LEADER    ✓    ✗    ✗    只能做详细复核
         * MANAGER   ✗    ✓    ✗    只能做一般复核
         * PARTNER   ✗    ✗    ✓    只能做重点复核
         * VIEWER    ✗    ✗    ✗    只读
         * </pre>
         *
         * <p>为什么不用 18 个独立 @Test：那样改一条规则要改 18 处，
         * 而且看不出整体是否符合制度。一张表把"规则"变成了"数据"，
         * 评审时对着制度文件逐格核对即可。
         */
        @Test
        @DisplayName("角色 × 级别 的 18 种组合全部符合复核制度")
        void fullPermissionMatrix() {
            // {role, 能否一级, 能否二级, 能否三级}
            Object[][] matrix = {
                    {Role.ADMIN,   true,  true,  true},
                    {Role.AUDITOR, false, false, false},
                    {Role.LEADER,  true,  false, false},
                    {Role.MANAGER, false, true,  false},
                    {Role.PARTNER, false, false, true},
                    {Role.VIEWER,  false, false, false},
            };

            for (Object[] row : matrix) {
                Role role = (Role) row[0];
                for (int level = 1; level <= 3; level++) {
                    boolean expected = (boolean) row[level];
                    assertThat(EvidenceReviewWorkflow.canReview(role, level))
                            .as("%s 在第 %d 级复核的权限", role.getDesc(), level)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("角色为空时一律无权限")
        void nullRoleHasNoPermission() {
            for (int level = 1; level <= 3; level++) {
                assertThat(EvidenceReviewWorkflow.canReview(null, level)).isFalse();
            }
        }

        /**
         * 级别越界：0 和 4 都不是合法复核级别。
         *
         * <p>这里必须返回 false 而不是抛异常 —— canReview 是"判断"方法，
         * 不该在判断过程中崩溃。抛异常的职责在 checkReviewPermission。
         */
        @Test
        @DisplayName("级别 0 与 4 越界，任何角色都无权限")
        void illegalLevelHasNoPermission() {
            for (Role role : Role.values()) {
                assertThat(EvidenceReviewWorkflow.canReview(role, 0)).isFalse();
                assertThat(EvidenceReviewWorkflow.canReview(role, 4)).isFalse();
                assertThat(EvidenceReviewWorkflow.canReview(role, -1)).isFalse();
            }
        }

        @Test
        @DisplayName("requiredRoleOf 与权限矩阵对称")
        void requiredRoleIsSymmetric() {
            assertThat(EvidenceReviewWorkflow.requiredRoleOf(1)).isEqualTo(Role.LEADER);
            assertThat(EvidenceReviewWorkflow.requiredRoleOf(2)).isEqualTo(Role.MANAGER);
            assertThat(EvidenceReviewWorkflow.requiredRoleOf(3)).isEqualTo(Role.PARTNER);

            // 对称性验证：requiredRoleOf 返回的角色，自己一定能做这一级
            for (int level = 1; level <= 3; level++) {
                assertThat(EvidenceReviewWorkflow.canReview(
                        EvidenceReviewWorkflow.requiredRoleOf(level), level))
                        .as("%d 级应由 %s 复核，但它自己却没权限", level,
                                EvidenceReviewWorkflow.requiredRoleOf(level).getDesc())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("requiredRoleOf 传入非法级别抛 IllegalArgumentException")
        void requiredRoleOfIllegalLevel() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.requiredRoleOf(4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法复核级别");
        }
    }

    /* ==================================================================
     * 三、越权提示
     * ================================================================== */

    @Nested
    @DisplayName("越权拦截提示 checkReviewPermission")
    class PermissionCheck {

        @Test
        @DisplayName("有权限时不抛异常")
        void passWhenPermitted() {
            assertThatCode(() -> EvidenceReviewWorkflow.checkReviewPermission(Role.LEADER, "项目负责人", 1))
                    .doesNotThrowAnyException();
        }

        /**
         * 越权时的异常必须满足三件事，缺一不可：
         * <ol>
         *   <li>错误码是 3008（前端据此识别"级别不匹配"，而不是笼统的"没权限"）</li>
         *   <li>消息里<b>明确说出</b>当前级别、该谁处理、你是谁</li>
         *   <li>消息是中文，能直接展示给用户</li>
         * </ol>
         *
         * <p>第 2 点是真实踩过的坑：最初只写"无权限"，用户以为是系统坏了。
         * 后来改成"当前环节为【一般复核】，需由【部门经理】处理。
         * 您的角色是【项目负责人】" —— 用户自己就知道该找谁。
         */
        @Test
        @DisplayName("跨级复核抛出 3008，且消息说清该谁处理")
        void crossLevelDeniedWithClearMessage() {
            assertThatThrownBy(() ->
                    EvidenceReviewWorkflow.checkReviewPermission(Role.LEADER, "项目负责人", 2))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED.getCode())
                    .hasMessageContaining("一般复核")   // 当前级别
                    .hasMessageContaining("部门经理")   // 该谁处理
                    .hasMessageContaining("项目负责人"); // 你是谁
        }

        @Test
        @DisplayName("审计员复核任何级别都被拒")
        void auditorDeniedAllLevels() {
            for (int level = 1; level <= 3; level++) {
                // lambda 里引用的变量必须 effectively final，
                // 循环变量 level 每一轮都在变，所以先拷一份再捕获
                final int lv = level;
                assertThatThrownBy(() ->
                        EvidenceReviewWorkflow.checkReviewPermission(Role.AUDITOR, "审计员", lv))
                        .as("审计员复核第 %d 级", lv)
                        .isInstanceOf(BusinessException.class);
            }
        }

        /**
         * roleDesc 传 null 时不能抛 NPE —— 消息里要显示"未知"。
         *
         * <p>这种情况真实存在：JWT 里没带 role 字段，或者拦器截解析时字段缺失。
         * 这时应该正常报"越权"，而不是因为拼提示语 NPE 变成 5000 系统错误。
         */
        @Test
        @DisplayName("角色描述为空时降级显示「未知」，不抛 NPE")
        void nullRoleDescFallsBackToUnknown() {
            assertThatThrownBy(() ->
                    EvidenceReviewWorkflow.checkReviewPermission(Role.VIEWER, null, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未知");
        }
    }

    /* ==================================================================
     * 四、提交复核
     * ================================================================== */

    @Nested
    @DisplayName("提交复核 submit")
    class Submit {

        @Test
        @DisplayName("草稿提交 -> 待详细复核，进入一级")
        void draftToPendingDetail() {
            var t = EvidenceReviewWorkflow.submit(EvidenceStatus.DRAFT.getCode());

            assertThat(t.fromStatus()).isEqualTo(EvidenceStatus.DRAFT.getCode());
            assertThat(t.toStatus()).isEqualTo(EvidenceStatus.PENDING_REVIEW.getCode());
            assertThat(t.nextLevel()).isEqualTo(1);
            assertThat(t.action()).isEqualTo(FlowAction.SUBMIT);
            assertThat(t.isFinalized()).isFalse();
            assertThat(t.isRejected()).isFalse();
        }

        /**
         * 已驳回的证据可以重新提交，而且<b>必须从一级重新走</b>。
         *
         * <p>这是设计上的强约束：二级驳回意味着"这份底稿从根本上不成立"，
         * 编制人改完之后，改动可能影响整份底稿，所以不能让二级直接再签一次 ——
         * 一级的"全量复核"必须重新发生。
         */
        @Test
        @DisplayName("已驳回重新提交 -> 回到一级，不继承上次级别")
        void rejectedRestartsFromLevelOne() {
            var t = EvidenceReviewWorkflow.submit(EvidenceStatus.REJECTED.getCode());

            assertThat(t.toStatus()).isEqualTo(EvidenceStatus.PENDING_REVIEW.getCode());
            assertThat(t.nextLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("已确认的证据不能再提交（终态）")
        void confirmedCannotResubmit() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.submit(EvidenceStatus.CONFIRMED.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_STATUS_ILLEGAL.getCode())
                    .hasMessageContaining("已确认");
        }

        @Test
        @DisplayName("已在复核流程中的证据不能重复提交")
        void reviewingCannotResubmit() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.submit(EvidenceStatus.PENDING_REVIEW.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("待详细复核");

            assertThatThrownBy(() -> EvidenceReviewWorkflow.submit(EvidenceStatus.PENDING_KEY.getCode()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("null 状态不能提交")
        void nullStatusCannotSubmit() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.submit(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未知");
        }
    }

    /* ==================================================================
     * 五、复核通过
     * ================================================================== */

    @Nested
    @DisplayName("复核通过 approve")
    class Approve {

        @Test
        @DisplayName("一级通过 -> 待一般复核，证据尚未生效")
        void level1ToLevel2() {
            var t = EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_REVIEW.getCode());

            assertThat(t.toStatus()).isEqualTo(EvidenceStatus.PENDING_GENERAL.getCode());
            assertThat(t.nextLevel()).isEqualTo(2);
            assertThat(t.action()).isEqualTo(FlowAction.APPROVE_L1);
            assertThat(t.isFinalized()).as("一级通过不等于完成").isFalse();
            assertThat(t.nextStageDesc()).contains("一般复核");
        }

        @Test
        @DisplayName("二级通过 -> 待重点复核，仍未生效")
        void level2ToLevel3() {
            var t = EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_GENERAL.getCode());

            assertThat(t.toStatus()).isEqualTo(EvidenceStatus.PENDING_KEY.getCode());
            assertThat(t.nextLevel()).isEqualTo(3);
            assertThat(t.action()).isEqualTo(FlowAction.APPROVE_L2);
            assertThat(t.isFinalized()).isFalse();
            assertThat(t.nextStageDesc()).contains("重点复核");
        }

        @Test
        @DisplayName("三级通过 -> 已确认，这是唯一的生效点")
        void level3Finalizes() {
            var t = EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_KEY.getCode());

            assertThat(t.toStatus()).isEqualTo(EvidenceStatus.CONFIRMED.getCode());
            assertThat(t.nextLevel()).as("走完三级后不再属于任何待办级别").isZero();
            assertThat(t.action()).isEqualTo(FlowAction.APPROVE_L3);
            assertThat(t.isFinalized()).isTrue();
            assertThat(t.nextStageDesc()).contains("证据生效");
        }

        /**
         * 核心不变式：<b>每次通过只前进一步，且只有第三级才落定</b>。
         *
         * <p>用循环把整条链路走一遍，断言"前两级都不生效"。
         * 这样如果哪天有人把 level &lt; MAX_LEVEL 写成 level &lt;= MAX_LEVEL，
         * 二级就会被误判成完成，这个测试立刻红。
         */
        @Test
        @DisplayName("完整链路：三级依次通过，只有最后一次生效")
        void fullChainOnlyFinalizesAtLast() {
            Integer status = EvidenceStatus.PENDING_REVIEW.getCode();
            int expectedLevel = 1;
            int approveCount = 0;

            while (EvidenceStatus.isReviewing(status)) {
                var t = EvidenceReviewWorkflow.approve(status);
                approveCount++;

                assertThat(t.nextLevel()).isEqualTo(expectedLevel < 3 ? expectedLevel + 1 : 0);
                assertThat(t.isFinalized()).isEqualTo(expectedLevel == 3);

                status = t.toStatus();
                expectedLevel++;
            }

            assertThat(approveCount).as("三级复核必须点三次通过").isEqualTo(3);
            assertThat(status).isEqualTo(EvidenceStatus.CONFIRMED.getCode());
        }

        @Test
        @DisplayName("非复核状态不能通过")
        void nonReviewingCannotApprove() {
            for (EvidenceStatus s : new EvidenceStatus[]{
                    EvidenceStatus.DRAFT, EvidenceStatus.CONFIRMED, EvidenceStatus.REJECTED}) {
                assertThatThrownBy(() -> EvidenceReviewWorkflow.approve(s.getCode()))
                        .as("%s 状态下不应能执行复核通过", s.getDesc())
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_STATUS_ILLEGAL.getCode());
            }
        }
    }

    /* ==================================================================
     * 六、复核驳回
     * ================================================================== */

    @Nested
    @DisplayName("复核驳回 reject")
    class Reject {

        /**
         * 任意一级驳回，都直接退回编制人，级别归零。
         *
         * <p>这是本项目的关键取舍：不打回上一级。理由写在 Workflow 类的注释里 ——
         * 退回上一级会变成"一级猜二级要什么"，来回踢皮球。
         * 测试把这个决定固化下来，防止后人"顺手优化"成退回上一级。
         */
        @Test
        @DisplayName("任意一级驳回都退回编制人，级别归零")
        void anyLevelRejectsToAuthor() {
            Object[][] cases = {
                    {EvidenceStatus.PENDING_REVIEW.getCode(), FlowAction.REJECT_L1},
                    {EvidenceStatus.PENDING_GENERAL.getCode(), FlowAction.REJECT_L2},
                    {EvidenceStatus.PENDING_KEY.getCode(), FlowAction.REJECT_L3},
            };

            for (Object[] c : cases) {
                var t = EvidenceReviewWorkflow.reject((Integer) c[0]);

                assertThat(t.toStatus()).isEqualTo(EvidenceStatus.REJECTED.getCode());
                assertThat(t.nextLevel()).isZero();
                assertThat(t.action()).isEqualTo(c[1]);
                assertThat(t.isRejected()).isTrue();
                assertThat(t.isFinalized()).isFalse();
                assertThat(t.nextStageDesc()).contains("编制人");
            }
        }

        @Test
        @DisplayName("非复核状态不能驳回")
        void nonReviewingCannotReject() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.reject(EvidenceStatus.DRAFT.getCode()))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> EvidenceReviewWorkflow.reject(EvidenceStatus.CONFIRMED.getCode()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("驳回后重新提交，可以从一级再次走完三级")
        void rejectThenResubmitThenComplete() {
            // 走到二级
            var t1 = EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_REVIEW.getCode());
            assertThat(t1.nextLevel()).isEqualTo(2);

            // 二级驳回
            var reject = EvidenceReviewWorkflow.reject(t1.toStatus());
            assertThat(reject.toStatus()).isEqualTo(EvidenceStatus.REJECTED.getCode());

            // 编制人改完重新提交 —— 回到一级
            var resubmit = EvidenceReviewWorkflow.submit(reject.toStatus());
            assertThat(resubmit.nextLevel()).isEqualTo(1);

            // 再走三级
            var a1 = EvidenceReviewWorkflow.approve(resubmit.toStatus());
            var a2 = EvidenceReviewWorkflow.approve(a1.toStatus());
            var a3 = EvidenceReviewWorkflow.approve(a2.toStatus());
            assertThat(a3.isFinalized()).isTrue();
        }
    }

    /* ==================================================================
     * 七、编制与复核分离
     * ================================================================== */

    @Nested
    @DisplayName("编制与复核分离 checkNotSelfReview")
    class SelfReview {

        @Test
        @DisplayName("自己复核自己提交的证据 -> 3005")
        void selfReviewDenied() {
            assertThatThrownBy(() -> EvidenceReviewWorkflow.checkNotSelfReview(7L, 7L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_NO_SELF_REVIEW.getCode())
                    .hasMessageContaining("编制与复核必须分离");
        }

        @Test
        @DisplayName("他人复核通过校验")
        void otherReviewAllowed() {
            assertThatCode(() -> EvidenceReviewWorkflow.checkNotSelfReview(7L, 8L))
                    .doesNotThrowAnyException();
        }

        /**
         * submitBy 为 null 时放行。
         *
         * <p>这种情况出现在历史数据里（改造前的记录没有 submitBy）。
         * 选择放行而不是抛异常：null 说明"不知道是谁提交的"，
         * 不能因此把整条证据卡死；而且此时还有角色校验兜底。
         *
         * <p>为什么用 Long 对象比较而不是 == ：
         * userId 是 Long，超出 -128~127 后 {@code ==} 比的是引用地址，
         * 两个值相同的 Long 对象会返回 false。这是 Java 里最容易踩的坑之一，
         * 所以这里用 Objects.equals。
         */
        @Test
        @DisplayName("submitBy 为空时放行（历史数据兼容）")
        void nullSubmitByPermitted() {
            assertThatCode(() -> EvidenceReviewWorkflow.checkNotSelfReview(null, 7L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("大数值 Long 也能正确比较（避开 == 的装箱陷阱）")
        void largeLongIdCorrectlyCompared() {
            // 超过 127 后 Long 不再走缓存池，== 会失效
            Long bigId = 10000000001L;
            assertThatThrownBy(() -> EvidenceReviewWorkflow.checkNotSelfReview(bigId, 10000000001L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /* ==================================================================
     * 八、流转结果对象
     * ================================================================== */

    @Nested
    @DisplayName("流转结果 Transition")
    class TransitionTest {

        @Test
        @DisplayName("三种结局的提示语各不相同")
        void stageDescDiffers() {
            assertThat(EvidenceReviewWorkflow.submit(EvidenceStatus.DRAFT.getCode()).nextStageDesc())
                    .contains("详细复核");

            assertThat(EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_REVIEW.getCode()).nextStageDesc())
                    .contains("一般复核");

            assertThat(EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_KEY.getCode()).nextStageDesc())
                    .contains("证据生效");

            assertThat(EvidenceReviewWorkflow.reject(EvidenceStatus.PENDING_GENERAL.getCode()).nextStageDesc())
                    .contains("驳回至编制人");
        }

        @Test
        @DisplayName("fromStatus 如实记录起始状态，便于写审计留痕")
        void fromStatusIsPreserved() {
            var t = EvidenceReviewWorkflow.approve(EvidenceStatus.PENDING_GENERAL.getCode());
            assertThat(t.fromStatus()).isEqualTo(EvidenceStatus.PENDING_GENERAL.getCode());
        }
    }
}
