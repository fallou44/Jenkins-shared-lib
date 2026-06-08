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
    def appName      = config.appName
    def notifyEmails = config.notifyEmails ?: env.NOTIFY_EMAIL_DEFAULT
    def fromEmail    = config.fromEmail    ?: env.FROM_MAIL

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

            // ── 3. Package ───────────────────────────────────────────────
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
                                .
                        """
                        echo "✅ Package created: /tmp/${tarFile}"
                    }
                }
            }

            // ── 4. Deploy to CapRover ─────────────────────────────────────
            // Déploiement via CapRover REST API (curl uniquement):
            //   Étape 1 — Login → récupère un token d'authentification
            //   Étape 2 — Upload du tarball → CapRover build & déploie
            stage('Deploy to CapRover') {
                steps {
                    script {
                        def tarFile    = "deploy-${appName}-${env.BUILD_NUMBER}.tar.gz"
                        // Normalise l'URL : supprime le slash final si présent
                        def captainUrl = envConfig.captainUrl.replaceAll('/+$', '')

                        echo "🚀 Deploying '${appName}' → ${envConfig.label} (${captainUrl})"

                        withCredentials([string(credentialsId: envConfig.credentialId, variable: 'CAPTAIN_PASSWORD')]) {

                            // Étape 1 : Login CapRover → récupère le token
                            // --insecure : accepte les certificats SSL auto-signés (fréquent sur CapRover)
                            def loginStatus = sh(
                                script: """
                                    set +x
                                    curl -sf --insecure -X POST \\
                                        "${captainUrl}/api/v2/login" \\
                                        -H "Content-Type: application/json" \\
                                        -d '{"password":"'"\\${CAPTAIN_PASSWORD}"'"}' \\
                                        -o /tmp/caprover_login_${env.BUILD_NUMBER}.json
                                    set -x
                                """,
                                returnStatus: true
                            )

                            if (loginStatus != 0) {
                                echo "❌ CapRover login response:"
                                sh "cat /tmp/caprover_login_${env.BUILD_NUMBER}.json || echo 'fichier vide'"
                                error("❌ CapRover login failed (curl exit ${loginStatus}). Vérifier CAPTAIN_URL et le credential.")
                            }

                            def token = sh(
                                script: "grep -o '\"token\":\"[^\"]*\"' /tmp/caprover_login_${env.BUILD_NUMBER}.json | cut -d'\"' -f4",
                                returnStdout: true
                            ).trim()

                            if (!token) {
                                echo "❌ Réponse login complète:"
                                sh "cat /tmp/caprover_login_${env.BUILD_NUMBER}.json || true"
                                error("❌ Token CapRover introuvable dans la réponse de login.")
                            }

                            echo "✅ Authentifié sur CapRover (${envConfig.label})"

                            // Étape 2 : Upload du tarball → CapRover build & deploy
                            def deployStatus = sh(
                                script: """
                                    set +x
                                    curl -sf --insecure -X POST \\
                                        "${captainUrl}/api/v2/user/apps/appData/${appName}" \\
                                        -H "x-captain-auth: ${token}" \\
                                        -F "sourceFile=@/tmp/${tarFile}" \\
                                        -o /tmp/caprover_deploy_${env.BUILD_NUMBER}.json
                                    set -x
                                """,
                                returnStatus: true
                            )

                            if (deployStatus != 0) {
                                sh "cat /tmp/caprover_deploy_${env.BUILD_NUMBER}.json || true"
                                error("❌ CapRover deployment API call failed.")
                            }

                            // Nettoyage des fichiers temporaires
                            sh """
                                rm -f /tmp/${tarFile} \
                                      /tmp/caprover_login_${env.BUILD_NUMBER}.json \
                                      /tmp/caprover_deploy_${env.BUILD_NUMBER}.json
                            """

                            echo "✅ '${appName}' deployed successfully to ${envConfig.label}!"
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
