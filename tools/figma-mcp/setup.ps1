# 重新安装 Figma MCP 的 stdio 桥依赖。
# vendor/ 与 headers.txt 都不入库；换机器或清缓存后跑一次本脚本即可。
#   pwsh -File tools/figma-mcp/setup.ps1
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$vendor = Join-Path $here "vendor"
New-Item -ItemType Directory -Force -Path $vendor | Out-Null

Push-Location $vendor
try {
    npm install mcp-remote --no-audit --no-fund --loglevel=error
} finally {
    Pop-Location
}

$proxy = Join-Path $vendor "node_modules/mcp-remote/dist/proxy.js"
if (-not (Test-Path $proxy)) { throw "mcp-remote 安装失败：$proxy 不存在" }

$headers = Join-Path $here "headers.txt"
if (-not (Test-Path $headers)) { throw "缺少 headers.txt：请按该文件内的说明填入 X-Figma-Token" }

Write-Host "OK  proxy: $proxy"
Write-Host "OK  headers: $headers"
Write-Host "填好 token 后重启 DeepSeek Harness 生效。"
