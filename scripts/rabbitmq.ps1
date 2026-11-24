[CmdletBinding()]
param(
    [ValidateSet('Install', 'Initialize', 'Start', 'Stop', 'Status', 'Load')]
    [string]$Action = 'Status',
    [switch]$InstallApproved
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$rabbitProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$rabbitRoot = Join-Path $rabbitProject '.local/rabbitmq'
$rabbitSettingsFile = Join-Path $rabbitRoot 'instance.json'
$rabbitSoftware = Join-Path $rabbitRoot 'software'
$rabbitHomePath = Join-Path $rabbitRoot 'home'
$rabbitConfig = Join-Path $rabbitRoot 'rabbitmq.conf'
$rabbitNode = 'foodly_business_dev@localhost'

function Test-RabbitPort([int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync('127.0.0.1', $Port).Wait(500) -and $socket.Connected }
    catch { return $false }
    finally { $socket.Dispose() }
}

function Protect-RabbitDirectory {
    New-Item -ItemType Directory -Path $rabbitRoot -Force | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $access = Get-Acl -LiteralPath $rabbitRoot
    if ($access.AreAccessRulesProtected -and $access.Access.Count -eq 1 -and
        $access.Access[0].IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $identity.Value) { return }
    $access = [Security.AccessControl.DirectorySecurity]::new()
    $access.SetAccessRuleProtection($true, $false)
    $access.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
        [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    Set-Acl -LiteralPath $rabbitRoot -AclObject $access
}

function New-RabbitSecret {
    $bytes = [byte[]]::new(32)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant() }
    finally { $random.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Expand-VerifiedRabbitPackage([string]$Archive, [string]$Destination) {
    # Windows PowerShell Expand-Archive fails on some plugin paths longer than MAX_PATH.
    # Extended paths avoid changing system policy. Existing files are verified, never overwritten or deleted.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $destinationRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\')
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            $segments = $entry.FullName -split '[/\\]'
            if ([IO.Path]::IsPathRooted($entry.FullName) -or $segments -contains '..' -or $entry.FullName.Contains(':')) { throw 'Unsafe ZIP entry rejected.' }
            $extended = '\\?\' + $destinationRoot + '\' + $entry.FullName.Replace('/', '\')
            if ($entry.FullName.EndsWith('/')) { [IO.Directory]::CreateDirectory($extended) | Out-Null; continue }
            [IO.Directory]::CreateDirectory($extended.Substring(0, $extended.LastIndexOf('\'))) | Out-Null
            $input = $entry.Open()
            try {
                if ([IO.File]::Exists($extended)) {
                    $existing = [IO.File]::OpenRead($extended)
                    $hash = [Security.Cryptography.SHA256]::Create()
                    try {
                        $same = [BitConverter]::ToString($hash.ComputeHash($input)) -eq [BitConverter]::ToString($hash.ComputeHash($existing))
                        if (!$same) { throw 'Existing package file differs from the verified ZIP; refusing to overwrite it.' }
                    } finally { $hash.Dispose(); $existing.Dispose() }
                } else {
                    $output = [IO.File]::Open($extended, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
                    try { $input.CopyTo($output) } finally { $output.Dispose() }
                }
            } finally { $input.Dispose() }
        }
    } finally { $zip.Dispose() }
}

if ($Action -eq 'Install') {
    if (!$InstallApproved) { throw 'Software installation requires prior user authorization and -InstallApproved.' }
    Protect-RabbitDirectory
    New-Item -ItemType Directory -Path $rabbitSoftware -Force | Out-Null
    $packages = @(
        @{Name='erlang';File='otp_win64_27.3.4.19.zip';Url='https://github.com/erlang/otp/releases/download/OTP-27.3.4.19/otp_win64_27.3.4.19.zip';Sha256='1f6fb477b4938e76c3d7473c7cb8c8e463ed6be42d77ae4b446bf74bbf2d0ae9'},
        @{Name='rabbitmq';File='rabbitmq-server-windows-4.2.9.zip';Url='https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.2.9/rabbitmq-server-windows-4.2.9.zip';Sha256='fc063ee718c4995b7c4d3bb4964a9f2bb0f0a74020ed4d4c52a083eb8d26d73d'}
    )
    foreach ($package in $packages) {
        $archive = Join-Path $rabbitSoftware $package.File
        $destination = Join-Path $rabbitSoftware $package.Name
        $marker = Join-Path $destination 'foodly-package.sha256'
        if (Test-Path -LiteralPath $marker) {
            if ((Get-Content -LiteralPath $marker -Raw).Trim() -ne $package.Sha256) { throw 'Unexpected installed package. No upgrade or deletion is performed.' }
            continue
        }
        if (!(Test-Path -LiteralPath $archive)) {
            Write-Output "Downloading official package $($package.File)"
            Invoke-WebRequest -UseBasicParsing -Uri $package.Url -OutFile $archive -TimeoutSec 300
        }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.Sha256) { throw 'Package SHA256 mismatch. Nothing will be executed.' }
        Expand-VerifiedRabbitPackage $archive $destination
        if ($package.Name -eq 'erlang') {
            $installer = Get-ChildItem -LiteralPath $destination -Recurse -File -Filter Install.exe | Select-Object -First 1
            if ($installer) {
                $setup = Start-Process -FilePath $installer.FullName -ArgumentList '-s' -WorkingDirectory $installer.DirectoryName -WindowStyle Hidden -Wait -PassThru
                if ($setup.ExitCode -ne 0) { throw 'Portable Erlang setup failed. No service was installed.' }
            }
        }
        $package.Sha256 | Set-Content -LiteralPath $marker -Encoding ASCII
        Write-Output "Prepared portable package $($package.Name). No Windows service or global PATH change."
    }
    return
}

if ($Action -eq 'Initialize') {
    Protect-RabbitDirectory
    if (!(Test-Path -LiteralPath $rabbitSettingsFile)) {
        foreach ($port in 5673,25673,4370) { if (Test-RabbitPort $port) { throw "Port $port is occupied. Existing processes will not be changed." } }
        $erl = Get-ChildItem -LiteralPath (Join-Path $rabbitSoftware 'erlang') -Recurse -File -Filter erl.exe |
            Where-Object {$_.Directory.Parent.Name -notlike 'erts-*'} | Select-Object -First 1
        $server = Get-ChildItem -LiteralPath (Join-Path $rabbitSoftware 'rabbitmq') -Recurse -File -Filter rabbitmq-server.bat | Select-Object -First 1
        if (!$erl -or !$server) { throw 'Portable Erlang/RabbitMQ are missing. Install only after authorization.' }
        New-Item -ItemType Directory -Path $rabbitHomePath -Force | Out-Null
        $cookie = New-RabbitSecret
        [IO.File]::WriteAllText((Join-Path $rabbitHomePath '.erlang.cookie'), $cookie, [Text.Encoding]::ASCII)
        $settings = [ordered]@{node=$rabbitNode;port=5673;distributionPort=25673;epmdPort=4370;
            username='foodly_business_dev';password=(New-RabbitSecret);vhost='/foodly-business-dev';
            erlangHome=$erl.Directory.Parent.FullName;rabbitHome=$server.Directory.Parent.FullName;root=$rabbitRoot}
        $configuration = @(
            'listeners.tcp.1 = 127.0.0.1:5673',
            'distribution.listener.port_range.min = 25673',
            'distribution.listener.port_range.max = 25673',
            'default_user = foodly_business_dev',
            ('default_pass = ' + $settings.password),
            'default_vhost = /foodly-business-dev',
            'default_permissions.configure = .*',
            'default_permissions.write = .*',
            'default_permissions.read = .*'
        )
        [IO.File]::WriteAllLines($rabbitConfig, $configuration, [Text.Encoding]::ASCII)
        $settings | ConvertTo-Json | Set-Content -LiteralPath $rabbitSettingsFile -Encoding UTF8
    }
    Write-Output 'Dedicated broker configuration prepared. Credentials/cookie are private and were not printed.'
    return
}

if (!(Test-Path -LiteralPath $rabbitSettingsFile)) { throw 'Run -Action Initialize after the authorized installation.' }
try { $settings = Get-Content -LiteralPath $rabbitSettingsFile -Raw | ConvertFrom-Json }
catch { throw 'Invalid private RabbitMQ configuration. Its contents were not printed.' }
if ($settings.node -ne $rabbitNode -or $settings.port -ne 5673 -or $settings.root -ne $rabbitRoot) { throw 'Unknown RabbitMQ instance. Refusing to manage it.' }
$env:ERLANG_HOME = $settings.erlangHome
$env:RABBITMQ_BASE = $rabbitRoot
$env:RABBITMQ_CONFIG_FILE = $rabbitConfig
$env:RABBITMQ_MNESIA_DIR = Join-Path $rabbitRoot 'data'
$env:RABBITMQ_LOG_BASE = Join-Path $rabbitRoot 'logs'
$env:RABBITMQ_NODENAME = $rabbitNode
$env:RABBITMQ_DIST_PORT = '25673'
$env:ERL_EPMD_PORT = '4370'
$env:ERL_EPMD_ADDRESS = '127.0.0.1'
$rabbitCookie = [IO.File]::ReadAllText((Join-Path $rabbitHomePath '.erlang.cookie')).Trim()
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS = '-setcookie ' + $rabbitCookie + ' -kernel inet_dist_use_interface {127,0,0,1}'
$env:RABBITMQ_CTL_ERL_ARGS = '-setcookie ' + $rabbitCookie + ' -kernel inet_dist_use_interface {127,0,0,1}'
$env:ERL_CRASH_DUMP = Join-Path $rabbitRoot 'erl_crash.dump'
$env:RABBITMQ_PID_FILE = Join-Path $rabbitRoot 'node.pid'
$env:RABBITMQ_ADDRESSES = 'amqp://127.0.0.1:5673'
$env:RABBITMQ_USERNAME = $settings.username
$env:RABBITMQ_PASSWORD = $settings.password
$env:RABBITMQ_VHOST = $settings.vhost
$ctl = Join-Path $settings.rabbitHome 'sbin/rabbitmqctl.bat'
$diagnostics = Join-Path $settings.rabbitHome 'sbin/rabbitmq-diagnostics.bat'

function Invoke-PrivateRabbitCli([string]$Program, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    & $Program @Arguments 2>&1 | ForEach-Object { Write-Output ($_.ToString().Replace($rabbitCookie, '[REDACTED]').Replace($settings.password, '[REDACTED]')) }
    $script:rabbitCliExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
}

if ($Action -eq 'Load') { Write-Output 'RabbitMQ development variables loaded for 127.0.0.1:5673. Credentials omitted.'; return }
if ($Action -eq 'Start') {
    if (!(Test-RabbitPort 5673)) {
        $server = Join-Path $settings.rabbitHome 'sbin/rabbitmq-server.bat'
        $launch = Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /s /c ""' + $server + '" -detached"') -WorkingDirectory $rabbitRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $rabbitRoot 'launcher.out.log') -RedirectStandardError (Join-Path $rabbitRoot 'launcher.err.log')
        if (!$launch.WaitForExit(20000)) { throw 'Dedicated broker launcher timed out. No existing service was stopped.' }
        $launch.Refresh()
        if ($null -ne $launch.ExitCode -and $launch.ExitCode -ne 0) { throw 'Dedicated broker process did not launch. Inspect private broker logs.' }
    }
    for ($attempt = 0; $attempt -lt 80 -and !(Test-RabbitPort 5673); $attempt++) { Start-Sleep -Milliseconds 500 }
    if (!(Test-RabbitPort 5673)) { throw 'The dedicated AMQP listener did not start. Inspect private broker logs.' }
    Invoke-PrivateRabbitCli $diagnostics @('-q', '-n', $rabbitNode, 'check_running')
} elseif ($Action -eq 'Stop') {
    if (!(Test-RabbitPort 5673)) { Write-Output 'Dedicated broker is already stopped. Its data is retained.'; return }
    Invoke-PrivateRabbitCli $ctl @('-q', '-n', $rabbitNode, 'stop')
} else {
    Invoke-PrivateRabbitCli $diagnostics @('-q', '-n', $rabbitNode, 'check_running')
    if ($script:rabbitCliExit -eq 0) { Invoke-PrivateRabbitCli $diagnostics @('-q', '-n', $rabbitNode, 'check_port_connectivity') }
}
if ($script:rabbitCliExit -ne 0) { throw 'RabbitMQ CLI could not verify the dedicated node. No existing service was altered.' }
Write-Output "RabbitMQ $Action completed: node $rabbitNode, AMQP 127.0.0.1:5673. Data is retained."
