# Jenkins Pipeline 构建与 Harbor 集成 — 完整调测记录

## 环境信息

| 组件 | 地址 | 凭据/说明 |
|------|------|-----------|
| Jenkins | http://localhost:9090 | 用户 `thr`，API Token `11236a348b3451e426fdadeb5da8bd2ce0` |
| Harbor | http://10.196.128.70:8090 | 管理员 `admin`，密码 `Harbor12345` |
| GitHub 仓库 | https://github.com/taohaoran/jenkins-build.git | Personal Access Token（Jenkins 凭据 ID: `jenkins-token`） |
| 宿主机 | 007IT00429 (10.196.128.70) | Docker 28.1.1, Docker Compose v2.35.1 |

---

## 项目结构

```
jenkins-build/
├── Jenkinsfile                      # 流水线定义（通用化，支持远程仓库）
├── docker/                          # 预置多阶段 Dockerfile 模板
│   ├── Dockerfile.java
│   ├── Dockerfile.python
│   └── Dockerfile.go
├── test-apps/                       # 示例应用源码（本地测试用）
│   ├── java-app/                    # Spring Boot 3.2.5, Java 17
│   │   ├── pom.xml
│   │   └── src/
│   ├── python-app/                  # Python + requirements.txt
│   │   ├── main.py
│   │   └── requirements.txt
│   └── go-app/                      # Go 1.22
│   │   ├── go.mod
│   │   └── main.go
└── docs/
    └── pipeline-setup.md            # 本文档
```

**两种使用模式：**

| 模式 | GIT_REPO | 说明 |
|------|----------|------|
| 远程仓库 | 填写 URL + 用户名/Token | 拉取任意 Git 仓库，自动检测类型，编译打包推送 |
| 本地项目 | 留空 | 使用 Jenkins 已检出的代码（兼容 test-apps） |

---

## 一、Jenkins Job 配置

### 1.1 Job 基本信息

- **名称**: `JenkinsfilePipline`
- **类型**: Pipeline (WorkflowJob)
- **定义来源**: Pipeline script from SCM
- **SCM**: Git (`https://github.com/taohaoran/jenkins-build.git`)
- **脚本路径**: `Jenkinsfile`

### 1.2 修改 Job 配置（通过 API）

Jenkins 不提供直接的 RESTful 参数修改接口，需要整体替换 `config.xml`：

```bash
# 1. 拉取当前配置
curl -s -u "thr:<token>" "http://localhost:9090/job/JenkinsfilePipline/config.xml" > config.xml

# 2. 修改 config.xml（例如将 */master 改为 */main）

# 3. 推送配置
curl -s -u "thr:<token>" -X POST \
  "http://localhost:9090/job/JenkinsfilePipline/config.xml" \
  -H "Content-Type: application/xml" \
  --data-binary @config.xml
```

config.xml 关键结构：

```xml
<flow-definition>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="hudson.plugins.git.GitSCM">
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>https://github.com/taohaoran/jenkins-build.git</url>
          <credentialsId>jenkins-token</credentialsId>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/main</name>  <!-- 这里从 */master 改为 */main -->
        </hudson.plugins.git.BranchSpec>
      </branches>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
  </definition>
</flow-definition>
```

---

## 二、问题排查全过程

### 问题 1：Git 分支不匹配（构建 #1）

**控制台日志**:

```
hudson.plugins.git.GitException: Command "git fetch --tags --force --progress --prune -- origin +refs/heads/master:refs/remotes/origin/master" returned status code 128:
stderr: fatal: couldn't find remote ref refs/heads/master
```

**原因**: Jenkins Job 创建时默认使用 `master` 分支，但 GitHub 仓库（`taohaoran/jenkins-build`）默认分支是 `main`。Git fetch 尝试拉取 `refs/heads/master`，远程仓库中不存在该引用。

**修复**: 通过 API 修改 Job config.xml，将 `<name>*/master</name>` 改为 `<name>*/main</name>`。

---

### 问题 2：Jenkinsfile 内 checkout 步骤缺少凭据（构建 #2）

**控制台日志**:

```
[Pipeline] checkout
No credentials specified
 > git rev-parse main^{commit} # timeout=10
ERROR: Couldn't find any revision to build.
```

