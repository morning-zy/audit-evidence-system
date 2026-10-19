# tools —— 验证脚本

这里的脚本**不参与 Maven 构建**，都是开发过程中用来"证明功能真的能用"的独立程序。
放进来而不是删掉，是因为它们对应 README 里引用的每一个数字：性能对比、冒烟结果、前后端一致性。

## 清单

| 脚本 | 语言 | 用途 | 前置条件 |
|---|---|---|---|
| `SmokeTest.java` | Java 21 | 24 项接口冒烟测试，覆盖登录 → 项目 → 事项 → 证据 → 复核的完整业务闭环 | 应用已启动 |
| `UiCheck.java` | Java 21 | 检查静态资源可访问 + 各列表接口字段是否满足前端假设 | 应用已启动 |
| `review_flow_test.py` | Python 3 | 三级复核流转的 12 个场景，含越权/越级的反向用例 | 应用已启动 |
| `RelCheck.js` | Node.js | 前端可靠性算法与后端 `EvidenceReliabilityEvaluator` 的逐条比对 | 无 |
| `Benchmark.java` | Java 21 | 压测项目列表接口，对比缓存冷/热状态的 P50/P95/QPS | 应用已启动 |
| `CacheBreakdownTest.java` | Java 21 | 缓存击穿压测：验证热点 key 失效瞬间不会把请求全打到数据库 | 应用已启动 |
| `RedisFlush.java` | Java 21 | 极简 RESP 客户端，压测前清缓存用（不引入 Jedis/Lettuce 依赖） | Redis 运行中 |

## 用法

Java 文件是单文件源码，JDK 21 可以直接运行，无需编译：

```bash
# 冒烟测试（项目根目录执行）
java tools/SmokeTest.java

# 压测
java tools/Benchmark.java

# 前端算法一致性校验
node tools/RelCheck.js

# 三级复核流转
python tools/review_flow_test.py
```

默认打到 `http://127.0.0.1:8090/api`；`SmokeTest` / `review_flow_test.py` 支持通过命令行参数改地址。

> 这些脚本依赖演示数据（`admin/123456`、示例项目）。换数据库后先跑一遍
> `docker compose down -v && docker compose up -d` 重置到初始状态。
