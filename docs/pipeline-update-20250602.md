# Pipeline 更新记录 — 2026-06-02

## 代码变更

### Jenkinsfile

**1. 更新 Go 版本选项**

`GO_VERSION` 参数从 `['1.22', '1.23', '1.21']` 扩展为 `['1.22', '1.23', '1.24', '1.25']`。

> 原因: `todolist-go` 项目的 `go.mod` 要求 `go >= 1.25.0`，原有选项不满足。

**2. 新增 Go 主包路径自动检测**

```groovy
if (env.DETECTED_APP_TYPE == 'go') {
    def mainPkg = sh(
        script: "cd ${env.BUILD_DIR} && find . -maxdepth 3 -type f -name 'main.go' ! -path '*/vendor/*' | head -1 | xargs dirname 2>/dev/null || echo '.'",
        returnStdout: true
    ).trim()
    env.GO_MAIN_PATH = mainPkg ?: '.'
    echo "Go main package path: ${env.GO_MAIN_PATH}"
}
```

> 原因: 构建命令此前固定为 `go build -o app .`，但部分项目 `main.go` 位于子目录（如 `cmd/server/`），导致 "no Go files" 错误。

**3. 构建命令改为动态主包路径**

```groovy
def mainPath = env.GO_MAIN_PATH ?: '.'
sh """
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
        -ldflags="-s -w" \
        -o app ${mainPath}
"""
```

**4. Docker 构建传递 MAIN_PATH 参数**

```groovy
buildArgs = "--build-arg GO_VERSION=${params.GO_VERSION} --build-arg MAIN_PATH=${env.GO_MAIN_PATH}"
```

---

### docker/Dockerfile.go

- 新增 `ARG MAIN_PATH=.` 构建参数
- `go build -o app .` → `go build -o app ${MAIN_PATH}`
- 更新使用说明注释

> 原因: 与 Jenkinsfile 配合，使多阶段 Docker 构建也支持非根目录的主包路径。

---

## 构建记录

| # | 项目 | 分支 | Go 版本 | 主包路径 | 结果 |
|---|------|------|---------|----------|------|
| 33 | todolist-go | main | 1.22 | — | ❌ 分支不存在（应使用 `master`） |
| 34 | todolist-go | master | 1.22 | `.` | ❌ `go.mod requires go >= 1.25.0` |
| 35 | todolist-go | master | 1.25 | `.` | ❌ `no Go files`（缺少主包检测） |
| 36 | todolist-go | master | 1.25 | `./cmd/server` | ❌ Harbor 不可达 |
| **37** | **todolist-go** | **master** | **1.25** | **`./cmd/server`** | ✅ **SUCCESS** |
| **38** | **test-apps/go-app** | **main** | **1.22** | **`.`** | ✅ **SUCCESS** |

### 关键参数

- Build #37 (todolist-go): `GIT_REPO=https://github.com/taohaoran/todolist-go.git`, `GIT_BRANCH=master`, `APP_TYPE=go`, `GO_VERSION=1.25`
- Build #38 (test-apps/go-app): `GIT_REPO=https://github.com/taohaoran/test-apps.git`, `GIT_BRANCH=main`, `APP_TYPE=go`, `APP_SUBDIR=go-app`

---

## 附带修复

- **Harbor 镜像仓库**: 容器已停止，执行 `sudo docker compose up -d` 重启
- **Jenkins 参数定义缓存**: Pipeline Job 的参数定义不会自动跟随 Jenkinsfile 更新，需通过 Script Console 或重新保存 Job 配置来同步