**Pipeline 代码（原始）**:

```groovy
stage('Checkout') {
    steps {
        script {
            checkout([
                $class: 'GitSCM',
                branches: [[name: "${params.GIT_BRANCH}"]],
                userRemoteConfigs: [[url: "${params.GIT_REPO}"]]
            ])
        }
    }
}
```

**原因**: 脚本中的 `checkout` 步骤未指定 `credentialsId`，导致使用匿名方式访问 GitHub，无法通过认证。

同时，`GIT_REPO` 参数默认值为空字符串 `''`。在 Groovy 中，空字符串是 truthy 的，所以条件 `if (params.GIT_REPO)` 对空字符串仍然为 true，导致 checkout 步骤执行了一个 URL 为空的 checkout。

**修复**:

```groovy
stage('Checkout') {
    steps {
        script {
            if (params.GIT_REPO?.trim()) {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "${params.GIT_BRANCH}"]],
                    userRemoteConfigs: [[url: "${params.GIT_REPO}", credentialsId: 'jenkins-token']]
                ])
            }
            env.GIT_COMMIT_SHORT = sh(
                script: 'git rev-parse --short HEAD',
                returnStdout: true
            ).trim()
        }
    }
}
```

关键改动：
- 添加 `credentialsId: 'jenkins-token'`
- 用 `if (params.GIT_REPO?.trim())` 做空值判断（`?.` 是安全导航符，避免 null 引用；`.trim()` 确保空字符串、空白字符串都被判定为 false）
- 当 GIT_REPO 为空时，跳过额外 checkout，直接使用 Pipeline SCM 已经检出的代码

---

### 问题 3：Groovy 空字符串 truthy（构建 #3、#4）

**原因**: 深入分析发现 Groovy 的 truthy 判断机制：`if ('')` 在 Groovy 中结果为 `true`。

| 表达式 | 结果 |
|--------|------|
| `if ('')` | true（Groovy 空字符串是 truthy） |
| `if (null)` | false |
| `if ('hello')` | true |
| `if (''?.trim())` | false（trim 后仍为空，转义为 falsy） |
| `if ('hello'?.trim())` | true |

**修复**: 使用 `if (params.GIT_REPO?.trim())` 同时处理 null 和空字符串两种情况。

---

### 问题 4：构建工作目录不对（构建 #4 → #5）

**控制台日志**:

```
+ mvn clean package
[ERROR] The goal you specified requires a project to execute but there is no POM in this directory
```

**原因**: SCM checkout 将整个仓库克隆到 workspace 根目录（`/var/jenkins_home/workspace/JenkinsfilePipline`），而 Java 项目的 `pom.xml` 在 `test-apps/java-app/` 子目录下。Pipeline 的构建步骤直接在 workspace 根目录执行 `mvn clean package`，找不到 `pom.xml`。

**修复**: 在每个构建阶段用 `dir()` 切换到应用子目录。

```groovy
// Build Java
dir("test-apps/java-app") {
    docker.image(mavenImage).inside("-v ${env.HOME}/.m2:/root/.m2") {
        sh "mvn ${mavenArgs}"
    }
}

// Build Python
dir("test-apps/python-app") {
    docker.image(pythonImage).inside(
        "-v ${env.WORKSPACE}:/workspace -w /workspace/test-apps/python-app"
    ) {
        sh 'pip install --no-cache-dir -r requirements.txt'
        sh 'python -m compileall .'
    }
}

// Build Go
dir("test-apps/go-app") {
    docker.image(goImage).inside(
        "-v ${env.WORKSPACE}:/workspace -w /workspace/test-apps/go-app"
    ) {
        sh 'go mod download'
        sh 'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o app .'
    }
}
```

---

### 问题 5：JDK 版本不兼容（构建 #5 → #6）

**控制台日志**:

```
[ERROR] Failed to execute goal ... maven-compiler-plugin:3.11.0:compile ... 
Fatal error compiling: invalid flag: --release
```

