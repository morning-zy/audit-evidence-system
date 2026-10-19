package com.maning.audit.service;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.dto.ProjectQuery;
import com.maning.audit.dto.ProjectSaveDTO;
import com.maning.audit.vo.ProjectVO;

import java.util.List;

/**
 * 审计项目服务
 */
public interface ProjectService {

    PageResult<ProjectVO> page(ProjectQuery query);

    ProjectVO detail(Long id);

    Long save(ProjectSaveDTO dto);

    void update(ProjectSaveDTO dto);

    void delete(Long id);

    /**
     * 项目状态流转
     *
     * @param id     项目ID
     * @param target 目标状态
     */
    void changeStatus(Long id, Integer target);

    List<ProjectVO> progressList(Integer limit);
}
