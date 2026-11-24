[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$credentialsTestRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$credentialsTestFixture = Join-Path $credentialsTestRoot ('.local/cloudinary-credentials-test-' + [Guid]::NewGuid().ToString('N'))
$credentialsTestIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$credentialsTestPrincipal = [Security.Principal.WindowsPrincipal]::new($credentialsTestIdentity)
$credentialsTestChecks = @{ passed = 0 }
$credentialsTestVariables = @('CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET')
$credentialsTestPreviousEnvironment = @{}
foreach ($variable in $credentialsTestVariables) {
    $credentialsTestPreviousEnvironment[$variable] = [Environment]::GetEnvironmentVariable($variable)
}

function Assert-CredentialsCheck([bool]$Condition, [string]$Name) {
    if (!$Condition) { throw ('Credential regression failed: ' + $Name) }
    $credentialsTestChecks.passed++
    Write-Output ('PASS: ' + $Name)
}

function Get-CredentialsTestAccess([IO.FileSystemInfo]$Item) {
    $sections = [Security.AccessControl.AccessControlSections]::Access
    if ($PSVersionTable.PSEdition -eq 'Desktop') { return $Item.GetAccessControl($sections) }
    return [IO.FileSystemAclExtensions]::GetAccessControl($Item, $sections)
}

function Set-CredentialsTestAccess([IO.DirectoryInfo]$Directory, [Security.AccessControl.DirectorySecurity]$Access) {
    if ($PSVersionTable.PSEdition -eq 'Desktop') { $Directory.SetAccessControl($Access) }
    else { [IO.FileSystemAclExtensions]::SetAccessControl($Directory, $Access) }
}

# Input substitution only: storage, Windows DACLs and DPAPI are real. Never use account credentials.
$credentialsTestInput = @{ prompts = 0; cloud = 'foodly-credential-regression' }
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($kind in @('key', 'secret')) {
        $bytes = [byte[]]::new(32)
        $random.GetBytes($bytes)
        $credentialsTestInput[$kind] = [Convert]::ToBase64String($bytes)
        $credentialsTestInput[$kind + 'Secure'] = ConvertTo-SecureString -String $credentialsTestInput[$kind] -AsPlainText -Force
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
} finally { $random.Dispose() }

function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    $credentialsTestInput.prompts++
    if (!$AsSecureString -and $Prompt -eq 'Cloudinary cloud name (development account)') { return $credentialsTestInput.cloud }
    if ($AsSecureString -and $Prompt -eq 'Cloudinary API key (hidden)') { return $credentialsTestInput.keySecure }
    if ($AsSecureString -and $Prompt -eq 'Cloudinary API secret (hidden)') { return $credentialsTestInput.secretSecure }
    throw 'Unexpected credential prompt.'
}

