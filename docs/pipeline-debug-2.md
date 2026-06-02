# Jenkins Pipeline 通用化调试记录

## 概述

将 Jenkins Pipeline 从硬编码 `test-apps/` 路径改造为通用流水线，支持传入 `GIT_REPO`、`GIT_USERNAME`、`GIT_TOKEN` 动态拉取任意仓库代码并构建推送。

---

## 遇到的问题与修复

### 问题 1：Jenkins 构建触发失败（HTTP 500）

**现象**：

`buildWithParameters` 返回 HTTP 500 `Oops!` 页面，GET 请求正常但 POST 失败。

**原因**：Job 的参数定义缓存了旧版 Jenkinsfile（`main` 分支），新 Jenkinsfile 中的参数（`APP_TYPE=auto`、`APP_SUBDIR`、`GIT_USERNAME`、`GIT_TOKEN` 等）尚未被 Jenkins 识别。Job 的 `DeclarativeJobPropertyTrackerAction` 中只记录了旧参数列表。

**修复步骤**：
1. 先用旧参数（`APP_TYPE=java`）触发一次构建，让 Jenkins 从 `jenkins-build-dev` 分支重新读取 Jenkinsfile
2. 构建成功扫描新 Jenkinsfile 后，Job 的参数定义自动更新（新增 `GIT_USERNAME`、`GIT_TOKEN`、`APP_SUBDIR`、`DOCKERFILE_PATH` 等）
3. 确认更新后再传入新参数触发构建

**验证**：
```bash
curl -s -u "thr:token" "http://localhost:9090/job/JenkinsfilePipline/api/json" \
  | python3 -c "... print([p['name'] for p in j['property'][0]['parameterDefinitions']])"
# 输出: ['GIT_REPO', 'GIT_USERNAME', 'GIT_TOKEN', 'GIT_BRANCH', 'APP_TYPE', 'APP_SUBDIR', ...]
```

**关键发现**：Jenkins Pipeline script from SCM 模式下，首次修改 Jenkinsfile 参数定义后，需至少执行一次构建扫描才会更新 Job 的参数元数据。

---

### 问题 2：Git 分支不存在（构建 #15）

**现象**：

```
fatal: couldn't find remote ref refs/heads/jenkins-build-dev
```

**原因**：Jenkins Job 的 SCM 配置指向 `*/jenkins-build-dev` 分支，但该分支仅在本地存在，未推送到 GitHub 远程仓库。

**修复**：
```bash
git push -u origin jenkins-build-dev
```

---

### 问题 3：GitHub 匿名克隆被拒（构建 #20-#23）

**现象**：

```
fatal: Authentication failed for 'https://github.com/taohaoran/test-apps.git/'
```

**原因**：公共仓库 `test-apps.git` 最初可匿名访问（构建 #17-#19 成功），但后续 GitHub 要求认证。可能是 IP 限流或仓库权限变更。

**修复**：传入 `GIT_USERNAME` + `GIT_TOKEN` 参数进行认证克隆。

---

### 问题 4：Jenkins 沙箱拦截 URLEncoder（构建 #24-#26）

**现象**：

控制台日志无任何 `echo` 输出，script 块直接失败：
```
hudson.remoting.ProxyException: groovy.lang.MissingMethodException
```

**Pipeline 代码（原始）**：
```groovy
def encodedUser = java.net.URLEncoder.encode(params.GIT_USERNAME.trim(), 'UTF-8')
def encodedToken = java.net.URLEncoder.encode(params.GIT_TOKEN.trim(), 'UTF-8')
```

**原因**：Jenkins Pipeline 沙箱安全策略下，`java.net.URLEncoder.encode()` 不在白名单中。需要通过 Jenkins Script Approval 审批或换用其他方式。

**Jenkins Pipeline 沙箱机制说明**：
- Pipeline 默认在 Groovy 沙箱中执行
- 所有 Java 标准库方法调用需显式审批
- 审批路径：Manage Jenkins → In-process Script Approval
- 也可通过 API 审批（本次未调通）

**修复**：去除 `URLEncoder`，直接拼接认证 URL。GitHub Personal Access Token (`ghp_*`) 由字母数字和下划线组成，均为 URL 安全字符，无需编码。

