package com.maning.audit.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.LoginDTO;
import com.maning.audit.entity.SysUser;
import com.maning.audit.mapper.SysUserMapper;
import com.maning.audit.service.AuthService;
import com.maning.audit.util.JwtUtil;
import com.maning.audit.vo.LoginVO;
import com.maning.audit.vo.UserSimpleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditProperties props;

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, dto.getUsername()));

        // 安全细节：用户不存在和密码错误返回同一句提示。
        // 如果分开提示"用户不存在"，攻击者就能拿这个接口枚举有效用户名。
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRealName(), user.getRole());

        // 更新最后登录时间，用 updateById 只更新这一个字段（MP 默认忽略 null 字段）
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLastLogin(LocalDateTime.now());
        userMapper.updateById(update);

        log.info("用户登录成功: {} ({})", user.getUsername(), user.getRealName());

        return LoginVO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(props.getJwt().getExpireMinutes() * 60)
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .roleName(roleName(user.getRole()))
                .build();
    }

    @Override
    public LoginVO currentUser() {
        Long userId = UserContext.getUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return LoginVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .roleName(roleName(user.getRole()))
                .build();
    }

    @Override
    public List<UserSimpleVO> listUsers() {
        List<UserSimpleVO> list = userMapper.selectSimpleList();
        list.forEach(u -> u.setRoleName(roleName(u.getRole())));
        return list;
    }

    @Override
    public void logout() {
        // JWT 是无状态的，服务端没有会话可销毁。
        // 真要做"登出即失效"，需要引入 Redis 黑名单：登录时存 token，
        // 登出时把 token 写入黑名单并设置过期时间等于 token 剩余有效期。
        // 本项目为简化未实现，这里只做日志记录。
        log.info("用户登出: {}", UserContext.getRealName());
    }

    private String roleName(String code) {
        for (Enums.Role r : Enums.Role.values()) {
            if (r.getCode().equals(code)) {
                return r.getDesc();
            }
        }
        return code;
    }
}
