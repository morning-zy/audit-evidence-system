package com.maning.audit.service;

import com.maning.audit.vo.DashboardVO;

/**
 * 统计服务
 */
public interface StatService {

    DashboardVO dashboard();

    /**
     * 项目维度统计（带 Redis 缓存）
     */
    DashboardVO projectStat(Long projectId);

    /** 主动刷缓存，避免改了数据还要等 TTL 过期 */
    void evictProjectStat(Long projectId);
}
