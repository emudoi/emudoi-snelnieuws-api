// =============================================================================
//  SnelNieuwsApi Jenkins CI/CD Pipeline
// =============================================================================
// Required Jenkins Credentials:
//   - APP_NODE_SSH_KEY    : SSH private key for app node (Jenkins SSH credential)
//   - github-token-secret : GitHub Classic PAT for GHCR push (secret text)
// =============================================================================

pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'ghcr.io'
        IMAGE_NAME = "${DOCKER_REGISTRY}/${env.GITHUB_REPOSITORY_OWNER ?: 'emudoi'}/emudoi-snelnieuws-api"
        IMAGE_TAG = "${env.GIT_COMMIT?.take(7) ?: 'latest'}"
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = env.GIT_SHORT_SHA
                    echo "Git SHA: ${env.GIT_SHA}"
                    echo "Image tag: ${env.IMAGE_TAG}"
                }
            }
        }

        stage('Test') {
            steps {
                echo '[emudoi] Running tests...'
                sh '''
                    if ! command -v sbt > /dev/null 2>&1; then
                        echo "[emudoi] Installing sbt..."
                        curl -fsSL "https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz" | tar xz -C /tmp
                        export PATH="/tmp/sbt/bin:$PATH"
                    fi
                    sbt test
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/test-reports/*.xml'
                }
                failure {
                    echo '[emudoi] Tests FAILED - merge will be blocked'
                }
            }
        }

        stage('Build Docker Image') {
            when {
                branch 'main'
            }
            steps {
                echo "[emudoi] Building Docker image: ${IMAGE_NAME}:${IMAGE_TAG}"
                sh """
                    docker build \
                        -t ${IMAGE_NAME}:${IMAGE_TAG} \
                        -t ${IMAGE_NAME}:latest \
                        --label "com.emudoi.git.sha=${GIT_SHA}" \
                        --label "com.emudoi.build.number=${BUILD_NUMBER}" \
                        --label "com.emudoi.build.url=${BUILD_URL}" \
                        .
                """
            }
        }

        stage('Push to GHCR') {
            when {
                branch 'main'
            }
            steps {
                echo '[emudoi] Pushing image to GitHub Container Registry...'
                withCredentials([string(credentialsId: 'github-token-secret', variable: 'GITHUB_TOKEN')]) {
                    sh """
                        echo "${GITHUB_TOKEN}" | docker login ${DOCKER_REGISTRY} -u emudoi --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                    """
                }
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                echo '[emudoi] Deploying to app node...'
                withCredentials([
                    string(credentialsId: 'github-token-secret', variable: 'GITHUB_TOKEN'),
                    sshUserPrivateKey(credentialsId: 'APP_NODE_SSH_KEY', keyFileVariable: 'SSH_KEY_FILE')
                ]) {
                    sh '''
                        APP_NODE_HOST=$(getent hosts api.snel.emudoi.com | awk '{print $1}')
                        SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"

                        echo "[emudoi] Removing old container if exists..."
                        ssh ${SSH_OPTS} -i "${SSH_KEY_FILE}" root@${APP_NODE_HOST} \
                            "docker rm -f emudoi-snelnieuws-api 2>/dev/null || true"

                        echo "[emudoi] Pulling latest image on app node..."
                        ssh ${SSH_OPTS} -i "${SSH_KEY_FILE}" root@${APP_NODE_HOST} \
                            "echo '${GITHUB_TOKEN}' | docker login ghcr.io -u emudoi --password-stdin && \
                             docker compose -f /opt/emudoi/docker-compose.yml pull emudoi-snelnieuws-api"

                        echo "[emudoi] Starting updated containers..."
                        ssh ${SSH_OPTS} -i "${SSH_KEY_FILE}" root@${APP_NODE_HOST} \
                            "docker compose -f /opt/emudoi/docker-compose.yml up -d emudoi-snelnieuws-api"

                        echo "[emudoi] Waiting for health check..."
                        sleep 15

                        ssh ${SSH_OPTS} -i "${SSH_KEY_FILE}" root@${APP_NODE_HOST} \
                            "curl -sf http://localhost:9002/health || exit 1"

                        echo "[emudoi] Deployment successful!"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo '[emudoi] Pipeline completed successfully!'
        }
        failure {
            echo '[emudoi] Pipeline FAILED!'
        }
    }
}