**原因**: `test-apps/java-app/pom.xml` 是 Spring Boot 3.2.5 项目，其 `java.version` 属性为 `17`。Spring Boot 3.x 的 Maven 编译器插件会使用 `--release 17` 参数，该参数仅在 JDK 9+ 才支持。但 Jenkinsfile 参数定义中 `JDK_VERSION` 的 choices 为 `['8', '11', '17', '21']`，第一个选项 `8` 是默认值。Maven 容器使用 `maven:3.9-eclipse-temurin-8`，JDK 8 不识别 `--release` 标志。

**修复**: 调整 choices 顺序，使 JDK 17 成为默认值。

```groovy
choice(
    name: 'JDK_VERSION',
    choices: ['17', '21', '11', '8'],  // 17 放首位作为默认
    description: 'JDK 版本 (Java 应用)'
)
```

**重要发现：Jenkins buildWithParameters 的参数缓存行为**

即使更新了 Jenkinsfile 中的默认值，通过 `buildWithParameters` 触发构建时，未显式传入的参数**不会使用 Jenkinsfile 中的 `defaultValue`**，而是**继承上次构建使用的参数值**。这是 Jenkins 的设计特性。

验证过程：
- 修改了 choices 默认值为 17
- 调用 `buildWithParameters` 不传 `JDK_VERSION`
- 构建日志仍显示 `maven:3.9-eclipse-temurin-8`

解决办法：**每次调用 `buildWithParameters` 时显式传入所有关键参数**：

```bash
curl -u thr:<token> -X POST \
  "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=java" \
  --data-urlencode "JDK_VERSION=17" \
  --data-urlencode "HARBOR_URL=10.196.128.70:8090" \
  --data-urlencode "HARBOR_PROJECT=myproject" \
  --data-urlencode "IMAGE_NAME=java-app" \
  --data-urlencode "IMAGE_TAG=latest"
```

---

### 问题 6：Harbor 凭据缺失（构建 #7）

**控制台日志**:

```
ERROR: Could not find credentials matching harbor-credentials
```

**Pipeline 环境变量**:

```groovy
environment {
    HARBOR_CREDENTIALS = 'harbor-credentials'
}
```

**原因**: Jenkinsfile 中引用了 `harbor-credentials` 凭据 ID，但 Jenkins 凭据存储中没有这个凭据。

**修复**: 通过 Jenkins API 创建凭据。

#### 创建 Harbor 凭据的完整步骤

**步骤 1：创建 XML 文件**

```xml
<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>harbor-credentials</id>
  <description>Harbor Registry Admin</description>
  <username>admin</username>
  <password>Harbor12345</password>
</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
```

**步骤 2：POST 到 Jenkins 凭据 API**

```bash
curl -s -u "thr:<token>" \
  -X POST "http://localhost:9090/credentials/store/system/domain/_/createCredentials" \
  -H "Content-Type: application/xml" \
  --data-binary @harbor-cred.xml
```

API 路径说明：
- `/credentials/store/system/domain/_/` — 系统级凭据存储的全局域
- 返回 HTTP 200 表示创建成功

---

### 问题 7：Harbor 仓库不存在（构建 #8 之前）

**原因**: 环境没有 Harbor 实例，需要从零搭建。

#### 使用 Docker 安装 Harbor 完整步骤

**步骤 1：下载 Harbor 离线安装包**

```bash
mkdir -p /home/plusai/harbor
cd /home/plusai/harbor
curl -sL "https://github.com/goharbor/harbor/releases/download/v2.12.2/harbor-offline-installer-v2.12.2.tgz" -o harbor.tgz
tar xzf harbor.tgz --strip-components=1
```

解压后得到:
- `harbor.v2.12.2.tar.gz` — Docker 镜像包
- `harbor.yml.tmpl` — 配置模板
- `install.sh` — 安装脚本
- `prepare` — 配置生成工具

**步骤 2：编写 harbor.yml**

Harbor 安装需要 `harbor.yml` 配置文件，从 `harbor.yml.tmpl` 复制后修改。关键配置项：

```yaml
# 必须使用可被外部访问的 IP 或主机名，不能用 localhost
hostname: 10.196.128.70

# HTTP 端口（不启用 HTTPS）
http:
  port: 8090

# HTTPS 必须注释掉（没有证书）
# https:
#   port: 443

# 管理员初始密码（仅首次安装生效）
harbor_admin_password: Harbor12345

# 数据库密码
database:
  password: root123

# 数据存储目录（改为 Harbor 安装目录下）
data_volume: /home/plusai/harbor/data
```

