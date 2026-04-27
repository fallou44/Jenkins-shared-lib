def call(Map config) {
    def notifyEmails = config.notifyEmails ?: env.NOTIFY_TEAM_OPS
    def fromEmail = config.fromEmail ?: env.FROM_MAIL
    def registryUrl = config.registryUrl
    def deploymentEnvironmentPath = config.deploymentEnvironmentPath
    def imageName = config.imageName
    def projectComposeFilePath = config.projectComposeFilePath
    def composeDeployedFilePath = config.composeDeployedFilePath
    def serviceAppName = config.serviceAppName
    def stackName = config.stackName
    def envConfigName = config.envConfigName
    def swarmMasterHostIp = config.swarmMasterHostIp
    def sshCredentialsId = config.sshCredentialsId
    def composeFileContent = ''
    def requiredParams = [
            'registryUrl'      : config.registryUrl,
            'deploymentEnvironmentPath'  : config.deploymentEnvironmentPath,
            'imageName'        : config.imageName,
            'projectComposeFilePath'  : config.projectComposeFilePath,
            'serviceAppName'   : config.serviceAppName,
            'stackName'        : config.stackName,
            'swarmMasterHostIp': config.swarmMasterHostIp,
            'sshCredentialsId' : config.sshCredentialsId
    ]

    def missingParams = []
    for (param in requiredParams) {
        def key = param.key
        def value = param.value
        if (value == null || value.toString().trim().isEmpty()) {
            missingParams.add(key)
        }
    }
    if (missingParams.size() > 0) {
        error "🚨 Missing required parameters: ${missingParams.join(', ')}"
    }

    pipeline {
        agent {
            docker {
                image 'fadildev/jenkins-node-caprover:1.0'
                args '-u root:root'
            }
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        environment {
            TIMESTAMP = sh(script: "date +'%Y-%m-%d-%H-%M-%S'", returnStdout: true).trim()
            IMAGE_TAG_LATEST = "${registryUrl}/${imageName}:latest"
            IMAGE_TAG_TIMESTAMP = "${registryUrl}/${imageName}:${TIMESTAMP}"
        }


        stages {
            stage('Build Docker Image') {
                steps {
                sh """
                    docker build --no-cache -t ${imageName}:latest -t ${imageName}:${TIMESTAMP} .
                """
                }
            }
            stage('Tag and Push Image') {
                when {
                    expression {
                        return config.registryUrl != null && !config.registryUrl.toString().trim().isEmpty()
                    }
                }
                steps {
                    script {
                        sh """
                            docker tag ${imageName}:${TIMESTAMP} ${IMAGE_TAG_TIMESTAMP}
                            docker tag ${imageName}:latest ${IMAGE_TAG_LATEST}
                            docker push ${IMAGE_TAG_TIMESTAMP}
                            docker push ${IMAGE_TAG_LATEST}
                        """
                    }
                }
            }
            stage('Read Compose File') {
                steps {
                    script {
                        try {
                            composeFileContent = readFile(projectComposeFilePath)
                            echo "Successfully read compose file content"
                        } catch (Exception e) {
                            error "Failed to read compose file at ${projectComposeFilePath}: ${e.message}"
                        }
                    }
                }
            }
            stage('Deploy to Remote Server') {
                steps {
                    script {
                        // First, write the compose file content to a local temp file
                        writeFile file: 'temp_compose.yml', text: composeFileContent

                        sshagent([sshCredentialsId]) {
                            // Copy the compose file to the remote server
                            sh "scp -o StrictHostKeyChecking=no temp_compose.yml ${swarmMasterHostIp}:${composeDeployedFilePath}"
                            // Now run the deployment commands
                            sh """
                                ssh -o StrictHostKeyChecking=no ${swarmMasterHostIp} << EOF
                                docker service rm ${serviceAppName} || true
                                # Check if envConfigName is set and not empty
                                if [ ! -z "${envConfigName}" ]; then
                                    docker config rm ${envConfigName} || true
                                    docker config create ${envConfigName} ${deploymentEnvironmentPath}
                                else
                                    echo "Skipping config update: envConfigName is not set"
                                fi
                                
                                docker stack deploy -c ${composeDeployedFilePath} --with-registry-auth ${stackName}
                                EOF
                            """
                        }

                        // Clean up the temp file
                        sh "rm temp_compose.yml"
                    }
                }
            }

        }

        post {
            success {
                script {
                    sendNotification(
                            status: 'success',
                            serviceAppName: serviceAppName,
                            notifyEmails: notifyEmails,
                            fromEmail: fromEmail
                    )
                }
            }

            failure {
                script {
                    sendNotification(
                            status: 'failure',
                            serviceAppName: serviceAppName,
                            notifyEmails: notifyEmails,
                            fromEmail: fromEmail
                    )
                }
            }

            always {
                cleanWs()
            }
        }
    }
}
