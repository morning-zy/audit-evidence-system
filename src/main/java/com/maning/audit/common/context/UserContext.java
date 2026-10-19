package com.maning.audit.common.context;

import com.maning.audit.common.constant.Enums;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文
 *
 * <p>用 ThreadLocal 存放：一次 HTTP 请求在同一线程内被 Tomcat 处理完，
 * 拦截器解析 JWT 后塞进来，Service/Controller 随处可取，不用层层传参。
 *
 * <p>关键点（面试常问的坑）：
 * ① 必须在请求结束时 remove()，否则线程池复用会导致"串号"——
 *    A 用户的身份信息被下一个请求的 B 用户读到。这是 ThreadLocal 最经典的坑。
 * ② Tomcat 用线程池处理请求，线程不是用完销毁的，所以不 remove 就一定会残留。
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    public static String getRealName() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getRealName();
    }

    public static String getRole() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getRole();
    }

    public static boolean isAdmin() {
        return Enums.Role.ADMIN.getCode().equals(getRole());
    }

    /**
     * 当前用户角色枚举。角色串不认识时返回 null（调用方按"无权限"处理）。
     *
     * <p>为什么要转成枚举而不是继续用字符串比：
     * 三级复核要判断"这个角色对应第几级"，逻辑在 {@link Enums.Role#reviewLevelOf()} 里。
     * 继续拿字符串 {@code "LEADER".equals(role)} 比，等于把规则又抄了一遍，
     * 以后加角色必然漏改。
     */
    public static Enums.Role getRoleEnum() {
        String code = getRole();
        if (code == null) {
            return null;
        }
        for (Enums.Role r : Enums.Role.values()) {
            if (r.getCode().equals(code)) {
                return r;
            }
        }
        return null;
    }

    /** 角色中文名，用于生成"您的角色是【审计员】"这类提示 */
    public static String getRoleDesc() {
        Enums.Role r = getRoleEnum();
        return r == null ? getRole() : r.getDesc();
    }

    /** 必须在 filter/interceptor 的 finally 里调用 */
    public static void clear() {
        HOLDER.remove();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginUser {
        private Long userId;
        private String username;
        private String realName;
        private String role;
    }
}
