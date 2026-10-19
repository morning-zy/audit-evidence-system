package com.maning.audit.util;

import com.maning.audit.config.AuditProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 工具类单元测试
 *
 * <h2>为什么鉴权的测试特别重要</h2>
 *
 * <p>JWT 是整套系统的安全边界。它出问题不是"功能不好用"，而是
 * <b>任何人都能伪造身份</b>——所有越权校验（角色、级别、编制复核分离）
 * 都建立在"token 里的 userId 是可信的"这个前提上。
 *
 * <p>所以要验证三件事：
 * <ol>
 *   <li>正常签发的 token 能解析出原样的 claims</li>
 *   <li>过期 token 解析失败（而不是被当成有效）</li>
 *   <li><b>动了签名的 token 必须解析失败</b> —— 这条是防篡改的核心，
 *       光加密不验签等于没做</li>
 * </ol>
 *
 * <p>这些用例不需要 Spring，直接 new 一个 JwtUtil，塞个配置进去就行。
 */
@DisplayName("JWT 工具")
class JwtUtilTest {

    private static final String SECRET = "audit-evidence-system-secret-key-for-hs256-must-be-long-enough";

    private JwtUtil jwtUtil;
    private AuditProperties props;

    @BeforeEach
    void setUp() {
        props = new AuditProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setExpireMinutes(120L);
        jwtUtil = new JwtUtil(props);
    }

    @Nested
    @DisplayName("签发与解析")
    class IssueAndParse {

        @Test
        @DisplayName("签发的 token 能解析出全部 claims")
        void roundTrip() {
            String token = jwtUtil.generate(7L, "leader", "陈项目", "LEADER");

            assertThat(token).isNotBlank();
            assertThat(token.split("\\.")).as("JWT 必须是三段式的 header.payload.signature").hasSize(3);

            Claims claims = jwtUtil.parse(token);

            assertThat(claims).isNotNull();
            assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(7L);
            assertThat(claims.get("username", String.class)).isEqualTo("leader");
            assertThat(claims.get("realName", String.class)).isEqualTo("陈项目");
            assertThat(claims.get("role", String.class)).isEqualTo("LEADER");
            assertThat(claims.getSubject()).isEqualTo("7");
        }

        /**
         * 拦截器里读 userId 用的是 {@code claims.get("userId", Number.class).longValue()}。
         *
         * <p>JSON 反序列化后，数字可能变成 Integer 也可能变成 Long，
         * 直接强转 Long 会在某些情况下 ClassCastException。
         * 这条测试验证"用 Number 取再转 long"是安全的 ——
         * 也是给拦截器那段代码留的一个可执行说明。
         */
        @Test
        @DisplayName("userId 以 Number 读取再转 long 是安全的")
        void userIdReadableAsNumber() {
            String token = jwtUtil.generate(Long.MAX_VALUE - 1, "u", "超大 ID", "ADMIN");
            Claims claims = jwtUtil.parse(token);

            assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(Long.MAX_VALUE - 1);
        }

        @Test
        @DisplayName("过期时间与配置一致")
        void expirationFollowsConfig() {
            props.getJwt().setExpireMinutes(5L);
            String token = jwtUtil.generate(1L, "u", "n", "AUDITOR");

            Claims claims = jwtUtil.parse(token);
            long ttlMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();

            assertThat(ttlMs).isEqualTo(5 * 60 * 1000L);
        }
    }

    @Nested
    @DisplayName("异常与防篡改")
    class Tampering {

        @Test
        @DisplayName("乱写的 token 解析返回 null")
        void garbageTokenReturnsNull() {
            assertThat(jwtUtil.parse("not-a-jwt")).isNull();
            assertThat(jwtUtil.parse("")).isNull();
        }

        /**
         * 篡改 payload：把角色从 AUDITOR 改成 ADMIN。
         *
         * <p>这是最现实的攻击方式 —— 抓包看到 token，base64 解出 payload，
         * 把 role 改成 ADMIN 再拼回去。如果没有验签，这一改就成了管理员。
         *
         * <p>测试做法：拿一个合法 token，换掉 payload 段的一个字符。
         * 签名对不上，parse 必须返回 null。
         */
        @Test
        @DisplayName("篡改 payload 后解析失败（签名校验生效）")
        void tamperedPayloadRejected() {
            String token = jwtUtil.generate(3L, "lisi", "李四", "AUDITOR");
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);

            // 把 payload 第一个字符换掉 —— 内容变了，签名就对不上了
            char first = parts[1].charAt(0);
            char replaced = first == 'e' ? 'f' : 'e';
            String tamperedPayload = replaced + parts[1].substring(1);
            String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

            assertThat(jwtUtil.parse(tampered)).as("被改过的 payload 必须解析失败").isNull();
        }

        /**
         * 换密钥后，旧 token 必须失效。
         *
         * <p>这条对应真实运维场景：密钥泄露后轮换 secret，
         * 所有已签发的 token 立刻作废。如果不失效，说明验签根本没生效。
         */
        @Test
        @DisplayName("密钥更换后旧 token 立即失效")
        void tokenInvalidAfterSecretRotation() {
            String token = jwtUtil.generate(1L, "u", "n", "ADMIN");

            AuditProperties other = new AuditProperties();
            other.getJwt().setSecret("a-totally-different-secret-key-also-long-enough-for-hs256!!");
            JwtUtil otherUtil = new JwtUtil(other);

            assertThat(otherUtil.parse(token)).isNull();
        }

        /**
         * isExpired 的三种返回值：
         * 有效 -> false；过期 -> true；乱写 -> false（无效不是过期，前端要区分）。
         *
         * <p>拦截器靠这个区分"过期了就提示重新登录"和"凭证无效可能是伪造"，
         * 返回值的语义必须精确。
         */
        @Test
        @DisplayName("isExpired：有效 false / 乱写 false / 过期 true")
        void isExpiredSemantics() {
            String valid = jwtUtil.generate(1L, "u", "n", "AUDITOR");
            assertThat(jwtUtil.isExpired(valid)).isFalse();

            assertThat(jwtUtil.isExpired("garbage")).as("乱写的 token 不算过期").isFalse();

            // 造一个已经过期的 token：把过期时间设成负数分钟
            props.getJwt().setExpireMinutes(-1L);
            String expired = jwtUtil.generate(1L, "u", "n", "AUDITOR");
            assertThat(jwtUtil.isExpired(expired)).isTrue();
            assertThat(jwtUtil.parse(expired)).as("过期 token 解析也应为 null").isNull();
        }
    }
}
