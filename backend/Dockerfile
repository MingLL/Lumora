# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
# Warm the dependency cache in its own layer. There is no src/ yet, so compilation
# and repackaging are skipped -- without the skips, spring-boot:repackage aborts
# the build with "Unable to find main class".
RUN mvn -q -B -DskipTests -Dmaven.main.skip=true -Dspring-boot.repackage.skip=true package
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 1001 lumora \
    && useradd --system --uid 1001 --gid lumora --home-dir /app --shell /usr/sbin/nologin lumora \
    && mkdir -p /app \
    && chown -R lumora:lumora /app
WORKDIR /app
COPY --from=build --chown=lumora:lumora /workspace/target/*.jar /app/lumora.jar
USER lumora
EXPOSE 8080
# TZ 只影响日志时间戳的渲染 —— 基础镜像默认 UTC，排查线上问题时得在脑子里 +8。
#
# 这行在 V5 迁移之前是加不得的：那时时间列是无时区的 timestamp，容器时区决定
# pgjdbc 把 Instant 渲染成哪个墙钟，一改就让应用写入的列与数据库默认值写入的列
# 错开 8 小时，EventRetentionMapper 还会按偏移后的 cutoff 多删 8 小时数据。
# V5 之后时间列全是 TIMESTAMPTZ，偏移量随值走，容器时区碰不到存的是哪一刻。
#
# 确认过 main 代码里没有 LocalDate.now() / systemDefault() 这类依赖 JVM 默认时区的
# 取值，时间一律走注入的 Clock 或显式 ZoneId，所以业务行为不随这行改变。数据库会话
# 仍由 Hikari 的 connection-init-sql 钉在 UTC（pgjdbc 建连时会先按 JVM 时区设一次，
# 随即被覆盖），WechatEventRepositoryTest 断言了这一点。
#
# 唯一的可见副作用：flyway_schema_history.installed_on 是无时区列且取 now()，此后
# 新增的迁移记录会按北京墙钟落，与历史行的 UTC 墙钟不同基准。该列不参与任何排序或
# 判断（Flyway 用 installed_rank/version），只是人看的。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    LUMORA_MODE=serve \
    TZ=Asia/Shanghai
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/lumora.jar"]
