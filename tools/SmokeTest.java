import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * 接口冒烟测试：不依赖 Postman，直接用 JDK 内置 HttpClient 跑通完整业务链路
 */
public class SmokeTest {

    static final String BASE = "http://127.0.0.1:8090/api";
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    static String token = null;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("========== 审计证据管理系统 接口冒烟测试 ==========\n");

        // 1. 登录
        String loginResp = post("/auth/login", "{\"username\":\"admin\",\"password\":\"123456\"}", false);
        System.out.println("[1] 登录 admin/123456");
        System.out.println("    " + brief(loginResp));
        token = extract(loginResp, "token");
        check("登录返回 token", token != null && !token.isBlank());

        // 2. 未带 token 访问受保护接口
        String noAuth = sendNoAuth("GET", "/project/page");
        System.out.println("[2] 未带 token 访问 /project/page");
        System.out.println("    " + brief(noAuth));
        check("未登录被拦截(401)", noAuth.contains("401"));

        // 3. 错误密码
        String badLogin = post("/auth/login", "{\"username\":\"admin\",\"password\":\"wrong\"}", false);
        System.out.println("[3] 错误密码登录");
        System.out.println("    " + brief(badLogin));
        check("错误密码返回1001", badLogin.contains("1001"));

        // 4. 当前用户
        String me = get("/auth/me");
        System.out.println("[4] 获取当前用户");
        System.out.println("    " + brief(me));
        check("当前用户是马宁", me.contains("马宁"));

        // 5. 项目分页
        String projects = get("/project/page?pageNum=1&pageSize=5");
        System.out.println("[5] 项目分页查询");
        System.out.println("    " + brief(projects));
        check("项目列表有数据", projects.contains("projectName"));
        check("聚合统计字段齐全", projects.contains("issueTotal") && projects.contains("progress"));

        // 6. 项目详情
        String p1 = get("/project/1");
        System.out.println("[6] 项目1详情");
        System.out.println("    " + brief(p1));
        check("项目详情状态中文名", p1.contains("进行中"));

        // 7. 事项列表
        String issues = get("/issue/list?projectId=1");
        System.out.println("[7] 项目1下的事项");
        System.out.println("    " + brief(issues));
        check("事项列表有数据", issues.contains("issueTitle"));

        // 8. 证据分页
        String evidences = get("/evidence/page?pageNum=1&pageSize=5");
        System.out.println("[8] 证据分页");
        System.out.println("    " + brief(evidences));
        check("证据列表有数据", evidences.contains("evidenceNo"));
        check("按钮权限标记存在", evidences.contains("canEdit") && evidences.contains("canReview"));

        // 9. 待办队列（主管视角）
        String todo = get("/evidence/page?todoOnly=true");
        System.out.println("[9] 待我复核队列");
        System.out.println("    " + brief(todo));
        check("待办只含待复核", todo.contains("\"status\":1") || todo.contains("待复核"));

        // 10. 证据流转时间轴
        String flows = get("/evidence/1/flows");
        System.out.println("[10] 证据1的流转时间轴");
        System.out.println("    " + brief(flows));
        // 证据1是三级复核改造前就存在的老数据，动作仍是旧的 APPROVE。
        // 断言要求历史记录能被正确翻译成中文（旧值显示「复核通过（旧版单级）」），
        // 而不是漏成裸的 "APPROVE" —— 后者说明兼容逻辑失效了。
        check("流转记录完整(创建/提交/通过)",
                flows.contains("创建") && flows.contains("提交复核")
                        && (flows.contains("复核通过") || flows.contains("复核通过（旧版单级）")));
        check("历史旧动作未被漏成裸英文", !flows.contains("\"actionName\":\"APPROVE\""));

        // 11. 统计看板
        String dashboard = get("/stat/dashboard");
        System.out.println("[11] 工作台统计");
        System.out.println("    " + brief(dashboard));
        check("看板有统计数据", dashboard.contains("evidenceTotal"));

