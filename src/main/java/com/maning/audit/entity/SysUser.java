package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    /** BCrypt 加密后的密码，查询时用 @TableField(select=false) 避免泄漏到 VO */
    private String password;

    private String realName;

    private String employeeNo;

    /** ADMIN / AUDITOR / VIEWER */
    private String role;

    private Long deptId;

    private String phone;

    /** 1启用 0禁用 */
    private Integer status;

    private java.time.LocalDateTime lastLogin;
}