```groovy
// 修复前（被沙箱拦截）
def encodedUser = java.net.URLEncoder.encode(params.GIT_USERNAME.trim(), 'UTF-8')
def encodedToken = java.net.URLEncoder.encode(params.GIT_TOKEN.trim(), 'UTF-8')
repoUrl = repoUrl.replaceFirst('https://', "https://${encodedUser}:${encodedToken}@")

// 修复后
def user = params.GIT_USERNAME.trim()
def token = params.GIT_TOKEN.toString()
repoUrl = repoUrl.replaceFirst('https://', "https://${user}:${token}@")
```

---

### 问题 5：password 参数类型返回 Secret 对象（构建 #27-#29）

**现象**：

```
groovy.lang.MissingMethodException: No signature of method: hudson.util.Secret.trim() is applicable for argument types: () values: []
```

**原因**：Jenkins 的 `password` 类型参数在 Pipeline 中返回的是 `hudson.util.Secret` 对象，而非 `String`。该对象没有 `.trim()` 方法。

**对比**：

| 参数类型 | Pipeline 中的 Java 类型 | 可调方法 |
|----------|------------------------|----------|
| `string` | `java.lang.String` | `.trim()`, `.isEmpty()` 等 |
| `password` | `hudson.util.Secret` | `.toString()` 获取明文 |

**修复**：
```groovy
// 修复前（Secret 对象无 .trim() 方法）
if (params.GIT_USERNAME?.trim() && params.GIT_TOKEN?.trim()) {
    def token = params.GIT_TOKEN.trim()

// 修复后（Secret 转为 String 后使用）
if (params.GIT_USERNAME?.trim() && params.GIT_TOKEN) {
    def token = params.GIT_TOKEN.toString()
```

> **注意**：`password` 参数在 Jenkins UI 和构建日志中自动掩码，但在 Groovy 中 `.toString()` 可获取明文。URL 拼接后仍可能在前几行日志中暴露（如 `echo` 只打印原始 URL 不含密码），但 `checkout` 步骤的 git 命令日志会显示完整认证 URL。生产环境建议使用 Jenkins Credentials 凭据存储。

---

## 最终验证

| 构建 | 应用 | 子目录 | 自动检测 | 认证 | 结果 |
|------|------|--------|----------|------|------|
| #30 | java-app | java-app | pom.xml → java | taohaoran + token | SUCCESS |
| #31 | go-app | go-app | go.mod → go | taohaoran + token | SUCCESS |
| #32 | python-app | python-app | requirements.txt → python | taohaoran + token | SUCCESS |

三种语言、自动检测、子目录构建、认证克隆、Docker 镜像推送全部验证通过。

---

## 通用流水线触发命令参考

```bash
# Java
curl -u thr:<jenkins-token> \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/user/repo.git" \
  --data-urlencode "GIT_USERNAME=username" \
  --data-urlencode "GIT_TOKEN=ghp_xxx" \
  --data-urlencode "APP_TYPE=auto" \
  --data-urlencode "APP_SUBDIR=java-app" \
  --data-urlencode "IMAGE_NAME=java-app" \
  --data-urlencode "IMAGE_TAG=latest"

# Go
curl -u thr:<jenkins-token> \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/user/repo.git" \
  --data-urlencode "GIT_USERNAME=username" \
  --data-urlencode "GIT_TOKEN=ghp_xxx" \
  --data-urlencode "APP_TYPE=auto" \
  --data-urlencode "APP_SUBDIR=go-app" \
  --data-urlencode "IMAGE_NAME=go-app" \
  --data-urlencode "IMAGE_TAG=latest"

# Python
curl -u thr:<jenkins-token> \
  -X POST "http://localhost:9090/job/JenkinsfilePipline/buildWithParameters" \
  --data-urlencode "GIT_REPO=https://github.com/user/repo.git" \
  --data-urlencode "GIT_USERNAME=username" \
  --data-urlencode "GIT_TOKEN=ghp_xxx" \
  --data-urlencode "APP_TYPE=auto" \
  --data-urlencode "APP_SUBDIR=python-app" \
  --data-urlencode "IMAGE_NAME=python-app" \
  --data-urlencode "IMAGE_TAG=latest"
```

---

## 相关 Commit

| Commit | 说明 |
|--------|------|
| `d505b5c` | refactor: generalize pipeline for any remote Git repo with auth |
| `b5a8e84` | fix: remove URLEncoder to avoid Jenkins sandbox restriction |
| `f4b1d52` | fix: convert Secret to String for GIT_TOKEN parameter |
