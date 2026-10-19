package com.maning.audit.controller;

import com.maning.audit.annotation.Log;
import com.maning.audit.common.result.Result;
import com.maning.audit.dto.LoginDTO;
import com.maning.audit.service.AuthService;
import com.maning.audit.vo.LoginVO;
import com.maning.audit.vo.UserSimpleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 认证接口
 *
 * <p>Controller 的职责边界（这是分层设计里最容易被写歪的一层）：
 * 只做三件事 —— 接参数、调 Service、包响应。
 * 不写业务判断、不写 SQL、不写事务。一旦 Controller 里出现了 if 判断业务规则，
 * 就说明逻辑放错层了。
 */
@Tag(name = "01-认证", description = "登录、当前用户、用户列表")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Log(module = "认证", value = "用户登录", saveParams = false)
    @Operation(summary = "登录", description = "用户名密码登录，返回 JWT")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", authService.login(dto));
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public Result<LoginVO> me() {
        return Result.success(authService.currentUser());
    }

    @Operation(summary = "查询用户下拉列表", description = "用于选择项目负责人、承办审计员")
    @GetMapping("/users")
    public Result<List<UserSimpleVO>> users() {
        return Result.success(authService.listUsers());
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
