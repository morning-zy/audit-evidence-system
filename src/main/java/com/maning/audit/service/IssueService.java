package com.maning.audit.service;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.dto.IssueQuery;
import com.maning.audit.dto.IssueSaveDTO;
import com.maning.audit.vo.IssueVO;

import java.util.List;

/**
 * 审计事项服务
 */
public interface IssueService {

    PageResult<IssueVO> page(IssueQuery query);

    IssueVO detail(Long id);

    List<IssueVO> listByProject(Long projectId);

    Long save(IssueSaveDTO dto);

    void update(IssueSaveDTO dto);

    void delete(Long id);

    /** 手工推进事项状态 */
    void changeStatus(Long id, Integer target);
}
