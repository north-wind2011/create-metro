# 生成 net.neoforged:minecraft-dependencies:1.21.1 的本地 BOM。
# 背景：maven.neoforged.net 不可达，无法直接下载官方 BOM；
# ModDevGradle 需要该 BOM 以解析 Minecraft 的全部库（含 LWJGL 的 natives）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/gen-minecraft-bom.ps1 `
#     -VersionManifest "D:\...\minecraft_1.21.1_version_manifest.json"
#
# 版本清单可来自 NeoFormRuntime 缓存：
#   <GRADLE_USER_HOME>\caches\neoformruntime\artifacts\minecraft_1.21.1_version_manifest.json
param(
    [Parameter(Mandatory = $true)]
    [string]$VersionManifest,
    [string]$Output = "local-maven/net/neoforged/minecraft-dependencies/1.21.1/minecraft-dependencies-1.21.1.pom"
)

$ErrorActionPreference = 'Stop'
$json = Get-Content -Path $VersionManifest -Raw | ConvertFrom-Json

$builder = New-Object System.Text.StringBuilder
[void]$builder.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$builder.AppendLine('<project xmlns="http://maven.apache.org/POM/4.0.0">')
[void]$builder.AppendLine('  <modelVersion>4.0.0</modelVersion>')
[void]$builder.AppendLine('  <groupId>net.neoforged</groupId>')
[void]$builder.AppendLine('  <artifactId>minecraft-dependencies</artifactId>')
[void]$builder.AppendLine('  <version>1.21.1</version>')
[void]$builder.AppendLine('  <packaging>pom</packaging>')
[void]$builder.AppendLine('  <description>Generated from the Minecraft 1.21.1 version manifest. Includes natives classifiers (LWJGL etc.) so that runClient has the native libraries on its classpath.</description>')
[void]$builder.AppendLine('  <dependencies>')

$count = 0
foreach ($library in $json.libraries) {
    $parts = $library.name.Split(':')
    if ($parts.Count -ne 3 -and $parts.Count -ne 4) { continue }
    $count++
    [void]$builder.AppendLine('    <dependency>')
    [void]$builder.AppendLine("      <groupId>$($parts[0])</groupId>")
    [void]$builder.AppendLine("      <artifactId>$($parts[1])</artifactId>")
    [void]$builder.AppendLine("      <version>$($parts[2])</version>")
    if ($parts.Count -eq 4) {
        [void]$builder.AppendLine("      <classifier>$($parts[3])</classifier>")
    }
    [void]$builder.AppendLine('    </dependency>')
}

[void]$builder.AppendLine('  </dependencies>')
[void]$builder.AppendLine('</project>')

$dir = Split-Path -Parent $Output
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
[System.IO.File]::WriteAllText((Resolve-Path $dir).Path + "\" + (Split-Path -Leaf $Output), $builder.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $Output with $count dependencies."