> **重要**: Harbor 官方文档强调 `hostname` 不能使用 `localhost` 或 `127.0.0.1`，因为 Docker 容器内部解析 `localhost` 指向自身而非宿主机。使用宿主机的 IP 地址（`10.196.128.70`）确保 Jenkins 容器和 Docker 客户端都能正确访问。

**步骤 3：运行安装脚本**

```bash
cd /home/plusai/harbor
sudo bash install.sh
```

脚本执行流程：
1. 检查 Docker 和 Docker Compose 是否安装
2. 加载 Harbor 镜像（`docker load < harbor.v2.12.2.tar.gz`）
3. 运行 `prepare` 生成配置文件
4. 通过 `docker compose up -d` 启动所有服务

启动的容器：

| 容器名 | 作用 |
|--------|------|
| nginx | 反向代理入口（监听 8090 端口） |
| harbor-core | 核心 API 服务 |
| harbor-portal | Web UI |
| harbor-db | PostgreSQL 数据库 |
| harbor-log | 日志收集（rsyslog） |
| harbor-jobservice | 异步任务（镜像扫描、复制等） |
| harbor-registryctl | Registry 控制器 |
| registry | Docker Registry v2 |
| redis | 缓存和会话 |
| trivy-adapter | 镜像漏洞扫描 |

**步骤 4：创建项目**

```bash
curl -u admin:Harbor12345 -X POST \
  "http://10.196.128.70:8090/api/v2.0/projects" \
  -H "Content-Type: application/json" \
  -d '{"project_name":"myproject","public":true}'
```

项目设为 `public: true`，允许匿名拉取镜像。

**步骤 5：配置 Docker 允许 HTTP 非安全仓库**

Harbor 使用 HTTP 而非 HTTPS，Docker 默认拒绝推送到非安全仓库。需要在 Docker daemon 配置中添加 `insecure-registries`。

编辑 `/etc/docker/daemon.json`：

```json
{
    "runtimes": {
        "nvidia": {
            "args": [],
            "path": "nvidia-container-runtime"
        }
    },
    "insecure-registries": ["10.196.128.70:8090"]
}
```

重启 Docker：

```bash
sudo systemctl restart docker
```

重启后验证：

```bash
docker info | grep -A3 "Insecure Registries"
# 输出应包含: 10.196.128.70:8090
```

**步骤 6：测试 Docker 登录**

```bash
echo "Harbor12345" | docker login "10.196.128.70:8090" -u admin --password-stdin
# Login Succeeded
```

**步骤 7：Docker 重启后恢复 Harbor**

`systemctl restart docker` 会停止所有容器。Harbor 需要手动重启：

```bash
cd /home/plusai/harbor
sudo docker compose up -d
```

---

### 问题 8：Harbor URL 和协议（构建 #8）

**控制台日志**:

```
Building image: harbor.example.com/myproject/java-app:latest
$ docker login -u admin -p ******** http://harbor.example.com
Error response from daemon: Get "https://harbor.example.com/v2/": dial tcp: lookup harbor.example.com on 127.0.0.53:53: no such host
```

**原因**: 
1. `HARBOR_URL` 参数默认值是 `harbor.example.com`（占位符），需要改为实际地址
2. 又遇到了参数缓存问题，虽然 Jenkinsfile 已更新，但 buildWithParameters 未传 HARBOR_URL 时用了旧值

**修复**:

Jenkinsfile 默认值修改：

```groovy
string(
    name: 'HARBOR_URL',
    defaultValue: '10.196.128.70:8090',
    description: 'Harbor 私有仓库地址'
)
```

Registry 协议从 HTTPS 改为 HTTP：

```groovy
docker.withRegistry(
    "http://${params.HARBOR_URL}",   // 原来是 "https://..."
    env.HARBOR_CREDENTIALS
)
```

同时，触发构建时显式传递 HARBOR_URL 和 HARBOR_PROJECT：