try {
    Assert-CredentialsCheck (!$credentialsTestPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) 'Non-administrator Windows token'
    $privileges = (& whoami.exe /priv /fo csv) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect token privileges.' }
    Assert-CredentialsCheck (!$privileges.Contains('SeSecurityPrivilege')) 'Token has no SeSecurityPrivilege'

    $fixtureScripts = Join-Path $credentialsTestFixture 'scripts'
    New-Item -ItemType Directory -Path $fixtureScripts -Force | Out-Null
    $fixtureScript = Join-Path $fixtureScripts 'cloudinary.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'cloudinary.ps1') -Destination $fixtureScript
    foreach ($variable in $credentialsTestVariables) { [Environment]::SetEnvironmentVariable($variable, $null, 'Process') }

    $configureOutput = @(& $fixtureScript -Action Configure)
    $credentialDirectory = Join-Path $credentialsTestFixture '.local/cloudinary'
    $credentialFile = Join-Path $credentialDirectory 'credentials.xml'
    Assert-CredentialsCheck (($configureOutput -join "`n").Contains('Private Windows credentials saved') -and $credentialsTestInput.prompts -eq 3) 'Configure completed using synthetic hidden input'

    $directoryAccess = Get-CredentialsTestAccess ([IO.DirectoryInfo]::new($credentialDirectory))
    $directoryRules = @($directoryAccess.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    Assert-CredentialsCheck ($directoryAccess.AreAccessRulesProtected -and $directoryRules.Count -eq 1 -and
        $directoryRules[0].IdentityReference.Value -eq $credentialsTestIdentity.User.Value -and
        $directoryRules[0].FileSystemRights -eq [Security.AccessControl.FileSystemRights]::FullControl -and
        $directoryRules[0].InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit') 'Directory has a protected, inheritable DACL for this user only'
    $fileAccess = Get-CredentialsTestAccess ([IO.FileInfo]::new($credentialFile))
    $fileRules = @($fileAccess.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    Assert-CredentialsCheck ($fileRules.Count -eq 1 -and $fileRules[0].IdentityReference.Value -eq $credentialsTestIdentity.User.Value -and
        $fileRules[0].FileSystemRights -eq [Security.AccessControl.FileSystemRights]::FullControl) 'Credential file inherits private access'

    $encryptedXml = [IO.File]::ReadAllText($credentialFile)
    $stored = Import-Clixml -LiteralPath $credentialFile
    Assert-CredentialsCheck (!$encryptedXml.Contains($credentialsTestInput.key) -and !$encryptedXml.Contains($credentialsTestInput.secret) -and
        $stored.apiKey -is [Security.SecureString] -and $stored.apiSecret -is [Security.SecureString]) 'DPAPI SecureString storage contains no plaintext credentials'

    $fixtureHash = (Get-FileHash -LiteralPath $credentialFile -Algorithm SHA256).Hash
    $loadOutput = @(. $fixtureScript -Action Load)
    Assert-CredentialsCheck ($env:CLOUDINARY_CLOUD_NAME -eq $credentialsTestInput.cloud -and
        $env:CLOUDINARY_API_KEY -eq $credentialsTestInput.key -and $env:CLOUDINARY_API_SECRET -eq $credentialsTestInput.secret) 'Load decrypts correctly for this Windows user'
    Assert-CredentialsCheck ($fixtureHash -eq (Get-FileHash -LiteralPath $credentialFile -Algorithm SHA256).Hash) 'Load does not rewrite credentials'

    $statusOutput = @(& $fixtureScript -Action Status)
    $statusText = $statusOutput -join "`n"
    Assert-CredentialsCheck ($statusText.Contains('CLOUDINARY_CLOUD_NAME: configured=True') -and
        $statusText.Contains('CLOUDINARY_API_KEY: configured=True') -and $statusText.Contains('CLOUDINARY_API_SECRET: configured=True') -and
        $statusText.Contains('Private Windows credential file present=True')) 'Status reports presence without values'
    $allOutput = @($configureOutput + $loadOutput + $statusOutput) -join "`n"
    Assert-CredentialsCheck (!$allOutput.Contains($credentialsTestInput.key) -and !$allOutput.Contains($credentialsTestInput.secret)) 'Configure, Load and Status never print credentials'

    Push-Location $credentialsTestFixture
    try { & git check-ignore --quiet -- .local/cloudinary/credentials.xml; $ignored = $LASTEXITCODE -eq 0 }
    finally { Pop-Location }
    Assert-CredentialsCheck $ignored 'Credential fixture is ignored by Git'
    $reconfigureRejected = $false
    try { $unexpected = @(& $fixtureScript -Action Configure) }
    catch { $reconfigureRejected = $_.Exception.Message -eq 'Private credentials already exist; they have not been overwritten.' }
    Assert-CredentialsCheck ($reconfigureRejected -and $fixtureHash -eq (Get-FileHash -LiteralPath $credentialFile -Algorithm SHA256).Hash) 'Configure refuses to overwrite existing credentials'

    # Regressions for hidden Ctrl+V input and mixed invalid text. No account credentials are used.
    $validKeySecure = $credentialsTestInput.keySecure
    $validSecretSecure = $credentialsTestInput.secretSecure
    $invalidCases = @(
        @{ name = 'paste-control-key'; key = [string][char]22; secret = $credentialsTestInput.secret },
        @{ name = 'paste-control-secret'; key = $credentialsTestInput.key; secret = [string][char]22 },
        @{ name = 'mixed-control'; key = $credentialsTestInput.key; secret = ('synthetic' + [char]22) },
        @{ name = 'surrounding-whitespace'; key = (' ' + $credentialsTestInput.key); secret = $credentialsTestInput.secret }
    )
    try {
        foreach ($invalidCase in $invalidCases) {
            $caseRoot = Join-Path $credentialsTestFixture $invalidCase.name
            $caseScripts = Join-Path $caseRoot 'scripts'
            New-Item -ItemType Directory -Path $caseScripts -Force | Out-Null
            $caseScript = Join-Path $caseScripts 'cloudinary.ps1'
            Copy-Item -LiteralPath $fixtureScript -Destination $caseScript
            $credentialsTestInput.keySecure = ConvertTo-SecureString -String $invalidCase.key -AsPlainText -Force
            $credentialsTestInput.secretSecure = ConvertTo-SecureString -String $invalidCase.secret -AsPlainText -Force
            try {
                $invalidOutput = [Collections.Generic.List[object]]::new()
                $rejected = $false
                try { & $caseScript -Action Configure | ForEach-Object { $invalidOutput.Add($_) } }
                catch { $rejected = $_.Exception.Message.StartsWith('Invalid credential input:') }
                $caseFile = Join-Path $caseRoot '.local/cloudinary/credentials.xml'
                Assert-CredentialsCheck ($rejected -and !(Test-Path -LiteralPath $caseFile) -and
                    !(($invalidOutput -join "`n").Contains('Private Windows credentials saved'))) ('Configure rejects invalid input before saving: ' + $invalidCase.name)

                # Reproduce an old invalid DPAPI file and prove Load rejects it without environment changes.
                [PSCustomObject]@{ cloudName = $credentialsTestInput.cloud; apiKey = $credentialsTestInput.keySecure; apiSecret = $credentialsTestInput.secretSecure } |
                    Export-Clixml -LiteralPath $caseFile
                $caseHash = (Get-FileHash -LiteralPath $caseFile -Algorithm SHA256).Hash
                foreach ($variable in $credentialsTestVariables) { [Environment]::SetEnvironmentVariable($variable,$null,'Process') }
                $loadRejected = $false
                try { $invalidLoadOutput = @(. $caseScript -Action Load) }
                catch { $loadRejected = $_.Exception.Message.StartsWith('Could not load private Cloudinary credentials:') }
                $exportedVariables = @($credentialsTestVariables | Where-Object { ![string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_)) })
                Assert-CredentialsCheck ($loadRejected -and $exportedVariables.Count -eq 0 -and
                    $caseHash -eq (Get-FileHash -LiteralPath $caseFile -Algorithm SHA256).Hash) ('Load rejects invalid DPAPI input without exporting or rewriting: ' + $invalidCase.name)
            } finally {
                $credentialsTestInput.keySecure.Dispose()
                $credentialsTestInput.secretSecure.Dispose()
                $credentialsTestInput.keySecure = $validKeySecure
                $credentialsTestInput.secretSecure = $validSecretSecure
            }
        }
    } finally {
        $credentialsTestInput.keySecure = $validKeySecure
        $credentialsTestInput.secretSecure = $validSecretSecure
    }
    $env:CLOUDINARY_CLOUD_NAME = $credentialsTestInput.cloud
    $env:CLOUDINARY_API_KEY = [string][char]22
    $env:CLOUDINARY_API_SECRET = $credentialsTestInput.secret
    $environmentRejected = $false
    try { $invalidEnvironmentOutput = @(. $fixtureScript -Action Load) }
    catch { $environmentRejected = $_.Exception.Message.StartsWith('Invalid Cloudinary environment credential format:') }
    $invalidStatus = @(& $fixtureScript -Action Status) -join "`n"
    Assert-CredentialsCheck ($environmentRejected -and $invalidStatus.Contains('CLOUDINARY_API_KEY: configured=True; formatValid=False')) 'Invalid environment input is rejected and Status distinguishes presence from valid format'
    foreach ($variable in $credentialsTestVariables) { [Environment]::SetEnvironmentVariable($variable,$null,'Process') }

    # Real AccessDenied fixture: deny creating child directories, retain WRITE_DAC for safe restoration.
    $blockedRoot = Join-Path $credentialsTestFixture 'blocked'
    $blockedScripts = Join-Path $blockedRoot 'scripts'
    New-Item -ItemType Directory -Path $blockedScripts -Force | Out-Null
    $blockedScript = Join-Path $blockedScripts 'cloudinary.ps1'
    Copy-Item -LiteralPath $fixtureScript -Destination $blockedScript
    $blockedParent = New-Item -ItemType Directory -Path (Join-Path $blockedRoot '.local')
    $blockedAccess = Get-CredentialsTestAccess $blockedParent
    $originalDacl = $blockedAccess.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)
    $blockedAccess.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($credentialsTestIdentity.User,
        [Security.AccessControl.FileSystemRights]::CreateDirectories, [Security.AccessControl.AccessControlType]::Deny))
    $failedOutput = [Collections.Generic.List[object]]::new()
    $permissionFailure = $false
    $promptsBeforePermissionFailure = $credentialsTestInput.prompts
    try {
        Set-CredentialsTestAccess $blockedParent $blockedAccess
        try { & $blockedScript -Action Configure | ForEach-Object { $failedOutput.Add($_) } }
        catch { $permissionFailure = $_.CategoryInfo.Category -eq [Management.Automation.ErrorCategory]::PermissionDenied }
    } finally {
        $restore = [Security.AccessControl.DirectorySecurity]::new()
        $restore.SetSecurityDescriptorSddlForm($originalDacl, [Security.AccessControl.AccessControlSections]::Access)
        Set-CredentialsTestAccess $blockedParent $restore
    }
    Assert-CredentialsCheck $permissionFailure 'Real filesystem permission denial terminates Configure'
    Assert-CredentialsCheck ($credentialsTestInput.prompts -eq $promptsBeforePermissionFailure -and
        !(Test-Path -LiteralPath (Join-Path $blockedRoot '.local/cloudinary/credentials.xml')) -and
        !(($failedOutput -join "`n").Contains('Private Windows credentials saved'))) 'Permission failure writes no credential file, asks for no secrets and reports no success'

    Write-Output ('PASSED: ' + $credentialsTestChecks.passed + ' checks using real Windows DACLs and DPAPI; no Cloudinary requests or existing credentials used.')
} finally {
    foreach ($variable in $credentialsTestVariables) {
        [Environment]::SetEnvironmentVariable($variable, $credentialsTestPreviousEnvironment[$variable], 'Process')
    }
    $credentialsTestInput.keySecure.Dispose()
    $credentialsTestInput.secretSecure.Dispose()
    if (Test-Path -LiteralPath $credentialsTestFixture) {
        # Delete only the fresh fixture created above, after checking the absolute workspace boundary.
        $allowedRoot = [IO.Path]::GetFullPath((Join-Path $credentialsTestRoot '.local')) + [IO.Path]::DirectorySeparatorChar
        $resolvedFixture = [IO.Path]::GetFullPath($credentialsTestFixture)
        if (!$resolvedFixture.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedFixture) -notmatch '^cloudinary-credentials-test-[a-f0-9]{32}$') {
            throw 'Refusing to remove a fixture outside the intended workspace directory.'
        }
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction Stop
    }
}
