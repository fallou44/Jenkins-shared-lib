/**
 * sharedPipeline.groovy — CapRover Deployment Pipeline
 * ─────────────────────────────────────────────────────────────────────────
 * Centralized deployment pipeline for all company services targeting CapRover.
 *
 * Deployment method: CapRover REST API via curl (no Docker plugin needed)
 *   → Packages the workspace as a tarball and uploads to CapRover directly.
 *   → Requires only: curl + tar (standard on any Linux Jenkins agent)
 *
 * Branch → Environment routing (automatic, no Jenkinsfile changes needed):
 *   main    → PROD  (CAPTAIN_URL_PROD  / caprover-prod-password)
 *   develop → DEV   (CAPTAIN_URL_DEV   / caprover-dev-password)
 *   other   → pipeline skipped gracefully (no deployment)
 *
 * ─── Minimal Jenkinsfile usage (recommended) ────────────────────────────
 *
 *   @Library('jenkins-shared-lib@main') _
 *   sharedPipeline(appName: 'my-api')
 *
 * ─── Full Jenkinsfile with optional overrides ────────────────────────────
 *
 *   @Library('jenkins-shared-lib@main') _
 *   sharedPipeline(
 *     appName      : 'my-api',
 *     notifyEmails : 'team@company.com;ops@company.com'
 *   )
 *
 * ─── Required Jenkins Global Environment Variables ───────────────────────
 *   CAPTAIN_URL_PROD       URL of the production CapRover instance
 *   CAPTAIN_URL_DEV        URL of the development CapRover instance
 *   NOTIFY_EMAIL_DEFAULT   Semicolon-separated recipient emails
 *   FROM_MAIL              Sender email address
 *
 * ─── Required Jenkins Credentials ────────────────────────────────────────
 *   caprover-prod-password  Secret text — CapRover PROD password
 *   caprover-dev-password   Secret text — CapRover DEV  password
 * ─────────────────────────────────────────────────────────────────────────
 */
