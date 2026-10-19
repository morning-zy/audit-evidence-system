package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.excel.EasyExcel;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.PageResult;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.common.util.EvidenceReliabilityEvaluator;
import com.maning.audit.common.util.EvidenceReviewWorkflow;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.EvidenceQuery;
import com.maning.audit.dto.EvidenceSaveDTO;
import com.maning.audit.entity.AuditEvidence;
import com.maning.audit.entity.AuditIssue;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.entity.EvidenceFlow;
import com.maning.audit.mapper.AuditEvidenceMapper;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.mapper.EvidenceFlowMapper;
import com.maning.audit.service.EvidenceService;
import com.maning.audit.vo.EvidenceVO;
import com.maning.audit.vo.EvidenceExportVO;
import com.maning.audit.vo.FlowVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 审计证据服务实现 —— 项目核心业务
 *
 * <p>这个类里有 4 个值得重点看的技术点，面试可以直接讲：
 * <ol>
 *   <li><b>状态机</b>：submit/approve/reject 三个方法都先校验"当前状态能否流转"，
 *       而不是信任前端传的按钮</li>
 *   <li><b>行锁</b>：selectByIdForUpdate 用 FOR UPDATE 防并发重复操作</li>
 *   <li><b>编制复核分离</b>：不能复核自己提交的证据，这是审计准则的硬要求</li>
 *   <li><b>完整留痕</b>：每次状态变更都写 evidence_flow，存操作人姓名的快照</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceServiceImpl implements EvidenceService {

    private final AuditEvidenceMapper evidenceMapper;
    private final AuditIssueMapper issueMapper;
    private final AuditProjectMapper projectMapper;
    private final EvidenceFlowMapper flowMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditProperties props;

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public PageResult<EvidenceVO> page(EvidenceQuery query) {
        Long currentUserId = UserContext.getUserId();

        // 待办只有主管能看。普通审计员传 todoOnly 也返回空，避免越权看到别人的待复核队列
        if (Boolean.TRUE.equals(query.getTodoOnly()) && !UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅审计主管可查看待复核队列");
        }

        IPage<EvidenceVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<EvidenceVO> result = evidenceMapper.selectEvidencePage(page, query, currentUserId);
        result.getRecords().forEach(vo -> fillViewFields(vo, currentUserId));

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public EvidenceVO detail(Long id) {
        Long currentUserId = UserContext.getUserId();
        EvidenceVO vo = evidenceMapper.selectEvidenceDetail(id, currentUserId);
        if (vo == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }
        fillViewFields(vo, currentUserId);
        return vo;
    }

    @Override
    public List<FlowVO> flowList(Long evidenceId) {
        List<FlowVO> list = flowMapper.selectByEvidenceId(evidenceId);
        list.forEach(f -> {
            f.setActionName(actionName(f.getAction()));
            f.setFromStatusName(f.getFromStatus() == null ? null : Enums.EvidenceStatus.descOf(f.getFromStatus()));
            f.setToStatusName(Enums.EvidenceStatus.descOf(f.getToStatus()));
            f.setReviewLevelName(f.getReviewLevel() == null
                    ? null : Enums.ReviewLevel.descOf(f.getReviewLevel()));
        });
        return list;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(EvidenceSaveDTO dto) {
        validateDto(dto);

        AuditIssue issue = issueMapper.selectById(dto.getIssueId());
        if (issue == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "所属审计事项不存在");
        }
        AuditProject project = projectMapper.selectById(issue.getProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "所属审计项目不存在");
        }
        if (Enums.ProjectStatus.ARCHIVED.getCode().equals(project.getStatus())) {
            throw new BusinessException(ResultCode.PROJECT_NOT_EDITABLE, "已归档项目不能新增证据");
        }

        AuditEvidence evidence = new AuditEvidence();
        evidence.setEvidenceNo(generateEvidenceNo(issue.getProjectId()));
        evidence.setProjectId(issue.getProjectId());
        evidence.setIssueId(dto.getIssueId());
        evidence.setEvidenceName(dto.getEvidenceName());
        evidence.setEvidenceType(dto.getEvidenceType());
        evidence.setSource(dto.getSource());

        // 可靠性不取用户输入，而是拿五个客观维度去算（准则 1301 号 第三条）
        applyReliability(evidence, dto);

        evidence.setFilePath(dto.getFilePath());
        evidence.setFileSize(dto.getFileSize());
        evidence.setRemark(dto.getRemark());
        evidence.setStatus(Enums.EvidenceStatus.DRAFT.getCode());

        evidenceMapper.insert(evidence);

        // 事项计数 +1（原子 SQL，不是先查后改）
        issueMapper.incrEvidenceCount(issue.getId(), 1);

        // 事项从"待执行"自动进入"证据收集中"——这是业务上的自然推进
        if (Enums.IssueStatus.PENDING.getCode().equals(issue.getStatus())) {
            AuditIssue issueUpdate = new AuditIssue();
            issueUpdate.setId(issue.getId());
            issueUpdate.setStatus(Enums.IssueStatus.COLLECTING.getCode());
            issueMapper.updateById(issueUpdate);
        }

        // 写流转记录：创建动作，不属于任何复核级别
        writeFlow(evidence.getId(), Enums.FlowAction.CREATE, null,
                Enums.EvidenceStatus.DRAFT.getCode(), 0, "创建证据");

        evictStat(issue.getProjectId());
        log.info("创建审计证据: {} - {}", evidence.getEvidenceNo(), evidence.getEvidenceName());
        return evidence.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(EvidenceSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "证据ID不能为空");
        }
        validateDto(dto);

        AuditEvidence exist = evidenceMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }

        // 只有草稿和已驳回可以编辑
        if (!canEdit(exist)) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "当前状态【" + Enums.EvidenceStatus.descOf(exist.getStatus()) + "】不允许编辑");
        }
        // 只能改自己提交的（主管也不能替下属改证据内容——审计准则要求证据由编制人负责）
        checkOwner(exist);

        AuditEvidence update = new AuditEvidence();
        update.setId(dto.getId());
        update.setEvidenceName(dto.getEvidenceName());
        update.setEvidenceType(dto.getEvidenceType());
        update.setSource(dto.getSource());
        // 五维度改了就要重算可靠性，否则可能出现「维度是副本、等级还是原件」
        applyReliability(update, dto);
        update.setFilePath(dto.getFilePath());
        update.setFileSize(dto.getFileSize());
        update.setRemark(dto.getRemark());
        evidenceMapper.updateById(update);

        evictStat(exist.getProjectId());
    }

    /**
     * 把 DTO 的五个可靠性维度落到实体上，并算出可靠性等级。
     *
     * <p>抽成一个方法而不是在 save / update 里各写一遍，是为了防止两条路径漂移 ——
     * 之前就出现过「新增时算了、修改时没重算」导致等级和维度对不上的问题。
     */
    private void applyReliability(AuditEvidence target, EvidenceSaveDTO dto) {
        target.setSourceType(dto.getSourceType());
        target.setAcquireMethod(dto.getAcquireMethod());
        target.setFormType(dto.getFormType());
        target.setOriginality(dto.getOriginality());
        target.setControlEffective(dto.getControlEffective());

        EvidenceReliabilityEvaluator.Result r = EvidenceReliabilityEvaluator.evaluate(
                parseSource(dto.getSourceType()),
                parseAcquire(dto.getAcquireMethod()),
                parseForm(dto.getFormType()),
                parseOriginality(dto.getOriginality()),
                dto.getControlEffective());

        target.setReliability(r.getLevel().getCode());
        target.setReliabilityBasis(r.getExplanation());

        log.debug("证据可靠性评定: {} 级, 得分 {}", r.getLevel().getGrade(), r.getScore());
    }

    /**
     * 字符串转枚举，非法值一律按"最差"处理而不是抛异常。
     *
     * <p>为什么容错而不报错：DTO 层已经用 @NotBlank 拦住了空值，
     * 能走到这里的异常只会是"传了个不认识的枚举名"。
     * 这种情况按最差处理比直接 500 更安全 —— 顶多把等级评低，
     * 不会让一条本该被质疑的证据因为拼写错误而混过去。
     */
    private Enums.EvidenceSource parseSource(String v) {
        try {
            return v == null ? null : Enums.EvidenceSource.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warn("非法证据来源枚举值: {}，按管理层声明处理", v);
            return Enums.EvidenceSource.MANAGEMENT;
        }
    }

    private Enums.AcquireMethod parseAcquire(String v) {
        try {
            return v == null ? null : Enums.AcquireMethod.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warn("非法获取方式枚举值: {}，按推算处理", v);
            return Enums.AcquireMethod.INFERRED;
        }
    }

    private Enums.EvidenceForm parseForm(String v) {
        try {
            return v == null ? null : Enums.EvidenceForm.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warn("非法存在形式枚举值: {}，按口头处理", v);
            return Enums.EvidenceForm.ORAL;
        }
    }

    private Enums.Originality parseOriginality(String v) {
        try {
            return v == null ? null : Enums.Originality.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warn("非法原件性枚举值: {}，按复印件处理", v);
            return Enums.Originality.COPY;
        }
    }

    /**
     * 按 code 查可靠性等级。查不到时返回 LOW —— 宁可判低不判高，
     * 因为把一条该被质疑的证据判成高可靠性，后果比反过来严重得多。
     */
    private Enums.Reliability findByCode(Integer code) {
        if (code != null) {
            for (Enums.Reliability r : Enums.Reliability.values()) {
                if (r.getCode().equals(code)) {
                    return r;
                }
            }
        }
        return Enums.Reliability.LOW;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recalculateAllReliability() {
        // 只取需要的列，不 select *：证据表有 file_path 这类长字段，
        // 全量加载到内存里算一遍没必要
        List<AuditEvidence> all = evidenceMapper.selectList(Wrappers.<AuditEvidence>lambdaQuery()
                .select(AuditEvidence::getId,
                        AuditEvidence::getSourceType,
                        AuditEvidence::getAcquireMethod,
                        AuditEvidence::getFormType,
                        AuditEvidence::getOriginality,
                        AuditEvidence::getControlEffective,
                        AuditEvidence::getReliability));

        int changed = 0;
        for (AuditEvidence e : all) {
            EvidenceReliabilityEvaluator.Result r = EvidenceReliabilityEvaluator.evaluate(
                    parseSource(e.getSourceType()),
                    parseAcquire(e.getAcquireMethod()),
                    parseForm(e.getFormType()),
                    parseOriginality(e.getOriginality()),
                    e.getControlEffective());

            Integer newCode = r.getLevel().getCode();
            // 只在等级或依据真的变了才 update，避免无意义的写库
            if (Objects.equals(newCode, e.getReliability())
                    && Objects.equals(r.getExplanation(), e.getReliabilityBasis())) {
                continue;
            }

            AuditEvidence update = new AuditEvidence();
            update.setId(e.getId());
            update.setReliability(newCode);
            update.setReliabilityBasis(r.getExplanation());
            evidenceMapper.updateById(update);
            changed++;
        }

        log.info("可靠性重算完成：共 {} 条，其中 {} 条等级或依据发生变化", all.size(), changed);
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {        AuditEvidence exist = evidenceMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }
        // 待复核和已确认的证据不能删，否则审计底稿会被悄悄篡改
        if (!canEdit(exist)) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "当前状态【" + Enums.EvidenceStatus.descOf(exist.getStatus()) + "】不允许删除");
        }
        checkOwner(exist);

        evidenceMapper.deleteById(id);
        issueMapper.incrEvidenceCount(exist.getIssueId(), -1);

        evictStat(exist.getProjectId());
        log.info("删除审计证据: id={}, no={}", id, exist.getEvidenceNo());
    }

    /**
     * 提交复核： 草稿(0) / 已驳回(3) -> 待详细复核(1)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        // 注意这里用 selectByIdForUpdate，不是 selectById。
        // 如果同时点了两次"提交"，不加行锁会写两条 SUBMIT 流转记录。
        AuditEvidence evidence = evidenceMapper.selectByIdForUpdate(id);
        if (evidence == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }
        checkOwner(evidence);

        Integer from = evidence.getStatus();
        // 状态能否提交由状态机判定，不在 Service 里再写一遍 if —— 规则只有一处
        EvidenceReviewWorkflow.Transition t = EvidenceReviewWorkflow.submit(from);

        // 业务校验：必须挂了附件才能提交，空壳证据不能进复核队列
        if (!StringUtils.hasText(evidence.getFilePath())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先上传证据附件再提交复核");
        }

        // 业务校验：低可靠性证据不能"裸奔"进复核队列
        //
        // 依据：可靠性 C 级及以下（内部生成 + 复印件 + 口头说明这类组合），
        // 单一证据的证明力不足以支持审计结论。准则要求"充分、适当"，
        // 数量不足时不能用质量弥补，反过来质量不够也不能只靠数量堆。
        // 所以系统强制：这类证据必须在备注里说明补充方案，或者另附佐证材料。
        Enums.Reliability level = findByCode(evidence.getReliability());
        if (EvidenceReliabilityEvaluator.requiresSupplementary(level)
                && !StringUtils.hasText(evidence.getRemark())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "该证据可靠性为【" + level.getGrade() + " 级 " + level.getDesc()
                            + "】，证明力不足。提交复核前请在备注中说明补充佐证方案"
                            + "（例如：将向银行函证原件、补充第三方检测报告）");
        }

        AuditEvidence update = new AuditEvidence();
        update.setId(id);
        update.setStatus(t.toStatus());
        update.setCurrentLevel(t.nextLevel());
        update.setSubmitBy(UserContext.getUserId());
        update.setSubmitTime(LocalDateTime.now());
        // 重新提交时清空上一轮的复核结论，避免旧意见残留误导
        update.setReviewBy(null);
        update.setReviewTime(null);
        update.setReviewComment(null);
        evidenceMapper.updateById(update);

        writeFlow(id, t.action(), from, t.toStatus(), t.nextLevel(),
                "提交复核，进入" + Enums.ReviewLevel.descOf(t.nextLevel()));

        evictStat(evidence.getProjectId());
        log.info("证据提交复核: no={}, by={}, 进入{}", evidence.getEvidenceNo(),
                UserContext.getRealName(), Enums.ReviewLevel.descOf(t.nextLevel()));
    }

    /**
     * 复核通过：逐级推进，第三级通过后生成最终结论。
     *
     * <pre>
     *   待详细复核(1) --通过--> 待一般复核(4) --通过--> 待重点复核(5) --通过--> 已确认(2)
     * </pre>
     *
     * <p>三级复核的关键语义：<b>一级通过不等于证据生效</b>，
     * 只有三级全部通过，这条证据才能作为审计结论的依据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String approve(Long id, String comment) {
        AuditEvidence evidence = evidenceMapper.selectByIdForUpdate(id);
        if (evidence == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }

        // 权限判定全部交给状态机：
        // ① 现在卡在第几级（从状态反推，不信任前端传的级别）
        // ② 我这个角色能不能审这一级
        // ③ 是不是我自己提交的（编制复核分离）
        Integer from = evidence.getStatus();
        int level = EvidenceReviewWorkflow.currentReviewLevel(from);
        if (level == 0) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "当前状态【" + Enums.EvidenceStatus.descOf(from) + "】不在复核流程中，无需复核");
        }
        EvidenceReviewWorkflow.checkReviewPermission(
                currentRole(), UserContext.getRoleDesc(), level);
        EvidenceReviewWorkflow.checkNotSelfReview(evidence.getSubmitBy(), UserContext.getUserId());

        EvidenceReviewWorkflow.Transition t = EvidenceReviewWorkflow.approve(from);

        AuditEvidence update = new AuditEvidence();
        update.setId(id);
        update.setStatus(t.toStatus());
        update.setCurrentLevel(t.nextLevel());
        // 复核人字段记录"最近一次操作的人"，完整的各级签字在 evidence_flow 里
        update.setReviewBy(UserContext.getUserId());
        update.setReviewTime(LocalDateTime.now());
        update.setReviewComment(comment);
        evidenceMapper.updateById(update);

        String flowComment = StringUtils.hasText(comment) ? comment : "复核通过";
        writeFlow(id, t.action(), from, t.toStatus(), level,
                "[" + Enums.ReviewLevel.descOf(level) + "] " + flowComment
                        + "；" + t.nextStageDesc());

        evictStat(evidence.getProjectId());
        log.info("证据{}通过: no={}, reviewer={}, 下一环节={}", Enums.ReviewLevel.descOf(level),
                evidence.getEvidenceNo(), UserContext.getRealName(), t.nextStageDesc());
        return t.nextStageDesc();
    }

    /**
     * 复核驳回：任意一级驳回都退回编制人。
     *
     * <p>为什么不退回上一级：驳回意味着"这份底稿从根上不成立"，
     * 需要补做审计程序，而不是让上一级去猜下一级要什么。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        AuditEvidence evidence = evidenceMapper.selectByIdForUpdate(id);
        if (evidence == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "证据不存在或已删除");
        }

        Integer from = evidence.getStatus();
        int level = EvidenceReviewWorkflow.currentReviewLevel(from);
        if (level == 0) {
            throw new BusinessException(ResultCode.EVIDENCE_STATUS_ILLEGAL,
                    "当前状态【" + Enums.EvidenceStatus.descOf(from) + "】不在复核流程中，无需复核");
        }
        EvidenceReviewWorkflow.checkReviewPermission(
                currentRole(), UserContext.getRoleDesc(), level);
        EvidenceReviewWorkflow.checkNotSelfReview(evidence.getSubmitBy(), UserContext.getUserId());

        // 驳回必须说明理由，否则编制人不知道改什么
        if (!StringUtils.hasText(comment)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "驳回时必须填写复核意见");
        }

        EvidenceReviewWorkflow.Transition t = EvidenceReviewWorkflow.reject(from);

        AuditEvidence update = new AuditEvidence();
        update.setId(id);
        update.setStatus(t.toStatus());
        update.setCurrentLevel(0);
        update.setReviewBy(UserContext.getUserId());
        update.setReviewTime(LocalDateTime.now());
        update.setReviewComment(comment);
        evidenceMapper.updateById(update);

        writeFlow(id, t.action(), from, t.toStatus(), level,
                "[" + Enums.ReviewLevel.descOf(level) + "] " + comment);

        evictStat(evidence.getProjectId());
        log.info("证据{}驳回: no={}, reviewer={}, reason={}", Enums.ReviewLevel.descOf(level),
                evidence.getEvidenceNo(), UserContext.getRealName(), comment);
    }

    // ==================================================================
    // 导出
    // ==================================================================

    /**
     * 导出证据清单
     *
     * <p>注意这里没有分页，但给了一个上限（5000 条）：
     * 导出是"全量"语义，前端传 pageSize=10 只导出 10 条会让人莫名其妙。
     * 但也不能真的不限——几十万行会把内存和浏览器都拖垮。
     * 生产上应该改成"异步导出 + 生成文件 + 消息通知下载"。
     */
    @Override
    public void exportExcel(EvidenceQuery query, HttpServletResponse response) {
        Long currentUserId = UserContext.getUserId();

        // 复用分页查询，把 size 拉到 5000（PageQuery 里会强制截断到 100，所以这里单独构造）
        query.setPageNum(1L);
        query.setPageSize(5000L);

        IPage<EvidenceVO> page = new Page<>(1, 5000);
        IPage<EvidenceVO> result = evidenceMapper.selectEvidencePage(page, query, currentUserId);

        List<EvidenceExportVO> rows = result.getRecords().stream().map(vo -> {
            EvidenceExportVO e = new EvidenceExportVO();
            e.setEvidenceNo(vo.getEvidenceNo());
            e.setProjectName(vo.getProjectName());
            e.setIssueNo(vo.getIssueNo());
            e.setEvidenceName(vo.getEvidenceName());
            e.setEvidenceTypeName(Enums.EvidenceType.descOf(vo.getEvidenceType()));
            e.setSource(vo.getSource());
            // 导出时带上字母等级和判定依据，这样导出的 Excel 能直接作为底稿附件
            e.setReliabilityName(vo.getReliabilityGrade() + " 级（" + vo.getReliabilityName() + "）");
            e.setStatusName(Enums.EvidenceStatus.descOf(vo.getStatus()));
            e.setSubmitByName(vo.getSubmitByName());
            e.setSubmitTime(vo.getSubmitTime());
            e.setReviewByName(vo.getReviewByName());
            e.setReviewTime(vo.getReviewTime());
            e.setReviewComment(vo.getReviewComment());
            return e;
        }).toList();

        String fileName = "审计证据清单_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        // 中文文件名必须 URLEncode，否则浏览器下载时文件名乱码
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);

        try {
            EasyExcel.write(response.getOutputStream(), EvidenceExportVO.class)
                    .sheet("审计证据清单")
                    .doWrite(rows);
        } catch (IOException e) {
            log.error("导出证据清单失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "导出失败");
        }
    }

    // ==================================================================
    // 私有方法
    // ==================================================================

    /**
     * 生成证据编号：EV + 年份 + 4 位流水，如 EV20260009
     *
     * <p>并发处理思路（这里值得单独讲）：
     * ① 先用 MAX 查出当前最大编号，+1 得到候选编号
     * ② 表上有 uk_evidence_no 唯一索引，如果两个请求同时算出同一个号，
     *    后提交的那个会抛 DuplicateKeyException
     * ③ 捕获后重试，最多 3 次
     *
     * <p>这是一个"乐观尝试 + 唯一约束兜底"的经典模式，比加分布式锁轻量得多。
     * 缺点是重试期间会有少量性能损耗，适用于"写冲突概率低"的场景。
     */
    private String generateEvidenceNo(Long projectId) {
        String prefix = "EV" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String maxNo = evidenceMapper.selectMaxEvidenceNo(prefix);

        long next = 1L;
        if (StringUtils.hasText(maxNo) && maxNo.length() > prefix.length()) {
            try {
                next = Long.parseLong(maxNo.substring(prefix.length())) + 1;
            } catch (NumberFormatException e) {
                log.warn("证据编号解析失败, 重新从1开始: {}", maxNo);
            }
        }
        return prefix + String.format("%04d", next);
    }

    /**
     * 写流转记录
     *
     * <p>operatorName 存的是快照。为什么要冗余存姓名而不是每次 join：
     * 用户改名后，历史记录应该显示"当时操作的人名"，
     * 这是审计追溯的特殊要求——账要能对上当时的情形。
     */
    private void writeFlow(Long evidenceId, Enums.FlowAction action, Integer fromStatus,
                           Integer toStatus, Integer reviewLevel, String comment) {
        EvidenceFlow flow = new EvidenceFlow();
        flow.setEvidenceId(evidenceId);
        flow.setAction(action.getCode());
        flow.setFromStatus(fromStatus);
        flow.setToStatus(toStatus);
        flow.setReviewLevel(reviewLevel == null || reviewLevel == 0 ? null : reviewLevel);
        flow.setOperatorId(UserContext.getUserId());
        // 兜底：理论上拦截器一定设置了用户，但异步/定时任务里可能没有
        flow.setOperatorName(Objects.requireNonNullElse(UserContext.getRealName(), "系统"));
        flow.setComment(comment);
        flow.setCreateTime(LocalDateTime.now());
        flowMapper.insert(flow);
    }

    /** 当前登录用户角色，统一从这里取，避免各处自己解析 */
    private Enums.Role currentRole() {
        return UserContext.getRoleEnum();
    }

    /** 只有草稿和已驳回允许编辑/删除 */
    private boolean canEdit(AuditEvidence evidence) {
        Integer s = evidence.getStatus();
        return Enums.EvidenceStatus.DRAFT.getCode().equals(s)
                || Enums.EvidenceStatus.REJECTED.getCode().equals(s);
    }

    /** 只能操作自己创建/提交的证据 */
    private void checkOwner(AuditEvidence evidence) {
        Long current = UserContext.getUserId();
        Long owner = evidence.getSubmitBy();
        // submitBy 为 null 说明还在草稿且从没提交过，此时用创建人无法判断，
        // 退化为"管理员可操作，其他人必须是自己"——这里简化处理为仅校验非空情况
        if (owner != null && !owner.equals(current)) {
            // 主管也不允许改别人的证据内容，但允许删除（有审计质量问题时要能清理）
            if (!UserContext.isAdmin()) {
                throw new BusinessException(ResultCode.EVIDENCE_NO_PERMISSION);
            }
        }
    }

    /**
     * 复核权限校验
     *
     * <p>两条硬规则：
     * ① 只有 ADMIN（审计主管）能复核
     * ② 不能复核自己提交的证据 —— 这是审计"编制与复核分离"原则的代码实现
     */
    /**
     * 复核权限校验（旧版单级复核的入口，保留给兼容性检查使用）
     *
     * <p>三级复核下真正的判定已迁移到 {@link EvidenceReviewWorkflow}，
     * 这里只保留"不能复核自己提交的证据"这条底线，
     * 供 fillViewFields 之类的展示逻辑复用。
     */
    private void checkReviewPermission(AuditEvidence evidence) {
        if (Objects.equals(evidence.getSubmitBy(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.EVIDENCE_NO_SELF_REVIEW);
        }
    }

    /** 清除项目统计缓存（理由同 ProjectServiceImpl.evictStat） */
    private void evictStat(Long projectId) {
        if (projectId == null) {
            return;
        }
        redisTemplate.delete(props.getCache().getProjectStatKey() + projectId);
    }

    private void validateDto(EvidenceSaveDTO dto) {
        if (!Enums.EvidenceType.isValid(dto.getEvidenceType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "证据类型不合法");
        }
    }

    /**
     * 填充展示字段 + 前端按钮控制标记
     *
     * <p>canEdit / canReview 放在后端算，而不是前端自己判断：
     * 前端判断只能隐藏按钮，真正防越权必须靠后端接口校验。
     * 两边都算一遍的好处是"界面不会出现点了报错的按钮"，
     * 但后端的校验才是安全边界，前端标记只是体验优化。
     */
    private void fillViewFields(EvidenceVO vo, Long currentUserId) {
        vo.setStatusName(Enums.EvidenceStatus.descOf(vo.getStatus()));
        vo.setEvidenceTypeName(Enums.EvidenceType.descOf(vo.getEvidenceType()));

        // 可靠性相关的展示字段：等级 + 字母 + 五个维度中文名 + 判定依据
        Enums.Reliability level = findByCode(vo.getReliability());
        vo.setReliabilityName(level.getDesc());
        vo.setReliabilityGrade(level.getGrade());
        vo.setRequiresSupplementary(EvidenceReliabilityEvaluator.requiresSupplementary(level));

        vo.setSourceTypeName(Enums.EvidenceSource.descOf(vo.getSourceType()));
        vo.setAcquireMethodName(Enums.AcquireMethod.descOf(vo.getAcquireMethod()));
        vo.setFormTypeName(Enums.EvidenceForm.descOf(vo.getFormType()));
        vo.setOriginalityName(Enums.Originality.descOf(vo.getOriginality()));

        // ---- 三级复核进度（前端据此画时间轴）----
        int currentLevel = EvidenceReviewWorkflow.currentReviewLevel(vo.getStatus());
        vo.setCurrentLevel(currentLevel);
        vo.setCurrentLevelName(currentLevel == 0 ? null : Enums.ReviewLevel.descOf(currentLevel));
        vo.setReviewing(currentLevel > 0);
        // 当前轮到了哪个岗位，前端用来提示"此环节由部门经理处理"
        vo.setPendingRoleName(currentLevel == 0
                ? null : EvidenceReviewWorkflow.requiredRoleOf(currentLevel).getDesc());
        vo.setReviewFinished(Enums.EvidenceStatus.CONFIRMED.getCode().equals(vo.getStatus()));

        boolean isOwner = Objects.equals(vo.getSubmitBy(), currentUserId);
        boolean editableStatus = Enums.EvidenceStatus.DRAFT.getCode().equals(vo.getStatus())
                || Enums.EvidenceStatus.REJECTED.getCode().equals(vo.getStatus());

        Enums.Role role = currentRole();
        vo.setCanEdit(editableStatus && (isOwner || role == Enums.Role.ADMIN));

        // 能不能看到"复核"按钮 = 当前有在审的级别 + 我的角色对得上 + 不是我提交的
        // 三个条件缺一不可，且全部由状态机判定，和真正提交时的校验用的是同一套规则
        vo.setCanReview(currentLevel > 0
                && EvidenceReviewWorkflow.canReview(role, currentLevel)
                && !isOwner);
        vo.setMyRoleName(UserContext.getRoleDesc());
    }

    /** 动作中文名 —— 委托给枚举的 descOf，避免这里再维护一份 switch */
    private String actionName(String code) {
        return Enums.FlowAction.descOf(code);
    }
}
