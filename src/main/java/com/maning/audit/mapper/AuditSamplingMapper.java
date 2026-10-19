package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.AuditSampling;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审计抽样计划 Mapper。
 *
 * <p>这里用注解 SQL 而不是 XML：抽样模块只有这一个自定义查询（取最大编号），
 * 为它单独建一个 XML 文件反而增加维护成本。
 * 项目里证据模块用 XML，是因为它的分页查询要 join 三张表，
 * 注解写不下 —— 按复杂度选实现方式，不必强行统一。
 */
public interface AuditSamplingMapper extends BaseMapper<AuditSampling> {

    /**
     * 查询当前前缀下的最大计划编号。
     *
     * <p><b>这里刻意不加 {@code deleted = 0}</b>。原因不是疏忽：
     * {@code uk_sampling_no} 是普通唯一索引，逻辑删除只是把 deleted 置 1，
     * 行还在表里、编号仍被索引占用。如果取最大编号时把已删除记录排除掉，
     * 下一个计划会重新拿到这个编号，插入时直接撞唯一索引 ——
     * 表现为"删除后新建计划报系统繁忙"。
     *
     * <p>从审计留痕的角度看，编号也不该复用：作废的底稿编号必须永久保留，
     * 新计划另起一号。所以编号出现跳号是正确行为，不是缺陷。
     */
    @Select("SELECT sampling_no FROM audit_sampling "
            + "WHERE sampling_no LIKE CONCAT(#{prefix}, '%') "
            + "ORDER BY CAST(SUBSTRING(sampling_no, CHAR_LENGTH(#{prefix}) + 1) AS UNSIGNED) DESC "
            + "LIMIT 1")
    String selectMaxSamplingNo(@Param("prefix") String prefix);
}
