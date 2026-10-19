package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.maning.audit.dto.EvidenceQuery;
import com.maning.audit.entity.AuditEvidence;
import com.maning.audit.vo.EvidenceVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计证据 Mapper
 */
public interface AuditEvidenceMapper extends BaseMapper<AuditEvidence> {

    /** 分页查询证据（带项目名、事项编号、提交人、复核人姓名） */
    IPage<EvidenceVO> selectEvidencePage(IPage<EvidenceVO> page,
                                         @Param("q") EvidenceQuery query,
                                         @Param("currentUserId") Long currentUserId);

    /** 证据详情 */
    EvidenceVO selectEvidenceDetail(@Param("id") Long id, @Param("currentUserId") Long currentUserId);

    /** 按状态分组统计 */
    List<java.util.Map<String, Object>> countGroupByStatus(@Param("projectId") Long projectId);

    /** 按类型分组统计 */
    List<java.util.Map<String, Object>> countGroupByType(@Param("projectId") Long projectId);

    /**
     * 查询最大的证据编号，用于生成下一个流水号
     *
     * <p>这里有个并发坑：如果两个请求同时拿到 "EV20260008"，
     * 都会生成 "EV20260009"，插入时唯一索引冲突。
     * 解决办法是唯一索引兜底 + 重试（见 EvidenceServiceImpl.generateEvidenceNo）。
     */
    String selectMaxEvidenceNo(@Param("prefix") String prefix);

    /** 用行锁查证据，用于状态流转时防止并发重复操作 */
    AuditEvidence selectByIdForUpdate(@Param("id") Long id);
}
