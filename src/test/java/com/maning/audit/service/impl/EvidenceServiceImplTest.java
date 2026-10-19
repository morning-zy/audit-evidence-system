package com.maning.audit.service.impl;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.EvidenceSaveDTO;
import com.maning.audit.entity.AuditEvidence;
import com.maning.audit.entity.EvidenceFlow;
import com.maning.audit.mapper.AuditEvidenceMapper;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.EvidenceFlowMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证据服务单元测试（Mockito 隔离版）
 *
 * <h2>为什么不用 @SpringBootTest</h2>
 *
 * <p>{@code @SpringBootTest} 会启动整个容器：连 MySQL、连 Redis、加载 MyBatis、
 * 初始化拦截器……跑一遍 3 秒起步，而且必须先把中间件都起起来。
 * 这里要验证的是<b>Service 里的业务判断</b>，不是"Spring 能不能装起来"。
 *
 * <p>所以把 4 个 Mapper 和 RedisTemplate 全部 mock 掉，
 * 被测对象就是一个普通的 Java 对象 —— 想让它看到"证据状态是待重点复核"，
 * 直接 {@code when(mapper.selectByIdForUpdate(1L)).thenReturn(evidence)} 就行，
 * 不用真的往库里插一条数据。
 *
 * <h2>这套测试补的是什么缺口</h2>
 *
 * <p>{@code EvidenceReviewWorkflowTest} 验证了状态机本身对不对，
 * 但状态机对不代表 Service 用对了 —— 比如"忘了调 checkNotSelfReview"、
 * "驳回时忘了清 currentLevel"、"越权时先改了数据再报错"（事务已污染）。
 * 这些只有测 Service 才能发现。
 *
 * <h2>一个必须注意的点：ThreadLocal</h2>
 *
 * <p>{@code UserContext} 是 ThreadLocal 实现，测试里得手动 {@code set}。
 * 如果不清理，同一个 JVM 里后续测试会读到上一条用例的身份 —— 就是生产上
 * "线程复用导致串号"那个坑的同款。所以 {@code @AfterEach} 里必须 clear。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计证据服务")
class EvidenceServiceImplTest {

    /* ---------------- Mock 依赖 ---------------- */

    @Mock
    private AuditEvidenceMapper evidenceMapper;
    @Mock
    private AuditIssueMapper issueMapper;
    @Mock
    private AuditProjectMapper projectMapper;
    @Mock
    private EvidenceFlowMapper flowMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;

    /** 被测对象 —— @InjectMocks 会按构造器参数类型自动把上面的 mock 注进去 */
    @InjectMocks
    private EvidenceServiceImpl evidenceService;

    /** AuditProperties 不是 mock：里面的默认值和生产一致（缓存 key 前缀等） */
    private final AuditProperties props = new AuditProperties();

    /**
     * 前置准备。
     *
     * <p>Mockito 的 @InjectMocks 走"最大构造器"策略：构造器要 6 个参数，
     * 其中 props 没有对应的 @Mock，会被注入 null，
     * 而 approve/submit 里会调用 props.getCache().getProjectStatKey() —— 直接 NPE。
     * 这里补一段 <b>反射注入</b>，把真实的 props 塞进私有 final 字段。
     *
     * <p>更干净的做法是把 AuditProperties 也写成 @Mock 并 stub，
     * 但那样每个用例都得重复 stub 缓存前缀，反而啰嗦。
     * 这里选择反射，并在下面附上说明 —— 面试被问到时能讲清为什么这么做。
     */
    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Field f = EvidenceServiceImpl.class.getDeclaredField("props");
        f.setAccessible(true);
        f.set(evidenceService, props);

        // RedisTemplate.opsForValue() 只在 evictStat 成功后可能被调用，
        // 用 lenient 避免"越权路径没走到这里"时 Mockito 报 UnnecessaryStubbing
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 每个用例结束后清理 ThreadLocal —— 模拟真实请求的 afterCompletion */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ==================================================================
     * 工具方法
     * ================================================================== */

