pipeline {
    agent any

    triggers {
        pollSCM('H/5 * * * *')   
    }

    environment {
        DOCKER_USER = 'prasad315'
        IMAGE_NAME  = "${DOCKER_USER}/java-welcome-app"
        GIT_REPO    = "github.com/prasad-dp/java-app.git"
        // FIXED: Added missing $ and changed brackets to ${}
        VERSION     = "v${env.BUILD_ID}"
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
                        // FIXED: Changed 'version' to 'VERSION' (Groovy is case-sensitive)
                        def img = docker.build("${IMAGE_NAME}:${VERSION}", ".")
                        img.push()
                        img.push('latest')
                    }
                }
            }
        }

        stage('Update Manifest') {
            steps {
                // NOTE: Using 'string' here because your github-token is 'Secret Text'
                withCredentials([string(credentialsId: 'github-token', variable: 'GIT_TOKEN')]) {
                    sh """
                        git config user.email "durgaprasadkalepuu@gmail.com"
                        git config user.name "prasad-dp"
                        
                        # Use the VERSION variable for consistency
                        sed -i 's|image: .*|image: ${IMAGE_NAME}:${VERSION}|g' k8s/deployment.yaml
                        
                        git add k8s/deployment.yaml
                        git commit -m "ArgoCD: Update image to version ${VERSION} [ci skip]"
                        
                        # FIXED: Using HEAD:main to resolve the 'refspec' error
                        git push https://prasad-dp:${GIT_TOKEN}@${GIT_REPO} HEAD:main
                    """
                }
            }
        }
    }
}
