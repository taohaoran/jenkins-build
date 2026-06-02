# Jenkins Pipeline 构建与 Harbor 集成

## 项目结构

```
jenkins-build/
├── Jenkinsfile                    # 流水线定义
├── docker/
│   ├── Dockerfile.java            # Java 多阶段构建
│   ├── Dockerfile.python          # Python 多阶段构建
│   └── Dockerfile.go              # Go 多阶段构建
└── test-apps/
    ├── java-app/                  # Spring Boot 3.2 (pom.xml + src/)
    ├── python-app/                # Python (main.py + requirements.txt)
    └── go-app/                    # Go (go.mod + main.go)
```

## 环境信息

| 服务 | 地址 | 凭据 |
|------|------|------|
| Jenkins | http://localhost:9090 | thr / API Token |
| Harbor | http://10.196.128.70:8090 | admin / Harbor12345 |
| Jenkins Node | 内置 (Docker 容器) | - |

## Pipeline 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| APP_TYPE | choice | java | java / python / go |
| JDK_VERSION | choice | 17 | 17 / 21 / 11 / 8 |
| PYTHON_VERSION | choice | 3.11 | 3.9 / 3.10 / 3.11 / 3.12 |
| GO_VERSION | choice | 1.22 | 1.21 / 1.22 / 1.23 |
| GIT_REPO | string | (空) | 留空使用 SCM 已检出的代码 |
| GIT_BRANCH | string | main | Git 分支 |
| HARBOR_URL | string | 10.196.128.70:8090 | Harbor 地址 |
| HARBOR_PROJECT | string | myproject | Harbor 项目名 |
| IMAGE_NAME | string | - | 镜像名称 |
| IMAGE_TAG | string | - | 镜像 Tag（留空用 git sha） |
| SKIP_TESTS | boolean | false | 跳过测试 |
| PUSH_LATEST | boolean | false | 同时推送 latest |

## Pipeline 阶段

```
Checkout → [Build Java | Build Python | Build Go] → Docker Build & Push
```

- **Checkout**: 仅在 `GIT_REPO` 非空时额外检出代码
- **Build Java**: `dir(test-apps/java-app)` → Maven 容器构建
- **Build Python**: `dir(test-apps/python-app)` → Python 容器安装依赖 + 编译检查
- **Build Go**: `dir(test-apps/go-app)` → Go 容器编译
- **Docker Build & Push**: `dir(test-apps/<type>-app)` → 多阶段 Docker 构建 → 推送到 Harbor

## 触发构建

```bash
# Java
curl -u thr:<token> -X POST \
  "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=java" \
  --data-urlencode "JDK_VERSION=17" \
  --data-urlencode "IMAGE_NAME=java-app" \
  --data-urlencode "IMAGE_TAG=latest" \
  --data-urlencode "HARBOR_URL=10.196.128.70:8090" \
  --data-urlencode "HARBOR_PROJECT=myproject"

# Python
curl -u thr:<token> -X POST \
  "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=python" \
  --data-urlencode "PYTHON_VERSION=3.11" \
  --data-urlencode "IMAGE_NAME=python-app" \
  --data-urlencode "IMAGE_TAG=latest" \
  --data-urlencode "HARBOR_URL=10.196.128.70:8090" \
  --data-urlencode "HARBOR_PROJECT=myproject"

# Go
curl -u thr:<token> -X POST \
  "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=go" \
  --data-urlencode "GO_VERSION=1.22" \
  --data-urlencode "IMAGE_NAME=go-app" \
  --data-urlencode "IMAGE_TAG=latest" \
  --data-urlencode "HARBOR_URL=10.196.128.70:8090" \
  --data-urlencode "HARBOR_PROJECT=myproject"
```

> **注意**: 使用 `buildWithParameters` 时，未传的参数会继承上次构建的值而非 Jenkinsfile 默认值。

## 踩坑记录

### 1. Git 分支不匹配
- **现象**: `fatal: couldn't find remote ref refs/heads/master`
- **原因**: Jenkins Job SCM 配置默认 `*/master`，仓库使用 `main`
- **修复**: 通过 API `POST /job/<name>/config.xml` 改为 `*/main`

### 2. Jenkinsfile 内 checkout 缺少凭据
- **现象**: `No credentials specified` → `Couldn't find any revision to build`
- **原因**: 脚本式 `checkout` 步骤未指定 `credentialsId`
- **修复**: 添加 `credentialsId: 'jenkins-token'`

### 3. Groovy 空字符串判断
- **现象**: `if (params.GIT_REPO)` 对空字符串为 true
- **原因**: Groovy 中 `''` 是 truthy
- **修复**: 改用 `if (params.GIT_REPO?.trim())`

### 4. 构建工作目录错误
- **现象**: Maven 报 `no POM in this directory`
- **原因**: 构建步骤在 workspace 根目录执行，源码在 `test-apps/<type>-app/`
- **修复**: 添加 `dir("test-apps/${params.APP_TYPE}-app")`

### 5. JDK 版本不兼容
- **现象**: `Fatal error compiling: invalid flag: --release`
- **原因**: Spring Boot 3.2 需要 Java 17+，默认 JDK 为 8
- **修复**: 将 choices 首位改为 `17`，但 `buildWithParameters` 仍会用缓存参数值

### 6. Docker BuildKit 不可用
- **现象**: `BuildKit is enabled but the buildx component is missing`
- **原因**: Jenkins 容器未安装 buildx
- **修复**: 移除 `DOCKER_BUILDKIT = '1'` 环境变量

### 7. Dockerfile ARG 顺序错误
- **现象**: `manifest for maven:3.9-eclipse-temurin- not found`（版本号为空）
- **原因**: `ARG` 声明在 `FROM` 之后，`FROM` 中使用时变量未定义
- **修复**: 将 `ARG JDK_VERSION=17` 移到 `FROM` 之前

```dockerfile
# 错误
FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
ARG JDK_VERSION=17

# 正确
ARG JDK_VERSION=17
FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
```

### 8. Go 项目缺少 go.sum
- **现象**: Docker `COPY go.mod go.sum ./` 失败
- **原因**: 无外部依赖时 `go.mod tidy` 不生成 `go.sum`
- **修复**: 改为 `COPY go.* ./`（通配符匹配存在的文件）

## Harbor 管理

### 启动 Harbor

```bash
cd /home/plusai/harbor
sudo docker compose up -d
```

### 创建项目

```bash
curl -u admin:Harbor12345 -X POST \
  "http://10.196.128.70:8090/api/v2.0/projects" \
  -H "Content-Type: application/json" \
  -d '{"project_name":"myproject","public":true}'
```

### Docker 配置（HTTP 非安全仓库）

`/etc/docker/daemon.json`:
```json
{
    "insecure-registries": ["10.196.128.70:8090"]
}
```

### Jenkins 凭据

Jenkins 需配置 `harbor-credentials`（Username/Password 类型）:
- 用户名: `admin`
- 密码: `Harbor12345`
