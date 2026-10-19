package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.ProjectSaveDTO;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.mapper.AuditIssueMapper;
import com.maning.audit.mapper.AuditProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目服务单元测试 —— 状态流转规则
 *
 * <h2>这一块测什么</h2>
 *
 * <p>项目状态机（草稿 → 进行中 → 待复核 → 已归档）比证据的状态机简单，
 * 但它有一条别的系统里很少见、审计行业里却是铁律的规则：
 * <b>已归档不可逆</b>。归档意味着审计报告已出具、底稿已成定稿，
 * 再改就失去了证据的公信力。
 *
 * <p>所以这里的重点不是"能不能流转"，而是"什么情况下必须拦住"。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计项目服务")
class ProjectServiceImplTest {

    @Mock
    private AuditProjectMapper projectMapper;
    @Mock
    private AuditIssueMapper issueMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private ProjectServiceImpl projectService;
    private final AuditProperties props = new AuditProperties();

    @BeforeEach
    void setUp() throws Exception {
        projectService = new ProjectServiceImpl(projectMapper, issueMapper, redisTemplate, props);
        org.mockito.Mockito.lenient().when(redisTemplate.delete(anyString())).thenReturn(true);
    }

    private AuditProject project(Integer status) {
        AuditProject p = new AuditProject();
        p.setId(1L);
        p.setProjectCode("AP2026001");
        p.setProjectName("2026 年度财务报表审计");
        p.setStatus(status);
        return p;
    }

    @Nested
    @DisplayName("状态流转 changeStatus")
    class ChangeStatus {

        /**
         * 合法流转用一张表列全，非法流转也列全 —— 两边都写死，
         * 才能保证"没有漏掉某个本来不该允许的组合"。
         */
        @ParameterizedTest(name = "{0} -> {1} 允许")
        @CsvSource({
                "DRAFT, IN_PROGRESS",        // 草稿 -> 立项
                "IN_PROGRESS, REVIEWING",    // 现场结束 -> 提交复核
                "REVIEWING, ARCHIVED",       // 复核通过 -> 归档
                "REVIEWING, IN_PROGRESS",    // 复核退回 -> 继续做
        })
        @DisplayName("四类合法流转")
        void allowedTransitions(Enums.ProjectStatus from, Enums.ProjectStatus to) {
            when(projectMapper.selectById(1L)).thenReturn(project(from.getCode()));

            projectService.changeStatus(1L, to.getCode());

            ArgumentCaptor<AuditProject> captor = ArgumentCaptor.forClass(AuditProject.class);
            verify(projectMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(to.getCode());
        }

        @ParameterizedTest(name = "{0} -> {1} 拒绝")
        @CsvSource({
                "DRAFT, REVIEWING",          // 草稿不能跳过立项直接送审
                "DRAFT, ARCHIVED",           // 草稿不能直接归档
                "IN_PROGRESS, ARCHIVED",     // 没走复核就不能归档 —— 这是关键
                "ARCHIVED, IN_PROGRESS",     // 已归档是终态，不可回退
                "ARCHIVED, DRAFT",           // 终态不可回退
                "ARCHIVED, REVIEWING",
        })
        @DisplayName("六类非法流转全部拒绝")
        void forbiddenTransitions(Enums.ProjectStatus from, Enums.ProjectStatus to) {
            when(projectMapper.selectById(1L)).thenReturn(project(from.getCode()));

            assertThatThrownBy(() -> projectService.changeStatus(1L, to.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.DATA_STATE_ERROR.getCode());

            verify(projectMapper, never()).updateById(any(AuditProject.class));
        }

        /**
         * 关键规则：进行中不能直接归档。
         *
         * <p>这条单独拎出来写，因为它是审计系统独有的业务约束。
         * "项目做完了直接归档"在普通项目管理系统里很常见，
         * 但审计必须先经过复核环节 —— 否则等于自己写的底稿自己盖章。
         */
        @Test
        @DisplayName("进行中不能直接归档，必须先经过待复核")
        void cannotArchiveWithoutReview() {
            when(projectMapper.selectById(1L))
                    .thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));

            assertThatThrownBy(() -> projectService.changeStatus(1L, Enums.ProjectStatus.ARCHIVED.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.DATA_STATE_ERROR.getCode())
                    // 提示语把"当前状态"和"目标状态"都写出来，用户才知道卡在哪
                    .hasMessageContaining("进行中")
                    .hasMessageContaining("已归档");
        }

        @Test
        @DisplayName("已处于目标状态 -> 2004 重复操作")
        void sameStatusIsRepeat() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));

