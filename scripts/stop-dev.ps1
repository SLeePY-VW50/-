. "$PSScriptRoot\dev-env.ps1"

function Test-LauncherOwnership([int]$launcherPid, [int]$processId) {
    if ($launcherPid -le 0) { return $false }
    $currentId = $processId
    for ($depth = 0; $depth -lt 12 -and $currentId -gt 0; $depth++) {
        if ($currentId -eq $launcherPid) { return $true }
        $parent = (Get-CimInstance Win32_Process -Filter "ProcessId=$currentId" -ErrorAction SilentlyContinue).ParentProcessId
        if ($null -eq $parent) { return $false }
        $currentId = [int]$parent
    }
    return $false
}

foreach ($port in @(5173, 8000, 8080)) {
    $pidName = switch ($port) { 5173 { 'web' } 8000 { 'ai-service' } 8080 { 'server' } }
    $pidFile = Join-Path $Root ".run\$pidName.pid"
    $launcherPid = if (Test-Path $pidFile) { [int](Get-Content $pidFile -Raw).Trim() } else { 0 }
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        $processId = $connection.OwningProcess
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        $commandLine = [string]$process.CommandLine
        $isProjectProcess = $commandLine.Contains($Root) -or $commandLine.Contains('lab-reservation-server') -or (Test-LauncherOwnership $launcherPid $processId)
        if ($isProjectProcess) {
            & taskkill.exe /PID $processId /T /F | Out-Null
            Write-Output "已停止端口 $port 的项目进程 $processId。"
        } else {
            Write-Warning "端口 $port 不属于本项目，未停止。"
        }
    }
}
