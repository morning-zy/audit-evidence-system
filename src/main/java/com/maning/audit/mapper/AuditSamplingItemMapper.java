package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.AuditSamplingItem;

/**
 * 抽样样本明细 Mapper。
 *
 * <p>没有自定义方法：明细表的查询都是"按 samplingId + 排序"，
 * 用 BaseMapper 的条件构造器一行就能表达，不需要写 SQL。
 */
public interface AuditSamplingItemMapper extends BaseMapper<AuditSamplingItem> {
}