            assertThatThrownBy(() -> projectService.changeStatus(1L, Enums.ProjectStatus.IN_PROGRESS.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.OPERATION_REPEAT.getCode());
        }

        @Test
        @DisplayName("目标状态为空 -> 400")
        void nullTargetRejected() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.DRAFT.getCode()));

            assertThatThrownBy(() -> projectService.changeStatus(1L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PARAM_ERROR.getCode());
        }

        /**
         * 状态变更后必须清缓存 —— 否则看板上项目还显示"进行中"。
         *
         * <p>和证据那边同一个道理：写操作改库不改缓存，是最常见的一致性 bug。
         */
        @Test
        @DisplayName("状态变更后清除项目统计缓存")
        void changeStatusEvictsCache() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.DRAFT.getCode()));

            projectService.changeStatus(1L, Enums.ProjectStatus.IN_PROGRESS.getCode());

            verify(redisTemplate).delete("audit:stat:project:1");
        }
    }

    @Nested
    @DisplayName("编辑与删除的保护")
    class Protection {

        /**
         * 已归档项目不允许修改。
         *
         * <p>这是审计底稿的"定稿"语义：报告出了，底稿就冻结了。
         * 要改只能走"撤销归档"的正式流程（本项目没做，属于已知限制）。
         */
        @Test
        @DisplayName("已归档项目禁止修改")
        void archivedCannotEdit() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.ARCHIVED.getCode()));

            ProjectSaveDTO dto = new ProjectSaveDTO();
            dto.setId(1L);
            dto.setProjectName("改名试试");

            assertThatThrownBy(() -> projectService.update(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PROJECT_NOT_EDITABLE.getCode());

            verify(projectMapper, never()).updateById(any(AuditProject.class));
        }

        /**
         * 起始日期晚于结束日期 —— 参数错误。
         *
         * <p>这个校验不起眼，但真发生过：新建项目时把日期填反，
         * 结果进度条算出负数，看板上显示 -30%。
         */
        @Test
        @DisplayName("开始日期晚于结束日期 -> 400")
        void invalidDateRange() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.IN_PROGRESS.getCode()));

            ProjectSaveDTO dto = new ProjectSaveDTO();
            dto.setId(1L);
            dto.setProjectName("项目");
            dto.setAuditType(Enums.AuditType.FINANCIAL.getCode());
            dto.setAuditedUnit("某公司");
            dto.setLeaderId(1L);
            dto.setRiskLevel(2);
            dto.setStartDate(LocalDate.of(2026, 12, 31));
            dto.setEndDate(LocalDate.of(2026, 1, 1));

            assertThatThrownBy(() -> projectService.update(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PARAM_ERROR.getCode())
                    .hasMessageContaining("开始日期");
        }

        /**
         * 有事项的项目不能删 —— 否则事项和证据会变成孤儿数据。
         *
         * <p>这里用 selectCount 的返回值模拟"库里有 3 个事项"。
         */
        @Test
        @DisplayName("项目下有事项时禁止删除")
        void cannotDeleteProjectWithIssues() {
            when(projectMapper.selectById(1L)).thenReturn(project(Enums.ProjectStatus.DRAFT.getCode()));
            when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

            assertThatThrownBy(() -> projectService.delete(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PROJECT_HAS_ISSUE.getCode())
                    .hasMessageContaining("3");

            verify(projectMapper, never()).deleteById(any(Long.class));
        }

        @Test
        @DisplayName("项目不存在 -> 2001")
        void projectNotFound() {
            when(projectMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> projectService.delete(99L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.DATA_NOT_FOUND.getCode());
        }
    }
}
