# Rebuild the NeoFormRuntime launcher that injects reachable Maven mirrors.
# Requires JDK 21 (javac/jar on PATH) and neoform-runtime-*-all.jar in the Gradle cache.
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$nfrtJar = Get-ChildItem -Path (Join-Path $gradleHome 'caches\modules-2\files-2.1\net.neoforged\neoform-runtime') -Recurse -Filter 'neoform-runtime-*-all.jar' |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $nfrtJar) {
    throw 'neoform-runtime all.jar not found. Run Gradle once so the dependency is downloaded.'
}

$classes = Join-Path $scriptDir 'classes'
$output = Join-Path $scriptDir 'neoform-runtime-launcher.jar'
New-Item -ItemType Directory -Path $classes -Force | Out-Null

& javac -encoding UTF-8 -cp $nfrtJar -d $classes (Join-Path $scriptDir 'NfrtLauncher.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Copy-Item $nfrtJar $output -Force
& jar --update --file $output --main-class NfrtLauncher -C $classes .
Write-Host "Generated $output"
