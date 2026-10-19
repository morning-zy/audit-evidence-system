package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户简明信息 VO（用于下拉选择、负责人显示）
 */
@Data
@Schema(description = "用户简明信息")
public class UserSimpleVO {

    private Long id;
    private String username;
    private String realName;
    private String role;
    private String roleName;
}
