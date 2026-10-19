package com.maning.audit.service.impl;

import com.maning.audit.common.constant.Enums;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.dto.LoginDTO;
import com.maning.audit.entity.SysUser;
import com.maning.audit.mapper.SysUserMapper;
import com.maning.audit.util.JwtUtil;
import com.maning.audit.vo.UserSimpleVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单元测试
 *
 * <h2>重点不在"能不能登录"，而在"失败时泄漏了什么"</h2>
 *
 * <p>登录接口是外网唯一无认证入口，攻击者最常拿它做的事是
 * <b>枚举用户名</b>：用一堆用户名配上烂密码去试，
 * 如果"用户不存在"和"密码错误"返回不同提示，就能筛出哪些用户名是真的。
 *
 * <p>所以这里有一条专门的测试：两种失败必须返回<b>完全一样</b>的响应。
 * 这类细节写进简历比"实现了 JWT 登录"有分量得多。
 *
 * <h2>为什么用真的 BCrypt 而不 mock PasswordEncoder</h2>
 *
 * <p>因为这里要验证的恰恰是"密码比对是否正确"。
 * mock 掉 PasswordEncoder 就等于假定它是对的，测试也就失去了意义。
 * BCrypt 每次加密 100ms 左右，这个类里只跑 2~3 次，完全可以接受。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录服务")
class AuthServiceImplTest {

    @Mock
    private SysUserMapper userMapper;

    private AuthServiceImpl authService;

    /** 用真实的 BCrypt，验证密码比对逻辑本身 */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AuditProperties props = new AuditProperties();

    @BeforeEach
    void setUp() {
        props.getJwt().setSecret("audit-evidence-system-secret-key-for-hs256-must-be-long-enough");
        props.getJwt().setExpireMinutes(120L);
        JwtUtil jwtUtil = new JwtUtil(props);
        authService = new AuthServiceImpl(userMapper, encoder, jwtUtil, props);
    }

    private SysUser user(String rawPassword, Integer status, Enums.Role role) {
        SysUser u = new SysUser();
        u.setId(7L);
        u.setUsername("leader");
        u.setRealName("陈项目");
        u.setPassword(encoder.encode(rawPassword));
        u.setStatus(status);
        u.setRole(role.getCode());
        return u;
    }

    private LoginDTO login(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @Nested
    @DisplayName("登录成功")
    class Success {

        @Test
        @DisplayName("正确凭证 -> 返回 token 与身份信息")
        void loginReturnsToken() {
            when(userMapper.selectOne(any())).thenReturn(user("123456", 1, Enums.Role.LEADER));

            var vo = authService.login(login("leader", "123456"));

            assertThat(vo.getToken()).isNotBlank().contains(".");
            assertThat(vo.getTokenType()).isEqualTo("Bearer");
            assertThat(vo.getExpiresIn()).as("120 分钟 = 7200 秒").isEqualTo(7200L);
            assertThat(vo.getUserId()).isEqualTo(7L);
            assertThat(vo.getRealName()).isEqualTo("陈项目");
            assertThat(vo.getRole()).isEqualTo("LEADER");
            assertThat(vo.getRoleName()).as("角色码要翻译成中文").isEqualTo("项目负责人");
        }

        /**
         * 登录成功后要更新最后登录时间 —— 用 updateById 只带 id 和 lastLogin，
         * MP 默认忽略 null 字段，所以不会把其他列刷成 null。
         *
         * <p>这条值得测：如果哪天有人改成 updateById(整个 user 对象)，
         * 而 user 对象里 password 是加密后的值，还没问题；
         * 但如果别的地方传了个字段不全的对象，就有覆盖风险。
         */
        @Test
        @DisplayName("登录成功更新最后登录时间，且只更新这一个字段")
        void loginUpdatesLastLogin() {
            when(userMapper.selectOne(any())).thenReturn(user("123456", 1, Enums.Role.LEADER));

            authService.login(login("leader", "123456"));

            org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
            verify(userMapper).updateById(captor.capture());
            SysUser updated = captor.getValue();
            assertThat(updated.getId()).isEqualTo(7L);
            assertThat(updated.getLastLogin()).isNotNull();
            assertThat(updated.getPassword()).as("不能把明文或密文密码带进 update 语句").isNull();
            assertThat(updated.getUsername()).isNull();
        }

        /**
         * 六个角色全部能正常登录并拿到正确的中文名。
         *
         * <p>用参数化遍历，防止以后加角色时漏配 roleName。
         */
        @ParameterizedTest(name = "{0} 登录成功")
        @EnumSource(Enums.Role.class)
        @DisplayName("所有角色都能登录并翻译出中文名")
        void allRolesCanLogin(Enums.Role role) {
            when(userMapper.selectOne(any())).thenReturn(user("pwd", 1, role));

            var vo = authService.login(login("u", "pwd"));

            assertThat(vo.getRoleName()).isEqualTo(role.getDesc());
        }
    }

    @Nested
    @DisplayName("登录失败")
    class Failure {

        /**
         * 用户不存在与密码错误必须返回同一个错误码和同一句提示。
         *
         * <p>这条测试是"安全细节"的可执行文档：
         * 两边的 code 都必须是 1001，message 必须完全相等。
         * 一旦有人把用户不存在改成更"友好"的提示，这里立刻红。
         */
        @Test
        @DisplayName("用户不存在与密码错误返回完全相同的错误（防用户名枚举）")
        void noUserEnumeration() {
            // 情况一：用户不存在
            when(userMapper.selectOne(any())).thenReturn(null);
            var notExist = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authService.login(login("ghost", "123456")));

            // 情况二：用户存在但密码错
            when(userMapper.selectOne(any())).thenReturn(user("123456", 1, Enums.Role.AUDITOR));
            var wrongPwd = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authService.login(login("lisi", "654321")));

            assertThat(notExist).isInstanceOf(BusinessException.class);
            assertThat(wrongPwd).isInstanceOf(BusinessException.class);

            BusinessException a = (BusinessException) notExist;
            BusinessException b = (BusinessException) wrongPwd;

            assertThat(a.getCode())
                    .as("两种失败必须同码同文案，否则可被用来枚举用户名")
                    .isEqualTo(b.getCode())
                    .isEqualTo(ResultCode.LOGIN_FAILED.getCode());
            assertThat(a.getMessage()).isEqualTo(b.getMessage());
        }

