[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^foodly/business/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$')]
    [string]$PublicId,
    [switch]$ReadOnlyRequestApproved
)

$ErrorActionPreference = 'Stop'
if (!$ReadOnlyRequestApproved) {
    throw 'Explicit authorization is required for one read-only Cloudinary Admin GET. No request was made.'
}

function ConvertTo-CloudinaryLookupResult {
    param([int]$HttpStatus, [string]$Body, [string]$RequestedId)
    $result = [ordered]@{
        result = 'ERROR'; httpStatus = $HttpStatus; publicId = $RequestedId
        resourceType = 'image'; format = $null; existence = 'UNKNOWN'; diagnostic = 'HTTP_ERROR'
    }
    switch ($HttpStatus) {
        200 {
            try {
                $metadata = ConvertFrom-Json -InputObject $Body -ErrorAction Stop
                $allowedFormats = @('png','jpg','jpeg','webp','gif','avif','heic','heif','bmp','tif','tiff','svg','ico','jxl','jp2','psd','pdf','eps','ai','raw')
                if ($metadata.public_id -isnot [string] -or $metadata.public_id -cne $RequestedId -or
                    $metadata.resource_type -cne 'image' -or $metadata.type -cne 'upload' -or
                    $metadata.format -isnot [string] -or $allowedFormats -cnotcontains $metadata.format) {
                    throw 'Unexpected resource metadata.'
                }
                $result.result = 'EXISTS'; $result.existence = 'EXISTS'
                $result.format = $metadata.format; $result.diagnostic = 'RESOURCE_FOUND'
            } catch { $result.diagnostic = 'INVALID_RESOURCE_RESPONSE' }
        }
        404 { $result.result = 'NOT_FOUND'; $result.existence = 'NOT_FOUND'; $result.diagnostic = 'RESOURCE_NOT_FOUND' }
        401 { $result.diagnostic = 'AUTHENTICATION_FAILED' }
        403 { $result.diagnostic = 'PERMISSION_DENIED' }
        429 { $result.diagnostic = 'RATE_LIMITED_NO_RETRY' }
    }
    return [PSCustomObject]$result
}

$lookupVariables = @('CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET')
$previousEnvironment = @{}
foreach ($variable in $lookupVariables) { $previousEnvironment[$variable] = [Environment]::GetEnvironmentVariable($variable) }
$client = $null; $handler = $null; $request = $null; $response = $null
$stage = 'CONFIGURATION'; $receivedStatus = $null
try {
    # Force the existing DPAPI loader to use the stored credentials. Restore the caller's environment below.
    foreach ($variable in $lookupVariables) { [Environment]::SetEnvironmentVariable($variable,$null,'Process') }
    . (Join-Path $PSScriptRoot 'cloudinary.ps1') -Action Load | Out-Null
    # Load validates local format only; account authentication is determined by the single GET.
    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.UseCookies = $false
    $handler.UseDefaultCredentials = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    $encodedId = [Uri]::EscapeDataString($PublicId)
    $uri = [Uri]::new('https://api.cloudinary.com/v1_1/' + $env:CLOUDINARY_CLOUD_NAME + '/resources/image/upload/' + $encodedId)
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $uri)
    $authBytes = [Text.Encoding]::UTF8.GetBytes($env:CLOUDINARY_API_KEY + ':' + $env:CLOUDINARY_API_SECRET)
    try {
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Basic', [Convert]::ToBase64String($authBytes))
    } finally { [Array]::Clear($authBytes,0,$authBytes.Length) }
    $request.Headers.Accept.ParseAdd('application/json')
    $stage = 'ADMIN_LOOKUP'
    # Exactly one GET, no redirect, retry, upload, destroy, Spring context or Outbox worker.
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $receivedStatus = [int]$response.StatusCode
    $body = if ($receivedStatus -eq 200) { $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } else { '' }
    ConvertTo-CloudinaryLookupResult -HttpStatus $receivedStatus -Body $body -RequestedId $PublicId
} catch {
    $diagnostic = if ($stage -eq 'CONFIGURATION') { 'LOCAL_CONFIGURATION_ERROR_NO_REQUEST' } else { 'TRANSPORT_OR_RESPONSE_ERROR' }
    $cause = $_.Exception
    for ($depth = 0; $null -ne $cause -and $depth -lt 12; $depth++) {
        if ($stage -eq 'CONFIGURATION') { break }
        if ($cause -is [System.Security.Authentication.AuthenticationException]) { $diagnostic = 'TLS_ERROR'; break }
        if ($cause -is [OperationCanceledException]) { $diagnostic = 'TIMEOUT'; break }
        if ($cause -is [Net.Sockets.SocketException]) { $diagnostic = 'NETWORK_ERROR'; break }
        $cause = $cause.InnerException
    }
    # Never emit raw exceptions, response bodies, URLs, authorization headers or credential values.
    [PSCustomObject][ordered]@{ result = 'ERROR'; httpStatus = $receivedStatus; publicId = $PublicId
        resourceType = 'image'; format = $null; existence = 'UNKNOWN'; diagnostic = $diagnostic }
} finally {
    if ($null -ne $response) { $response.Dispose() }
    if ($null -ne $request) { $request.Dispose() }
    if ($null -ne $client) { $client.Dispose() }
    elseif ($null -ne $handler) { $handler.Dispose() }
    foreach ($variable in $lookupVariables) { [Environment]::SetEnvironmentVariable($variable,$previousEnvironment[$variable],'Process') }
}
