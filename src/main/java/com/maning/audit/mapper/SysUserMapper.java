package com.maning.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maning.audit.entity.SysUser;
import com.maning.audit.vo.UserSimpleVO;

import java.util.List;

/**
 * 用户 Mapper
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户下拉列表（只取必要字段，不带密码） */
    List<UserSimpleVO> selectSimpleList();
}
