param (
  [switch]$Clean,
  [switch]$Vector
)

# ============================================================
# 渲染 doc\uml 下全部 PlantUML 源文件为 PNG（可选 SVG）
# 用法（本文件保留 UTF-8 BOM，以兼容 Windows PowerShell 5.1）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\render-all.ps1 -Vector
#   pwsh -File .\render-all.ps1 -Vector    # PowerShell 7 可用此命令
#   默认仅输出 PNG；-Vector 同时输出 SVG；-Clean 先清理临时测试产物
# ============================================================

$ErrorActionPreference = 'Stop'
$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar       = 'E:\yyy\tools\plantuml-full.jar'
$umlRoot   = Join-Path $root 'uml'

if (-not (Test-Path $jar)) { throw "找不到 PlantUML jar：$jar" }

$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

if ($Clean) {
  Get-ChildItem $umlRoot -Recurse -File |
    Where-Object { $_.Name -like '_test*' -or $_.Name -like '_probe*' -or $_.Name -like '_v?.puml' } |
    Remove-Item -Force
  Write-Host '已清理临时测试产物。'
}

$sources = Get-ChildItem $umlRoot -Recurse -File -Filter *.puml |
  Where-Object { $_.Name -notlike '_style*' -and $_.Name -notlike '_test*' -and $_.Name -notlike '_probe*' -and $_.Name -notlike '_v?.puml' } |
  Sort-Object FullName

if (-not $sources) { throw "在 $umlRoot 下没有找到 .puml 文件" }

Write-Host ("共 {0} 个图源文件，开始渲染…" -f $sources.Count)
$formats = @('-tpng')
if ($Vector) { $formats += '-tsvg' }

foreach ($fmt in $formats) {
  Write-Host ("--- 格式 {0} ---" -f $fmt)
  $batch = $sources | ForEach-Object { $_.FullName }
  & java -jar $jar -charset UTF-8 $fmt $batch
  if ($LASTEXITCODE -ne 0) { Write-Warning ("{0} 渲染返回码 {1}" -f $fmt, $LASTEXITCODE) }
}

Write-Host ''
Write-Host '渲染结果：'
Get-ChildItem $umlRoot -Recurse -File -Include *.png, *.svg |
  Where-Object { $_.Name -notlike '_test*' -and $_.Name -notlike '_probe*' -and $_.Name -notlike '_v?.*' } |
  Sort-Object FullName |
  ForEach-Object {
    $rel = $_.FullName.Substring($root.Length + 1)
    Write-Host ("  {0,-52} {1,8:N0} KB" -f $rel, ($_.Length / 1KB))
  }