[CmdletBinding()]
param(
    [ValidateSet('Configure', 'Load', 'Status')]
    [string]$Action = 'Status'
)

$ErrorActionPreference = 'Stop'
$cloudinaryProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cloudinaryDirectory = Join-Path $cloudinaryProjectRoot '.local/cloudinary'
$cloudinaryFile = Join-Path $cloudinaryDirectory 'credentials.xml'
$cloudinaryVariables = @('CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET')

function Initialize-CloudinaryPrivateDirectory {
    Push-Location $cloudinaryProjectRoot
    try {
        & git check-ignore --quiet -- .local/cloudinary/credentials.xml
        if ($LASTEXITCODE -ne 0) { throw 'The private Cloudinary directory must be ignored by Git.' }
    } finally { Pop-Location }
    $directory = New-Item -ItemType Directory -Path $cloudinaryDirectory -Force -ErrorAction Stop
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $access = [Security.AccessControl.DirectorySecurity]::new()
    # Mark only Access as modified: private, protected DACL with one inheritable user ACE.
    # Do not set Owner/Group or read/write Audit (SACL). Set-Acl's provider first tries All,
    # which can request SeSecurityPrivilege even when no audit changes were intended.
    $sections = [Security.AccessControl.AccessControlSections]::Access
    $access.SetSecurityDescriptorSddlForm(('D:P(A;OICI;FA;;;{0})' -f $identity.Value), $sections)
    try {
        # .NET persists only the modified sections. Windows PowerShell 5.1 has instance
        # methods; PowerShell 7 exposes these operations through FileSystemAclExtensions.
        if ($PSVersionTable.PSEdition -eq 'Desktop') {
            $directory.SetAccessControl($access)
            $applied = $directory.GetAccessControl($sections)
        } else {
            [IO.FileSystemAclExtensions]::SetAccessControl($directory, $access)
            $applied = [IO.FileSystemAclExtensions]::GetAccessControl($directory, $sections)
        }
        $rules = @($applied.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        if (!$applied.AreAccessRulesProtected -or $rules.Count -ne 1 -or
            $rules[0].IdentityReference.Value -ne $identity.Value -or $rules[0].IsInherited -or
            $rules[0].AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl -or
            $rules[0].InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit' -or
            $rules[0].PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw 'The private directory DACL could not be verified.'
        }
    } catch {
        throw 'Cannot secure the private Cloudinary directory DACL. Configuration stopped; no success is claimed.'
    }
}

function ConvertFrom-CloudinarySecureString([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Test-CloudinaryCredentialText([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -cne $Value.Trim()) { return $false }
    foreach ($character in $Value.ToCharArray()) { if ([char]::IsControl($character)) { return $false } }
    return $true
}

if ($Action -eq 'Status') {
    foreach ($variable in $cloudinaryVariables) {
        $statusValue = [Environment]::GetEnvironmentVariable($variable)
        $configured = ![string]::IsNullOrWhiteSpace($statusValue)
        $formatValid = if ($variable -eq 'CLOUDINARY_CLOUD_NAME') { $configured -and $statusValue -cmatch '^[a-zA-Z0-9_-]+$' }
            else { $configured -and (Test-CloudinaryCredentialText $statusValue) }
        Write-Output ("{0}: configured={1}; formatValid={2}" -f $variable, $configured, $formatValid)
    }
    Write-Output ('Private Windows credential file present=' + (Test-Path -LiteralPath $cloudinaryFile -PathType Leaf))
    Write-Output 'Status performs no Cloudinary requests and does not prove account connectivity.'
    return
}

if ($Action -eq 'Configure') {
    if (Test-Path -LiteralPath $cloudinaryFile) { throw 'Private credentials already exist; they have not been overwritten.' }
    Initialize-CloudinaryPrivateDirectory
    $cloudName = Read-Host 'Cloudinary cloud name (development account)'
    if ($cloudName -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid cloud name.' }
    $apiKey = Read-Host 'Cloudinary API key (hidden)' -AsSecureString
    $apiSecret = Read-Host 'Cloudinary API secret (hidden)' -AsSecureString
    if ($apiKey.Length -eq 0 -or $apiSecret.Length -eq 0) { throw 'Both credentials are required.' }
    try {
        $keyText = ConvertFrom-CloudinarySecureString $apiKey
        $secretText = ConvertFrom-CloudinarySecureString $apiSecret
        if (!(Test-CloudinaryCredentialText $keyText) -or !(Test-CloudinaryCredentialText $secretText)) {
            throw 'Invalid credential input: blank, surrounding whitespace or control characters. Paste using the console Paste menu and enter the credentials again; values were not saved.'
        }
    } finally { $keyText = $null; $secretText = $null }
    # Export-Clixml encrypts SecureString values with Windows DPAPI for this user/machine.
    [PSCustomObject]@{ cloudName = $cloudName; apiKey = $apiKey; apiSecret = $apiSecret } |
        Export-Clixml -LiteralPath $cloudinaryFile
    Write-Output 'Private Windows credentials saved, without contacting Cloudinary. No credential values were displayed.'
    return
}

$configuredCount = @($cloudinaryVariables | Where-Object {
    ![string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
}).Count
if ($configuredCount -gt 0 -and $configuredCount -lt 3) {
    throw 'Partial Cloudinary environment configuration. Set all three variables or remove them before loading the private file.'
}
if ($configuredCount -eq 0) {
    if (!(Test-Path -LiteralPath $cloudinaryFile -PathType Leaf)) {
        throw 'Cloudinary credentials are missing. Configure them interactively or export all three CLOUDINARY_* variables; never paste secrets into chat.'
    }
    try {
        $privateCloudinary = Import-Clixml -LiteralPath $cloudinaryFile
        if ($privateCloudinary.cloudName -notmatch '^[a-zA-Z0-9_-]+$' -or
            $privateCloudinary.apiKey -isnot [Security.SecureString] -or
            $privateCloudinary.apiSecret -isnot [Security.SecureString] -or
            $privateCloudinary.apiKey.Length -eq 0 -or $privateCloudinary.apiSecret.Length -eq 0) {
            throw 'Invalid private configuration.'
        }
        $loadedKey = ConvertFrom-CloudinarySecureString $privateCloudinary.apiKey
        $loadedSecret = ConvertFrom-CloudinarySecureString $privateCloudinary.apiSecret
        if (!(Test-CloudinaryCredentialText $loadedKey) -or !(Test-CloudinaryCredentialText $loadedSecret)) {
            throw 'Invalid stored credential format.'
        }
        $env:CLOUDINARY_CLOUD_NAME = $privateCloudinary.cloudName
        $env:CLOUDINARY_API_KEY = $loadedKey
        $env:CLOUDINARY_API_SECRET = $loadedSecret
    } catch { throw 'Could not load private Cloudinary credentials: invalid format or Windows decryption failed. Contents were not displayed; existing credentials were not overwritten.' }
    finally { $loadedKey = $null; $loadedSecret = $null }
}
if ($env:CLOUDINARY_CLOUD_NAME -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid Cloudinary cloud name.' }
if (!(Test-CloudinaryCredentialText $env:CLOUDINARY_API_KEY) -or !(Test-CloudinaryCredentialText $env:CLOUDINARY_API_SECRET)) {
    throw 'Invalid Cloudinary environment credential format: blank, surrounding whitespace or control characters. Values omitted.'
}
Initialize-CloudinaryPrivateDirectory
Write-Output 'Cloudinary variables loaded for this process only. Values omitted; no external requests made.'
