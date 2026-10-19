import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 击穿防护验证：统计「缓存失效瞬间，数据库到底被查了几次」。
 *
 * <p>这是整个优化里最关键的一个测试，因为 QPS 数字会骗人 ——
 * 清空缓存后压 10 秒，只有头几十毫秒是"冷"的，后面 14 万次请求全是热的，
 * QPS 自然好看。真正要衡量的是：<b>冷启动那一瞬间，有没有出现并发查库。</b>
 *
 * <p>方法：
 * 1) 先记录 MySQL 的 Com_select 总量（数据库共执行了多少条查询）
 * 2) 清掉缓存
 * 3) 用 N 个线程在同一毫秒同时发起请求
 * 4) 再读一次 Com_select，算出这轮新增了多少条查询
 *
 * <p>判定标准：
 * - 无防护：N 个线程几乎同时查库，增量约为 N × 每次重建的 SQL 条数（6 条）
 * - 有互斥锁：只有一个线程查库，增量约为 6 条（加上若干次空缓存检查）
 */
public class CacheBreakdownTest {

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8090";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long projectId = args.length > 2 ? Long.parseLong(args[2]) : 1L;

        String token = login(base);
        String path = "/api/stat/project/" + projectId;

        // 预热一次，确保连接池、JIT 都就绪，避免把这些开销算进冷启动
        send(base, path, token);
        send(base, path, token);

        long before = mysqlSelectCount();
        clearCache();

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        List<Long> costs = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();
                        long t0 = System.nanoTime();
                        int code = send(base, path, token);
                        long cost = (System.nanoTime() - t0) / 1_000_000;
                        synchronized (costs) {
                            costs.add(cost);
                        }
                        if (code == 200) ok.incrementAndGet(); else err.incrementAndGet();
                    } catch (Exception e) {
                        err.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();      // 所有线程在此刻同时起跑
            done.await();
        }

        long after = mysqlSelectCount();
        costs.sort(Long::compareTo);

        System.out.println("=".repeat(66));
        System.out.printf("并发线程数: %d   目标: %s%n", threads, path);
        System.out.println("=".repeat(66));
        System.out.printf("成功: %d   失败: %d%n", ok.get(), err.get());
        System.out.printf("耗时中位数: %d ms   最慢: %d ms   最快: %d ms%n",
                costs.get(costs.size() / 2), costs.get(costs.size() - 1), costs.get(0));
        System.out.println("-".repeat(66));
        System.out.printf("MySQL 查询增量: %d 条%n", after - before);
        System.out.println("  说明：单次缓存重建需要 6 条 SQL（4 次 count + 2 次 group by）");
        System.out.printf("  若 %d 线程都查到库，增量应接近 %d 条%n", threads, threads * 6);
        System.out.printf("  实测 %d 条 → 等价于约 %d 个线程真正查了库%n",
                after - before, Math.round((after - before) / 6.0));
        System.out.println("=".repeat(66));
    }

    static long mysqlSelectCount() throws Exception {
        // 通过 MySQL 的 performance_schema 读全局状态变量会太重，
        // 这里直接查 SHOW GLOBAL STATUS。用 JDBC 需要驱动，改用 mysql 命令行更省事。
        String[] cmd = {
                "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe",
                "-uroot", "-pM060330n", "-h127.0.0.1", "-N", "-B",
                "-e", "SHOW GLOBAL STATUS LIKE 'Com_select'"
        };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        String[] parts = out.split("\\s+");
        return Long.parseLong(parts[parts.length - 1]);
    }

    static void clearCache() throws Exception {
        String[] cmd = {
                "C:/Users/mn060/.jdks/ms-21.0.11/bin/java.exe",
                "E:/WorkBuddy/workbuddy-resource/audit-evidence-system/docs/RedisFlush.java",
                "192.168.56.128", "6379", "M060330n", "audit:stat:*"
        };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    static String login(String base) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .build();
        String resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
        int i = resp.indexOf("\"token\":\"");
        int start = i + 9;
        return resp.substring(start, resp.indexOf('"', start));
    }

    static int send(String base, String path, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            return CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