```bash
--data-urlencode "HARBOR_URL=10.196.128.70:8090"
--data-urlencode "HARBOR_PROJECT=myproject"
```

---

### 问题 9：Docker BuildKit 不可用（构建 #9）

**控制台日志**:

```
ERROR: BuildKit is enabled but the buildx component is missing or broken.
```

**Pipeline 环境变量**:

```groovy
environment {
    DOCKER_BUILDKIT = '1'       // 这行导致了问题
    HARBOR_CREDENTIALS = 'harbor-credentials'
}
```

**原因**: 设置 `DOCKER_BUILDKIT = '1'` 启用了 Docker BuildKit 构建模式，但 Jenkins 所在的 Docker 容器内没有安装 `buildx` 组件。

**修复**: 直接移除 `DOCKER_BUILDKIT = '1'`。

```groovy
environment {
    HARBOR_CREDENTIALS = 'harbor-credentials'
}
```

> **说明**: 去掉 BuildKit 不影响多阶段构建（multi-stage build）的功能。多阶段构建是 Dockerfile 语法特性，由 Docker daemon 原生支持，不需要 BuildKit。

---

### 问题 10：Dockerfile ARG 顺序错误（构建 #10）

**控制台日志**:

```
Step 1/15 : FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
manifest for maven:3.9-eclipse-temurin- not found: manifest unknown
```

注意版本号部分为空：`maven:3.9-eclipse-temurin-` 后面没有 JDK 版本号。

**原始 Dockerfile（错误）**:

```dockerfile
FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
ARG JDK_VERSION=17
```

**原因**: Docker 中 `ARG` 的作用域规则：

- **`ARG` 在 `FROM` 之前声明**: 该 ARG 属于"全局构建参数"，可在 `FROM` 指令中使用，但在任何 stage 内部不可见（除非 stage 内也声明了同名 ARG）
- **`ARG` 在 `FROM` 之后声明**: 该 ARG 仅属于该 stage 内部，`FROM` 指令执行时变量尚未定义，结果为**空字符串**

因此 `${JDK_VERSION}` 在 `FROM` 中解析为空。

**修复后的 Dockerfile**:

```dockerfile
# ARG 必须在 FROM 之前声明
ARG JDK_VERSION=17
FROM maven:3.9-eclipse-temurin-${JDK_VERSION} AS builder
```

三个 Dockerfile 都需要修改：

| 文件 | FROM 镜像 | ARG 变量 |
|------|-----------|----------|
| Dockerfile.java | `maven:3.9-eclipse-temurin-${JDK_VERSION}` | `JDK_VERSION=17` |
| Dockerfile.python | `python:${PYTHON_VERSION}` | `PYTHON_VERSION=3.11` |
| Dockerfile.go | `golang:${GO_VERSION}-alpine` | `GO_VERSION=1.22` |

---

### 问题 11：Go 项目缺少 go.sum（构建 #12）

**控制台日志**:

```
Step 5/15 : COPY go.mod go.sum ./
ERROR: script returned exit code 1
```

**原始 Dockerfile.go**:

```dockerfile
RUN apk add --no-cache ca-certificates tzdata
COPY go.mod go.sum ./
RUN go mod download
```

**原因**: `test-apps/go-app/go.mod` 只声明了 module 名和 Go 版本：

```
module go-app

go 1.22
```

没有任何外部依赖，因此运行 `go mod tidy` 不会生成 `go.sum` 文件。Docker 的 `COPY go.mod go.sum ./` 在源文件 `go.sum` 不存在时会失败。

**修复**: 使用通配符 `COPY go.* ./`，匹配所有 `go.*` 文件：

```dockerfile
RUN apk add --no-cache ca-certificates tzdata
COPY go.* ./
RUN go mod download
```

`go.*` 通配符在只有 `go.mod` 存在时匹配 `go.mod`；在 `go.mod` 和 `go.sum` 都存在时同时匹配两者。比逐个列出文件名更健壮。

---

## 三、Harbor 凭据体系

本项目涉及两套凭据，分别管理在不同的地方：

### 3.1 GitHub 凭据（jenkins-token）

