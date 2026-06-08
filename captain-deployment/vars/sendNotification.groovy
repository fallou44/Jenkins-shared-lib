/**
 * sendNotification.groovy
 * ─────────────────────────────────────────────────────────
 * Sends an HTML email notification after a CapRover deployment.
 *
 * @param params Map with the following keys:
 *   - status       : 'success' | 'failure'  (required)
 *   - appName      : application name        (required)
 *   - environment  : 'DEV' | 'PROD'          (required)
 *   - notifyEmails : semicolon-separated list of recipient emails
 *   - fromEmail    : sender email address
 */
def call(Map params) {
    def status       = params.status
    def appName      = params.appName ?: 'unknown-app'
    def environment  = params.environment ?: 'UNKNOWN'
    def notifyEmails = params.notifyEmails ?: env.NOTIFY_EMAIL_DEFAULT
    def fromEmail    = params.fromEmail    ?: env.FROM_MAIL

    // Silently skip if no recipients are configured
    if (!notifyEmails) {
        echo "⚠️  No notification emails configured — skipping email step."
        return
    }

    def recipients  = notifyEmails.split(';').collect { "<${it.trim()}>" }.join(', ')
    def buildUrl    = env.BUILD_URL    ?: 'N/A'
    def buildNumber = env.BUILD_NUMBER ?: 'N/A'
    def gitBranch   = env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'unknown'
    def timestamp   = new Date().format("yyyy-MM-dd HH:mm:ss 'UTC'", TimeZone.getTimeZone('UTC'))

    def envColor  = (environment == 'PROD') ? '#e53e3e' : '#d69e2e'
    def envBadge  = """<span style="background:${envColor};color:#fff;padding:2px 8px;
                        border-radius:4px;font-size:12px;font-weight:bold;">${environment}</span>"""

    if (status == 'success') {
        emailext(
            subject: "✅ [${environment}] Deployed: ${appName} — Build #${buildNumber}",
            mimeType: 'text/html',
            to: recipients,
            from: fromEmail,
            replyTo: fromEmail,
            attachLog: false,
            body: """
<!DOCTYPE html>
<html>
<body style="margin:0;padding:0;font-family:Arial,Helvetica,sans-serif;background:#f4f6f9;color:#2d3748;">
  <div style="max-width:600px;margin:32px auto;background:#fff;border-radius:8px;overflow:hidden;
              box-shadow:0 2px 8px rgba(0,0,0,0.08);">
    <!-- Header -->
    <div style="background:#276749;padding:24px 32px;">
      <h1 style="margin:0;color:#fff;font-size:20px;">✅ Deployment Successful</h1>
      <p style="margin:4px 0 0;color:#c6f6d5;font-size:13px;">CapRover Automated Deployment</p>
    </div>
    <!-- Body -->
    <div style="padding:24px 32px;">
      <table style="width:100%;border-collapse:collapse;font-size:14px;">
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;width:140px;">Application</td>
          <td style="padding:10px 0;font-weight:bold;">${appName}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Environment</td>
          <td style="padding:10px 0;">${envBadge}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Branch</td>
          <td style="padding:10px 0;"><code style="background:#edf2f7;padding:2px 6px;border-radius:3px;">${gitBranch}</code></td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Build</td>
          <td style="padding:10px 0;">#${buildNumber}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Deployed At</td>
          <td style="padding:10px 0;">${timestamp}</td>
        </tr>
      </table>
      <div style="margin-top:24px;">
        <a href="${buildUrl}" style="background:#276749;color:#fff;padding:10px 20px;
           border-radius:6px;text-decoration:none;font-size:13px;">View Build Logs →</a>
      </div>
    </div>
    <div style="padding:16px 32px;background:#f7fafc;font-size:11px;color:#a0aec0;text-align:center;">
      Sent by Jenkins CI/CD • ${appName} Pipeline
    </div>
  </div>
</body>
</html>
            """
        )
    } else {
        emailext(
            subject: "❌ [${environment}] Failed: ${appName} — Build #${buildNumber}",
            mimeType: 'text/html',
            to: recipients,
            from: fromEmail,
            replyTo: fromEmail,
            attachLog: true,
            compressLog: true,
            body: """
<!DOCTYPE html>
<html>
<body style="margin:0;padding:0;font-family:Arial,Helvetica,sans-serif;background:#f4f6f9;color:#2d3748;">
  <div style="max-width:600px;margin:32px auto;background:#fff;border-radius:8px;overflow:hidden;
              box-shadow:0 2px 8px rgba(0,0,0,0.08);">
    <!-- Header -->
    <div style="background:#9b2c2c;padding:24px 32px;">
      <h1 style="margin:0;color:#fff;font-size:20px;">❌ Deployment Failed</h1>
      <p style="margin:4px 0 0;color:#fed7d7;font-size:13px;">CapRover Automated Deployment</p>
    </div>
    <!-- Body -->
    <div style="padding:24px 32px;">
      <table style="width:100%;border-collapse:collapse;font-size:14px;">
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;width:140px;">Application</td>
          <td style="padding:10px 0;font-weight:bold;">${appName}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Environment</td>
          <td style="padding:10px 0;">${envBadge}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Branch</td>
          <td style="padding:10px 0;"><code style="background:#edf2f7;padding:2px 6px;border-radius:3px;">${gitBranch}</code></td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Build</td>
          <td style="padding:10px 0;">#${buildNumber}</td>
        </tr>
        <tr style="border-bottom:1px solid #e2e8f0;">
          <td style="padding:10px 0;color:#718096;">Failed At</td>
          <td style="padding:10px 0;">${timestamp}</td>
        </tr>
      </table>
      <div style="margin-top:16px;background:#fff5f5;border-left:4px solid #fc8181;padding:12px 16px;
                  border-radius:0 4px 4px 0;font-size:13px;color:#742a2a;">
        The build log is attached to this email. Please investigate and fix the issue.
      </div>
      <div style="margin-top:24px;">
        <a href="${buildUrl}" style="background:#9b2c2c;color:#fff;padding:10px 20px;
           border-radius:6px;text-decoration:none;font-size:13px;">View Build Logs →</a>
      </div>
    </div>
    <div style="padding:16px 32px;background:#f7fafc;font-size:11px;color:#a0aec0;text-align:center;">
      Sent by Jenkins CI/CD • ${appName} Pipeline
    </div>
  </div>
</body>
</html>
            """
        )
    }
}
