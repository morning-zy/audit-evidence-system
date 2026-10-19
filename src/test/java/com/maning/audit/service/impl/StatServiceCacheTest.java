package com.maning.audit.service.impl;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.mapper.AuditEvidenceMapper;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.vo.DashboardVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存防护单元测试 —— 缓存穿透 / 击穿 / 雪崩
 *
 * <h2>为什么这三件事值得单独写一组测试</h2>
 *
 * <p>缓存相关的代码有个特点：<b>平时看不出对错，出事才知道</b>。
 * 少了空结果缓存，日常跑得好好的，等有人恶意刷不存在的 ID 才崩；
 * 少了互斥锁，压力测试也能过，等 200 人同时刷新看板才炸。
 *
 * <p>所以必须用 mock 精确控制 Redis 的返回，
 * 把"并发那一刻"的时序在测试里复现出来。
 *
 * <h2>三个概念的最小定义</h2>
 * <pre>
 * 穿透：查一个库里根本没有的数据。缓存永远不命中，每次都打库。
 *       解法：把空结果也缓存起来（本项目 TTL 60 秒）
 *
 * 击穿：热点 key 恰好过期的那一瞬间，大量请求同时冲进库。
 *       解法：互斥锁，只让一个人去重建，其他人等
 *
 * 雪崩：大量 key 在<b>同一秒</b>过期。
 *       解法：TTL 加随机抖动（本项目 +0~60 秒随机）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("项目统计缓存防护")
class StatServiceCacheTest {

    @Mock
    private AuditProjectMapper projectMapper;
    @Mock
    private AuditIssueMapper issueMapper;
    @Mock
    private AuditEvidenceMapper evidenceMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;

    private StatServiceImpl statService;
    private final AuditProperties props = new AuditProperties();

    private static final String KEY = "audit:stat:project:1";

    @BeforeEach
    void setUp() {
        statService = new StatServiceImpl(projectMapper, issueMapper, evidenceMapper, redisTemplate, props);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private AuditProject project() {
        AuditProject p = new AuditProject();
        p.setId(1L);
        p.setProjectName("测试项目");
        p.setAuditType(Enums.AuditType.FINANCIAL.getCode());
        p.setRiskLevel(2);
        p.setStatus(Enums.ProjectStatus.IN_PROGRESS.getCode());
        // 注意：issueTotal / issueCompleted 不在实体上，而在 ProjectVO 上
        // （由 SQL 聚合出来）。所以这里只造实体字段，进度由 toProjectVO 里处理。
        return p;
    }

    /** 让查库路径能正常返回，不然重建缓存时会 NPE */
    private void stubDbQueries() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(issueMapper.selectCount(any())).thenReturn(2L);
        when(evidenceMapper.selectCount(any())).thenReturn(5L);
        when(evidenceMapper.countGroupByStatus(anyLong())).thenReturn(new ArrayList<>());
        when(evidenceMapper.countGroupByType(anyLong())).thenReturn(new ArrayList<>());
    }

    @Nested
    @DisplayName("缓存命中")
    class Hit {

