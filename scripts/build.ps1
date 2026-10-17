$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
Push-Location $workspace
try {
    mvn -B clean verify
} finally {
    Pop-Location
}

if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed with exit code $LASTEXITCODE."
}

New-Item -ItemType Directory -Force -Path "$workspace/hardening-lib" | Out-Null
Copy-Item -Force `
    "$workspace/target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar" `
    "$workspace/hardening-lib/"

Write-Host "Hardened plugin copied to $workspace/hardening-lib"
