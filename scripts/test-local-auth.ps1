[CmdletBinding()]
param(
    [string]$BaseUri = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$targetUri = $null
if (![Uri]::TryCreate($BaseUri, [UriKind]::Absolute, [ref]$targetUri) -or
    $targetUri.Scheme -notin @('http','https') -or !$targetUri.Host -or $targetUri.UserInfo -or
    $targetUri.Query -or $targetUri.Fragment -or $targetUri.AbsolutePath -ne '/') {
    throw 'BaseUri must be an absolute HTTP(S) server URL without credentials, query, fragment or an API path.'
}
$baseUri = $targetUri.AbsoluteUri.TrimEnd('/')
# The HTTP probe needs the local JWT, not the startup launcher's database selection.
# A caller targeting 8082 may already have MONGODB_URI set to its isolated test database.
$previousMongoUri = [Environment]::GetEnvironmentVariable('MONGODB_URI')
try {
    [Environment]::SetEnvironmentVariable('MONGODB_URI', $null, 'Process')
    . (Join-Path $PSScriptRoot 'local-auth.ps1') -Action Load
} finally {
    [Environment]::SetEnvironmentVariable('MONGODB_URI', $previousMongoUri, 'Process')
}
$key = [Convert]::FromBase64String($env:IAM_JWT_SECRET_BASE64)

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# In-memory test fixtures only. No issuer endpoint, credential store or token output is provided.
function New-VerificationToken([hashtable]$Claims, [byte[]]$SigningKey, [switch]$Unsigned) {
    $algorithm = if ($Unsigned) { 'none' } else { 'HS256' }
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes((@{alg=$algorithm;typ='JWT'} | ConvertTo-Json -Compress)))
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes(($Claims | ConvertTo-Json -Compress)))
    if ($Unsigned) { return "$header.$payload." }
    $mac = [Security.Cryptography.HMACSHA256]::new($SigningKey)
    try { $signature = ConvertTo-Base64Url ($mac.ComputeHash([Text.Encoding]::ASCII.GetBytes("$header.$payload"))) }
    finally { $mac.Dispose() }
    return "$header.$payload.$signature"
}

function Get-VerificationStatus([string]$Path, [string]$Bearer) {
    $headers = @{}
    if ($Bearer) { $headers.Authorization = 'Bearer ' + $Bearer }
    try { return [int](Invoke-WebRequest -UseBasicParsing -Uri ($baseUri + $Path) -Headers $headers -TimeoutSec 10).StatusCode }
    catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw 'The local HTTP server is unavailable. No token or key has been included in this error.'
    }
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$valid = @{sub=[Guid]::NewGuid().ToString();role='HUARIQUE_OWNER';iss=$env:IAM_JWT_ISSUER;
    aud=@($env:IAM_JWT_AUDIENCE);iat=$now;exp=$now+300}
$results = [Collections.Generic.List[object]]::new()
function Add-VerificationResult([string]$Name, [int]$Expected, [int]$Actual) {
    $results.Add([pscustomobject]@{Test=$Name;Expected=$Expected;Actual=$Actual;Passed=($Expected -eq $Actual)})
}

try {
    $query = '/business-api/huariques?page=0&size=1'
    Add-VerificationResult 'Missing bearer' 401 (Get-VerificationStatus $query '')
    Add-VerificationResult 'Valid signed JWT' 200 (Get-VerificationStatus $query (New-VerificationToken $valid $key))
    foreach ($variant in 'missing-audience','wrong-audience','missing-issuer','wrong-issuer','expired','current-iam-shape') {
        $claims = @{} + $valid
        switch ($variant) {
            'missing-audience' { $claims.Remove('aud') }
            'wrong-audience' { $claims.aud = @('another-service') }
            'missing-issuer' { $claims.Remove('iss') }
            'wrong-issuer' { $claims.iss = 'another-issuer' }
            'expired' { $claims.iat = $now-300; $claims.exp = $now-120 }
            'current-iam-shape' { $claims.Remove('iss'); $claims.Remove('aud') }
        }
        Add-VerificationResult $variant 401 (Get-VerificationStatus $query (New-VerificationToken $claims $key))
    }
    $wrongKey = [byte[]]$key.Clone()
    $wrongKey[0] = $wrongKey[0] -bxor 1
    Add-VerificationResult 'Wrong signature' 401 (Get-VerificationStatus $query (New-VerificationToken $valid $wrongKey))
    Add-VerificationResult 'Unsigned JWT' 401 (Get-VerificationStatus $query (New-VerificationToken $valid $key -Unsigned))
    # A new subject has no huarique: 404 confirms that owner authorization passed without creating data.
    Add-VerificationResult 'Configured HUARIQUE_OWNER role' 404 (Get-VerificationStatus '/business-api/huariques/me' (New-VerificationToken $valid $key))
    $legacyRole = @{} + $valid
    $legacyRole.role = 'OWNER'
    Add-VerificationResult 'Unconfigured OWNER role' 403 (Get-VerificationStatus '/business-api/huariques/me' (New-VerificationToken $legacyRole $key))
    $results | Format-Table -AutoSize
    $failed = @($results | Where-Object { !$_.Passed }).Count
    Write-Output "JWT_HTTP_VERIFICATION: passed=$($results.Count-$failed), failed=$failed, skipped=0"
    Write-Output "DEPENDENCY_HEALTH_HTTP_STATUS=$(Get-VerificationStatus '/actuator/health' '')"
    if ($failed) { throw 'Local HTTP authentication regression detected. Secret and tokens were not printed.' }
} finally {
    [Array]::Clear($key, 0, $key.Length)
    if ($wrongKey) { [Array]::Clear($wrongKey, 0, $wrongKey.Length) }
}
