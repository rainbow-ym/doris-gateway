param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$javaCompiler = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$javaRunner = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$outputDirectory = Join-Path $PSScriptRoot 'target/test-classes'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javaCompiler -encoding UTF-8 -source 8 -target 8 -d $outputDirectory $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& $javaRunner -ea -cp $outputDirectory com.example.doris.gateway.observability.RecorderTest
if ($LASTEXITCODE -ne 0) { throw 'Java tests failed' }
node --test (Join-Path $PSScriptRoot 'preview/server.test.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Preview tests failed' }