| 项目 | 值 |
|------|-----|
| Jenkins 凭据 ID | `jenkins-token` |
| 类型 | Username with Password 或 Personal Access Token |
| 用途 | Jenkins 从 GitHub 拉取 Jenkinsfile 和项目代码 |
| 配置位置 | Job config.xml 的 `<credentialsId>` 元素 |
| 使用方式 | Pipeline SCM 定义和 Jenkinsfile 内的 `checkout` 步骤 |

在 Pipeline SCM 定义中：

```xml
<userRemoteConfigs>
  <hudson.plugins.git.UserRemoteConfig>
    <url>https://github.com/taohaoran/jenkins-build.git</url>
    <credentialsId>jenkins-token</credentialsId>
  </hudson.plugins.git.UserRemoteConfig>
</userRemoteConfigs>
```

在 Jenkinsfile 脚本中：

```groovy
checkout([
    $class: 'GitSCM',
    branches: [[name: "${params.GIT_BRANCH}"]],
    userRemoteConfigs: [[url: "${params.GIT_REPO}", credentialsId: 'jenkins-token']]
])
```

### 3.2 Harbor 凭据（harbor-credentials）

| 项目 | 值 |
|------|-----|
| Jenkins 凭据 ID | `harbor-credentials` |
| 类型 | Username with Password |
| 用户名 | `admin` |
| 密码 | `Harbor12345` |
| 用途 | Jenkins Pipeline 中 `docker.withRegistry` 登录 Harbor 推送镜像 |
| 配置位置 | Jenkins 系统凭据存储 (`/credentials/store/system/domain/_/`) |
| 创建方式 | POST XML 到 Jenkins 凭据 API |
| Pipeline 引用 | `env.HARBOR_CREDENTIALS = 'harbor-credentials'` |

在 Jenkinsfile 中的使用方式：

```groovy
docker.withRegistry(
    "http://${params.HARBOR_URL}",
    env.HARBOR_CREDENTIALS   // 引用凭据 ID
) {
    def image = docker.build(fullImage, "${buildArgs} -f ${dockerfile} .")
    image.push()
}
```

`docker.withRegistry` 内部会从 Jenkins 凭据存储查找 `harbor-credentials`，自动执行 `docker login` 和 `docker logout`。

---

## 四、Docker 镜像构建完整流程

以 Java 为例（远程仓库模式），Pipeline 的 Docker 构建推送阶段执行过程：

**1. Clone 项目代码**

```groovy
// GIT_REPO=https://github.com/your-org/myapp.git
// GIT_USERNAME=user, GIT_TOKEN=ghp_xxx
// → 构造认证 URL: https://user:ghp_xxx@github.com/your-org/myapp.git
// → checkout 到 workspace/project/
env.PROJECT_DIR = 'project'
```

**2. 计算构建目录**

```groovy
// APP_SUBDIR=. → BUILD_DIR=project
// APP_SUBDIR=src → BUILD_DIR=project/src
env.BUILD_DIR = 'project'
```

**3. 自动检测/确认应用类型**

```groovy
// APP_TYPE=auto 时检测 BUILD_DIR 下的标志文件
// pom.xml → java, go.mod → go, requirements.txt → python
env.DETECTED_APP_TYPE = 'java'
```

**4. 确定 Dockerfile**

```groovy
// 优先级: DOCKERFILE_PATH > BUILD_DIR/Dockerfile > docker/Dockerfile.{type}
env.DOCKERFILE = '/var/jenkins_home/workspace/.../docker/Dockerfile.java'
```

**5. 确定镜像名称**

```groovy
// IMAGE_NAME 未指定 → 自动使用仓库名
env.IMAGE_NAME = 'myapp'
```

**6. 计算镜像 Tag**

```groovy
def tag = params.IMAGE_TAG ?: env.GIT_COMMIT_SHORT
def fullImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${env.IMAGE_NAME}:${tag}"
// 结果: 10.196.128.70:8090/myproject/myapp:abc1234
```

**7. 设置构建参数**

```groovy
def buildArgs = "--build-arg JDK_VERSION=${params.JDK_VERSION}"
// 结果: --build-arg JDK_VERSION=17
```

**8. 切换到构建目录执行 Docker 构建**

