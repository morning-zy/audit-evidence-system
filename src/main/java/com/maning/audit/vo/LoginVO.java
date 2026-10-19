package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 登录响应 VO
 *
 * <p>为什么 VO 要和 DTO/Entity 分开：
 * Entity 是数据库的形状，DTO 是请求的形状，VO 是前端要的形状。
 * 三者经常不一样——比如这里绝不能把 password 带出去，
 * 也不该让前端拿到 deleted 这种纯内部字段。
 * 用独立 VO 是最省心的隔离方式。
 */
@Data
@Builder
@Schema(description = "登录响应")
public class LoginVO {

    @Schema(description = "JWT 令牌")
    private String token;

    @Schema(description = "令牌类型")
    private String tokenType;

    @Schema(description = "有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "角色")
    private String role;

    @Schema(description = "角色中文名")
    private String roleName;
}
