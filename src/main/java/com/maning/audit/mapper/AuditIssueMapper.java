package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.maning.audit.dto.IssueQuery;
import com.maning.audit.entity.AuditIssue;
import com.maning.audit.vo.IssueVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计事项 Mapper
 */
public interface AuditIssueMapper extends BaseMapper<AuditIssue> {

    /** 分页查询事项（带项目名、审计员姓名） */
    IPage<IssueVO> selectIssuePage(IPage<IssueVO> page, @Param("q") IssueQuery query);

    /** 查询某项目下的全部事项（不分页，用于项目详情页的下拉） */
    List<IssueVO> selectByProjectId(@Param("projectId") Long projectId);

    /**
     * 原子递增证据计数
     *
     * <p>为什么用 UPDATE ... SET count = count + 1 而不是
     * "先 select 出 count，+1，再 update"：
     * 后者在并发下会丢更新（A 和 B 都读到 5，各自写 6，实际应该是 7）。
     * 让数据库自己做加法是原子操作，天然无并发问题。
     */
    int incrEvidenceCount(@Param("id") Long id, @Param("delta") Integer delta);

    /**
     * 查询某项目下已用的最大事项编号（含逻辑删除的记录）
     *
     * <p><b>为什么不能用 selectCount 来生成流水号</b>：
     * MyBatis-Plus 的 selectCount 会自动追加 deleted = 0 条件。
     * 假设项目下有 IS001-01、IS001-02、IS001-03，删掉 IS001-02 之后，
     * count 变成 2，下一个算出 IS001-03 —— 和已存在的编号撞车，
     * 直接抛 DuplicateKeyException（uk_issue_no 唯一索引）。
     *
     * <p>逻辑删除的语义是"业务上不可见"，但**编号不能复用**：
     * 审计底稿里 IS001-02 这个号已经被引用过（可能出现在纸质底稿、往来邮件里），
     * 让新事项顶替它的编号会造成追溯混乱。
     * 所以这里绕开逻辑删除，查物理最大编号，流水号只增不退。
     */
    String selectMaxIssueNo(@Param("projectId") Long projectId);
}
