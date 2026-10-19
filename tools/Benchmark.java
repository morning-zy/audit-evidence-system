import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量压测工具：不依赖 wrk / JMeter，用 JDK 21 虚拟线程跑并发。
 *
 * <p>为什么自己写而不是用 wrk：
 * 1) 本机没有 wrk，装工具链的时间比写这 200 行代码还长；
 * 2) 需要带 JWT 头压测，wrk 要写 Lua 脚本，这里直接 Java 里塞 header 更省事；
 * 3) 虚拟线程让"并发数"和"线程数"解耦，两万并发也不会把机器压垮。
 *
 * <p>输出指标：
 * - QPS（吞吐量，越高越好）
 * - P50 / P95 / P99 延迟（分位数比平均值更有意义，平均值会被长尾掩盖）
 * - 错误率
 *
 * <p>用法：
 * java Benchmark.java &lt;baseUrl&gt; &lt;path&gt; [并发数] [持续秒数] [是否带token]
 */
public class Benchmark {

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // HTTP/1.1 更贴近真实浏览器行为，HTTP/2 在本机回环下会有额外开销
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8090";
        String path = args.length > 1 ? args[1] : "/api/stat/dashboard/1";
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        boolean withToken = args.length <= 4 || Boolean.parseBoolean(args[4]);

        String token = withToken ? login(base) : null;

        System.out.println("=".repeat(70));
        System.out.println("压测目标: " + base + path);
        System.out.println("并发数: " + concurrency + "  持续时间: " + seconds + "s  " + (withToken ? "带JWT" : "不带JWT"));
        System.out.println("=".repeat(70));

        // 预热：让 JIT 编译、连接池、缓存都进入稳态，否则前 100 个请求会污染数据
        System.out.print("预热中...");
        for (int i = 0; i < Math.min(concurrency * 2, 200); i++) {
            send(base, path, token);
        }
        System.out.println(" 完成");

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicLong totalLatency = new AtomicLong();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

        // 虚拟线程：每个虚拟线程一个并发客户端，阻塞在 IO 上也不占平台线程
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            int code = send(base, path, token);
                            long cost = (System.nanoTime() - t0) / 1000; // 微秒
                            totalLatency.addAndGet(cost);
                            latencies.add(cost);
                            if (code == 200) {
                                success.incrementAndGet();
                            } else {
                                fail.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await();
        }

        long total = success.get() + fail.get();
        double qps = total / (double) seconds;

        // 算分位数：排序后取对应位置，比平均值更能反映真实体感
        long[] arr = latencies.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);

        System.out.println();
        System.out.printf("总请求: %d    成功: %d    失败: %d%n", total, success.get(), fail.get());
        System.out.printf("QPS:    %.1f%n", qps);
        System.out.printf("错误率: %.2f%%%n", total == 0 ? 0 : fail.get() * 100.0 / total);
        System.out.printf("平均延迟: %.2f ms%n", total == 0 ? 0 : totalLatency.get() / (double) total / 1000);
        if (arr.length > 0) {
            System.out.printf("P50: %.2f ms%n", arr[(int) (arr.length * 0.50)] / 1000.0);
            System.out.printf("P95: %.2f ms%n", arr[(int) (arr.length * 0.95)] / 1000.0);
            System.out.printf("P99: %.2f ms%n", arr[(int) (arr.length * 0.99)] / 1000.0);
            System.out.printf("Max: %.2f ms%n", arr[arr.length - 1] / 1000.0);
        }
        System.out.println("=".repeat(70));
    }

    static String login(String base) throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"123456\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        String resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
        int i = resp.indexOf("\"token\":\"");
        if (i < 0) {
            throw new IllegalStateException("登录失败: " + resp);
        }
        int start = i + 9;
        int end = resp.indexOf('"', start);
        return resp.substring(start, end);
    }

    static int send(String base, String path, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
