[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$lookupPath = Join-Path $PSScriptRoot 'get-cloudinary-resource.ps1'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($lookupPath,[ref]$tokens,[ref]$parseErrors)
if (@($parseErrors).Count) { throw 'Read-only script has parser errors.' }
# Load only the pure response parser. Do not run the script, credential loader or HTTP client.
$parser = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ConvertTo-CloudinaryLookupResult'},$true)
. ([scriptblock]::Create($parser.Extent.Text))
$testId = 'foodly/business/11111111-2222-3333-4444-555555555555'
$checks = 0
foreach ($status in @(404,401,403,429,500,302)) {
    $actual = ConvertTo-CloudinaryLookupResult -HttpStatus $status -Body 'sensitive-provider-response' -RequestedId $testId
    $expected = if ($status -eq 404) { 'NOT_FOUND' } else { 'ERROR' }
    if ($actual.result -ne $expected -or $actual.httpStatus -ne $status -or ($actual | ConvertTo-Json -Compress).Contains('sensitive-provider-response')) {
        throw 'HTTP status interpretation or sanitization failed.'
    }
    $checks++
}
$valid = @{ public_id=$testId; resource_type='image'; type='upload'; format='png'; secure_url='sensitive-extra-field'; api_key='sensitive-extra-field' } | ConvertTo-Json
$found = ConvertTo-CloudinaryLookupResult -HttpStatus 200 -Body $valid -RequestedId $testId
if ($found.result -ne 'EXISTS' -or $found.format -ne 'png' -or ($found | ConvertTo-Json -Compress).Contains('sensitive-extra-field')) {
    throw 'Valid metadata interpretation or output allowlist failed.'
}
$checks++
foreach ($invalidBody in @('{invalid-json', '{"public_id":"different","resource_type":"image","type":"upload","format":"png"}', '{}')) {
    $invalid = ConvertTo-CloudinaryLookupResult -HttpStatus 200 -Body $invalidBody -RequestedId $testId
    if ($invalid.result -ne 'ERROR' -or $invalid.diagnostic -ne 'INVALID_RESOURCE_RESPONSE') { throw 'Invalid metadata was accepted.' }
    $checks++
}
$guarded = $false
try { $unexpected = @(& $lookupPath -PublicId $testId) }
catch { $guarded = $_.Exception.Message.StartsWith('Explicit authorization is required') }
if (!$guarded) { throw 'Read-only authorization guard did not stop the script.' }
$checks++
Write-Output ('PASSED: ' + $checks + ' local read-only parser/guard checks. No credentials loaded and no HTTP requests made.')