```groovy
dir(env.BUILD_DIR) {
    docker.withRegistry("http://${params.HARBOR_URL}", env.HARBOR_CREDENTIALS) {
        def image = docker.build(fullImage, "${buildArgs} -f ${env.DOCKERFILE} .")
        image.push()
    }
}
```

执行过程：
1. `docker.withRegistry` → `docker login -u admin --password-stdin http://10.196.128.70:8090`
2. `docker.build` → `docker build --build-arg JDK_VERSION=17 -f /path/to/Dockerfile.java -t 10.196.128.70:8090/myproject/myapp:abc1234 .`
3. Docker 执行 Dockerfile 多阶段构建（Maven 编译 → JRE 运行镜像）
4. `image.push()` → `docker push 10.196.128.70:8090/myproject/myapp:abc1234`

**Dockerfile.java 多阶段构建流程**:

```
第一阶段 (builder):
  ARG JDK_VERSION=17
  FROM maven:3.9-eclipse-temurin-17 AS builder
  COPY pom.xml .
  RUN mvn dependency:go-offline -B      # 缓存依赖
  COPY src ./src
  RUN mvn clean package -DskipTests -B  # 编译打包

第二阶段 (runtime):
  FROM eclipse-temurin:17-jre-alpine
  COPY --from=builder /build/target/*.jar app.jar
  ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 五、通用流水线参数说明

Pipeline 已通用化，不再硬编码 `test-apps/` 路径。支持从任意 Git 仓库拉取代码并构建。

### 完整参数列表

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| GIT_REPO | string | (空) | Git 仓库地址，留空则使用已检出代码 |
| GIT_USERNAME | string | (空) | Git 用户名（私有仓库认证） |
| GIT_TOKEN | password | (空) | Git Token 或密码（私有仓库认证） |
| GIT_BRANCH | string | main | Git 分支 |
| APP_TYPE | choice | auto | 应用类型：auto/java/python/go |
| APP_SUBDIR | string | . | 代码在仓库中的子目录 |
| DOCKERFILE_PATH | string | (空) | Dockerfile 路径，留空自动选择 |
| JDK_VERSION | choice | 17 | JDK 版本（Java） |
| PYTHON_VERSION | choice | 3.11 | Python 版本 |
| GO_VERSION | choice | 1.22 | Go 版本 |
| HARBOR_URL | string | 10.196.128.70:8090 | Harbor 地址 |
| HARBOR_PROJECT | string | myproject | Harbor 项目名 |
| IMAGE_NAME | string | (空) | 镜像名，留空自动使用仓库名 |
| IMAGE_TAG | string | (空) | 镜像 Tag，留空使用 git commit sha |
| SKIP_TESTS | boolean | false | 跳过测试 |
| PUSH_LATEST | boolean | false | 同时推送 latest 标签 |

### Dockerfile 选择优先级

1. 参数 `DOCKERFILE_PATH` 指定 → 直接使用
2. 项目目录下存在 `Dockerfile` → 使用项目自带
3. 使用预置模板 `docker/Dockerfile.{java|python|go}`

### APP_TYPE 自动检测规则

| 检测文件 | 判定类型 |
|----------|----------|
| pom.xml | java |
| go.mod | go |
| requirements.txt / setup.py / pyproject.toml | python |

---

## 六、触发构建命令参考

### 远程仓库构建（推荐方式）

```bash
# Java 项目
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/your-org/java-project.git" \
  --data-urlencode "GIT_USERNAME=your-username" \
  --data-urlencode "GIT_TOKEN=ghp_xxxxxxxxxxxx" \
  --data-urlencode "GIT_BRANCH=main" \
  --data-urlencode "APP_TYPE=java" \
  --data-urlencode "JDK_VERSION=17" \
  --data-urlencode "IMAGE_NAME=java-app" \
  --data-urlencode "IMAGE_TAG=v1.0.0"
```

```bash
# Python 项目（自动检测类型）
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/your-org/python-project.git" \
  --data-urlencode "GIT_USERNAME=your-username" \
  --data-urlencode "GIT_TOKEN=ghp_xxxxxxxxxxxx" \
  --data-urlencode "GIT_BRANCH=main" \
  --data-urlencode "APP_TYPE=auto" \
  --data-urlencode "IMAGE_NAME=python-app"
