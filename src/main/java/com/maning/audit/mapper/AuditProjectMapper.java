package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.maning.audit.dto.ProjectQuery;
import com.maning.audit.entity.AuditProject;
import com.maning.audit.vo.ProjectVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计项目 Mapper
 *
 * <p>继承 BaseMapper 就白得 17 个单表方法（insert/updateById/selectById/
 * selectPage/deleteById...），无需写一行 SQL。
 * 只有"需要 join 或复杂条件"的方法才自己声明。
 */
public interface AuditProjectMapper extends BaseMapper<AuditProject> {

    /**
     * 分页查询项目列表（带负责人姓名与统计聚合）
     *
     * <p>为什么在 SQL 里一次性算好统计数，而不是查完列表再循环查：
     * 假设一页 10 条项目，循环查每个项目的事项数+证据数 = 21 次查询（含分页两次），
     * 这就是 N+1 问题。改成 join + 子查询聚合后只有 2 次查询
     * （1 次 count，1 次取数据），响应时间从几十毫秒降到几毫秒。
     *
     * <p>注意：这里用 LEFT JOIN 派生表而不是相关子查询，
     * 是为了让 MySQL 只扫一次 audit_issue / audit_evidence，
     * 而不是对每一行项目都重新执行一次子查询。
     */
    IPage<ProjectVO> selectProjectPage(IPage<ProjectVO> page, @Param("q") ProjectQuery query);

    /**
     * 单个项目详情（含统计）
     */
    ProjectVO selectProjectDetail(@Param("id") Long id);

    /**
     * 待复核项目的进度列表（给看板用，不分页，限制条数）
     */
    List<ProjectVO> selectProjectProgress(@Param("limit") Integer limit);

    /**
     * 按状态统计项目数
     */
    List<java.util.Map<String, Object>> countByStatus();
}
