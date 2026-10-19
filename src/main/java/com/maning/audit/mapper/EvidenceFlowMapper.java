package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.EvidenceFlow;
import com.maning.audit.vo.FlowVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 证据流转记录 Mapper
 */
public interface EvidenceFlowMapper extends BaseMapper<EvidenceFlow> {

    /** 某证据的完整流转链（按时间正序，用于时间轴展示） */
    List<FlowVO> selectByEvidenceId(@Param("evidenceId") Long evidenceId);
}
