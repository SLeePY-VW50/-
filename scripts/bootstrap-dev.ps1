. "$PSScriptRoot\dev-env.ps1"

if (-not (Test-Port 3307) -or -not (Test-Port 6379)) {
    & 'D:\lab-reservation\scripts\start-all.ps1'
}
if (-not (Wait-Port 3307)) { throw 'MySQL 8 (3307) 未就绪。' }
if (-not (Wait-Port 6379)) { throw 'Redis (6379) 未就绪。' }

$env:MYSQL_PWD = $env:LAB_MYSQL_ROOT_PASSWORD
$mysql = 'D:\lab-reservation\mysql\mysql-8.0.46-winx64\bin\mysql.exe'
& $mysql '--host=127.0.0.1' '--port=3307' '--user=lab_admin' '--execute=CREATE DATABASE IF NOT EXISTS lab_reservation_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS ''lab_app''@''localhost''; CREATE USER IF NOT EXISTS ''lab_app''@''%''; GRANT ALL PRIVILEGES ON lab_reservation_test.* TO ''lab_app''@''localhost''; GRANT ALL PRIVILEGES ON lab_reservation_test.* TO ''lab_app''@''%'';'
if ($LASTEXITCODE -ne 0) { throw '隔离测试库准备失败。' }
Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue

if (-not (Test-Path (Join-Path $Root 'ai-service\.venv\Scripts\python.exe'))) {
    & python -m venv (Join-Path $Root 'ai-service\.venv')
    if ($LASTEXITCODE -ne 0) { throw 'AI 虚拟环境创建失败。' }
}
& (Join-Path $Root 'ai-service\.venv\Scripts\python.exe') -m pip install -r (Join-Path $Root 'ai-service\requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'AI 依赖安装失败。' }

Push-Location (Join-Path $Root 'web')
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
} finally {
    Pop-Location
}

Write-Output '开发依赖已准备完成，可运行 scripts/start-dev.ps1。'