        // 12. Redis 缓存统计（连跑两次，看第二次是否命中缓存）
        String stat1 = get("/stat/project/1");
        String stat2 = get("/stat/project/1");
        // 去掉 timestamp 字段再比对（每次请求的时间戳必然不同，不是业务差异）
        stat1 = stat1.replaceAll(",\"timestamp\":\\d+", "");
        stat2 = stat2.replaceAll(",\"timestamp\":\\d+", "");
        System.out.println("[12] 项目统计缓存");
        System.out.println("    第一次: " + brief(stat1));
        System.out.println("    第二次: " + brief(stat2));
        check("两次结果一致", stat1.equals(stat2));

        // 13. 参数校验：项目名为空
        String invalid = post("/project", "{\"projectName\":\"\",\"auditType\":\"FINANCIAL\",\"auditedUnit\":\"x\",\"leaderId\":1,\"riskLevel\":1}", true);
        System.out.println("[13] 新增项目-项目名为空");
        System.out.println("    " + brief(invalid));
        check("参数校验拦截(400)", invalid.contains("400"));

        // 14. 非法状态流转：项目1(进行中) -> 已归档(3) 应被拒
        String badFlow = put("/project/1/status?status=3", "");
        System.out.println("[14] 非法状态流转 进行中->已归档");
        System.out.println("    " + brief(badFlow));
        check("非法流转被拦截(2003)", badFlow.contains("2003"));

        // 15. 完整业务闭环：新建事项 -> 新建证据 -> 提交 -> 审核
        System.out.println("\n========== 完整业务闭环测试 ==========");
        String newIssue = post("/issue", "{\"projectId\":1,\"issueTitle\":\"冒烟测试事项\",\"riskPoint\":\"测试风险点\",\"auditorId\":1}", true);
        System.out.println("[15] 新建事项: " + brief(newIssue));
        String issueId = extract(newIssue, "data");
        check("事项创建成功", issueId != null);

        // 可靠性不再由用户填 reliability，而是填五个客观维度由系统计算（准则 1301 号）
        // 这里故意用「外部+直接+书面+原件」组合，算出 A 级，才能顺利通过后续提交复核
        String newEv = post("/evidence", "{\"issueId\":" + issueId
                + ",\"evidenceName\":\"冒烟测试证据\",\"evidenceType\":\"DOCUMENT\""
                + ",\"source\":\"银行回函\",\"sourceType\":\"EXTERNAL\""
                + ",\"acquireMethod\":\"DIRECT\",\"formType\":\"WRITTEN\",\"originality\":\"ORIGINAL\""
                + ",\"filePath\":\"/test/x.pdf\"}", true);
        System.out.println("[16] 新建证据: " + brief(newEv));
        String evId = extract(newEv, "data");
        check("证据创建成功", evId != null);

        // 验证系统按准则 1301 号算出的等级：「外部+直接+书面+原件」应为 A 级
        String evDetail = get("/evidence/" + evId);
        check("可靠性自动算出 A 级", evDetail.contains("\"reliabilityGrade\":\"A\""));
        check("判定依据已生成", evDetail.contains("准则 1301 号"));

        String submit = put("/evidence/" + evId + "/submit", "");
        System.out.println("[17] 提交复核: " + brief(submit));
        check("提交复核准成功", submit.contains("200"));

        // 17b. 自己复核自己 -> 应被拒（编制复核分离）
        String selfReview = put("/evidence/" + evId + "/approve", "{}");
        System.out.println("[18] 尝试复核自己提交的证据");
        System.out.println("     " + brief(selfReview));
        check("自己复核自己被拒(3005)", selfReview.contains("3005"));

        // 17c. 换人复核 -> 一级通过后应流转到「待一般复核」，而不是直接已确认
        // 三级复核：详细复核(项目负责人) -> 一般复核(部门经理) -> 重点复核(主任会计师)
        String lv1Token = extract(post("/auth/login", "{\"username\":\"leader\",\"password\":\"123456\"}", false), "token");
        String oldToken = token;
        token = lv1Token;
        String approve1 = put("/evidence/" + evId + "/approve", "{\"comment\":\"详细复核通过\"}");
        System.out.println("[19] 项目负责人一级复核: " + brief(approve1));
        check("一级复核通过", approve1.contains("200"));
        String afterLv1 = get("/evidence/" + evId);
        check("一级通过后 -> 待一般复核", afterLv1.contains("\"currentLevel\":2"));
        check("一级通过后证据未生效", afterLv1.contains("\"reviewFinished\":false"));

