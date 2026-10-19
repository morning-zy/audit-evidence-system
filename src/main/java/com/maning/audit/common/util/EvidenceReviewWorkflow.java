package com.maning.audit.common.util;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.constant.Enums.EvidenceStatus;
import com.maning.audit.common.constant.Enums.FlowAction;
import com.maning.audit.common.constant.Enums.ReviewLevel;
import com.maning.audit.common.constant.Enums.Role;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;

import java.util.Objects;

/**
 * 三级复核状态机 —— 审计证据复核流程的唯一权威规则来源。
 *
 * <h2>为什么要单独抽一个类</h2>
 *
 * <p>如果把这些判断散在 Service 的 approve / reject 里，会有两个后果：
 * <ol>
 *   <li><b>规则漂移</b>：加一种新角色或新级别时，容易只改一处漏另一处</li>
 *   <li><b>无法测试</b>：Service 依赖 Mapper、Redis、UserContext，
 *       想验证"第三级能不能被二级复核员批"就得起整个 Spring 容器</li>
 * </ol>
 *
 * <p>抽成纯函数类后，它 <b>不依赖数据库、不依赖 ThreadLocal</b>，
 * 所有输入都由参数传入 —— 于是可以用普通 JUnit 单测穷举所有状态 × 级别的组合。
 * 这就是"把业务规则从框架里剥出来"的价值。
 *
 * <h2>三级复核制度的来源</h2>
 *
 * <p>依据财政部《会计师事务所质量管理准则第 5101 号》与审计实务通用的三级复核制度：
 * <pre>
 *   一级 详细复核 —— 项目负责人，逐张复核全部底稿
 *   二级 一般复核 —— 部门经理，复核重要会计账项与调整事项
 *   三级 重点复核 —— 主任会计师，复核重大审计问题与审计意见
 * </pre>
 *
 * <p>为什么必须分级而不是一个人看完：
 * 单级复核把成败押在一个人的判断上，而审计结论直接影响报表使用者。
 * 三级之间是<b>逐层收窄</b>的关系 —— 一级看全量、二级看重点、三级看重大，
 * 每层关注点不同，形成交叉验证。这是"独立复核"原则的制度化落地。
 */
public final class EvidenceReviewWorkflow {

    /** 复核总级数。三级复核固定为 3，写常量而不是散落魔数 */
    public static final int MAX_LEVEL = 3;

    private EvidenceReviewWorkflow() {
    }

    /* ==================================================================
     * 一、级别判定
     * ================================================================== */

    /**
     * 判断某条证据当前"在等谁审"。
     *
     * @return 1/2/3；返回 0 表示当前不在复核流程中（草稿、已确认、已驳回）
     */
    public static int currentReviewLevel(Integer evidenceStatus) {
        return EvidenceStatus.levelOf(evidenceStatus);
    }

    /**
     * 判断某角色是否有权复核指定级别。
     *
     * <p>两条规则：
     * <ol>
     *   <li>角色必须与级别一一对应（项目负责人只能做一级）</li>
     *   <li>ADMIN 是系统管理角色，可代行任一级别 —— 现实中用于主任会计师出差时的
     *       流程兜底，但代码里必须写明白这是"代行"，流转记录会如实记下真实操作人</li>
     * </ol>
     *
     * <p><b>为什么不让高一级的人顺手把低一级也批了</b>：
     * 部门经理当然有能力做详细复核，但制度要求"逐级"，因为每级的复核范围不同。
     * 让二级跳过去批一级，等于一级的"全量复核"这件事根本没发生，
     * 只是形式上凑齐了三个签字 —— 那三级复核就成了走流程的摆设。
     */
    public static boolean canReview(Role role, int level) {
        if (role == null || level < 1 || level > MAX_LEVEL) {
            return false;
        }
        if (role == Role.ADMIN) {
            return true;   // 系统管理角色可代行
        }
        Integer levelOfRole = role.reviewLevelOf();
        return levelOfRole != null && levelOfRole == level;
    }

    /**
     * 校验复核权限，不满足直接抛业务异常。
     *
     * <p>错误信息要写清"你是什么角色、这是第几级、该谁来"，而不是笼统的"无权限"。
     * 理由是：四级拦截里最容易被当成 bug 的就是权限拒绝 ——
     * 使用者看不到"为什么不行"，只会认为是系统坏了。
     */
    public static void checkReviewPermission(Role role, String roleDesc, int level) {
        if (canReview(role, level)) {
            return;
        }
        throw new BusinessException(ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED,
                String.format("当前环节为【%s】，需由【%s】处理。您的角色是【%s】，无权复核该级别",
                        ReviewLevel.descOf(level),
                        requiredRoleOf(level).getDesc(),
                        roleDesc == null ? "未知" : roleDesc));
    }

    /** 某级别应由哪个角色处理 —— 与 {@link #canReview} 保持对称 */
    public static Role requiredRoleOf(int level) {
        return switch (level) {
            case 1 -> Role.LEADER;
            case 2 -> Role.MANAGER;
            case 3 -> Role.PARTNER;
            default -> throw new IllegalArgumentException("非法复核级别: " + level);
        };
    }

