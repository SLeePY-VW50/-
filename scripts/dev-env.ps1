$Root = Split-Path -Parent $PSScriptRoot

function Import-UserSecret([string]$Name, [string]$TargetName = $Name) {
    $value = [Environment]::GetEnvironmentVariable($Name, 'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        [Environment]::SetEnvironmentVariable($TargetName, $value, 'Process')
    }
}

Import-UserSecret 'LAB_DB_PASSWORD'
Import-UserSecret 'LAB_MYSQL_ROOT_PASSWORD'
Import-UserSecret 'LAB_REDIS_PASSWORD'
Import-UserSecret 'WECHAT_APP_ID'
Import-UserSecret 'WECHAT_APP_SECRET'
Import-UserSecret 'WECHAT_CHECKIN_QR_SECRET'
$env:REDIS_PASSWORD = $env:LAB_REDIS_PASSWORD
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
$env:REDIS_DB = '0'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SPRING_INTERNAL_BASE_URL = 'http://127.0.0.1:8080'
$env:AI_CORS_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
if ([string]::IsNullOrWhiteSpace($env:AI_INTERNAL_TOKEN)) { $env:AI_INTERNAL_TOKEN = 'local-ai-internal-token' }
if ([string]::IsNullOrWhiteSpace($env:LAB_JWT_SECRET)) { $env:LAB_JWT_SECRET = 'local-only-jwt-secret-with-at-least-32-bytes' }

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(600) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Port([int]$Port, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
