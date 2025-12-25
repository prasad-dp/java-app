pipeline {
    agent any

    triggers {
        pollSCM('H/5 * * * *')   
    }

    environment {
        DOCKER_USER = 'prasad315'
        IMAGE_NAME  = "${DOCKER_USER}/java-welcome-app"
        GIT_REPO    = "github.com/prasad-dp/java-app.git"
    }

    stages {
        stage('Build & Push') {
            steps {
                script {
                    
                        // Step 1: Compile the JAR inside a Maven container
                        docker.image('maven:3.8.4-openjdk-17-slim').inside('-v /root/.m2:/root/.m2') {
                            sh 'mvn clean package -DskipTests'
                        }

                        // Step 2: Build and Push the Docker image
                        docker.withRegistry('', 'docker-hub-creds') {
                            def img = docker.build("${IMAGE_NAME}:${env.BUILD_ID}", ".")
                            img.push()
                            img.push('latest')
                        }
                    
                }
            }
        }

        stage('Update Manifest') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-token', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USER')]) {
                    sh """
                        git config user.email "durgaprasadkalepuu@gmail.com"
                        git config user.name "prasad-dp"
                        
                        sed -i 's|image: .*|image: ${IMAGE_NAME}:${env.BUILD_ID}|g' k8s/deployment.yaml
                        
                        git add k8s/deployment.yaml
                        git commit -m "ArgoCD: Update image to version ${env.BUILD_ID} [ci skip]"
                        git push https://${GIT_USER}:${GIT_TOKEN}@${GIT_REPO} main
                    """
                }
            }
        }
    }
}
