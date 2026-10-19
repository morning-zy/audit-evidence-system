package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.AuditVariableSamplingItem;

/**
 * 变量抽样样本明细 Mapper。
 *
 * <p>只需要"按 samplingId 查询并排序"这一种读法，用条件构造器即可，
 * 不必单独写 SQL。
 */
public interface AuditVariableSamplingItemMapper extends BaseMapper<AuditVariableSamplingItem> {
}
