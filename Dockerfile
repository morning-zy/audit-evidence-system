# ============================================================
# 审计证据管理系统 —— 多阶段构建
#
# 为什么分两个阶段（面试常问）：
#   编译需要完整 JDK + Maven（约 700MB），运行只需要 JRE（约 180MB）。
#   如果合成一个镜像，最终交付物里会塞满编译工具链和 Maven 本地仓库，
#   镜像体积从 ~250MB 膨胀到 1GB 以上，拉取和部署都变慢。
#   多阶段构建让"编译产物"跨阶段传递，运行阶段从干净的 JRE 镜像开始。
#
# COPY pom.xml 单独一行的用意（这是缓存优化）：
#   Docker 逐层构建，某一层的内容没变就直接复用缓存。
#   pom.xml 很少改、src 天天改。先 copy pom.xml 并下载依赖，
#   以后改代码时这一层命中缓存，跳过大文件下载，构建从 3 分钟降到 20 秒。
# ============================================================

# ---------- 阶段一：编译 ----------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先只拷 pom.xml，把依赖层单独固化
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests

# 再拷源码编译
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:21-jre

# 时区：容器默认 UTC，不改的话日志时间和业务时间会差 8 小时
# 对审计系统来说这是硬伤 —— 底稿时间必须和现场一致
ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8

# 不用 root 跑应用。容器里默认是 root，一旦容器被攻破，
# 攻击者拿到的就是容器内最高权限。创建普通用户是基本的安全实践。
RUN groupadd -r app && useradd -r -g app app

WORKDIR /app

# 从编译阶段只取 jar 产物
COPY --from=build /build/target/*.jar app.jar

# 日志目录，挂载卷时用
RUN mkdir -p /app/logs && chown -R app:app /app

USER app

EXPOSE 8090

# 为什么用 exec 形式（JSON 数组）而不是 shell 形式：
# shell 形式会变成 /bin/sh -c "java ..."，java 进程的 PID 不是 1，
# 收不到 docker stop 发来的 SIGTERM，最终被 SIGKILL 强杀 ——
# Spring Boot 的优雅停机（等请求处理完再退出）就失效了。
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
