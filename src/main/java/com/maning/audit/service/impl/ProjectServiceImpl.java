package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.PageResult;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.ProjectQuery;
import com.maning.audit.dto.ProjectSaveDTO;
import com.maning.audit.entity.AuditIssue;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import com.maning.audit.service.ProjectService;
import com.maning.audit.vo.ProjectVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计项目服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final AuditProjectMapper projectMapper;
    private final AuditIssueMapper issueMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditProperties props;

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public PageResult<ProjectVO> page(ProjectQuery query) {
        IPage<ProjectVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ProjectVO> result = projectMapper.selectProjectPage(page, query);

        // 计算进度百分比。放在 Java 里算而不是 SQL 里算，因为进度是"展示逻辑"，
        // 算法以后可能要改（比如按证据数加权），改 Java 比改 SQL 灵活。
        result.getRecords().forEach(this::fillViewFields);

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public ProjectVO detail(Long id) {
        ProjectVO vo = projectMapper.selectProjectDetail(id);
        if (vo == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "项目不存在或已删除");
        }
        fillViewFields(vo);
        return vo;
    }

    @Override
    public List<ProjectVO> progressList(Integer limit) {
        List<ProjectVO> list = projectMapper.selectProjectProgress(limit == null ? 6 : limit);
        list.forEach(this::fillViewFields);
        return list;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(ProjectSaveDTO dto) {
        validateDates(dto);

        AuditProject project = new AuditProject();
        project.setProjectCode(generateProjectCode());
        project.setProjectName(dto.getProjectName());
        project.setAuditType(dto.getAuditType());
        project.setAuditedUnit(dto.getAuditedUnit());
        project.setLeaderId(dto.getLeaderId());
        project.setStartDate(dto.getStartDate());
        project.setEndDate(dto.getEndDate());
        project.setRiskLevel(dto.getRiskLevel());
        project.setDescription(dto.getDescription());
        // 新建项目默认草稿，由负责人手动立项（进入进行中）
        project.setStatus(Enums.ProjectStatus.DRAFT.getCode());
        project.setCreateBy(UserContext.getUserId());

        projectMapper.insert(project);
        log.info("创建审计项目: {} - {}", project.getProjectCode(), project.getProjectName());
        return project.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ProjectSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "项目ID不能为空");
        }
        AuditProject exist = getExistProject(dto.getId());

        // 已归档的项目不允许再改，这是审计业务的硬规则：
        // 归档意味着审计报告已出具，底稿不允许再变动，否则就失去了审计证据的公信力
        if (Enums.ProjectStatus.ARCHIVED.getCode().equals(exist.getStatus())) {
            throw new BusinessException(ResultCode.PROJECT_NOT_EDITABLE, "已归档项目不允许修改");
        }
        validateDates(dto);

        AuditProject update = new AuditProject();
        update.setId(dto.getId());
        update.setProjectName(dto.getProjectName());
        update.setAuditType(dto.getAuditType());
        update.setAuditedUnit(dto.getAuditedUnit());
        update.setLeaderId(dto.getLeaderId());
        update.setStartDate(dto.getStartDate());
        update.setEndDate(dto.getEndDate());
        update.setRiskLevel(dto.getRiskLevel());
        update.setDescription(dto.getDescription());

        projectMapper.updateById(update);
        evictStat(dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getExistProject(id);

        // 有事项的项目不能删：否则事项和证据会变成孤儿数据
        Long issueCount = issueMapper.selectCount(Wrappers.<AuditIssue>lambdaQuery()
                .eq(AuditIssue::getProjectId, id));
        if (issueCount != null && issueCount > 0) {
            throw new BusinessException(ResultCode.PROJECT_HAS_ISSUE,
                    "项目下还有 " + issueCount + " 个审计事项，请先删除事项");
        }

        projectMapper.deleteById(id);
        evictStat(id);
        log.info("删除审计项目: id={}", id);
    }

    /**
     * 项目状态流转
     *
     * <p>状态机（不是任意状态都能跳到任意状态）：
     * <pre>
     *   草稿(0) --立项--> 进行中(1) --提交复核--> 待复核(2) --归档--> 已归档(3)
     *                        ^                      |
     *                        +-------退回----------+
     * </pre>
     * 把"哪个状态能到哪个状态"写成显式校验，而不是信任前端传值——
     * 前端可以绕过，后端必须自己守住。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer target) {
        AuditProject project = getExistProject(id);
        Integer current = project.getStatus();

        if (target == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标状态不能为空");
        }
        if (current.equals(target)) {
            throw new BusinessException(ResultCode.OPERATION_REPEAT, "项目已处于该状态");
        }

        boolean allowed = switch (current) {
            case 0 -> target == 1;                 // 草稿 -> 进行中
            case 1 -> target == 2;                 // 进行中 -> 待复核
            case 2 -> target == 3 || target == 1;  // 待复核 -> 归档 / 退回进行中
            default -> false;                      // 已归档是终态，不可再变
        };

        if (!allowed) {
            throw new BusinessException(ResultCode.DATA_STATE_ERROR,
                    "不允许从【" + Enums.ProjectStatus.descOf(current)
                            + "】变更为【" + Enums.ProjectStatus.descOf(target) + "】");
        }

        AuditProject update = new AuditProject();
        update.setId(id);
        update.setStatus(target);
        projectMapper.updateById(update);

        evictStat(id);
        log.info("项目状态变更: id={}, {} -> {}", id,
                Enums.ProjectStatus.descOf(current), Enums.ProjectStatus.descOf(target));
    }

    // ==================================================================
    // 私有方法
    // ==================================================================

    /**
     * 清除项目统计缓存
     *
     * <p>注意这里直接用 RedisTemplate 删 key，而不是调 statService.evictProjectStat。
     * 原因：原来 projectService -> statService，而 statService 又需要 projectService，
     * 形成循环依赖被 Spring 拦下。解决方案见 StatServiceImpl.toProjectVO() 的注释。
     * 清缓存的实现只有一行 redisTemplate.delete，不值得为它再造一层依赖。
     */
    private void evictStat(Long projectId) {
        if (projectId == null) {
            return;
        }
        redisTemplate.delete(props.getCache().getProjectStatKey() + projectId);
    }

    private AuditProject getExistProject(Long id) {
        AuditProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "项目不存在或已删除");
        }
        return project;
    }

    private void validateDates(ProjectSaveDTO dto) {
        if (dto.getStartDate() != null && dto.getEndDate() != null
                && dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "计划开始日期不能晚于结束日期");
        }
    }

    /**
     * 生成项目编号：AP + 年份 + 三位流水号，如 AP2026005
     *
     * <p>并发风险：高并发下可能生成重复编号，但表上有 uk_project_code 唯一索引兜底，
     * 重复时会抛 DuplicateKeyException。真要做严格无重复，应该用 Redis 自增
     * （INCR）或数据库序列表，本项目数据量小，用唯一索引 + 失败提示即可。
     */
    private String generateProjectCode() {
        String prefix = "AP" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        Long count = projectMapper.selectCount(Wrappers.<AuditProject>lambdaQuery()
                .likeRight(AuditProject::getProjectCode, prefix));
        long next = (count == null ? 0 : count) + 1;
        return prefix + String.format("%03d", next);
    }

    /**
     * 填充展示字段（枚举中文名、进度百分比）
     *
     * <p>放进私有方法而不是每个查询里重复写，这里有 4 个查询都用得到。
     */
    private void fillViewFields(ProjectVO vo) {
        vo.setStatusName(Enums.ProjectStatus.descOf(vo.getStatus()));
        vo.setAuditTypeName(auditTypeName(vo.getAuditType()));
        vo.setRiskLevelName(riskLevelName(vo.getRiskLevel()));

        int total = vo.getIssueTotal() == null ? 0 : vo.getIssueTotal();
        int done = vo.getIssueCompleted() == null ? 0 : vo.getIssueCompleted();
        // 没有事项时进度按 0 算，避免除零
        vo.setProgress(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
    }

    private String auditTypeName(String code) {
        for (Enums.AuditType t : Enums.AuditType.values()) {
            if (t.getCode().equals(code)) {
                return t.getDesc();
            }
        }
        return code;
    }

    private String riskLevelName(Integer level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case 1 -> "低";
            case 2 -> "中";
            case 3 -> "高";
            default -> "未知";
        };
    }
}
