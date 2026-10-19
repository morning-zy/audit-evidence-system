import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 Redis 客户端 —— 只为压测前清缓存用，不引入任何依赖。
 *
 * <p>为什么手写而不是用 RedisTemplate：
 * 压测脚本是 docs 下的独立 Java 文件，不参与 Maven 构建，拿不到 Spring 容器。
 * RESP 协议足够简单（就是 "命令\r\n参数\r\n"），20 行能写完。
 *
 * <p>用法：java RedisFlush.java &lt;host&gt; &lt;port&gt; &lt;password&gt; &lt;pattern&gt;
 * 只删指定 pattern 的 key，不执行 FLUSHALL —— 万一机器上还有别的业务数据，不能误伤。
 */
public class RedisFlush {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.56.128";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        String password = args.length > 2 ? args[2] : "";
        String pattern = args.length > 3 ? args[3] : "audit:stat:*";

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if (!password.isEmpty()) {
                send(out, "AUTH", password);
                System.out.println("AUTH -> " + readLine(in));
            }

            // 用 SCAN 而不是 KEYS：KEYS 会阻塞 Redis 主线程，生产环境禁用
            // SCAN 返回格式：[cursor, [key1, key2, ...]]
            long cursor = 0;
            int deleted = 0;
            do {
                send(out, "SCAN", String.valueOf(cursor), "MATCH", pattern, "COUNT", "1000");
                String head = readLine(in);          // "*2"
                int arrayLen = Integer.parseInt(head.substring(1));
                if (arrayLen != 2) {
                    throw new IllegalStateException("SCAN 返回格式异常: " + head);
                }

                // 第一个元素：新的 cursor（bulk string）
                cursor = Long.parseLong(readBulk(in));

                // 第二个元素：key 数组
                String arrHead = readLine(in);       // "*N"
                int keyCount = Integer.parseInt(arrHead.substring(1));
                for (int k = 0; k < keyCount; k++) {
                    String key = readBulk(in);
                    if (key != null) {
                        send(out, "DEL", key);
                        readLine(in);
                        deleted++;
                    }
                }
            } while (cursor != 0);

            System.out.println("清理完成，共删除 " + deleted + " 个 key（pattern=" + pattern + "）");
        }
    }


    static void send(OutputStream out, String... parts) throws Exception {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(b.length).append("\r\n").append(p).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // 吃掉 \n
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    static String readBulk(InputStream in) throws Exception {
        String head = readLine(in);
        if (!head.startsWith("$")) {
            return null;
        }
        return readBulkBody(in, head);
    }

    static String readBulkBody(InputStream in, String head) throws Exception {
        int len = Integer.parseInt(head.substring(1));
        if (len < 0) {
            return null;
        }
        byte[] buf = new byte[len + 2]; // 含结尾 \r\n
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r < 0) break;
            read += r;
        }
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }
}
