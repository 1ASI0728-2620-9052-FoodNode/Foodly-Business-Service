[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$integrationRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Load credentials without displaying them. Java receives no secret command-line parameters.
. (Join-Path $PSScriptRoot 'local-auth.ps1') -Action Load
. (Join-Path $PSScriptRoot 'rabbitmq.ps1') -Action Load
$env:OUTBOX_ENABLED = 'true'
foreach ($port in 8082) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { $occupied = $socket.ConnectAsync('127.0.0.1', $port).Wait(500) -and $socket.Connected }
    catch { $occupied = $false }
    finally { $socket.Dispose() }
    if ($occupied) { throw "Port $port is occupied; existing HTTP servers will not be stopped." }
}
Push-Location $integrationRoot
try {
    $classpathFile = Join-Path $integrationRoot 'target/rabbitmq-classpath.txt'
    & .\mvnw.cmd -q compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Maven could not prepare the real integration probe.' }
    $classes = Join-Path $integrationRoot 'target/rabbitmq-helper'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $dependencies = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $classpath = (Join-Path $integrationRoot 'target/classes') + [IO.Path]::PathSeparator + $dependencies
    $java = (Get-Command java -ErrorAction Stop).Source
    $compiler = Join-Path (Split-Path $java) 'javac.exe'
    & $compiler --release 17 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'java/RabbitOutboxVerification.java')
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile the integration probe.' }
    $classpath = $classes + [IO.Path]::PathSeparator + $classpath
    $privateLog = Join-Path $integrationRoot '.local/rabbitmq/integration.log'
    $ErrorActionPreference = 'Continue'
    & $java -cp $classpath com.foodly.business.tools.RabbitOutboxVerification $integrationRoot 2>&1 | ForEach-Object {
        $safe = $_.ToString().Replace($env:IAM_JWT_SECRET_BASE64, '[REDACTED]').Replace($env:RABBITMQ_PASSWORD, '[REDACTED]')
        $safe | Add-Content -LiteralPath $privateLog -Encoding UTF8
        Write-Output $safe
    }
    $result = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($result -ne 0) { throw 'Real integration verification failed. Inspect the private report/log; no records were removed.' }
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