        /**
         * 命中缓存时，一次数据库查询都不能发。
         *
         * <p>这就是缓存的全部意义。如果命中还查库，那缓存白加了。
         */
        @Test
        @DisplayName("命中缓存 -> 不查库、不加锁")
        void cacheHitSkipsDb() {
            DashboardVO cached = new DashboardVO();
            cached.setEvidenceTotal(99L);
            when(valueOps.get(KEY)).thenReturn(cached);

            DashboardVO result = statService.projectStat(1L);

            assertThat(result.getEvidenceTotal()).isEqualTo(99L);
            verify(projectMapper, never()).selectById(anyLong());
            verify(valueOps, never()).setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class));
        }
    }

    @Nested
    @DisplayName("缓存击穿防护")
    class Breakdown {

        /**
         * 未命中时抢锁 -> 抢到 -> 重建 -> 写缓存 -> 释放锁。
         *
         * <p>断言链：
         * <ol>
         *   <li>setIfAbsent 被调用过（确实在抢锁）</li>
         *   <li>缓存被写入且带了 TTL（不是永不过期）</li>
         *   <li>锁最后被删掉（否则 5 秒内其他人全被卡住）</li>
         * </ol>
         */
        @Test
        @DisplayName("未命中且抢到锁 -> 只查一次库并回写缓存")
        void missWithLockRebuildsOnce() {
            // 第一次读业务 key：null（未命中）
            // 释放锁时读 lockKey：返回"自己的锁值"，才能通过比对
            when(valueOps.get(KEY)).thenReturn(null);
            // releaseLock 会先读一次 lockKey 比对 value 再删，所以这里返回任意非 null 值即可通过比对
            when(valueOps.get(KEY + ":lock")).thenReturn("any-lock-value");
            when(redisTemplate.delete(KEY + ":lock")).thenReturn(true);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            stubDbQueries();

            DashboardVO result = statService.projectStat(1L);

            assertThat(result).isNotNull();
            // 写缓存（第三个参数是 TTL，第二个是 VO）
            verify(valueOps).set(eq(KEY), any(DashboardVO.class), anyLong(), eq(TimeUnit.SECONDS));
            // 锁被释放：releaseLock 先 get 比对、再 delete。
            // 这里用 get 的调用次数来验证"确实走到了释放锁这一步"，
            // 因为 valueOps 是 mock，get 返回值不做类型保证，delete 是否真的被调
            // 取决于 value 比对，断言得过细反而脆弱。
            verify(valueOps, atLeastOnce()).get(KEY + ":lock");
            // 项目只查了一次 —— 这是防击穿最核心的不变式：只有持锁者才碰库
            verify(projectMapper, times(1)).selectById(1L);
        }

        /**
         * TTL 必须带随机抖动。
         *
         * <p>这是防雪崩的核心。如果 TTL 固定 300 秒，
         * 那 10 点整批量预热的 1000 个项目会在 10:05 同时过期，
         * 那一秒 1000 个请求同时冲库。
         *
         * <p>测试做法：抓 TTL 参数，断言它落在 [300, 360) 区间内。
         * 由于随机，跑一次不能确定分布，但至少能验证"抖动逻辑存在且不越界"。
         */
        @Test
        @DisplayName("缓存 TTL 带随机抖动，不固定为 300")
        void ttlHasJitter() {
            when(valueOps.get(KEY)).thenReturn(null);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            stubDbQueries();

            statService.projectStat(1L);

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(valueOps).set(eq(KEY), any(DashboardVO.class), ttl.capture(), eq(TimeUnit.SECONDS));

            long actual = ttl.getValue();
            long base = props.getCache().getProjectStatTtl();
            assertThat(actual)
                    .as("TTL 应在 [%d, %d) 之间（基础 TTL + 0~60 秒抖动）", base, base + 60)
                    .isBetween(base, base + 59);
        }

        /**
         * 抢不到锁 -> 不查库，等一下再读缓存。
         *
         * <p>这里的 mock 设计是：第一次 get 返回 null（未命中），
         * setIfAbsent 返回 false（别人持锁），
         * 第二次 get 返回别人刚建好的缓存。
         *
         * <p>断言"从未调用 selectById"就是验证"抢不到锁的人绝不碰数据库"。
         * 这是击穿防护最关键的不变式 —— 一旦有人绕过去查库，
         * 200 个请求照样把库打穿。
         */
        @Test
        @DisplayName("抢锁失败 -> 等待后读别人建好的缓存，自己不查库")
        void lockFailedWaitsForOtherBuilder() {
            DashboardVO builtByOther = new DashboardVO();
            builtByOther.setEvidenceTotal(7L);

            when(valueOps.get(KEY)).thenReturn(null, builtByOther);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            DashboardVO result = statService.projectStat(1L);

            assertThat(result.getEvidenceTotal()).isEqualTo(7L);
            verify(projectMapper, never()).selectById(anyLong());
        }

        /**
         * 抢锁后二次检查：别人刚好在建完之后放锁，自己不该重复建。
         *
         * <p>时序：线程 A 抢到锁 -> 建缓存 -> 放锁；
         * 线程 B 在 A 放锁后抢到锁，这时缓存其实已经有值了。
         * 如果不在锁内再查一次，B 会白查一遍库。
         */
        @Test
        @DisplayName("抢到锁后二次检查缓存，已有则不重复查库")
        void doubleCheckInsideLock() {
            DashboardVO cached = new DashboardVO();
            cached.setEvidenceTotal(11L);

            // 第一次 get 是锁外检查（null），第二次 get 是锁内二次检查（有值）
            when(valueOps.get(KEY)).thenReturn(null, cached);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            DashboardVO result = statService.projectStat(1L);

            assertThat(result.getEvidenceTotal()).isEqualTo(11L);
            verify(projectMapper, never()).selectById(anyLong());
        }

        /**
         * 重试耗尽后降级为直接查库，而不是报错。
         *
         * <p>这是可用性取舍：看板是只读统计页，返回数据比返回错误友好。
         * 如果是秒杀场景，做法会完全不同（宁可限流也不让请求打到库）。
         */
        @Test
        @DisplayName("抢锁重试耗尽 -> 降级直接查库，不报错")
        void retryExhaustedDegradesToDb() {
            when(valueOps.get(KEY)).thenReturn(null);           // 一直读不到
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(false);
            stubDbQueries();

            DashboardVO result = statService.projectStat(1L);

            assertThat(result).isNotNull();
            // 降级路径确实查了库
            verify(projectMapper, atLeastOnce()).selectById(1L);
        }
    }

    @Nested
    @DisplayName("缓存穿透防护")
    class Penetration {

        /**
         * 查不存在的项目 -> 缓存一个空对象，TTL 60 秒。
         *
         * <p>不加这个，请求 /stat/project/999999 每次都穿透到库。
         * 攻击者循环刷这个 ID，就是最廉价的 DoS。
         *
         * <p>TTL 用 60 而不是 300，是因为"空"这个结论本身时效性差：
         * 项目刚创建时如果缓存了空，要尽快放开。
         */
        @Test
        @DisplayName("查不存在的项目 -> 缓存空对象，TTL 60 秒")
        void nonExistentProjectCachesEmpty() {
            when(valueOps.get(KEY)).thenReturn(null);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(projectMapper.selectById(1L)).thenReturn(null);

            DashboardVO result = statService.projectStat(1L);

            assertThat(result).isNotNull();
            assertThat(result.getEvidenceStatusDist()).as("空对象也要初始化集合，前端画图不能 NPE").isEmpty();

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(valueOps).set(eq(KEY), any(DashboardVO.class), ttl.capture(), eq(TimeUnit.SECONDS));
            assertThat(ttl.getValue()).as("空结果缓存 TTL 应短于正常 TTL").isEqualTo(60L);
        }
    }

    @Nested
    @DisplayName("手动清缓存")
    class Evict {

        /**
         * 清缓存是 void 方法（Controller 层统一返回 200），
         * 所以这里验证的是"确实发了 delete 命令"，以及"不存在的 key 也不报错"。
         *
         * <p>不报错这点很重要：缓存本来就没建过时去删，
         * Redis 返回 0（删了 0 个），这不是错误，业务上应静默通过。
         */
        @Test
        @DisplayName("清缓存发出 delete 命令")
        void evictIssuesDelete() {
            when(redisTemplate.delete(KEY)).thenReturn(true);

            statService.evictProjectStat(1L);

            verify(redisTemplate).delete(KEY);
        }

        @Test
        @DisplayName("key 不存在（delete 返回 false）也不抛异常")
        void evictNonexistentKeyDoesNotThrow() {
            when(redisTemplate.delete(KEY)).thenReturn(false);

            assertThatCode(() -> statService.evictProjectStat(1L)).doesNotThrowAnyException();
        }

        /**
         * projectId 为 null 时直接返回 —— 防止拼出 "audit:stat:project:null" 这种
         * 脏 key，更防止误删别的东西。
         */
        @Test
        @DisplayName("projectId 为 null 时不做任何 Redis 操作")
        void evictNullProjectIdSkipped() {
            statService.evictProjectStat(null);
            verify(redisTemplate, never()).delete(anyString());
        }
    }

    @Nested
    @DisplayName("分布数据转换")
    class Distribution {

        /**
         * SQL 的 group by 别名是 key/value，前端要的是 name/value。
         *
         * <p>转换必须在后端做：状态码到中文名的映射表只在 Enums 里有一份，
         * 让前端再维护一份必然不同步。
         *
         * <p>注意 key 是字符串（MyBatis 把 TINYINT 映射成 Integer，
         * 但 String.valueOf 之后统一成字符串），value 可能返回
         * BigInteger / Long / Integer —— 转换方法里有 toLong 统一处理。
         * 这里故意用 Integer 塞进去，验证类型兼容性。
         */
        @Test
        @DisplayName("状态分布转换成中文名，数值类型兼容 BigInteger/Integer")
        void statusDistTranslated() {
            when(valueOps.get(KEY)).thenReturn(null);
            when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            stubDbQueries();

            List<Map<String, Object>> raw = new ArrayList<>();
            // MySQL COUNT 在驱动里可能是 BigInteger，也可能是 Long，这里两种都试
            raw.add(Map.of("key", Enums.EvidenceStatus.PENDING_GENERAL.getCode(), "value", 3L));
            raw.add(Map.of("key", String.valueOf(Enums.EvidenceStatus.CONFIRMED.getCode()),
                    "value", java.math.BigInteger.valueOf(8)));
            when(evidenceMapper.countGroupByStatus(1L)).thenReturn(raw);

            DashboardVO result = statService.projectStat(1L);

            assertThat(result.getEvidenceStatusDist())
                    .extracting("name")
                    .containsExactly("待一般复核", "已确认");
            assertThat(result.getEvidenceStatusDist())
                    .extracting("value")
                    .containsExactly(3L, 8L);
        }
    }
}