def call(Map config = [:]) {

    // ── Required parameter ───────────────────────────────────────────────
    if (!config.appName) {
        error '❌ sharedPipeline requires "appName" to be set.\n' +
              '   Example: sharedPipeline(appName: \'my-api\')'
    }

    // ── Optional parameters with sensible defaults ───────────────────────
    def appName                 = config.appName
    def notifyEmails            = config.notifyEmails ?: env.NOTIFY_EMAIL_DEFAULT
    def fromEmail               = config.fromEmail    ?: env.FROM_MAIL
    def sonarEnabled            = config.sonarEnabled ?: false
    def sonarServer             = config.sonarServer  ?: 'SonarQube'
    def sonarWaitForQualityGate = config.sonarWaitForQualityGate ?: false

    // ── Resolve branch → target environment (automatique) ────────────────
    // main    → PROD  |  develop → DEV  |  autre → skippé
    def envConfig = detectEnvironment(config)

    // ─────────────────────────────────────────────────────────────────────
    pipeline {
        // agent any = fonctionne sur tout Jenkins sans plugin Docker.
        // Déploiement via curl + tar uniquement (disponibles partout sur Linux).
        agent any

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timestamps()
        }

        // ── Stages ───────────────────────────────────────────────────────
        stages {

            // ── 1. Branch Gate ───────────────────────────────────────────
            // Stoppe immédiatement si la branche n'est pas déployable.
            // Évite de consommer un executor pour rien sur les feature branches.
            stage('Branch Gate') {
                steps {
                    script {
                        echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━'
                        echo "  📦 App         : ${appName}"
                        echo "  🌿 Branch      : ${envConfig.branch}"
                        echo "  🎯 Environment : ${envConfig.label}"
                        echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━'

                        if (envConfig.environment == 'skip') {
                            currentBuild.result = 'NOT_BUILT'
                            error(
                                "⚪ Branch '${envConfig.branch}' is not a deployable branch.\n" +
                                "   Deployable branches:\n" +
                                "     • main    → PROD\n" +
                                "     • develop → DEV\n" +
                                "   This build has been skipped — no deployment was performed."
                            )
                        }

                        if (!envConfig.captainUrl) {
                            error(
                                "❌ CapRover URL is not configured for environment '${envConfig.environment}'.\n" +
                                "   Please set the following Jenkins global environment variable:\n" +
                                "     ${envConfig.environment == 'prod' ? 'CAPTAIN_URL_PROD' : 'CAPTAIN_URL_DEV'}"
                            )
                        }

                        echo "✅ Branch gate passed — deploying to ${envConfig.label}"
                    }
                }
            }

            // ── 2. Pre-flight Check ───────────────────────────────────────
            // Vérifie que curl et tar sont disponibles (standard sur tout Linux).
            // Aucun plugin Docker, npm ou Node.js requis.
            stage('Pre-flight Check') {
                steps {
                    sh 'curl --version | head -1'
                    sh 'tar --version | head -1'
                    sh 'git --version'
                    echo '✅ Pre-flight checks passed'
                }
            }

            // ── 3. SonarQube Analysis (Optional) ──────────────────────────
            stage('SonarQube Analysis') {
                when {
                    expression { return sonarEnabled }
                }
                steps {
                    script {
                        echo "🔍 Starting SonarQube analysis for ${appName}..."
                        withSonarQubeEnv(sonarServer) {
                            def hasScanner = sh(
                                script: "command -v sonar-scanner >/dev/null 2>&1 && echo 'yes' || echo 'no'",
                                returnStdout: true
                            ).trim()
                            
                            if (hasScanner == 'yes') {
                                sh "sonar-scanner"
                            } else {
                                echo "⚠️ 'sonar-scanner' executable not found on this Jenkins agent."
                                echo "   Please install 'sonar-scanner' on the host or configure it as a global tool."
                                error "'sonar-scanner' is required for SonarQube analysis."
                            }
                        }
                        
                        if (sonarWaitForQualityGate) {
                            echo "⏳ Waiting for SonarQube Quality Gate result..."
                            timeout(time: 10, unit: 'MINUTES') {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "❌ SonarQube Quality Gate failed: ${qg.status}"
                                }
                                echo "✅ SonarQube Quality Gate passed: ${qg.status}"
                            }
                        }
                    }
                }
            }

            // ── 4. Package ───────────────────────────────────────────────
            // Crée une archive tar.gz du workspace (source code).
            // Exclut git, node_modules, etc. pour alléger le tarball.
            stage('Package') {
                steps {
                    script {
                        def tarFile = "deploy-${appName}-${env.BUILD_NUMBER}.tar.gz"
                        echo "📦 Packaging source code → ${tarFile}"
                        sh """
                            tar -czf /tmp/${tarFile} \
                                --exclude='.git' \
                                --exclude='node_modules' \
                                --exclude='.env' \
                                --exclude='*.log' \
                                --exclude='dist' \
                                --exclude='build' \
                                --exclude='uploads' \
                                --exclude='coverage' \
                                --exclude='*.sql' \
                                --exclude='*.zip' \
                                --exclude='*.tar.gz' \
                                .
                        """
                        echo "✅ Package created: /tmp/${tarFile}"
                    }
                }
            }

            // ── 5. Deploy to CapRover ─────────────────────────────────────
            // Déploiement via CapRover REST API (curl/CLI hybride):
            //   Étape 1 — Détection CLI / npm
            //   Étape 2 — Déploiement ou repli sur API curl (mode detached)
            stage('Deploy to CapRover') {
                steps {
                    script {
                        def tarFile    = "deploy-${appName}-${env.BUILD_NUMBER}.tar.gz"
                        // Normalise l'URL : supprime le slash final si présent
                        def captainUrl = envConfig.captainUrl.replaceAll('/+$', '')

                        echo "🚀 Deploying '${appName}' → ${envConfig.label} (${captainUrl})"

                        withCredentials([string(credentialsId: envConfig.credentialId, variable: 'CAPTAIN_PASSWORD')]) {

                            def deployStatus = sh(
                                script: """
                                    set +x
                                    if command -v caprover >/dev/null 2>&1; then
                                        echo "✅ CapRover CLI found. Deploying via CLI..."
                                        caprover deploy \\
                                            -h "${captainUrl}" \\
                                            -p "\${CAPTAIN_PASSWORD}" \\
                                            -b "${envConfig.branch}" \\
                                            -a "${appName}"
                                    elif command -v npm >/dev/null 2>&1; then
                                        echo "📦 npm found. Installing CapRover CLI locally..."
                                        npm install -g caprover || npm install -g caprover --unsafe-perm
                                        caprover deploy \\
                                            -h "${captainUrl}" \\
                                            -p "\${CAPTAIN_PASSWORD}" \\
                                            -b "${envConfig.branch}" \\
                                            -a "${appName}"
                                    else
                                        echo "⚠️ CapRover CLI and npm not found on this agent."
                                        echo "📤 Falling back to non-blocking API upload (curl)..."
                                        
                                        # Login step to get token
                                        http_login_code=\$(curl --insecure -s -X POST \\
                                            "${captainUrl}/api/v2/login" \\
                                            -H "Content-Type: application/json" \\
                                            -d '{"password":"'"\$CAPTAIN_PASSWORD"'"}' \\
                                            -o /tmp/caprover_login_${env.BUILD_NUMBER}.json \\
                                            -w "%{http_code}")
                                        
                                        if [ "\$http_login_code" != "200" ]; then
                                            echo "❌ CapRover login failed (HTTP status \$http_login_code)"
                                            cat /tmp/caprover_login_${env.BUILD_NUMBER}.json || true
                                            exit 1
                                        fi
                                        
                                        token=\$(grep -o '"token":"[^"]*"' /tmp/caprover_login_${env.BUILD_NUMBER}.json | cut -d'"' -f4)
                                        
                                        # Upload step (detached mode to avoid 504 Gateway Timeouts)
                                        http_deploy_code=\$(curl --insecure -s -X POST \\
                                            "${captainUrl}/api/v2/user/apps/appData/${appName}?detached=1" \\
                                            -H "x-captain-auth: \$token" \\
                                            -F "sourceFile=@/tmp/${tarFile}" \\
                                            -o /tmp/caprover_deploy_${env.BUILD_NUMBER}.json \\
                                            -w "%{http_code}")
                                        
                                        echo "CapRover response HTTP code: \$http_deploy_code"
                                        if [ -f /tmp/caprover_deploy_${env.BUILD_NUMBER}.json ]; then
                                            echo "📝 CapRover API response:"
                                            cat /tmp/caprover_deploy_${env.BUILD_NUMBER}.json
                                            echo ""
                                        fi
                                        
                                        if [ "\$http_deploy_code" != "200" ]; then
                                            echo "❌ CapRover deployment failed with HTTP status \$http_deploy_code"
                                            exit 1
                                        fi
                                    fi
                                    set -x
                                """,
                                returnStatus: true
                            )

                            if (deployStatus != 0) {
                                error("❌ CapRover deployment failed.")
                            }

                            // Nettoyage des fichiers temporaires
                            sh """
                                rm -f /tmp/${tarFile} \
                                      /tmp/caprover_login_${env.BUILD_NUMBER}.json \
                                      /tmp/caprover_deploy_${env.BUILD_NUMBER}.json
                            """

                            echo "✅ '${appName}' deployment process completed for ${envConfig.label}!"
                        }
                    }
                }
            }

        }

        // ── Post actions ─────────────────────────────────────────────────
        post {
            success {
                script {
                    sendNotification(
                        status      : 'success',
                        appName     : appName,
                        environment : envConfig.label,
                        notifyEmails: notifyEmails,
                        fromEmail   : fromEmail
                    )
                }
            }
            failure {
                script {
                    // Guard: ne pas envoyer d'email pour les skips intentionnels
                    if (currentBuild.result != 'NOT_BUILT') {
                        sendNotification(
                            status      : 'failure',
                            appName     : appName,
                            environment : envConfig.label,
                            notifyEmails: notifyEmails,
                            fromEmail   : fromEmail
                        )
                    }
                }
            }
            always {
                cleanWs()
            }
        }
    }
}
