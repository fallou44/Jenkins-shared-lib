/**
 * detectEnvironment.groovy
 * ─────────────────────────────────────────────────────────
 * Resolves the target deployment environment based on the
 * current Git branch name.
 *
 * Branch routing rules:
 *   main    → PROD  (uses CAPTAIN_URL_PROD + caprover-prod-password)
 *   develop → DEV   (uses CAPTAIN_URL_DEV  + caprover-dev-password)
 *   other   → SKIP  (pipeline is aborted gracefully)
 *
 * @param overrides  Optional map to manually override captainUrl or credentialId
 * @return Map { environment, label, captainUrl, credentialId, branch }
 */
def call(Map overrides = [:]) {
    // BRANCH_NAME is set by the Multibranch Pipeline plugin (preferred).
    // GIT_BRANCH is set by the Git plugin and may carry the 'origin/' prefix.
    def rawBranch = env.BRANCH_NAME ?: env.GIT_BRANCH ?: ''
    def branch    = rawBranch.replaceAll('^origin/', '').trim()

    echo "🔍 Detected branch: '${branch}'"

    def envConfig = [:]

    switch (branch) {
        case 'main':
            envConfig = [
                environment : 'prod',
                label       : 'PROD',
                captainUrl  : overrides.captainUrl  ?: env.CAPTAIN_URL_PROD,
                credentialId: overrides.credentialId ?: 'caprover-prod-password'
            ]
            break

        case 'develop':
            envConfig = [
                environment : 'dev',
                label       : 'DEV',
                captainUrl  : overrides.captainUrl  ?: env.CAPTAIN_URL_DEV,
                credentialId: overrides.credentialId ?: 'caprover-dev-password'
            ]
            break

        default:
            envConfig = [
                environment : 'skip',
                label       : 'NONE',
                captainUrl  : null,
                credentialId: null
            ]
            break
    }

    envConfig.branch = branch
    return envConfig
}
