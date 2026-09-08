# Only the processor receives these values. Do not print settings: SMTP credentials
# may be present. The private file is optional; process environment takes precedence.
function Get-DemoNotificationSettingNames {
    return @('NOTIFICATION_MODE', 'NOTIFICATION_FROM', 'NOTIFICATION_RECIPIENTS',
        'NOTIFICATION_SMTP_HOST', 'NOTIFICATION_SMTP_PORT', 'NOTIFICATION_SMTP_USERNAME',
        'NOTIFICATION_SMTP_PASSWORD', 'NOTIFICATION_SMTP_TLS', 'NOTIFICATION_MAX_PER_MINUTE')
}

function Get-DemoNotificationEnvironment {
    param([string]$LocalDirectory)
    $allowed = Get-DemoNotificationSettingNames
    $settings = @{
        NOTIFICATION_MODE = 'mailpit'
        NOTIFICATION_FROM = 'record-integrity@example.test'
        NOTIFICATION_RECIPIENTS = 'security@example.test'
        NOTIFICATION_SMTP_HOST = '127.0.0.1'
        NOTIFICATION_SMTP_PORT = '1025'
        NOTIFICATION_SMTP_TLS = 'starttls'
        NOTIFICATION_MAX_PER_MINUTE = '30'
    }
    $path = Join-Path $LocalDirectory 'notifications.json'
    if (Test-Path -LiteralPath $path) {
        try { $configured = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json }
        catch { throw 'Invalid .local/notifications.json. Expected one object of notification settings.' }
        if ($null -eq $configured -or $configured -isnot [pscustomobject]) {
            throw 'Notification settings must be a JSON object.'
        }
        foreach ($property in $configured.PSObject.Properties) {
            if ($property.Name -cnotin $allowed) { throw 'Unsupported notification setting in private configuration.' }
            if ($null -eq $property.Value -or $property.Value -isnot [string]) {
                throw 'Notification configuration values must be strings.'
            }
            $settings[$property.Name] = $property.Value
        }
    }
    foreach ($name in $allowed) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($null -ne $value) { $settings[$name] = $value }
    }
    if ($settings.NOTIFICATION_MODE -cnotin @('disabled', 'mailpit', 'smtp')) {
        throw 'NOTIFICATION_MODE must be disabled, mailpit, or smtp.'
    }
    if ($settings.NOTIFICATION_MODE -eq 'smtp') {
        foreach ($name in @('NOTIFICATION_SMTP_USERNAME', 'NOTIFICATION_SMTP_PASSWORD')) {
            if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
                throw 'Real SMTP requires credentials supplied privately; do not paste them into chat.'
            }
        }
        if ($settings.NOTIFICATION_SMTP_HOST -in @('127.0.0.1', '::1', 'localhost') -or
                $settings.NOTIFICATION_FROM.EndsWith('@example.test') -or
                $settings.NOTIFICATION_RECIPIENTS -eq 'security@example.test') {
            throw 'Real SMTP requires an explicit relay host, authorized sender, and intended recipients.'
        }
    }
    return $settings
}
