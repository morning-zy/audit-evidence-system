package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.AuditVariableSampling;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 变量抽样计划 Mapper。
 *
 * <p>编号规则与属性抽样完全一致（见 {@link AuditSamplingMapper} 里关于逻辑删除、
 * 唯一索引和跳号的说明）。两处刻意保持相同的实现，而不是抽成公共方法 ——
 * 编号前缀和表名都不同，为了复用三行 SQL 去搞泛型反而更难懂。
 */
public interface AuditVariableSamplingMapper extends BaseMapper<AuditVariableSampling> {

    @Select("SELECT sampling_no FROM audit_variable_sampling "
            + "WHERE sampling_no LIKE CONCAT(#{prefix}, '%') "
            + "ORDER BY CAST(SUBSTRING(sampling_no, CHAR_LENGTH(#{prefix}) + 1) AS UNSIGNED) DESC "
            + "LIMIT 1")
    String selectMaxSamplingNo(@Param("prefix") String prefix);
}
