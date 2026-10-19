package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO
 *
 * <p>为什么要单独的 DTO 而不是直接用 SysUser 接参：
 * ① 安全 —— SysUser 里有 role、status、id，用实体接参意味着前端能直接传
 *    "role=ADMIN" 提权，这是最典型的一类越权漏洞
 * ② 校验 —— 可以精确标 @NotBlank，实体上加会污染持久层
 * ③ 演进 —— 登录参数以后加验证码、加图形 token，不影响实体
 */
@Data
@Schema(description = "登录请求")
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "登录账号", example = "admin")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "123456")
    private String password;
}
