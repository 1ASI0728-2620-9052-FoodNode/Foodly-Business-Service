[CmdletBinding()]
param(
    [ValidateSet('Start', 'Status', 'Stop', 'Verify')]
    [string]$Action = 'Status',
    [string]$MongodPath = $env:FOODLY_MONGOD,
    [switch]$WriteVerificationData
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeRoot = Join-Path $projectRoot '.local/mongodb-rs0-27018'
$dataPath = Join-Path $runtimeRoot 'data'
$markerPath = Join-Path $runtimeRoot 'instance.json'
$mongoUri = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'
$javaPath = (Get-Command java -ErrorAction Stop).Source

function Test-DevelopmentPort {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync('127.0.0.1', 27018).Wait(500) -and $socket.Connected }
    catch { return $false }
    finally { $socket.Dispose() }
}

function Assert-InstanceMarker {
    if (!(Test-Path -LiteralPath $markerPath -PathType Leaf)) {
        throw "No management marker at $markerPath. Refusing to adopt or stop an unknown instance."
    }
    $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
    if ($marker.port -ne 27018 -or $marker.replicaSet -ne 'rs0' -or $marker.dataPath -ne $dataPath) {
        throw 'Instance marker does not match this workspace. No data or process has been changed.'
    }
}

if ($Action -eq 'Verify' -and !$WriteVerificationData) {
    throw 'Verify retains one new huarique and its Outbox event. Use -WriteVerificationData only after authorizing those new records.'
}

# Resolve dependencies through the existing pom; the helper has no additional dependencies.
Push-Location $projectRoot
try {
    $classPathFile = Join-Path $projectRoot 'target/mongodb-classpath.txt'
    if (!(Test-Path -LiteralPath $classPathFile) -or !(Test-Path -LiteralPath 'target/classes/com/foodly/business/BusinessServiceApplication.class')) {
        & (Join-Path $projectRoot 'mvnw.cmd') -q compile dependency:build-classpath "-Dmdep.outputFile=$classPathFile"
        if ($LASTEXITCODE -ne 0) { throw 'Maven could not prepare the MongoDB helper classpath.' }
    }
    $dependencyPath = (Get-Content -LiteralPath $classPathFile -Raw).Trim()
    $helperClassPath = (Join-Path $projectRoot 'target/classes') + [IO.Path]::PathSeparator + $dependencyPath
    $helperSource = Join-Path $PSScriptRoot 'java/MongoDevelopment.java'
    $helperClasses = Join-Path $projectRoot 'target/mongodb-helper'
    $helperMain = 'com.foodly.business.tools.MongoDevelopment'
    $helperClass = Join-Path $helperClasses 'com/foodly/business/tools/MongoDevelopment.class'
    if (!(Test-Path -LiteralPath $helperClass) -or (Get-Item -LiteralPath $helperSource).LastWriteTime -gt (Get-Item -LiteralPath $helperClass).LastWriteTime) {
        New-Item -ItemType Directory -Path $helperClasses -Force | Out-Null
        $compiler = Join-Path (Split-Path $javaPath) 'javac.exe'
        & $compiler --release 17 -cp $helperClassPath -d $helperClasses $helperSource
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile the MongoDB development helper. A JDK 17 installation is required.' }
    }
    $helperClassPath = $helperClasses + [IO.Path]::PathSeparator + $helperClassPath

    if ($Action -eq 'Start') {
        if (Test-Path -LiteralPath $runtimeRoot) {
            Assert-InstanceMarker
        } else {
            if (Test-DevelopmentPort) { throw 'Port 27018 is occupied. No existing process will be changed.' }
            New-Item -ItemType Directory -Path $dataPath -Force | Out-Null
            [pscustomobject]@{ port = 27018; replicaSet = 'rs0'; dataPath = $dataPath } |
                ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding UTF8
        }
        if (!(Test-DevelopmentPort)) {
            if ([string]::IsNullOrWhiteSpace($MongodPath)) { $MongodPath = (Get-Command mongod -ErrorAction Stop).Source }
            $MongodPath = (Resolve-Path -LiteralPath $MongodPath).Path
            $logPath = Join-Path $runtimeRoot 'mongod.log'
            $arguments = @('--dbpath', ('"{0}"' -f $dataPath), '--port', '27018', '--bind_ip', '127.0.0.1',
                '--replSet', 'rs0', '--logpath', ('"{0}"' -f $logPath), '--logappend')
            # A separate user process: no Windows service installation or administrative changes.
            $mongoProcess = Start-Process -FilePath $MongodPath -ArgumentList $arguments -WindowStyle Hidden -PassThru
            Write-Output "Started dedicated MongoDB process $($mongoProcess.Id). Data: $dataPath"
        }
        & $javaPath --class-path $helperClassPath $helperMain init $dataPath
    } else {
        Assert-InstanceMarker
        if (!(Test-DevelopmentPort)) {
            if ($Action -eq 'Stop') { Write-Output 'The dedicated instance is already stopped. Data remains on disk.'; return }
            throw 'The dedicated MongoDB instance is not running. Use -Action Start.'
        }
        if ($Action -eq 'Verify') {
            if ($env:MONGODB_URI -and $env:MONGODB_URI -ne $mongoUri) {
                throw 'Verify only permits the dedicated development URI on 127.0.0.1:27018. Set MONGODB_URI accordingly.'
            }
            $env:MONGODB_URI = $mongoUri
        }
        & $javaPath --class-path $helperClassPath $helperMain $Action.ToLowerInvariant() $dataPath
    }
    if ($LASTEXITCODE -ne 0) { throw "MongoDB helper failed with exit code $LASTEXITCODE. Data has not been removed." }
    if ($Action -eq 'Stop') {
        for ($attempt = 0; $attempt -lt 30 -and (Test-DevelopmentPort); $attempt++) { Start-Sleep -Milliseconds 500 }
        if (Test-DevelopmentPort) { throw 'Port 27018 is still open; no process was force-killed.' }
        Write-Output 'Dedicated MongoDB stopped. Persistent data is retained.'
    } else {
        Write-Output "MONGODB_URI=$mongoUri"
    }
} finally {
    Pop-Location
}
