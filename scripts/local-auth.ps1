[CmdletBinding()]
param(
    [ValidateSet('Initialize', 'Load', 'Start')]
    [string]$Action = 'Load'
)

$ErrorActionPreference = 'Stop'
$authProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$authDirectory = Join-Path $authProjectRoot '.local/auth'
$authFile = Join-Path $authDirectory 'business-jwt.json'
$authMongoUri = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'

if ($Action -eq 'Initialize') {
    Push-Location $authProjectRoot
    try {
        & git check-ignore --quiet -- .local/auth/business-jwt.json
        if ($LASTEXITCODE -ne 0) { throw 'The local key path must be ignored by Git before initialization.' }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Path $authDirectory -Force | Out-Null
    # Restrict the directory to the current Windows identity. New files inherit this ACL.
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $access = Get-Acl -LiteralPath $authDirectory
    $access.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow)
    $access.SetAccessRule($rule)
    Set-Acl -LiteralPath $authDirectory -AclObject $access
    if (!(Test-Path -LiteralPath $authFile)) {
        $keyBytes = [byte[]]::new(32)
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
        $localContract = [ordered]@{
            purpose = 'Foodly Business local development only; not an IAM-issued credential'
            algorithm = 'HS256'
            issuer = 'foodly-local-development'
            audience = 'foodly-business-local'
            ownerRole = 'HUARIQUE_OWNER'
            secretBase64 = [Convert]::ToBase64String($keyBytes)
        }
        $jsonBytes = [Text.Encoding]::UTF8.GetBytes(($localContract | ConvertTo-Json))
        $keyStream = [IO.File]::Open($authFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $keyStream.Write($jsonBytes, 0, $jsonBytes.Length) } finally { $keyStream.Dispose() }
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        [Array]::Clear($jsonBytes, 0, $jsonBytes.Length)
    }
}

if (!(Test-Path -LiteralPath $authFile -PathType Leaf)) {
    throw 'Local JWT configuration is missing. Run scripts/local-auth.ps1 -Action Initialize first.'
}
try {
    $localJwt = Get-Content -LiteralPath $authFile -Raw | ConvertFrom-Json
    $decodedKey = [Convert]::FromBase64String($localJwt.secretBase64)
} catch {
    throw 'Invalid local JWT configuration; its contents have not been printed or overwritten.'
}
if ($localJwt.algorithm -ne 'HS256' -or $decodedKey.Length -ne 32 -or
    $localJwt.issuer -ne 'foodly-local-development' -or $localJwt.audience -ne 'foodly-business-local' -or
    $localJwt.ownerRole -ne 'HUARIQUE_OWNER') {
    throw 'Local JWT configuration does not match the isolated development contract. It has not been overwritten.'
}
[Array]::Clear($decodedKey, 0, $decodedKey.Length)

if ($Action -eq 'Initialize') {
    Write-Output 'Local HS256 configuration prepared/reused in .local/auth/business-jwt.json. The key is never displayed.'
    return
}

if ($env:MONGODB_URI -and $env:MONGODB_URI -ne $authMongoUri) {
    throw 'This local launcher only permits the development MongoDB URI on 127.0.0.1:27018.'
}
$env:IAM_JWT_SECRET_BASE64 = $localJwt.secretBase64
$env:IAM_JWT_ISSUER = $localJwt.issuer
$env:IAM_JWT_AUDIENCE = $localJwt.audience
$env:IAM_OWNER_ROLE = $localJwt.ownerRole
$env:MONGODB_URI = $authMongoUri
Write-Output 'Local JWT variables loaded: HS256, issuer foodly-local-development, audience foodly-business-local, owner role HUARIQUE_OWNER. Secret omitted.'

if ($Action -eq 'Start') {
    if ($env:OUTBOX_ENABLED -and $env:OUTBOX_ENABLED -ne 'true') {
        throw 'Local startup requires OUTBOX_ENABLED=true. The launcher will not disable the Outbox.'
    }
    $env:OUTBOX_ENABLED = 'true'
    $jar = Join-Path $authProjectRoot 'target/business-service-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar)) { throw 'Build the JAR first with mvnw.cmd clean verify.' }
    foreach ($variable in 'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD') {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variable))) {
            Write-Output "Missing external configuration: $variable. No substitute credentials will be generated."
        }
    }
    $log = Join-Path $authDirectory 'business-startup.log'
    Push-Location $authProjectRoot
    try {
        # Sanitize child output even if a future framework error includes a configuration value.
        $ErrorActionPreference = 'Continue'
        & java -jar $jar 2>&1 | ForEach-Object {
            $safeLine = $_.ToString().Replace($localJwt.secretBase64, '[REDACTED]')
            $safeLine | Add-Content -LiteralPath $log -Encoding UTF8
            Write-Output $safeLine
        }
        $startupExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    if ($startupExit -ne 0) { throw "Business startup failed (exit $startupExit). Sanitized log: .local/auth/business-startup.log" }
}
