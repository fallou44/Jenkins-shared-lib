/**
 * sharedPipeline.groovy — CapRover Deployment Pipeline
 * ─────────────────────────────────────────────────────────────────────────
 * Centralized deployment pipeline for all company services targeting CapRover.
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
 *     appName          : 'my-api',
 *     notifyEmails     : 'team@company.com;ops@company.com',
 *     deploymentTimeout: '180'
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

    // ── Resolve branch → target environment ─────────────────────────────
    def envConfig = detectEnvironment(config)

    // ─────────────────────────────────────────────────────────────────────
    pipeline {
        // 'agent any' = utilise le premier nœud Jenkins disponible.
        // Compatible avec tout Jenkins sans plugin Docker.
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
            // Immediately stops the pipeline if the branch is not deployable.
            // This avoids wasting agent resources on feature branches.
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
            // Installs caprover CLI if not already present, then verifies tools.
            stage('Pre-flight Check') {
                steps {
                    script {
                        // Installe caprover CLI si non disponible sur le nœud
                        def caproverInstalled = sh(
                            script: 'command -v caprover >/dev/null 2>&1 && echo "yes" || echo "no"',
                            returnStdout: true
                        ).trim()

                        if (caproverInstalled == 'no') {
                            echo '📦 caprover CLI non trouvé — installation via npm...'
                            sh 'npm install -g caprover'
                        }

                        sh 'caprover --version'
                        sh 'git --version'
                        echo '✅ Pre-flight checks passed'
                    }
                }
            }

            // ── 3. Deploy to CapRover ─────────────────────────────────────
            // Runs `caprover deploy` using the resolved URL and credential.
            // The password is injected via withCredentials — never echoed.
            stage('Deploy to CapRover') {
                steps {
                    script {
                        echo "🚀 Deploying '${appName}' → ${envConfig.label} (${envConfig.captainUrl})"
                        withCredentials([string(credentialsId: envConfig.credentialId, variable: 'CAPTAIN_PASSWORD')]) {
                            sh """
                                set +x
                                caprover deploy \\
                                    --host     ${envConfig.captainUrl} \\
                                    --password \${CAPTAIN_PASSWORD} \\
                                    --branch   ${envConfig.branch} \\
                                    --appName  ${appName}
                                set -x
                            """
                        }
                        echo "✅ '${appName}' successfully deployed to ${envConfig.label}!"
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
                    // Guard: don't send failure email for intentional skips
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