    /** 以某个角色登录 */
    private void loginAs(long userId, String realName, Enums.Role role) {
        UserContext.set(new UserContext.LoginUser(userId, "u" + userId, realName, role.getCode()));
    }

    /** 造一条证据，默认值是一个"可以提交"的完整证据 */
    private AuditEvidence evidence(Integer status, Long submitBy) {
        AuditEvidence e = new AuditEvidence();
        e.setId(1L);
        e.setEvidenceNo("EV2026001");
        e.setProjectId(10L);
        e.setIssueId(20L);
        e.setStatus(status);
        e.setCurrentLevel(Enums.EvidenceStatus.levelOf(status));
        e.setSubmitBy(submitBy);
        e.setFilePath("/upload/bank-confirm.pdf");
        e.setReliability(Enums.Reliability.HIGH.getCode());
        e.setRemark(null);
        return e;
    }

    /* ==================================================================
     * 一、复核通过的三级推进
     * ================================================================== */

    @Nested
    @DisplayName("复核通过 approve")
    class Approve {

        /**
         * 一级通过：状态推到"待一般复核"，currentLevel 变成 2，
         * 流转记录里 action=APPROVE_L1、reviewLevel=1。
         *
         * <p>这里同时抓两个 ArgumentCaptor：
         * 一个抓 update 语句（看改了哪些字段），
         * 一个抓 flow 记录（看留痕写了什么）。
         * 只断言返回值是不够的 —— 返回值对但库里写错，问题一样严重。
         */
        @Test
        @DisplayName("一级通过 -> 状态推到待一般复核，流转记为 APPROVE_L1")
        void level1ApprovePushesToLevel2() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            String nextStage = evidenceService.approve(1L, "询证函核对一致");

            assertThat(nextStage).contains("一般复核");

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getStatus())
                    .isEqualTo(Enums.EvidenceStatus.PENDING_GENERAL.getCode());
            assertThat(upd.getValue().getCurrentLevel()).isEqualTo(2);
            assertThat(upd.getValue().getReviewBy()).isEqualTo(7L);
            assertThat(upd.getValue().getReviewComment()).isEqualTo("询证函核对一致");