        // 17d. 错角色复核 -> 应被拒
        // 此时证据在二级（待一般复核），项目负责人再来批就该被拦住
        String mgrToken = extract(post("/auth/login", "{\"username\":\"manager\",\"password\":\"123456\"}", false), "token");
        token = lv1Token;
        String wrongRole = put("/evidence/" + evId + "/approve", "{}");
        System.out.println("[20a] 项目负责人尝试复核二级（越权）");
        System.out.println("     " + brief(wrongRole));
        check("跨级别复核被拒(3008)", wrongRole.contains("3008"));

        // 17e. 二级通过
        token = mgrToken;
        String approve2 = put("/evidence/" + evId + "/approve", "{\"comment\":\"一般复核通过\"}");
        System.out.println("[20b] 部门经理二级复核: " + brief(approve2));
        check("二级复核通过", approve2.contains("200"));

        // 17f. 三级通过 -> 至此才落定为已确认
        String partnerToken = extract(post("/auth/login", "{\"username\":\"partner\",\"password\":\"123456\"}", false), "token");
        token = partnerToken;
        String approve3 = put("/evidence/" + evId + "/approve", "{\"comment\":\"重点复核通过\"}");
        System.out.println("[20c] 主任会计师三级复核: " + brief(approve3));
        check("三级复核通过", approve3.contains("200"));
        String afterLv3 = get("/evidence/" + evId);
        check("三级通过后 -> 已确认", afterLv3.contains("\"reviewFinished\":true"));

        // 17g. 已确认后再复核 -> 应被拒
        String reApprove = put("/evidence/" + evId + "/approve", "{}");
        System.out.println("[20d] 已确认后重复复核");
        System.out.println("     " + brief(reApprove));
        check("已确认后不可再复核(3003)", reApprove.contains("3003"));
        token = oldToken;

        // 18. 清理测试数据（逻辑删除）
        String del = delete("/evidence/" + evId);
        System.out.println("[21] 删除已确认证据(应被拒)");
        System.out.println("     " + brief(del));
        check("已确认证据不可删(3003)", del.contains("3003"));

        // 19. 操作日志
        String logs = get("/log/page?pageNum=1&pageSize=3");
        System.out.println("[22] 操作日志");
        System.out.println("     " + brief(logs));
        check("AOP 日志已落库", logs.contains("module"));

        // 汇总
        System.out.println("\n=================================================");
        System.out.println("  测试完成: 通过 " + pass + " 项, 失败 " + fail + " 项");
        System.out.println("=================================================");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------- 工具方法 ----------------

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("    [PASS] " + name);
        } else {
            fail++;
            System.out.println("    [FAIL] " + name);
        }
    }

    static String brief(String s) {
        if (s == null) return "null";
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    /** 从 JSON 中粗略提取字段值（冒烟测试够用，不引入 JSON 库） */
    static String extract(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i >= 0) {
            int start = i + key.length();
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : null;
        }
        // 数字类型
        String key2 = "\"" + field + "\":";
        int j = json.indexOf(key2);
        if (j >= 0) {
            int start = j + key2.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return end > start ? json.substring(start, end) : null;
        }
        return null;
    }

    static String get(String path) throws Exception {
        return send("GET", path, null);
    }

    static String post(String path, String body, boolean auth) throws Exception {
        return sendWithAuth("POST", path, body);
    }

    static String put(String path, String body) throws Exception {
        return sendWithAuth("PUT", path, body);
    }

    static String delete(String path) throws Exception {
        return sendWithAuth("DELETE", path, null);
    }

    static String sendWithAuth(String method, String path, String body) throws Exception {
        return send(method, path, body);
    }

    /** 不带 token 的请求（用于验证鉴权） */
    static String sendNoAuth(String method, String path) throws Exception {
        String saved = token;
        token = null;
        try {
            return send(method, path, null);
        } finally {
            token = saved;
        }
    }

    static String send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body == null || method.equals("GET") || method.equals("DELETE")) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }
}
