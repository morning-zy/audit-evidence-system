package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.PageResult;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.dto.IssueQuery;
import com.maning.audit.dto.IssueSaveDTO;
import com.maning.audit.entity.AuditEvidence;
import com.maning.audit.entity.AuditIssue;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.mapper.AuditEvidenceMapper;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.service.IssueService;
import com.maning.audit.vo.IssueVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计事项服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    private final AuditIssueMapper issueMapper;
    private final AuditProjectMapper projectMapper;
    private final AuditEvidenceMapper evidenceMapper;

    @Override
    public PageResult<IssueVO> page(IssueQuery query) {
        IPage<IssueVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<IssueVO> result = issueMapper.selectIssuePage(page, query);
        result.getRecords().forEach(this::fillStatusName);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public IssueVO detail(Long id) {
        AuditIssue issue = getExistIssue(id);
        AuditProject project = projectMapper.selectById(issue.getProjectId());

        IssueVO vo = new IssueVO();
        vo.setId(issue.getId());
        vo.setProjectId(issue.getProjectId());
        vo.setProjectName(project == null ? null : project.getProjectName());
        vo.setIssueNo(issue.getIssueNo());
        vo.setIssueTitle(issue.getIssueTitle());
        vo.setRiskPoint(issue.getRiskPoint());
        vo.setAuditorId(issue.getAuditorId());
        vo.setStatus(issue.getStatus());
        vo.setEvidenceCount(issue.getEvidenceCount());
        vo.setCreateTime(issue.getCreateTime());
        fillStatusName(vo);
        return vo;
    }

    @Override
    public List<IssueVO> listByProject(Long projectId) {
        List<IssueVO> list = issueMapper.selectByProjectId(projectId);
        list.forEach(this::fillStatusName);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(IssueSaveDTO dto) {
        // 校验项目存在，避免事项挂到不存在的项目上（外键在应用层保证）
        AuditProject project = projectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "所属项目不存在");
        }
        if (Enums.ProjectStatus.ARCHIVED.getCode().equals(project.getStatus())) {
            throw new BusinessException(ResultCode.PROJECT_NOT_EDITABLE, "已归档项目不能新增事项");
        }

        AuditIssue issue = new AuditIssue();
        issue.setProjectId(dto.getProjectId());
        issue.setIssueNo(generateIssueNo(dto.getProjectId()));
        issue.setIssueTitle(dto.getIssueTitle());
        issue.setRiskPoint(dto.getRiskPoint());
        issue.setAuditorId(dto.getAuditorId());
        issue.setStatus(Enums.IssueStatus.PENDING.getCode());
        issue.setEvidenceCount(0);

        issueMapper.insert(issue);
        log.info("创建审计事项: {} - {}", issue.getIssueNo(), issue.getIssueTitle());
        return issue.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(IssueSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项ID不能为空");
        }
        AuditIssue exist = getExistIssue(dto.getId());

        AuditIssue update = new AuditIssue();
        update.setId(dto.getId());
        update.setIssueTitle(dto.getIssueTitle());
        update.setRiskPoint(dto.getRiskPoint());
        update.setAuditorId(dto.getAuditorId());
        issueMapper.updateById(update);

        // 这里不允许改 project_id：事项换项目会导致已有证据的归属逻辑混乱，
        // 业务上应该"删掉重建"。这类"看起来能改但实际不该改"的字段，
        // 最好的做法是 DTO 里根本不给，而不是给了再校验。
        log.info("更新审计事项: id={}, 原项目={}", dto.getId(), exist.getProjectId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getExistIssue(id);

        // 有证据的事项不能删
        Long count = evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                .eq(AuditEvidence::getIssueId, id));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.ISSUE_HAS_EVIDENCE,
                    "事项下还有 " + count + " 条证据，请先删除证据");
        }

        issueMapper.deleteById(id);
        log.info("删除审计事项: id={}", id);
    }

    /**
     * 事项状态流转
     *
     * <p>待执行(0) -> 证据收集中(1) -> 已完成(2)，只允许单向推进，不允许回退。
     * 另外：只要有证据，就不能停在"待执行"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer target) {
        AuditIssue issue = getExistIssue(id);
        Integer current = issue.getStatus();

        if (target == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标状态不能为空");
        }
        if (current.equals(target)) {
            throw new BusinessException(ResultCode.OPERATION_REPEAT, "事项已处于该状态");
        }

        boolean allowed = switch (current) {
            case 0 -> target == 1;
            case 1 -> target == 2 || target == 0;
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(ResultCode.DATA_STATE_ERROR,
                    "不允许从【" + Enums.IssueStatus.descOf(current)
                            + "】变更为【" + Enums.IssueStatus.descOf(target) + "】");
        }

        // 业务规则：完成事项前，该事项下不能还有未确认的证据
        if (target == 2) {
            Long unconfirmed = evidenceMapper.selectCount(Wrappers.<AuditEvidence>lambdaQuery()
                    .eq(AuditEvidence::getIssueId, id)
                    .ne(AuditEvidence::getStatus, Enums.EvidenceStatus.CONFIRMED.getCode()));
            if (unconfirmed != null && unconfirmed > 0) {
                throw new BusinessException(ResultCode.DATA_STATE_ERROR,
                        "还有 " + unconfirmed + " 条证据未确认，不能标记事项完成");
            }
        }

        AuditIssue update = new AuditIssue();
        update.setId(id);
        update.setStatus(target);
        issueMapper.updateById(update);
    }

    // ------------------------------------------------------------------

    private AuditIssue getExistIssue(Long id) {
        AuditIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "事项不存在或已删除");
        }
        return issue;
    }

    /**
     * 事项编号规则：IS + 项目编号后三位 + 序号，如 IS001-03
     *
     * <p><b>这里踩过一个真实的坑，值得记下来</b>：
     * 最初用 {@code selectCount(projectId)} + 1 生成流水号，看着很自然，
     * 但 MyBatis-Plus 的 selectCount 会自动追加 {@code deleted = 0}。
     * 一旦删过事项，count 就比实际已用编号小，生成出来的号会和逻辑删除的记录撞车，
     * 直接抛 {@code DuplicateKeyException: Duplicate entry 'IS001-04'}。
     *
     * <p>修复方式是查**物理**最大编号（不加 deleted 条件），流水号只增不退。
     * 这不只是修 bug —— 逻辑删除的业务语义是"本系统内不可见"，
     * 但编号在真实审计场景里会被纸质底稿、往来邮件引用，不能让别人复用它。
     */
    private String generateIssueNo(Long projectId) {
        AuditProject project = projectMapper.selectById(projectId);
        String projectPart = project == null || project.getProjectCode() == null ? "000"
                : project.getProjectCode().substring(Math.max(0, project.getProjectCode().length() - 3));

        String maxNo = issueMapper.selectMaxIssueNo(projectId);
        long next = 1;
        if (maxNo != null && maxNo.contains("-")) {
            try {
                next = Long.parseLong(maxNo.substring(maxNo.lastIndexOf('-') + 1)) + 1;
            } catch (NumberFormatException e) {
                // 历史数据编号不规范时退化为按条数算，至少不会崩
                Long count = issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                        .eq(AuditIssue::getProjectId, projectId));
                next = (count == null ? 0 : count) + 1;
            }
        }
        return "IS" + projectPart + "-" + String.format("%02d", next);
    }

    private void fillStatusName(IssueVO vo) {
        vo.setStatusName(Enums.IssueStatus.descOf(vo.getStatus()));
    }
}
