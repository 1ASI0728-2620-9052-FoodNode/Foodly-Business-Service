[CmdletBinding()]
param(
    [switch]$PrepareOnly,
    [switch]$ExternalOperationsApproved
)

$ErrorActionPreference = 'Stop'
if (!$PrepareOnly -and !$ExternalOperationsApproved) {
    throw 'Explicit authorization is required: this probe uploads one PNG, reads its metadata and deletes only its own new asset. Use -PrepareOnly to compile without external operations.'
}
$probeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$PrepareOnly) { . (Join-Path $PSScriptRoot 'cloudinary.ps1') -Action Load }
Push-Location $probeRoot
try {
    $classpathFile = Join-Path $probeRoot 'target/cloudinary-classpath.txt'
    # compile does not repackage/replace the JAR used by an existing server.
    & .\mvnw.cmd -q compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Maven could not prepare the Cloudinary probe.' }
    $classes = Join-Path $probeRoot 'target/cloudinary-helper'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $classpath = (Join-Path $probeRoot 'target/classes') + [IO.Path]::PathSeparator + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $java = (Get-Command java -ErrorAction Stop).Source
    $compiler = Join-Path (Split-Path $java) 'javac.exe'
    & $compiler --release 17 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'java/CloudinaryVerification.java')
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile the Cloudinary probe.' }
    $classpath = $classes + [IO.Path]::PathSeparator + $classpath
    $probeAction = if ($PrepareOnly) { '--prepare-only' } else { '--approved' }
    # Secrets are inherited as environment variables, never command-line arguments.
    $ErrorActionPreference = 'Continue'
    & $java '-Djava.awt.headless=true' -cp $classpath com.foodly.business.tools.CloudinaryVerification $probeAction $probeRoot 2>&1 |
        ForEach-Object {
            $safe = $_.ToString()
            foreach ($credential in @($env:CLOUDINARY_API_KEY, $env:CLOUDINARY_API_SECRET)) {
                if (![string]::IsNullOrEmpty($credential)) { $safe = $safe.Replace($credential, '[REDACTED]') }
            }
            # Defense in depth: never echo SHA-1/SHA-256 signature material from native output.
            $safe = [regex]::Replace($safe, '(?i)(?<![a-f0-9])[a-f0-9]{40}(?:[a-f0-9]{24})?(?![a-f0-9])', '[REDACTED-HASH]')
            Write-Output $safe
        }
    $probeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($probeExit -ne 0) { throw 'Cloudinary verification did not complete successfully. Consult the private receipt, if created; do not assume cleanup succeeded.' }
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