        /**
         * 账号被禁用（status != 1）时单独提示。
         *
         * <p>这里的取舍：密码对的前提下才提示"账号被禁用"，
         * 所以不会泄漏用户名 —— 攻击者得先猜对密码。
         * 对被封用户来说，知道"我被封了"比"密码错了"更有用。
         */
        @Test
        @DisplayName("密码正确但账号被禁用 -> 1002")
        void disabledAccount() {
            when(userMapper.selectOne(any())).thenReturn(user("123456", 0, Enums.Role.AUDITOR));

            assertThatThrownBy(() -> authService.login(login("lisi", "123456")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ACCOUNT_DISABLED.getCode());
        }

        /**
         * status 为 null 也要按禁用处理，不能 NPE。
         *
         * <p>历史数据里可能有 status 为 null 的行，
         * 如果写成 {@code user.getStatus() == 1} 就会拆箱 NPE。
         */
        @Test
        @DisplayName("status 为 null 按禁用处理，不抛 NPE")
        void nullStatusTreatedAsDisabled() {
            when(userMapper.selectOne(any())).thenReturn(user("123456", null, Enums.Role.AUDITOR));

            assertThatThrownBy(() -> authService.login(login("lisi", "123456")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ACCOUNT_DISABLED.getCode());
        }
    }

    @Nested
    @DisplayName("用户列表与登出")
    class Misc {

        @Test
        @DisplayName("用户列表补齐角色中文名")
        void listUsersFillsRoleName() {
            UserSimpleVO vo = new UserSimpleVO();
            vo.setId(7L);
            vo.setRealName("陈项目");
            vo.setRole("LEADER");
            when(userMapper.selectSimpleList()).thenReturn(new java.util.ArrayList<>(List.of(vo)));

            var list = authService.listUsers();

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getRoleName()).isEqualTo("项目负责人");
        }

        /**
         * 未知角色码不能抛异常 —— 返回原码即可。
         *
         * <p>场景：数据库里有个已经不在枚举里的角色（比如重构时删掉了 VIEWER），
         * 列表页不能因此整页崩掉。
         */
        @Test
        @DisplayName("未知角色码降级显示原码，不抛异常")
        void unknownRoleFallsBackToCode() {
            UserSimpleVO vo = new UserSimpleVO();
            vo.setRole("LEGACY_ROLE");
            when(userMapper.selectSimpleList()).thenReturn(new java.util.ArrayList<>(List.of(vo)));

            var list = authService.listUsers();

            assertThat(list.get(0).getRoleName()).isEqualTo("LEGACY_ROLE");
        }

        @Test
        @DisplayName("登出是无状态操作，不抛异常")
        void logoutIsNoop() {
            org.assertj.core.api.Assertions.assertThatCode(() -> authService.logout())
                    .doesNotThrowAnyException();
        }
    }
}
