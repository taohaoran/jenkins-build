pipeline {
    agent any

    parameters {
        choice(
            name: 'APP_TYPE',
            choices: ['java', 'python', 'go'],
            description: '选择应用类型'
        )
        choice(
            name: 'JDK_VERSION',
            choices: ['8', '11', '17', '21'],
            description: 'JDK 版本 (Java 应用)'
        )
        choice(
            name: 'PYTHON_VERSION',
            choices: ['3.9', '3.10', '3.11', '3.12'],
            description: 'Python 版本 (Python 应用)'
        )
        choice(
            name: 'GO_VERSION',
            choices: ['1.21', '1.22', '1.23'],
            description: 'Go 版本 (Go 应用)'
        )
        string(
            name: 'GIT_REPO',
            defaultValue: '',
            description: 'Git 仓库地址'
        )
        string(
            name: 'GIT_BRANCH',
            defaultValue: 'main',
            description: 'Git 分支'
        )
        string(
            name: 'HARBOR_URL',
            defaultValue: 'harbor.example.com',
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
            description: 'Docker 镜像名称'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Docker 镜像 Tag (留空则使用 git commit sha)'
        )
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
    }

    environment {
        DOCKER_BUILDKIT = '1'
        HARBOR_CREDENTIALS = 'harbor-credentials'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "${params.GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${params.GIT_REPO}"]]
                    ])
                    env.GIT_COMMIT_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                }
            }
        }

        // ============================================================
        //  Java 构建
        // ============================================================
        stage('Build Java') {
            when {
                expression { params.APP_TYPE == 'java' }
            }
            steps {
                script {
                    def mavenImage = "maven:3.9-eclipse-temurin-${params.JDK_VERSION}"
                    def mavenArgs = params.SKIP_TESTS ? 'clean package -DskipTests' : 'clean package'

                    docker.image(mavenImage).inside(
                        "-v ${env.HOME}/.m2:/root/.m2"
                    ) {
                        sh "mvn ${mavenArgs}"
                        sh 'cp target/*.jar app.jar'
                    }
                }
            }
        }

        // ============================================================
        //  Python 构建 (依赖安装 + 语法校验)
        // ============================================================
        stage('Build Python') {
            when {
                expression { params.APP_TYPE == 'python' }
            }
            steps {
                script {
                    def pythonImage = "python:${params.PYTHON_VERSION}"

                    docker.image(pythonImage).inside(
                        "-v ${env.WORKSPACE}:/workspace -w /workspace"
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

        // ============================================================
        //  Go 构建
        // ============================================================
        stage('Build Go') {
            when {
                expression { params.APP_TYPE == 'go' }
            }
            steps {
                script {
                    def goImage = "golang:${params.GO_VERSION}"

                    docker.image(goImage).inside(
                        "-v ${env.WORKSPACE}:/workspace -w /workspace"
                    ) {
                        sh '''
                            go mod download
                            CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
                                -ldflags="-s -w" \
                                -o app .
                        '''
                        if (!params.SKIP_TESTS) {
                            sh 'go test ./... || true'
                        }
                    }
                }
            }
        }

        // ============================================================
        //  Docker 镜像构建 & 推送到 Harbor
        // ============================================================
        stage('Docker Build & Push') {
            steps {
                script {
                    def tag = params.IMAGE_TAG ?: env.GIT_COMMIT_SHORT
                    def fullImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${params.IMAGE_NAME}:${tag}"

                    // 根据应用类型选择对应的 Dockerfile
                    def dockerfile = "docker/Dockerfile.${params.APP_TYPE}"
                    if (!fileExists(dockerfile)) {
                        dockerfile = 'Dockerfile'
                    }

                    echo "Building image: ${fullImage}"
                    echo "Using Dockerfile: ${dockerfile}"

                    def buildArgs = ''
                    if (params.APP_TYPE == 'java') {
                        buildArgs = "--build-arg JDK_VERSION=${params.JDK_VERSION}"
                    } else if (params.APP_TYPE == 'python') {
                        buildArgs = "--build-arg PYTHON_VERSION=${params.PYTHON_VERSION}"
                    } else if (params.APP_TYPE == 'go') {
                        buildArgs = "--build-arg GO_VERSION=${params.GO_VERSION}"
                    }

                    docker.withRegistry(
                        "https://${params.HARBOR_URL}",
                        env.HARBOR_CREDENTIALS
                    ) {
                        def image = docker.build(
                            fullImage,
                            "${buildArgs} -f ${dockerfile} ."
                        )
                        image.push()
                        echo "Pushed: ${fullImage}"

                        if (params.PUSH_LATEST) {
                            def latestImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${params.IMAGE_NAME}:latest"
                            image.push('latest')
                            echo "Pushed: ${latestImage}"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def tag = params.IMAGE_TAG ?: env.GIT_COMMIT_SHORT
                def fullImage = "${params.HARBOR_URL}/${params.HARBOR_PROJECT}/${params.IMAGE_NAME}:${tag}"
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
