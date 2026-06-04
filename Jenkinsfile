pipeline {
    agent any

    options {
        timeout(time: 1, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    parameters {
        // ============================================================
        //  Git 仓库配置
        // ============================================================
        string(
            name: 'GIT_REPO',
            defaultValue: '',
            description: 'Git 仓库地址 (例: https://github.com/user/repo.git)。留空则使用 Jenkins 已检出的代码'
        )
        string(
            name: 'GIT_USERNAME',
            defaultValue: '',
            description: 'Git 用户名 (私有仓库认证)'
        )
        password(
            name: 'GIT_TOKEN',
            defaultValue: '',
            description: 'Git Token 或密码 (私有仓库认证)'
        )
        string(
            name: 'GIT_BRANCH',
            defaultValue: 'main',
            description: 'Git 分支'
        )

        // ============================================================
        //  项目配置
        // ============================================================
        choice(
            name: 'APP_TYPE',
            choices: ['auto', 'java', 'python', 'go'],
            description: '应用类型。auto = 自动检测 (pom.xml→java, go.mod→go, requirements.txt/setup.py/pyproject.toml→python)'
        )
        string(
            name: 'APP_SUBDIR',
            defaultValue: '.',
            description: '应用代码在仓库中的子目录路径 (例: src/app, . 表示仓库根目录)'
        )
        string(
            name: 'DOCKERFILE_PATH',
            defaultValue: '',
            description: 'Dockerfile 路径。留空则按优先级自动选择: 1)项目自带Dockerfile 2)预置docker/Dockerfile.{type}'
        )

        // ============================================================
        //  构建工具版本
        // ============================================================
        choice(
            name: 'JDK_VERSION',
            choices: ['17', '21', '11', '8'],
            description: 'JDK 版本 (Java 应用)'
        )
        choice(
            name: 'PYTHON_VERSION',
            choices: ['3.11', '3.12', '3.10', '3.9'],
            description: 'Python 版本 (Python 应用)'
        )
        choice(
            name: 'GO_VERSION',
            choices: ['1.22', '1.23', '1.24', '1.25'],
            description: 'Go 版本 (Go 应用)'
        )

        // ============================================================
        //  Harbor 镜像仓库配置
        // ============================================================
        string(
            name: 'HARBOR_URL',
            defaultValue: '10.196.128.70:8090',
            description: 'Harbor 私有仓库地址'
        )
        string(
            name: 'HARBOR_PROJECT',
            defaultValue: 'myproject',
            description: 'Harbor 项目名称'
        )
        string(
            name: 'IMAGE_NAME',
            defaultValue: '',
            description: 'Docker 镜像名称 (留空则使用仓库名)'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Docker 镜像 Tag (留空则使用 git commit sha)'
        )

        // ============================================================
        //  构建选项
        // ============================================================
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: '跳过测试'
        )
        booleanParam(
            name: 'PUSH_LATEST',
            defaultValue: false,
            description: '同时推送 latest 标签'
        )

        // ============================================================
        //  SonarQube 代码分析
        // ============================================================
        booleanParam(
            name: 'SONAR_ANALYSIS',
            defaultValue: true,
            description: '执行 SonarQube 代码分析'
        )
        string(
            name: 'SONAR_HOST_URL',
            defaultValue: 'http://10.196.128.70:9000',
            description: 'SonarQube 服务地址'
        )
    }

    environment {
        HARBOR_CREDENTIALS = 'harbor-credentials'
    }

    stages {
        // ============================================================
        //  Checkout — 拉取项目代码
        // ============================================================
        stage('Checkout') {
            steps {
                script {
                    if (params.GIT_REPO?.trim()) {
                        def repoUrl = params.GIT_REPO.trim()
                        if (params.GIT_USERNAME?.trim() && params.GIT_TOKEN) {
                            def user = params.GIT_USERNAME.trim()
                            def token = params.GIT_TOKEN.toString()
                            repoUrl = repoUrl.replaceFirst('https://', "https://${user}:${token}@")
                        }

                        echo "Cloning ${params.GIT_REPO} (branch: ${params.GIT_BRANCH})"

                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: "refs/heads/${params.GIT_BRANCH}"]],
                            userRemoteConfigs: [[url: repoUrl]],
                            extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'project']]
                        ])
                        env.PROJECT_DIR = 'project'
                    } else {
                        echo 'GIT_REPO 为空，使用当前工作空间代码'
                        env.PROJECT_DIR = '.'
                    }

                    // 计算构建目录
                    if (params.APP_SUBDIR?.trim() && params.APP_SUBDIR != '.') {
                        env.BUILD_DIR = "${env.PROJECT_DIR}/${params.APP_SUBDIR.trim()}"
                    } else {
                        env.BUILD_DIR = env.PROJECT_DIR
                    }

                    // 获取 git commit sha
                    env.GIT_COMMIT_SHORT = sh(
                        script: "cd ${env.PROJECT_DIR} && git rev-parse --short HEAD",
                        returnStdout: true
                    ).trim()

                    echo "Project dir: ${env.PROJECT_DIR}, Build dir: ${env.BUILD_DIR}"
                    echo "Git commit: ${env.GIT_COMMIT_SHORT}"

                    // --------------------------------------------------
                    //  自动检测应用类型
                    // --------------------------------------------------
                    if (params.APP_TYPE == 'auto') {
                        if (fileExists("${env.BUILD_DIR}/pom.xml")) {
                            env.DETECTED_APP_TYPE = 'java'
                        } else if (fileExists("${env.BUILD_DIR}/go.mod")) {
                            env.DETECTED_APP_TYPE = 'go'
                        } else if (fileExists("${env.BUILD_DIR}/requirements.txt") ||
                                   fileExists("${env.BUILD_DIR}/setup.py") ||
                                   fileExists("${env.BUILD_DIR}/pyproject.toml")) {
                            env.DETECTED_APP_TYPE = 'python'
                        } else {
                            error("无法自动检测应用类型。请在 ${env.BUILD_DIR} 下提供 pom.xml / go.mod / requirements.txt，或手动指定 APP_TYPE")
                        }
                        echo "自动检测应用类型: ${env.DETECTED_APP_TYPE}"
                    } else {
                        env.DETECTED_APP_TYPE = params.APP_TYPE
                    }

                    // --------------------------------------------------
                    //  检测 Go 主包路径 (cmd/server, cmd/app, 或根目录)
                    // --------------------------------------------------
                    if (env.DETECTED_APP_TYPE == 'go') {
                        def mainPkg = sh(
                            script: "cd ${env.BUILD_DIR} && find . -maxdepth 3 -type f -name 'main.go' ! -path '*/vendor/*' | head -1 | xargs dirname 2>/dev/null || echo '.'",
                            returnStdout: true
                        ).trim()
                        env.GO_MAIN_PATH = mainPkg ?: '.'
                        echo "Go main package path: ${env.GO_MAIN_PATH}"
                    }

                    // --------------------------------------------------
                    //  确定 Dockerfile
                    // --------------------------------------------------
                    if (params.DOCKERFILE_PATH?.trim()) {
                        env.DOCKERFILE = params.DOCKERFILE_PATH.trim()
                    } else if (fileExists("${env.BUILD_DIR}/Dockerfile")) {
                        env.DOCKERFILE = "${env.BUILD_DIR}/Dockerfile"
                    } else {
                        env.DOCKERFILE = "${env.WORKSPACE}/docker/Dockerfile.${env.DETECTED_APP_TYPE}"
                    }
                    echo "Dockerfile: ${env.DOCKERFILE}"

                    // --------------------------------------------------
                    //  确定镜像名称
                    // --------------------------------------------------
                    if (!params.IMAGE_NAME?.trim()) {
                        def repoName = sh(
                            script: "cd ${env.PROJECT_DIR} && basename `git rev-parse --show-toplevel`",
                            returnStdout: true
                        ).trim()
                        env.IMAGE_NAME = repoName
                    } else {
                        env.IMAGE_NAME = params.IMAGE_NAME.trim()
                    }
                }
            }
        }

        // ============================================================
        //  Java 构建 (编译 + 测试)
        // ============================================================
        stage('Build Java') {
            when {
                expression { env.DETECTED_APP_TYPE == 'java' }
            }
            steps {
                script {
                    def mavenImage = "maven:3.9-eclipse-temurin-${params.JDK_VERSION}"
                    def mavenArgs = params.SKIP_TESTS ? 'clean package -DskipTests' : 'clean package'

                    dir(env.BUILD_DIR) {
                        docker.image(mavenImage).inside(
                            "-v ${env.HOME}/.m2:/root/.m2"
                        ) {
                            sh "mvn ${mavenArgs} -B"
                            sh 'cp target/*.jar app.jar'
                        }
                    }
                }
            }
        }

        // ============================================================
        //  Python 构建 (依赖安装 + 语法校验)
        // ============================================================
        stage('Build Python') {
            when {
                expression { env.DETECTED_APP_TYPE == 'python' }
            }
            steps {
                script {
                    def pythonImage = "python:${params.PYTHON_VERSION}"

                    dir(env.BUILD_DIR) {
                        docker.image(pythonImage).inside(
                            "-v ${env.WORKSPACE}:/workspace -w /workspace/${env.BUILD_DIR}"
                        ) {
                            sh '''
                                pip install --no-cache-dir -r requirements.txt
                                python -m compileall .
                            '''
                            if (!params.SKIP_TESTS) {
                                sh 'python -m pytest --junitxml=test-report.xml || true'
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        //  Go 构建 (编译 + 测试)
        // ============================================================
        stage('Build Go') {
            when {
                expression { env.DETECTED_APP_TYPE == 'go' }
            }
            steps {
                script {
                    def goImage = "golang:${params.GO_VERSION}"
                    def mainPath = env.GO_MAIN_PATH ?: '.'

                    dir(env.BUILD_DIR) {
                        docker.image(goImage).inside(
                            "-v ${env.WORKSPACE}:/workspace -w /workspace/${env.BUILD_DIR}"
                        ) {
                            sh """
                                go mod download
                                CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
                                    -ldflags="-s -w" \
                                    -o app ${mainPath}
                            """
                            if (!params.SKIP_TESTS) {
                                sh 'go test ./... || true'
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        //  SonarQube 代码分析
        // ============================================================
        stage('SonarQube Analysis') {
            when {
                expression { params.SONAR_ANALYSIS }
            }
            steps {
                script {
                    def projectKey = "${env.IMAGE_NAME}"
                    def projectVersion = "${env.GIT_COMMIT_SHORT}"
                    def sources = '.'

                    // 导出变量供 shell 使用
                    env.SONAR_PROJECT_KEY = projectKey
                    env.SONAR_SOURCES = sources
                    env.SONAR_HOST_URL = params.SONAR_HOST_URL

                    // 在项目构建目录中执行 SonarQube 分析
                    dir(env.BUILD_DIR) {
                        // 使用 withCredentials 绑定 SonarQube Token
                        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                            sh '''
                                SCANNER_VERSION="5.0.1.3006"
                                SCANNER_HOME="/tmp/sonar-scanner-${SCANNER_VERSION}-linux"
                                if [ ! -d "${SCANNER_HOME}" ]; then
                                    echo "Downloading SonarScanner ${SCANNER_VERSION}..."
                                    curl -sL "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SCANNER_VERSION}-linux.zip" \
                                        -o /tmp/sonar-scanner.zip
                                    unzip -qo /tmp/sonar-scanner.zip -d /tmp
                                fi
                                ${SCANNER_HOME}/bin/sonar-scanner \
                                    -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                    -Dsonar.sources=${SONAR_SOURCES} \
                                    -Dsonar.host.url=${SONAR_HOST_URL} \
                                    -Dsonar.sourceEncoding=UTF-8 \
                                    -Dsonar.projectVersion=${GIT_COMMIT_SHORT} \
                                    -Dsonar.token=${SONAR_TOKEN}
                            '''
                        }
                    }
                    echo "SonarQube analysis completed for ${projectKey}"
                }
            }
        }

        // ============================================================
        //  Docker 镜像构建 & 推送到 Harbor
        // ============================================================
        stage('Docker Build & Push') {
            steps {
                script {
                    def tag = params.IMAGE_TAG?.trim() ?: env.GIT_COMMIT_SHORT
                    def fullImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${env.IMAGE_NAME}:${tag}"

                    echo "Building image: ${fullImage}"
                    echo "Dockerfile: ${env.DOCKERFILE}"
                    echo "Build context: ${env.BUILD_DIR}"

                    def buildArgs = ''
                    if (env.DETECTED_APP_TYPE == 'java') {
                        buildArgs = "--build-arg JDK_VERSION=${params.JDK_VERSION}"
                    } else if (env.DETECTED_APP_TYPE == 'python') {
                        buildArgs = "--build-arg PYTHON_VERSION=${params.PYTHON_VERSION}"
                    } else if (env.DETECTED_APP_TYPE == 'go') {
                        buildArgs = "--build-arg GO_VERSION=${params.GO_VERSION} --build-arg MAIN_PATH=${env.GO_MAIN_PATH}"
                    }

                    dir(env.BUILD_DIR) {
                        docker.withRegistry(
                            "http://${params.HARBOR_URL}",
                            env.HARBOR_CREDENTIALS
                        ) {
                            def image = docker.build(
                                fullImage,
                                "${buildArgs} -f ${env.DOCKERFILE} ."
                            )
                            image.push()
                            echo "Pushed: ${fullImage}"

                            if (params.PUSH_LATEST) {
                                image.push('latest')
                                echo "Pushed latest tag"
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def tag = params.IMAGE_TAG?.trim() ?: env.GIT_COMMIT_SHORT
                def fullImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${env.IMAGE_NAME}:${tag}"
                echo "✓ 构建成功: ${fullImage}"
            }
        }
        failure {
            echo "✗ 构建失败，请检查日志"
        }
        always {
            cleanWs()
        }
    }
}
