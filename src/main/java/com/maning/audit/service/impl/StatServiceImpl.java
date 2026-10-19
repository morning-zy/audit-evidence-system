package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.entity.AuditEvidence;
import com.maning.audit.entity.AuditIssue;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.mapper.AuditEvidenceMapper;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.service.StatService;
import com.maning.audit.vo.DashboardVO;
import com.maning.audit.vo.ProjectVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务实现
 *
 * <p>Redis 在这个项目里承担两件事：
 * ① <b>缓存</b>：项目统计是"多表聚合"的重查询，每次打开看板都算一遍很浪费，
 *    缓存 5 分钟，写操作时主动失效（而不是傻等 TTL 过期）
 * ② <b>缓存穿透防护</b>：查询不存在的项目时，也会缓存一个空对象，
 *    否则恶意请求不存在的 ID 会每次都打到数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    /** 缓存重建锁的 TTL：够查库用就行，太长了持锁线程崩了会影响后续请求 */
    private static final long LOCK_TTL_SECONDS = 5L;

    /** 抢锁失败后最多重试几次。10 次 × 50ms = 500ms，超过这个时间说明不该再等 */
    private static final int LOCK_MAX_RETRY = 10;

    /** 空结果缓存 TTL：短一些，缩短「项目刚创建但缓存还是空」的不一致窗口 */
    private static final long EMPTY_TTL_SECONDS = 60L;

    /** TTL 随机抖动上限（秒）：防止大量 key 同时写入后同时过期造成雪崩 */
    private static final int TTL_JITTER_SECONDS = 60;

    private final AuditProjectMapper projectMapper;
    private final AuditIssueMapper issueMapper;
    private final AuditEvidenceMapper evidenceMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditProperties props;

    @Override
    public DashboardVO dashboard() {
        DashboardVO vo = new DashboardVO();

        // 进行中项目数
        vo.setActiveProjectCount(projectMapper.selectCount(Wrappers.<AuditProject>lambdaQuery()
                .eq(AuditProject::getStatus, Enums.ProjectStatus.IN_PROGRESS.getCode())));

        vo.setIssueTotal(issueMapper.selectCount(null));
        vo.setIssueCompleted(issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getStatus, Enums.IssueStatus.COMPLETED.getCode())));
        vo.setEvidenceTotal(evidenceMapper.selectCount(null));

        // 待我复核：主管视角下"别人提交的待复核"，普通审计员为 0
        if (UserContext.isAdmin()) {
            vo.setPendingReviewCount(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                    .eq(AuditEvidence::getStatus, Enums.EvidenceStatus.PENDING_REVIEW.getCode())
                    .ne(AuditEvidence::getSubmitBy, UserContext.getUserId())));
        } else {
            vo.setPendingReviewCount(0L);
        }

        vo.setMyEvidenceCount(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getSubmitBy, UserContext.getUserId())));

        vo.setEvidenceStatusDist(convertStatusDist(evidenceMapper.countGroupByStatus(null)));
        vo.setEvidenceTypeDist(convertTypeDist(evidenceMapper.countGroupByType(null)));

        // 直接调 Mapper 拿原始数据再组装，而不是调 projectService.progressList()。
        // 原因：Service 之间互相调用容易成环（见 toProjectVO 的注释），
        // 而这里需要的只是"几个统计数 + 项目基本信息"，Mapper 已经完全够用。
        // selectProjectProgress 已经 join 好了统计字段，只需补齐中文名和进度
        List<ProjectVO> progressList = projectMapper.selectProjectProgress(8);
        progressList.forEach(p -> {
            p.setStatusName(Enums.ProjectStatus.descOf(p.getStatus()));
            for (Enums.AuditType t : Enums.AuditType.values()) {
                if (t.getCode().equals(p.getAuditType())) {
                    p.setAuditTypeName(t.getDesc());
                    break;
                }
            }
            p.setRiskLevelName(switch (p.getRiskLevel() == null ? 0 : p.getRiskLevel()) {
                case 1 -> "低";
                case 2 -> "中";
                case 3 -> "高";
                default -> "";
            });
            int total = p.getIssueTotal() == null ? 0 : p.getIssueTotal();
            int done = p.getIssueCompleted() == null ? 0 : p.getIssueCompleted();
            p.setProgress(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
        });
        vo.setProjectProgressList(progressList);

        return vo;
    }

    /**
     * 项目维度统计（带缓存 + 防击穿）
     *
     * <p>缓存 key 设计：audit:stat:project:{projectId}
     * 用冒号分段是 Redis 社区约定，既便于阅读也便于 SCAN 匹配
     * （比如 SCAN MATCH audit:stat:project:* 能批量找到全部项目缓存）。
     *
     * <p><b>为什么需要防击穿</b>：
     * 假设看板缓存 TTL 到期，恰好这一刻有 200 个用户同时刷新。
     * 如果没有保护，200 个请求会同时查 Redis 拿到 null、同时冲进 MySQL，
     * 而本方法内部要发 6 条 SQL（4 次 count + 2 次 group by），
     * 200 × 6 = 1200 条 SQL 同时打到连接池上（HikariCP 默认只给 20 个连接），
     * 剩下 180 个请求排队等连接，等到超时就报错。
     *
     * <p><b>这里用互斥锁而不是逻辑过期</b>：
     * 逻辑过期（永不失效 + 后台异步重建）能做到完全无阻塞，但需要额外维护
     * "逻辑过期时间"字段，且实现复杂度高、缓存里存的是包装对象，排查时容易懵。
     * 本项目是审计系统，看板数据不是秒杀级别的高频热点，
     * 加锁带来的那一点等待（查库 30ms 左右）完全可以接受 —— 这是按业务量做的取舍，
     * 不是"不知道逻辑过期"。
     */
    @Override
    @SuppressWarnings("unchecked")
    public DashboardVO projectStat(Long projectId) {
        String key = props.getCache().getProjectStatKey() + projectId;

        // 1) 第一次检查：绝大多数请求走到这里就返回了，根本不会碰锁
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof DashboardVO vo) {
            log.debug("命中项目统计缓存: {}", key);
            return vo;
        }

        // 2) 缓存未命中，去抢锁。抢不到说明别人正在重建，自己睡一下再看缓存
        String lockKey = key + ":lock";
        String lockValue = UUID.randomUUID().toString();
        DashboardVO result = null;

        for (int attempt = 1; attempt <= LOCK_MAX_RETRY; attempt++) {
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(locked)) {
                try {
                    // 3) 双重检查：抢到锁不代表缓存一定还是空的 ——
                    // 可能上一个持锁者刚写完缓存并释放锁，那自己就不必再查一遍库
                    Object recheck = redisTemplate.opsForValue().get(key);
                    if (recheck instanceof DashboardVO vo) {
                        log.debug("抢到锁后二次命中缓存: {}", key);
                        return vo;
                    }
                    result = loadFromDbAndCache(projectId, key);
                    return result;
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }

            // 没抢到锁：等一小会儿再试，通常 50ms 内就能读到别人重建好的缓存
            sleepQuietly(50);
            Object retry = redisTemplate.opsForValue().get(key);
            if (retry instanceof DashboardVO vo) {
                log.debug("等待后命中他人重建的缓存: {}", key);
                return vo;
            }
        }

        // 4) 重试耗尽仍未拿到缓存：降级为直接查库。
        // 为什么不像秒杀那样直接返回"系统繁忙"：看板是只读统计页，
        // 返回旧数据或直接查库都比报错友好，可用性优先于一致性。
        log.warn("获取缓存重建锁失败，降级为直接查库: {}", key);
        return loadFromDbAndCache(projectId, key);
    }

    /**
     * 查库并回写缓存 —— 只有持有锁的线程才会走到这里。
     *
     * <p>单独抽出来是为了让 projectStat 的主流程（加锁/重试/降级）保持可读，
     * 面试时讲这段逻辑不至于被 6 条 SQL 淹没。
     */
    private DashboardVO loadFromDbAndCache(Long projectId, String key) {
        AuditProject project = projectMapper.selectById(projectId);
        if (project == null) {
            // 缓存穿透防护：空结果也缓存，但 TTL 短一些（60秒），
            // 避免"项目创建了但缓存还是空"的不一致窗口太长
            DashboardVO empty = new DashboardVO();
            empty.setEvidenceStatusDist(new ArrayList<>());
            empty.setEvidenceTypeDist(new ArrayList<>());
            empty.setProjectProgressList(new ArrayList<>());
            redisTemplate.opsForValue().set(key, empty, EMPTY_TTL_SECONDS, TimeUnit.SECONDS);
            return empty;
        }

        DashboardVO vo = new DashboardVO();
        vo.setIssueTotal(issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getProjectId, projectId)));
        vo.setIssueCompleted(issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getProjectId, projectId)
                .eq(AuditIssue::getStatus, Enums.IssueStatus.COMPLETED.getCode())));
        vo.setEvidenceTotal(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getProjectId, projectId)));
        vo.setPendingReviewCount(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getProjectId, projectId)
                .eq(AuditEvidence::getStatus, Enums.EvidenceStatus.PENDING_REVIEW.getCode())));

        vo.setEvidenceStatusDist(convertStatusDist(evidenceMapper.countGroupByStatus(projectId)));
        vo.setEvidenceTypeDist(convertTypeDist(evidenceMapper.countGroupByType(projectId)));

        ProjectVO pv = toProjectVO(project);
        vo.setProjectProgressList(List.of(pv));

        // TTL 加随机抖动，防止大量 key 同一秒写入后同一秒集体过期（雪崩）
        long ttl = props.getCache().getProjectStatTtl() + ThreadLocalRandom.current().nextInt(TTL_JITTER_SECONDS);
        redisTemplate.opsForValue().set(key, vo, ttl, TimeUnit.SECONDS);
        log.debug("写入项目统计缓存: {}, ttl={}s", key, ttl);
        return vo;
    }

    /**
     * 释放锁 —— 必须校验 value 再删。
     *
     * <p>为什么不能直接 delete：如果本次查库耗时超过了锁的 TTL（比如数据库卡了 10 秒，
     * 锁 5 秒就自动过期了），锁已经被别人抢走，这时直接 delete 会把<b>别人的锁</b>删掉，
     * 相当于没加锁。所以要先比对 value 是不是自己的，是才删。
     *
     * <p>严格来说"比对 + 删除"要原子完成（用 Lua 脚本），
     * 因为两步之间仍有极小的窗口。这里用 RedisTemplate 的简单实现，
     * 并发量低时足够；高并发场景应换成 Lua 或 Redisson（看门狗续期）。
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            Object current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            // 释放锁失败不影响业务：锁有 TTL 兜底，最多等它自己过期
            log.warn("释放缓存重建锁失败: {}", lockKey, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 主动失效缓存
     *
     * <p>为什么必须主动删而不是等 TTL：
     * 用户改完数据立刻刷新看板，如果还看到旧数字，会认为是 bug。
     * "更新数据库后删缓存"是 Cache Aside 模式的标准做法。
     *
     * <p>更严谨的做法是"延迟双删"（更新前删一次、更新后延迟再删一次），
     * 应对"删缓存 -> 另一个请求把旧值读回缓存"的极端并发场景。
     * 本项目并发低，单删足够。
     */
    @Override
    public void evictProjectStat(Long projectId) {
        if (projectId == null) {
            return;
        }
        String key = props.getCache().getProjectStatKey() + projectId;
        Boolean deleted = redisTemplate.delete(key);
        log.debug("清除项目统计缓存: {}, 结果={}", key, deleted);
    }

    // ------------------------------------------------------------------

    /**
     * 由实体组装 ProjectVO
     *
     * <p><b>这里是为了解开循环依赖而刻意做的内联实现。</b>
     * 原先 statService 调 projectService.detail()，而 projectService 又要调
     * statService.evictProjectStat() 清缓存，形成环：
     * <pre>
     *   StatServiceImpl  ->  ProjectService
     *        ↑                    |
     *        └────────────────────┘
     * </pre>
     * Spring Boot 2.6+ 默认禁止循环依赖（这是对的，循环依赖几乎总是职责划分错了）。
     *
     * <p>三种解法：
     * ① spring.main.allow-circular-references=true —— 把问题藏起来，不解决
     * ② @Lazy 注入 —— 拖延创建时机，本质还是环，只是不报错
     * ③ 拆掉调用 —— 统计需要的只是"项目基本字段 + 统计数"，
     *    这些在 StatServiceImpl 里本来就能查到，没必要绕回 ProjectService
     *
     * <p>选 ③。这也说明一个道理：<b>循环依赖的根因往往是职责重叠</b>，
     * 把重叠的部分拆干净，环自然就没了。
     */
    private ProjectVO toProjectVO(AuditProject p) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setProjectCode(p.getProjectCode());
        vo.setProjectName(p.getProjectName());
        vo.setAuditType(p.getAuditType());
        vo.setAuditedUnit(p.getAuditedUnit());
        vo.setLeaderId(p.getLeaderId());
        vo.setStartDate(p.getStartDate());
        vo.setEndDate(p.getEndDate());
        vo.setStatus(p.getStatus());
        vo.setStatusName(Enums.ProjectStatus.descOf(p.getStatus()));
        vo.setRiskLevel(p.getRiskLevel());
        vo.setDescription(p.getDescription());
        vo.setCreateTime(p.getCreateTime());
        for (Enums.AuditType t : Enums.AuditType.values()) {
            if (t.getCode().equals(p.getAuditType())) {
                vo.setAuditTypeName(t.getDesc());
                break;
            }
        }
        vo.setIssueTotal(vo2Int(issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getProjectId, p.getId()))));
        vo.setIssueCompleted(vo2Int(issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getProjectId, p.getId())
                .eq(AuditIssue::getStatus, Enums.IssueStatus.COMPLETED.getCode()))));
        vo.setEvidenceTotal(vo2Int(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getProjectId, p.getId()))));
        vo.setEvidenceConfirmed(vo2Int(evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getProjectId, p.getId())
                .eq(AuditEvidence::getStatus, Enums.EvidenceStatus.CONFIRMED.getCode()))));
        vo.setProgress(vo.getIssueTotal() == 0 ? 0
                : (int) Math.round(vo.getIssueCompleted() * 100.0 / vo.getIssueTotal()));
        return vo;
    }

    private Integer vo2Int(Long v) {
        return v == null ? 0 : v.intValue();
    }

    private List<DashboardVO.NameValueVO> convertStatusDist(List<Map<String, Object>> raw) {
        List<DashboardVO.NameValueVO> list = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String key = String.valueOf(row.get("key"));
            Long value = toLong(row.get("value"));
            String name;
            try {
                name = Enums.EvidenceStatus.descOf(Integer.parseInt(key));
            } catch (NumberFormatException e) {
                name = key;
            }
            list.add(new DashboardVO.NameValueVO(name, value));
        }
        return list;
    }

    private List<DashboardVO.NameValueVO> convertTypeDist(List<Map<String, Object>> raw) {
        List<DashboardVO.NameValueVO> list = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String key = String.valueOf(row.get("key"));
            list.add(new DashboardVO.NameValueVO(Enums.EvidenceType.descOf(key), toLong(row.get("value"))));
        }
        return list;
    }

    /** MySQL 的 COUNT 返回 BigInteger，直接强转 Long 会 ClassCastException */
    private Long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }
}
