pipeline {
    agent any

    triggers {
        pollSCM('H/5 * * * *')   
    }

    environment {
        DOCKER_USER = 'prasad315'
        IMAGE_NAME  = "${DOCKER_USER}/java-welcome-app"
        GIT_REPO    = "github.com/prasad-dp/java-app.git"
        VERSION     = "v${env.BUILD_ID}"
        
        // SonarQube Project Key
        SONAR_PROJECT_KEY = "java-app"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Trivy FS Scan') {
            steps {
                // Scans source code for secrets/vulnerabilities
                sh "trivy fs . --format table -o trivy-fs-report.txt"
            }
        }

        stage('Build, Test & Analysis') {
            steps {
                script {
                    // We combine Build and Analysis to use the Maven container for both
                    docker.image('maven:3.8.4-openjdk-17-slim').inside('-v /root/.m2:/root/.m2') {
                        
                        // Inject SonarQube credentials/URL into the container
                        withSonarQubeEnv('sonar-server') {
                            
                            // ONE COMMAND: Clean, Build JAR, and Run Sonar Scan
                            // This bypasses the need for the "SonarScanner" tool installation
                            sh """
                                mvn clean package org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                                -DskipTests \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY}
                            """
                        }
                    }

                    // Build Docker Image
                    def img = docker.build("${IMAGE_NAME}:${VERSION}", ".")

                    // Scan Docker Image (Container Security)
                   // sh "trivy image ${IMAGE_NAME}:${VERSION} --severity HIGH,CRITICAL" - it consumers a ot of memory 
                    // Added '--scanners vuln' to skip memory-heavy secret scanning
                   sh "trivy image --scanners vuln ${IMAGE_NAME}:${VERSION} --severity HIGH,CRITICAL"

                    // Push to Docker Hub
                    docker.withRegistry('', 'docker-hub-creds') {
                        img.push()
                        img.push('latest')
                    }
                }
            }
        }

        stage('Update Manifest') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-token', passwordVariable: 'GIT_TOKEN', usernameVariable: 'G_USER')]) {
                    sh """
                        # 1. CLEANUP: Always remove old folder to prevent conflicts
                        rm -rf temp-manifest-update

                        # 2. CLONE: Get a fresh copy of the repo
                        git clone https://${G_USER}:${GIT_TOKEN}@${GIT_REPO} temp-manifest-update
                        cd temp-manifest-update

                        # 3. CONFIGURE GIT
                        git config user.email "durgaprasadkalepuu@gmail.com"
                        git config user.name "prasad-dp"

                        # 4. UPDATE YAML
                        sed -i 's|image: .*|image: ${IMAGE_NAME}:${VERSION}|g' k8s/deployment.yaml

                        # 5. COMMIT & PUSH
                        git add k8s/deployment.yaml
                        
                        # Note: [ci skip] prevents infinite loop
                        git commit -m "ArgoCD: Update image to ${VERSION} [ci skip]"
                        git push origin main
                    """
                }
            }
        }
    }
}


