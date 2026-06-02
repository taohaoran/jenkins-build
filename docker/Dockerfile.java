# -------------------------------------------
#  Java 应用 - 多阶段 Dockerfile
#  使用方式: docker build --build-arg JDK_VERSION=17 -f docker/Dockerfile.java .
# -------------------------------------------

# -- 构建阶段 --
ARG JDK_VERSION=17
FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
WORKDIR /build
COPY pom.xml .
# 利用 Docker 缓存层: 先下载依赖，再拷贝源码
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# -- 运行阶段 --
FROM eclipse-temurin:${JDK_VERSION}-jre-alpine
ARG JDK_VERSION=17
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