    /* ==================================================================
     * 二、状态流转
     * ================================================================== */

    /**
     * 提交复核：草稿 / 已驳回 -> 待详细复核（进入一级）。
     */
    public static Transition submit(Integer fromStatus) {
        if (!EvidenceStatus.DRAFT.getCode().equals(fromStatus)
                && !EvidenceStatus.REJECTED.getCode().equals(fromStatus)) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "只有草稿或已驳回的证据可以提交复核，当前状态为【"
                            + EvidenceStatus.descOf(fromStatus) + "】");
        }
        return new Transition(
                fromStatus,
                EvidenceStatus.PENDING_REVIEW.getCode(),
                ReviewLevel.DETAIL.getCode(),
                FlowAction.SUBMIT);
    }

    /**
     * 复核通过：往下一级推进；若已是第三级通过，则整条证据变为"已确认"。
     *
     * <p>这里是三级复核最核心的一步：<b>通过不等于结束</b>。
     * 一、二级通过只是"本级同意，往上送"，只有三级通过才产生最终效力。
     */
    public static Transition approve(Integer fromStatus) {
        int level = requireReviewing(fromStatus);
        if (level < MAX_LEVEL) {
            int next = level + 1;
            return new Transition(
                    fromStatus,
                    EvidenceStatus.pendingOf(next).getCode(),
                    next,
                    FlowAction.approveOf(level));
        }
        // 第三级通过 —— 走完三级，落定为已确认
        return new Transition(
                fromStatus,
                EvidenceStatus.CONFIRMED.getCode(),
                0,
                FlowAction.approveOf(level));
    }

    /**
     * 复核驳回：任意一级驳回，都直接退回到"已驳回"交给编制人重做。
     *
     * <p><b>为什么驳回不退回上一级，而是直接打回编制人</b>：
     * 二级驳回的意思是"这份底稿从根本上不成立"，需要补做审计程序，
     * 不是"一级看漏了一个小地方"。退回上一级会让一级去猜二级到底要什么，
     * 变成来回踢皮球。直接退回编制人，意见写得具体，改起来最有效率。
     */
    public static Transition reject(Integer fromStatus) {
        int level = requireReviewing(fromStatus);
        return new Transition(
                fromStatus,
                EvidenceStatus.REJECTED.getCode(),
                0,
                FlowAction.rejectOf(level));
    }

    /**
     * 取出当前复核级别，若不在复核流程中则抛异常。
     *
     * <p>所有流转方法都先走这一步，等于给状态机加了一道统一入口，
     * 防止"草稿被直接审批通过"这类越级操作。
     */
    private static int requireReviewing(Integer status) {
        if (!EvidenceStatus.isReviewing(status)) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "当前状态【" + EvidenceStatus.descOf(status) + "】不在复核流程中，不能执行复核操作");
        }
        return EvidenceStatus.levelOf(status);
    }

    /* ==================================================================
     * 三、编制与复核分离
     * ================================================================== */

    /**
     * 校验"不能复核自己编制的证据"。
     *
     * <p>这是审计独立性原则的代码化。准则要求项目组成员不能复核自己执行的工作，
     * 因为人对自己的成果有天然的确认偏误，看到的是"我想让它长成的样子"。
     *
     * <p>注意这里比对的是 <b>submitBy（提交人）</b> 而不是创建人：
     * 现实中底稿可能是助理编制、项目经理提交的，提交人才是"对这份底稿负责的人"。
     */
    public static void checkNotSelfReview(Long submitBy, Long currentUserId) {
        if (submitBy != null && Objects.equals(submitBy, currentUserId)) {
            throw new BusinessException(ResultCode.EVIDENCE_NO_SELF_REVIEW,
                    "不能复核自己提交的证据（编制与复核必须分离）");
        }
    }

    /* ==================================================================
     * 四、流转结果
     * ================================================================== */

    /**
     * 一次状态流转的结果。
     *
     * @param fromStatus  起始状态
     * @param toStatus    目标状态
     * @param nextLevel   流转后所处的复核级别（0 表示不在复核中）
     * @param action      写入流转记录的动作
     */
    public record Transition(Integer fromStatus, Integer toStatus, int nextLevel, FlowAction action) {

        /** 是否走完三级、形成最终结论 */
        public boolean isFinalized() {
            return EvidenceStatus.CONFIRMED.getCode().equals(toStatus);
        }

        /** 是否被打回给编制人 */
        public boolean isRejected() {
            return EvidenceStatus.REJECTED.getCode().equals(toStatus);
        }

        /** 通过后还要等哪一级 —— 用于生成"已送部门经理复核"这类提示语 */
        public String nextStageDesc() {
            if (isFinalized()) {
                return "三级复核已完成，证据生效";
            }
            if (isRejected()) {
                return "已驳回至编制人";
            }
            return "已流转至" + ReviewLevel.descOf(nextLevel);
        }
    }
}
