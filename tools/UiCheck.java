import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * 前端页面 + 接口联动验证
 * 不依赖浏览器，直接检查：
 *   ① /api/ui/index.html 与三个 JS / CSS 是否可匿名访问（200）
 *   ② 登录接口返回结构是否与前端 api.js 的假设一致
 *   ③ 工作台 / 项目 / 事项 / 证据 四类列表接口字段是否齐全
 */
public class UiCheck {
    static final String BASE = "http://127.0.0.1:8090";
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("===== 静态资源（必须匿名可访问） =====");
        String[] assets = {"/api/ui/index.html", "/api/ui/css/app.css", "/api/ui/js/api.js",
                "/api/ui/js/ui.js", "/api/ui/js/app.js"};
        for (String a : assets) {
            HttpResponse<String> r = get(a, null);
            boolean ok = r.statusCode() == 200 && r.body().length() > 200;
            report(ok, a + " -> HTTP " + r.statusCode() + " len=" + r.body().length());
        }

        System.out.println("\n===== 未登录访问接口应 401 =====");
        HttpResponse<String> r401 = get("/api/project/page", null);
        report(r401.body().contains("401") || r401.statusCode() == 401,
                "未带 token 被拦截 -> " + r401.body().replaceAll("\\s+", " ").substring(0, Math.min(70, r401.body().length())));

        System.out.println("\n===== 登录 =====");
        HttpResponse<String> lr = post("/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"123456\"}", null);
        String token = pick(lr.body(), "token");
        report(token != null && !token.isEmpty(), "admin 登录拿到 token");
        report(lr.body().contains("\"role\":\"ADMIN\""), "返回 role=ADMIN");
        report(lr.body().contains("roleName"), "返回 roleName（前端侧边栏要用）");

        String auth = "Bearer " + token;

        System.out.println("\n===== 工作台字段 =====");
        HttpResponse<String> dash = get("/api/stat/dashboard", auth);
        report(dash.body().contains("pendingReviewCount"), "有 pendingReviewCount");
        report(dash.body().contains("evidenceStatusDist"), "有 evidenceStatusDist（环形图）");
        report(dash.body().contains("evidenceTypeDist"), "有 evidenceTypeDist（类型分布）");
        report(dash.body().contains("projectProgressList"), "有 projectProgressList");
        report(dash.body().contains("\"progress\""), "项目含 progress 字段（进度条）");

        System.out.println("\n===== 项目列表 =====");
        HttpResponse<String> pj = get("/api/project/page?pageNum=1&pageSize=10", auth);
        report(pj.body().contains("\"records\""), "分页结构含 records");
        report(pj.body().contains("\"total\""), "含 total");
        report(pj.body().contains("projectCode"), "含 projectCode（列表副标题）");
        report(pj.body().contains("auditedUnit"), "含 auditedUnit");

        System.out.println("\n===== 事项列表 =====");
        HttpResponse<String> is = get("/api/issue/page?pageNum=1&pageSize=10", auth);
        report(is.body().contains("issueTitle"), "含 issueTitle");
        report(is.body().contains("riskPoint"), "含 riskPoint");
        report(is.body().contains("evidenceCount"), "含 evidenceCount");

        System.out.println("\n===== 证据列表 =====");
        HttpResponse<String> ev = get("/api/evidence/page?pageNum=1&pageSize=10", auth);
        report(ev.body().contains("evidenceNo"), "含 evidenceNo");
        report(ev.body().contains("canEdit"), "含 canEdit（控制编辑按钮）");
        report(ev.body().contains("canReview"), "含 canReview（控制复核按钮）");
        report(ev.body().contains("statusName"), "含 statusName");

        System.out.println("\n===== 证据详情 + 流转轨迹 =====");
        String firstId = firstId(ev.body());
        if (firstId != null) {
            HttpResponse<String> det = get("/api/evidence/" + firstId, auth);
            report(det.body().contains("evidenceName"), "详情返回正常 id=" + firstId);

            HttpResponse<String> fl = get("/api/evidence/" + firstId + "/flows", auth);
            report(fl.body().contains("\"data\":["), "流转轨迹返回数组");
            report(fl.body().contains("actionName"), "轨迹含 actionName（时间轴标题）");
            report(fl.body().contains("fromStatusName"), "轨迹含 fromStatusName");
        } else {
            report(false, "证据列表已空，无法继续详情检查");
        }

        System.out.println("\n===== 用户下拉（新建项目用） =====");
        HttpResponse<String> us = get("/api/auth/users", auth);
        report(us.body().contains("realName"), "用户列表含 realName");

        System.out.println("\n===== 操作日志（主管可见） =====");
        HttpResponse<String> lg = get("/api/log/page?pageNum=1&pageSize=5", auth);
        report(lg.body().contains("\"records\""), "日志分页正常");

        System.out.println("\n===== Swagger 仍可访问 =====");
        HttpResponse<String> sw = get("/api/v3/api-docs", null);
        report(sw.statusCode() == 200, "/v3/api-docs -> " + sw.statusCode() + " len=" + sw.body().length());

        System.out.println("\n----------------------------------------");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void report(boolean ok, String msg) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + msg);
        if (ok) pass++;
        else fail++;
    }

    static HttpResponse<String> get(String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
        if (auth != null) b.header("Authorization", auth);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 从 JSON 里粗暴取一个字符串字段值，避免为了测试引入 jackson 依赖 */
    static String pick(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int s = i + k.length();
        int e = json.indexOf('"', s);
        return e > s ? json.substring(s, e) : null;
    }

    /** 取第一条记录的 id（雪花 ID 可能是字符串） */
    static String firstId(String json) {
        int i = json.indexOf("\"records\":[");
        if (i < 0) return null;
        int s = json.indexOf("\"id\":", i);
        if (s < 0) return null;
        s += 5;
        while (s < json.length() && json.charAt(s) == ' ') s++;
        if (json.charAt(s) == '"') {
            int e = json.indexOf('"', s + 1);
            return json.substring(s + 1, e);
        }
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)))) e++;
        return json.substring(s, e);
    }
}
