param([switch]$SkipInfrastructure)

. "$PSScriptRoot\dev-env.ps1"
$runDir = Join-Path $Root '.run'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

if (-not $SkipInfrastructure) {
    if (-not (Test-Port 3307) -or -not (Test-Port 6379)) {
        & 'D:\lab-reservation\scripts\start-all.ps1'
    }
    if (-not (Wait-Port 3307)) { throw 'MySQL 8 (3307) 未就绪，请检查便携环境日志。' }
    if (-not (Wait-Port 6379)) { throw 'Redis (6379) 未就绪，请检查便携环境日志。' }
}

if (-not (Test-Path (Join-Path $Root 'web\node_modules'))) { throw '请先运行 scripts/bootstrap-dev.ps1 安装前端依赖。' }
if (-not (Test-Path (Join-Path $Root 'ai-service\.venv\Scripts\python.exe'))) { throw '请先运行 scripts/bootstrap-dev.ps1 安装 AI 依赖。' }

$applications = @(
    @{ Name = 'server'; Port = 8080; Working = Join-Path $Root 'server'; Executable = 'D:\lab-reservation\maven\apache-maven-3.10.0\bin\mvn.cmd'; Arguments = @('--batch-mode', 'spring-boot:run') },
    @{ Name = 'ai-service'; Port = 8000; Working = Join-Path $Root 'ai-service'; Executable = Join-Path $Root 'ai-service\.venv\Scripts\python.exe'; Arguments = @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000') },
    @{ Name = 'web'; Port = 5173; Working = Join-Path $Root 'web'; Executable = 'npm.cmd'; Arguments = @('run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173') }
)

foreach ($application in $applications) {
    if (Test-Port $application.Port) {
        Write-Output "$($application.Name) 已在 $($application.Port) 端口运行。"
        continue
    }
    $stdout = Join-Path $runDir "$($application.Name).out.log"
    $stderr = Join-Path $runDir "$($application.Name).err.log"
    $process = Start-Process -FilePath $application.Executable `
        -ArgumentList $application.Arguments `
        -WorkingDirectory $application.Working `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -WindowStyle Hidden -PassThru
    Set-Content -Path (Join-Path $runDir "$($application.Name).pid") -Value $process.Id -Encoding ascii
}

if (-not (Wait-Port 8080 90)) { throw 'Java 服务启动超时，请查看 .run/server.*.log。' }
if (-not (Wait-Port 8000 45)) { throw 'AI 服务启动超时，请查看 .run/ai-service.*.log。' }
if (-not (Wait-Port 5173 45)) { throw 'Vue 服务启动超时，请查看 .run/web.*.log。' }

Write-Output '项目已启动：'
Write-Output '  电脑端：http://127.0.0.1:5173'
Write-Output '  签到页：http://127.0.0.1:5173/terminal'
Write-Output '  AI健康：http://127.0.0.1:8000/api/ai/health'
Write-Output '  日志目录：.run'