```

```bash
# Go 项目（子目录构建）
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/your-org/go-project.git" \
  --data-urlencode "GIT_USERNAME=your-username" \
  --data-urlencode "GIT_TOKEN=ghp_xxxxxxxxxxxx" \
  --data-urlencode "GIT_BRANCH=main" \
  --data-urlencode "APP_TYPE=go" \
  --data-urlencode "APP_SUBDIR=cmd/server" \
  --data-urlencode "IMAGE_NAME=go-app"
```

```bash
# 使用项目自带 Dockerfile
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/your-org/project.git" \
  --data-urlencode "GIT_USERNAME=your-username" \
  --data-urlencode "GIT_TOKEN=ghp_xxxxxxxxxxxx" \
  --data-urlencode "APP_TYPE=auto" \
  --data-urlencode "DOCKERFILE_PATH=deploy/Dockerfile" \
  --data-urlencode "IMAGE_NAME=my-app"
```

### 本地 test-apps 构建（兼容旧模式）

GIT_REPO 留空则使用 Jenkins 已检出的代码，保持向后兼容：

```bash
# Java (test-apps)
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=java" \
  --data-urlencode "JDK_VERSION=17" \
  --data-urlencode "APP_SUBDIR=test-apps/java-app" \
  --data-urlencode "IMAGE_NAME=java-app" \
  --data-urlencode "IMAGE_TAG=latest"
```

```bash
# Python (test-apps)
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=python" \
  --data-urlencode "PYTHON_VERSION=3.11" \
  --data-urlencode "APP_SUBDIR=test-apps/python-app" \
  --data-urlencode "IMAGE_NAME=python-app" \
  --data-urlencode "IMAGE_TAG=latest"
```

```bash
# Go (test-apps)
curl -u thr:11236a348b3451e426fdadeb5da8bd2ce0 \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "APP_TYPE=go" \
  --data-urlencode "GO_VERSION=1.22" \
  --data-urlencode "APP_SUBDIR=test-apps/go-app" \
  --data-urlencode "IMAGE_NAME=go-app" \
  --data-urlencode "IMAGE_TAG=latest"
```

---

## 七、构建历史总览

| 构建 | APP_TYPE | 关键问题 | 结果 |
|------|----------|----------|------|
| #1 | (默认) | Git `master` 分支不存在 | FAILURE |
| #2 | (默认) | checkout 无凭据 + GIT_REPO 空 | FAILURE |
| #3 | (默认) | 同上，Jenkinsfile 未生效 | FAILURE |
| #4 | (默认) | 同上 | FAILURE |
| #5 | java(8) | 无 pom.xml（未 cd 到子目录） | FAILURE |
| #6 | java(8) | JDK 8 不兼容 --release | FAILURE |
| #7 | java(17) | Maven 成功，Harbor 凭据缺失 | FAILURE |
| #8 | java(17) | HARBOR_URL 仍为 harbor.example.com | FAILURE |
| #9 | java(17) | BuildKit 不可用 | FAILURE |
| #10 | java(17) | Dockerfile ARG 顺序错误 | FAILURE |
| #11 | java(17) | — | **SUCCESS** |
| #12 | python(3.11) | — | **SUCCESS** |
| #13 | go(1.22) | go.sum 文件缺失 | FAILURE |
| #14 | go(1.22) | — | **SUCCESS** |

---

## 八、Harbor 运维管理

### 7.1 启动

```bash
cd /home/plusai/harbor
sudo docker compose up -d
```

### 7.2 停止

```bash
cd /home/plusai/harbor
sudo docker compose down
```

### 7.3 查看状态

```bash
curl -s "http://10.196.128.70:8090/api/v2.0/health"
```

### 7.4 查看项目及仓库

```bash
curl -u admin:Harbor12345 \
  "http://10.196.128.70:8090/api/v2.0/projects/myproject/repositories" \
  | python3 -m json.tool
```

### 7.5 登录 Web UI

浏览器访问 `http://10.196.128.70:8090`，用户名 `admin`，密码 `Harbor12345`。