            ArgumentCaptor<EvidenceFlow> flow = ArgumentCaptor.forClass(EvidenceFlow.class);
            verify(flowMapper).insert(flow.capture());
            assertThat(flow.getValue().getAction()).isEqualTo("APPROVE_L1");
            assertThat(flow.getValue().getReviewLevel()).isEqualTo(1);
            assertThat(flow.getValue().getFromStatus())
                    .isEqualTo(Enums.EvidenceStatus.PENDING_REVIEW.getCode());
            assertThat(flow.getValue().getToStatus())
                    .isEqualTo(Enums.EvidenceStatus.PENDING_GENERAL.getCode());
            assertThat(flow.getValue().getOperatorName()).as("签名要存快照").isEqualTo("陈项目");
            assertThat(flow.getValue().getComment()).contains("一般复核");
        }

        /**
         * 三级通过：这是唯一一次证据真正生效的时刻。
         *
         * <p>断言点：status -> 已确认、currentLevel -> 0（不再属于任何待办）、
         * 流转 action -> APPROVE_L3。
         */
        @Test
        @DisplayName("三级通过 -> 已确认生效，currentLevel 归零")
        void level3ApproveFinalizes() {
            loginAs(9L, "吴主任", Enums.Role.PARTNER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_KEY.getCode(), 3L));

            String nextStage = evidenceService.approve(1L, "重大审计判断已复核");

            assertThat(nextStage).contains("证据生效");

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getStatus()).isEqualTo(Enums.EvidenceStatus.CONFIRMED.getCode());
            assertThat(upd.getValue().getCurrentLevel()).as("终态不再有下一级").isZero();

            ArgumentCaptor<EvidenceFlow> flow = ArgumentCaptor.forClass(EvidenceFlow.class);
            verify(flowMapper).insert(flow.capture());
            assertThat(flow.getValue().getAction()).isEqualTo("APPROVE_L3");
            assertThat(flow.getValue().getReviewLevel()).isEqualTo(3);
        }

        /**
         * 跨级复核必须被拦下，而且要在<b>写库之前</b>拦。
         *
         * <p>{@code verify(..., never())} 就是在验证"没有任何 SQL 发出去"。
         * 这一条比"抛了异常"更重要：如果异常是在 updateById 之后抛的，
         * 事务虽然会回滚，但 AOP 日志、Redis 删除这些副作用可能已经发生了。
         */
        @Test
        @DisplayName("项目负责人批二级 -> 3008，且不产生任何写操作")
        void crossLevelDeniedWithoutWriting() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_GENERAL.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.approve(1L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED.getCode())
                    .hasMessageContaining("部门经理");

            verify(evidenceMapper, never()).updateById(any(AuditEvidence.class));
            verify(flowMapper, never()).insert(any(EvidenceFlow.class));
            verify(redisTemplate, never()).delete(anyString());
        }

        /**
         * 审计员、查阅人任何一级都批不了 —— 用枚举遍历覆盖全部 6 个角色。
         *
         * <p>用 @EnumSource 而不是手写，是为了以后加角色时自动覆盖。
         */
        @ParameterizedTest(name = "{0} 无复核权")
        @EnumSource(value = Enums.Role.class, names = {"AUDITOR", "VIEWER"})
        @DisplayName("审计员与查阅人一律无复核权")
        void nonReviewerDenied(Enums.Role role) {
            loginAs(5L, "某人", role);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.approve(1L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED.getCode());
        }

        /**
         * 自己提交、自己复核 —— 即使角色对得上也要拦。
         *
         * <p>这是编制与复核分离原则。测试构造很关键：让 ADMIN 去复核
         * 自己提交的证据。ADMIN 有全部权限（能过角色校验），
         * 所以是否被拦，完全取决于 checkNotSelfReview 有没有被调用 ——
         * 这正是这条测试要验证的东西。
         */
        @Test
        @DisplayName("自己提交自己批 -> 3005（即使角色有权限）")
        void selfReviewDeniedEvenWithPermission() {
            loginAs(7L, "陈项目", Enums.Role.ADMIN);
            // submitBy 和当前用户都是 7
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 7L));

            assertThatThrownBy(() -> evidenceService.approve(1L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_NO_SELF_REVIEW.getCode());

            verify(evidenceMapper, never()).updateById(any(AuditEvidence.class));
        }

        /**
         * 不在复核流程中的证据，批不了。
         *
         * <p>包括草稿（还没提交就想批）、已确认（批过了再批一次）、
         * 已驳回（驳回了还想"通过"）三种。用参数化一次覆盖。
         */
        @ParameterizedTest(name = "状态 {0} 不可复核")
        @EnumSource(value = Enums.EvidenceStatus.class, names = {"DRAFT", "CONFIRMED", "REJECTED"})
        @DisplayName("非复核状态一律拒绝，返回 3003")
        void nonReviewingStatusRejected(Enums.EvidenceStatus status) {
            loginAs(9L, "吴主任", Enums.Role.PARTNER);
            when(evidenceMapper.selectByIdForUpdate(1L)).thenReturn(evidence(status.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.approve(1L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_STATUS_ILLEGAL.getCode())
                    .hasMessageContaining("不在复核流程中");
        }

        @Test
        @DisplayName("证据不存在 -> 2001")
        void evidenceNotFound() {
            loginAs(9L, "吴主任", Enums.Role.PARTNER);
            when(evidenceMapper.selectByIdForUpdate(99L)).thenReturn(null);

            assertThatThrownBy(() -> evidenceService.approve(99L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.DATA_NOT_FOUND.getCode());
        }

        /**
         * 复核通过后必须清掉项目统计缓存。
         *
         * <p>否则看板上"待复核数量"会一直是旧值，直到 TTL 过期。
         * 这是"缓存一致性"最容易漏的一步 —— 写操作只改库不改缓存。
         */
        @Test
        @DisplayName("复核通过后清除项目统计缓存")
        void approveEvictsCache() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            evidenceService.approve(1L, "同意");

            verify(redisTemplate, times(1)).delete("audit:stat:project:10");
        }
    }

    /* ==================================================================
     * 二、驳回
     * ================================================================== */

    @Nested
    @DisplayName("复核驳回 reject")
    class Reject {

        /**
         * 驳回的特征：currentLevel 必须显式置 0，而不是靠状态推。
         *
         * <p>已驳回(3) 本身 levelOf 就是 0，所以理论上不设也不会出错。
         * 但显式写 0 是防御性的：万一以后有人改了 EvidenceStatus 的
         * reviewLevel 配置，这里也不会留下脏的 currentLevel。
         */
        @Test
        @DisplayName("驳回 -> 已驳回，currentLevel 置 0")
        void rejectResetsLevel() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            evidenceService.reject(1L, "回函与账面差异未说明");

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getStatus()).isEqualTo(Enums.EvidenceStatus.REJECTED.getCode());
            assertThat(upd.getValue().getCurrentLevel()).isZero();
            assertThat(upd.getValue().getReviewComment()).isEqualTo("回函与账面差异未说明");

            ArgumentCaptor<EvidenceFlow> flow = ArgumentCaptor.forClass(EvidenceFlow.class);
            verify(flowMapper).insert(flow.capture());
            assertThat(flow.getValue().getAction()).isEqualTo("REJECT_L1");
            assertThat(flow.getValue().getReviewLevel()).isEqualTo(1);
        }

        /**
         * 驳回必须填理由。
         *
         * <p>这是真实审计场景的硬要求：只说"驳回"，编制人不知道改什么，
         * 会反复来回。系统层面强制填意见，并把意见写进流转记录。
         */
        @Test
        @DisplayName("驳回不填理由 -> 参数错误，不写库")
        void rejectWithoutCommentDenied() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.reject(1L, "   "))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PARAM_ERROR.getCode())
                    .hasMessageContaining("复核意见");

            verify(evidenceMapper, never()).updateById(any(AuditEvidence.class));
        }

        /**
         * 二级驳回也直接退回编制人（不回退到一级）。
         *
         * <p>这是设计决策，测试把它锁住：如果有人把它改成"退回一级"，
         * 这个用例会立刻发现 fromStatus 对不上。
         */
        @Test
        @DisplayName("二级驳回直接退回编制人，不退回一级")
        void level2RejectGoesToAuthor() {
            loginAs(8L, "周部门", Enums.Role.MANAGER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_GENERAL.getCode(), 3L));

            evidenceService.reject(1L, "重大调整事项未取得佐证");

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getStatus())
                    .as("直接打回编制人，而不是退回待详细复核")
                    .isEqualTo(Enums.EvidenceStatus.REJECTED.getCode());

            ArgumentCaptor<EvidenceFlow> flow = ArgumentCaptor.forClass(EvidenceFlow.class);
            verify(flowMapper).insert(flow.capture());
            assertThat(flow.getValue().getAction()).isEqualTo("REJECT_L2");
        }

        @Test
        @DisplayName("越权驳回 -> 3008")
        void crossLevelRejectDenied() {
            loginAs(9L, "吴主任", Enums.Role.PARTNER);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.reject(1L, "不同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED.getCode());
        }
    }

    /* ==================================================================
     * 三、提交复核
     * ================================================================== */

    @Nested
    @DisplayName("提交复核 submit")
    class Submit {

        @Test
        @DisplayName("草稿提交 -> 待详细复核，记录提交人与时间")
        void draftSubmitEntersLevel1() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            // 提交人设成 null：草稿从未提交过，checkOwner 在 owner 为 null 时放行
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.DRAFT.getCode(), null));

            evidenceService.submit(1L);

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getStatus()).isEqualTo(Enums.EvidenceStatus.PENDING_REVIEW.getCode());
            assertThat(upd.getValue().getCurrentLevel()).isEqualTo(1);
            assertThat(upd.getValue().getSubmitBy()).isEqualTo(3L);
            assertThat(upd.getValue().getSubmitTime()).isNotNull();
            assertThat(upd.getValue().getReviewComment()).as("重新提交要清空上一轮意见").isNull();

            ArgumentCaptor<EvidenceFlow> flow = ArgumentCaptor.forClass(EvidenceFlow.class);
            verify(flowMapper).insert(flow.capture());
            assertThat(flow.getValue().getAction()).isEqualTo("SUBMIT");
            assertThat(flow.getValue().getReviewLevel()).isEqualTo(1);
        }

        /**
         * 没有附件不能提交。
         *
         * <p>空壳证据进复核队列是审计系统里很丢人的问题：
         * 底稿上写着"银行函证"，点开却没有文件。
         */
        @Test
        @DisplayName("没上传附件 -> 不允许提交")
        void submitWithoutFileDenied() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            AuditEvidence e = evidence(Enums.EvidenceStatus.DRAFT.getCode(), null);
            e.setFilePath("  ");
            when(evidenceMapper.selectByIdForUpdate(1L)).thenReturn(e);

            assertThatThrownBy(() -> evidenceService.submit(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("上传证据附件");

            verify(evidenceMapper, never()).updateById(any(AuditEvidence.class));
        }

        /**
         * C 级及以下证据提交时，必须在备注里写补充佐证方案。
         *
         * <p>这是"上一条规则的延伸"：可靠性不够的证据不能裸奔进复核队列。
         * 测试构造了一份 C 级证据（可靠性=2 即 MEDIUM），
         * 并且 remark 为空 —— 应该被拦。
         */
        @Test
        @DisplayName("低可靠性证据未写补充方案 -> 不允许提交")
        void lowReliabilityWithoutRemarkDenied() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            AuditEvidence e = evidence(Enums.EvidenceStatus.DRAFT.getCode(), null);
            e.setReliability(Enums.Reliability.MEDIUM.getCode());
            e.setRemark(null);
            when(evidenceMapper.selectByIdForUpdate(1L)).thenReturn(e);

            assertThatThrownBy(() -> evidenceService.submit(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("补充佐证方案");
        }

        @Test
        @DisplayName("低可靠性证据写了补充方案 -> 可以提交")
        void lowReliabilityWithRemarkAllowed() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            AuditEvidence e = evidence(Enums.EvidenceStatus.DRAFT.getCode(), null);
            e.setReliability(Enums.Reliability.MEDIUM.getCode());
            e.setRemark("将向银行函证原件");
            when(evidenceMapper.selectByIdForUpdate(1L)).thenReturn(e);

            assertThatCode(() -> evidenceService.submit(1L)).doesNotThrowAnyException();
            verify(evidenceMapper).updateById(any(AuditEvidence.class));
        }

        /**
         * 不能提交别人的证据。
         *
         * <p>checkOwner 的逻辑：submitBy 非空且不等于当前用户，且当前不是 ADMIN，
         * 就抛 3004。这里让李四(3) 去提交王五(4) 的证据。
         */
        @Test
        @DisplayName("提交别人的证据 -> 3004")
        void submitOthersEvidenceDenied() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.REJECTED.getCode(), 4L));

            assertThatThrownBy(() -> evidenceService.submit(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_NO_PERMISSION.getCode());
        }

        /**
         * 已确认的证据不能再提交（终态）。
         */
        @Test
        @DisplayName("已确认证据不可重新提交 -> 3003")
        void confirmedCannotResubmit() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.CONFIRMED.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.submit(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_STATUS_ILLEGAL.getCode());
        }

        @Test
        @DisplayName("驳回后重新提交 -> 从一级重走")
        void rejectedResubmitRestartsFromLevel1() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            AuditEvidence e = evidence(Enums.EvidenceStatus.REJECTED.getCode(), 3L);
            e.setRemark("已补齐银行回函原件");
            when(evidenceMapper.selectByIdForUpdate(1L)).thenReturn(e);

            evidenceService.submit(1L);

            ArgumentCaptor<AuditEvidence> upd = ArgumentCaptor.forClass(AuditEvidence.class);
            verify(evidenceMapper).updateById(upd.capture());
            assertThat(upd.getValue().getCurrentLevel()).as("不继承上次级别，从一级重来").isEqualTo(1);
            assertThat(upd.getValue().getStatus()).isEqualTo(Enums.EvidenceStatus.PENDING_REVIEW.getCode());
        }
    }

    /* ==================================================================
     * 四、按钮权限计算
     * ================================================================== */

    @Nested
    @DisplayName("按钮权限计算 fillViewFields")
    class ButtonPermission {

        /**
         * 按钮权限是"当前级别 + 我的角色 + 是不是我提交的"三者的交集。
         *
         * <p>这组测试用同一份证据（待一般复核）、三个不同角色登录，
         * 检查 canReview 的显隐。
         *
         * <p>为什么这个必须测：前端就是靠这个字段决定按钮出不出现。
         * 算错了会导致"按钮出现了但点了报错"（体验差）或者
         * "该批的人看不到按钮"（流程卡死）。
         */
        @Test
        @DisplayName("部门经理面对待一般复核 -> 显示复核按钮")
        void managerSeesButtonOnLevel2() {
            loginAs(8L, "周部门", Enums.Role.MANAGER);

            var vo = detailVO(Enums.EvidenceStatus.PENDING_GENERAL.getCode(), 3L);

            assertThat(vo.getCurrentLevel()).isEqualTo(2);
            assertThat(vo.getCurrentLevelName()).isEqualTo("一般复核");
            assertThat(vo.getPendingRoleName()).isEqualTo("部门经理");
            assertThat(vo.getReviewing()).isTrue();
            assertThat(vo.getCanReview()).isTrue();
        }

        @Test
        @DisplayName("项目负责人面对待一般复核 -> 不显示按钮")
        void leaderCannotSeeButtonOnLevel2() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            var vo = detailVO(Enums.EvidenceStatus.PENDING_GENERAL.getCode(), 3L);

            assertThat(vo.getCanReview()).isFalse();
            assertThat(vo.getMyRoleName()).isEqualTo("项目负责人");
        }

        @Test
        @DisplayName("自己是提交人 -> 即使级别对得上也不显示按钮")
        void submitterCannotSeeButton() {
            // 周部门既是处理二级的人，又是这条证据的提交人
            loginAs(8L, "周部门", Enums.Role.MANAGER);
            var vo = detailVO(Enums.EvidenceStatus.PENDING_GENERAL.getCode(), 8L);

            assertThat(vo.getCanReview()).isFalse();
        }

        @Test
        @DisplayName("已确认 -> 无待办级别、无按钮、标记已完成")
        void confirmedHasNoButton() {
            loginAs(9L, "吴主任", Enums.Role.PARTNER);
            var vo = detailVO(Enums.EvidenceStatus.CONFIRMED.getCode(), 3L);

            assertThat(vo.getCurrentLevel()).isZero();
            assertThat(vo.getCurrentLevelName()).isNull();
            assertThat(vo.getReviewing()).isFalse();
            assertThat(vo.getReviewFinished()).isTrue();
            assertThat(vo.getCanReview()).isFalse();
            assertThat(vo.getPendingRoleName()).isNull();
        }

        @Test
        @DisplayName("草稿 -> 可编辑但不可复核")
        void draftEditableNotReviewable() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            var vo = detailVO(Enums.EvidenceStatus.DRAFT.getCode(), 3L);

            assertThat(vo.getCanEdit()).isTrue();
            assertThat(vo.getCanReview()).isFalse();
            assertThat(vo.getReviewFinished()).isFalse();
        }

        /**
         * 通过真实的 detail() 方法取 VO，而不是手工 new 一个 ——
         * 这样才真正覆盖了 fillViewFields 的逻辑。
         *
         * <p>注意这里 mock 的是 selectEvidenceDetail（返回 VO 的方法），
         * 不是 selectByIdForUpdate（返回实体）。Service 的 detail() 走的是前者。
         */
        private com.maning.audit.vo.EvidenceVO detailVO(Integer status, Long submitBy) {
            com.maning.audit.vo.EvidenceVO vo = new com.maning.audit.vo.EvidenceVO();
            vo.setId(1L);
            vo.setStatus(status);
            vo.setSubmitBy(submitBy);
            vo.setEvidenceType(Enums.EvidenceType.DOCUMENT.getCode());
            vo.setReliability(Enums.Reliability.HIGH.getCode());
            vo.setSourceType(Enums.EvidenceSource.EXTERNAL.name());
            vo.setAcquireMethod(Enums.AcquireMethod.DIRECT.name());
            vo.setFormType(Enums.EvidenceForm.WRITTEN.name());
            vo.setOriginality(Enums.Originality.ORIGINAL.name());
            when(evidenceMapper.selectEvidenceDetail(1L, UserContext.getUserId())).thenReturn(vo);
            return evidenceService.detail(1L);
        }
    }

    /* ==================================================================
     * 五、流转记录翻译
     * ================================================================== */

    @Nested
    @DisplayName("流转记录翻译 flowList")
    class FlowList {

        /**
         * 历史旧数据（三级复核改造前的 APPROVE / REJECT）必须如实标注成
         * "旧版单级"，不能显示成裸英文，也不能硬改成 APPROVE_L3。
         *
         * <p>这条测试守的是一个真实决策：审计系统的历史记录不允许篡改。
         * 当时改造时有过两个选项 —— 把历史数据迁移成 APPROVE_L3，
         * 或者保留原值并标注。选了后者，因为"把单级复核写成三级通过"
         * 等于伪造底稿。
         *
         * <p>注意 mapper 返回的已经是 VO（SQL 里写好了 join），
         * 所以这里直接构造 FlowVO，Service 只负责把 action 码翻译成中文。
         */
        @Test
        @DisplayName("旧版 APPROVE / REJECT 显示为「旧版单级」而非裸英文")
        void legacyActionsTranslated() {
            var legacyApprove = new com.maning.audit.vo.FlowVO();
            legacyApprove.setAction("APPROVE");
            legacyApprove.setToStatus(Enums.EvidenceStatus.CONFIRMED.getCode());

            var legacyReject = new com.maning.audit.vo.FlowVO();
            legacyReject.setAction("REJECT");
            legacyReject.setToStatus(Enums.EvidenceStatus.REJECTED.getCode());

            when(flowMapper.selectByEvidenceId(1L))
                    .thenReturn(List.of(legacyApprove, legacyReject));

            var flows = evidenceService.flowList(1L);

            assertThat(flows.get(0).getActionName()).isEqualTo("复核通过（旧版单级）");
            assertThat(flows.get(1).getActionName()).isEqualTo("复核驳回（旧版单级）");
        }

        @Test
        @DisplayName("三级动作显示为具体级别，并带出状态与级别中文名")
        void threeLevelActionsTranslated() {
            var f = new com.maning.audit.vo.FlowVO();
            f.setAction("APPROVE_L2");
            f.setFromStatus(Enums.EvidenceStatus.PENDING_GENERAL.getCode());
            f.setToStatus(Enums.EvidenceStatus.PENDING_KEY.getCode());
            f.setReviewLevel(2);

            when(flowMapper.selectByEvidenceId(1L)).thenReturn(List.of(f));

            var vo = evidenceService.flowList(1L).get(0);

            assertThat(vo.getActionName()).isEqualTo("一般复核通过");
            assertThat(vo.getFromStatusName()).isEqualTo("待一般复核");
            assertThat(vo.getToStatusName()).isEqualTo("待重点复核");
            assertThat(vo.getReviewLevelName()).isEqualTo("一般复核");
        }

        @Test
        @DisplayName("非复核动作（创建/提交）的 reviewLevelName 为空")
        void nonReviewActionHasNoLevelName() {
            var f = new com.maning.audit.vo.FlowVO();
            f.setAction("CREATE");
            f.setToStatus(Enums.EvidenceStatus.DRAFT.getCode());
            f.setReviewLevel(null);

            when(flowMapper.selectByEvidenceId(1L)).thenReturn(List.of(f));

            var vo = evidenceService.flowList(1L).get(0);
            assertThat(vo.getActionName()).isEqualTo("创建");
            assertThat(vo.getReviewLevelName()).isNull();
        }
    }

    /* ==================================================================
     * 六、待办队列权限
     * ================================================================== */

    @Nested
    @DisplayName("待复核队列 todoOnly")
    class TodoQueue {

        /**
         * 待办队列只对复核人开放。
         *
         * <p>审计员查待办没意义（他不能复核），而且会看到别人的底稿。
         * 所以直接抛 403 而不是返回空列表 —— 返回空会让人以为"确实没待办"。
         */
        @Test
        @DisplayName("审计员查待办队列 -> 403")
        void auditorCannotViewTodo() {
            loginAs(3L, "李四", Enums.Role.AUDITOR);
            var q = new com.maning.audit.dto.EvidenceQuery();
            q.setTodoOnly(true);

            assertThatThrownBy(() -> evidenceService.page(q))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN.getCode());

            verify(evidenceMapper, never()).selectEvidencePage(any(), any(), anyLong());
        }
    }

    /* ==================================================================
     * 七、ThreadLocal 清理
     * ================================================================== */

    @Nested
    @DisplayName("用户上下文")
    class Context {

        /**
         * 未登录时调用业务方法（比如单元测试直接调、或者定时任务里调），
         * UserContext 全是 null，不能抛 NPE。
         *
         * <p>以 approve 为例：currentRole() 返回 null → canReview 返回 false →
         * 抛的是 3008 业务异常（合理），而不是 NullPointerException（5000）。
         */
        @Test
        @DisplayName("未登录时走业务异常而非 NPE")
        void withoutLoginThrowsBusinessException() {
            UserContext.clear();
            when(evidenceMapper.selectByIdForUpdate(1L))
                    .thenReturn(evidence(Enums.EvidenceStatus.PENDING_REVIEW.getCode(), 3L));

            assertThatThrownBy(() -> evidenceService.approve(1L, "同意"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.EVIDENCE_REVIEW_LEVEL_DENIED.getCode());
        }

        @Test
        @DisplayName("clear 之后读不到上一个用户的身份")
        void clearRemovesIdentity() {
            loginAs(7L, "陈项目", Enums.Role.LEADER);
            assertThat(UserContext.getUserId()).isEqualTo(7L);

            UserContext.clear();

            assertThat(UserContext.getUserId()).isNull();
            assertThat(UserContext.getRoleEnum()).isNull();
            assertThat(UserContext.isAdmin()).isFalse();
        }
    }

    /* ==================================================================
     * 八、DTO 校验（Bean Validation）
     * ================================================================== */

    @Nested
    @DisplayName("DTO 校验注解")
    class DtoValidation {

        /**
         * DTO 上的 @NotBlank / @NotNull / @Size 是"第一道防线"，
         * 在 Controller 层由 @Valid 触发，请求根本进不到 Service。
         *
         * <p>这里直接用 Validator 校验，不需要起 Spring MVC ——
         * 说明"校验规则本身"和"Web 层"是可以分开测的。
         */
        @Test
        @DisplayName("证据 DTO 缺必填项时报错")
        void evidenceDtoRequiresMandatoryFields() {
            var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            var dto = new EvidenceSaveDTO();

            var violations = validator.validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                    .contains("evidenceName", "issueId", "evidenceType", "sourceType");
        }

        @Test
        @DisplayName("证据名称超过 128 字符被拒")
        void evidenceNameTooLong() {
            var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            var dto = new EvidenceSaveDTO();
            dto.setIssueId(1L);
            dto.setEvidenceName("证".repeat(129));
            dto.setEvidenceType(Enums.EvidenceType.DOCUMENT.getCode());
            dto.setSourceType(Enums.EvidenceSource.EXTERNAL.name());
            dto.setAcquireMethod(Enums.AcquireMethod.DIRECT.name());
            dto.setFormType(Enums.EvidenceForm.WRITTEN.name());
            dto.setOriginality(Enums.Originality.ORIGINAL.name());

            assertThat(validator.validate(dto))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("evidenceName"));
        }
    }
}
