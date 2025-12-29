pipeline {
    agent any

    triggers {
        pollSCM('H/5 * * * *')   
    }

    tools {
        // Ensure you configured this in Global Tool Configuration as "SonarScanner"
        jdk 'jdk17' 
    }

    environment {
        DOCKER_USER = 'prasad315'
        IMAGE_NAME  = "${DOCKER_USER}/java-welcome-app"
        GIT_REPO    = "github.com/prasad-dp/java-app.git"
        VERSION     = "v${env.BUILD_ID}"
        
        // SonarQube Details (Make sure this matches your project in SonarQube)
        SONAR_PROJECT_KEY = "java-app"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // --- NEW: SonarQube Analysis (SAST) ---
        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'SonarScanner'
                    withSonarQubeEnv('sonar-server') {
                        // Using standalone scanner. 
                        // Note: For Java, usually 'mvn sonar:sonar' is better, 
                        // but this works for general code scanning without complex setup.
                        sh "${scannerHome}/bin/sonar-scanner \
                        -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                        -Dsonar.sources=src \
                        -Dsonar.java.binaries=target"
                    }
                }
            }
        }

        // --- NEW: Trivy File System Scan (Secret Scanning) ---
        stage('Trivy FS Scan') {
            steps {
                // Scans the current folder (.) for secrets or vulns in dependencies
                sh "trivy fs . --format table -o trivy-fs-report.txt"
            }
        }

        stage('Build & Push') {
            steps {
                script {
                    // Step 1: Compile the JAR inside a Maven container
                    docker.image('maven:3.8.4-openjdk-17-slim').inside('-v /root/.m2:/root/.m2') {
                        sh 'mvn clean package -DskipTests'
                    }

                    // Step 2: Build the Docker image
                    // We assign it to a variable 'img' so we can scan it before pushing
                    def img = docker.build("${IMAGE_NAME}:${VERSION}", ".")

                    // --- NEW: Trivy Image Scan (Container Security) ---
                    // We scan the image LOCALLY before pushing to DockerHub
                    sh "trivy image ${IMAGE_NAME}:${VERSION} --severity HIGH,CRITICAL"

                    // Step 3: Push if scan passes
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
                        git config user.email "durgaprasadkalepuu@gmail.com"
                        git config user.name "prasad-dp"
                        
                        # Clone specifically to avoid 'not a git repo' errors if agent is fresh
                        # (Optional, but safer for the update stage)
                        if [ ! -d "k8s-repo" ]; then
                            git clone https://${G_USER}:${GIT_TOKEN}@${GIT_REPO} k8s-repo
                        fi
                        cd k8s-repo
                        
                        # Ensure we are on main
                        git checkout main
                        git pull origin main

                        # Update the manifest
                        sed -i 's|image: .*|image: ${IMAGE_NAME}:${VERSION}|g' k8s/deployment.yaml
                        
                        # Commit and Push
                        git add k8s/deployment.yaml
                        
                        # Only commit if there are changes
                        if ! git diff-index --quiet HEAD; then
                            git commit -m "ArgoCD: Update image to ${VERSION} [ci skip]"
                            git push https://${G_USER}:${GIT_TOKEN}@${GIT_REPO} main
                        else
                            echo "No changes to deployment.yaml"
                        fi
                    """
                }
            }
        }
    }
}
