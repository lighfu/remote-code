[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claude', 'codex', 'antigravity', 'cloudflared')]
    [string]$Target,
    [switch]$Plan
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$OutputEncoding = New-Object Text.UTF8Encoding($false)
try { [Console]::OutputEncoding = $OutputEncoding } catch { }

function Test-NativeExecutable([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or [IO.Path]::GetExtension($Path) -ine '.exe' -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $stream = $null
    try {
        $stream = [IO.File]::OpenRead($Path)
        return $stream.ReadByte() -eq 0x4d -and $stream.ReadByte() -eq 0x5a
    } catch { return $false } finally { if ($stream) { $stream.Dispose() } }
}

function Find-DependencyExecutable([string]$Name) {
    $leaf = @{ claude = 'claude.exe'; codex = 'codex.exe'; antigravity = 'agy.exe'; cloudflared = 'cloudflared.exe' }[$Name]
    $overrideName = @{ claude = 'CLAUDE_BIN'; codex = 'CODEX_BIN'; antigravity = 'AGY_BIN'; cloudflared = 'CLOUDFLARED_BIN' }[$Name]
    $override = [Environment]::GetEnvironmentVariable($overrideName)
    if ($override) {
        if (Test-NativeExecutable $override) { return [IO.Path]::GetFullPath($override) }
        throw "$overrideName points to an invalid native executable: $override. Correct or remove the override before installing."
    }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "RemoteCode\dependencies\$Name\bin\$leaf"),
        (Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\$leaf"),
        (Join-Path $env:USERPROFILE ".local\bin\$leaf"),
        (Join-Path $env:USERPROFILE ".gemini\bin\$leaf")
    )
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles "WinGet\Links\$leaf" }
    $command = Get-Command $leaf -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { $candidates += $command.Source }
    if ($Name -eq 'antigravity') { $candidates += Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe' }
    if ($Name -eq 'cloudflared') {
        if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'cloudflared\cloudflared.exe' }
        if (${env:ProgramFiles(x86)}) { $candidates += Join-Path ${env:ProgramFiles(x86)} 'cloudflared\cloudflared.exe' }
    }
    if ($Name -eq 'codex') {
        $patterns = @(
            (Join-Path $env:LOCALAPPDATA 'Programs\Codex\resources\codex.exe')
        )
        if ($env:ProgramFiles) {
            $patterns += Join-Path $env:ProgramFiles 'WindowsApps\OpenAI.Codex*\resources\codex.exe'
            $patterns += Join-Path $env:ProgramFiles 'WindowsApps\OpenAI.Codex*\app\resources\codex.exe'
        }
        $moduleRoots = @((Join-Path $env:APPDATA 'npm\node_modules'))
        $wrapper = Get-Command codex.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($wrapper) { $moduleRoots += Join-Path (Split-Path $wrapper.Source -Parent) 'node_modules' }
        foreach ($modules in $moduleRoots) {
            foreach ($package in @('@openai\codex', '@openai\codex-win32-x64', '@openai\codex\node_modules\@openai\codex-win32-x64', '@openai\codex-win32-arm64', '@openai\codex\node_modules\@openai\codex-win32-arm64')) {
                $patterns += Join-Path $modules "$package\vendor\*\bin\codex.exe"
                $patterns += Join-Path $modules "$package\vendor\*\codex\codex.exe"
            }
        }
        foreach ($pattern in $patterns) {
            $candidates += @(Get-Item -Path $pattern -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-NativeExecutable $candidate) { return [IO.Path]::GetFullPath($candidate) }
    }
    return $null
}

function Assert-DownloadUrl([string]$Url, [string]$HostName, [string]$PathPrefix) {
    $uri = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -cne 'https' -or
        $uri.Host -ine $HostName -or -not $uri.IsDefaultPort -or $uri.UserInfo -or
        -not $uri.AbsolutePath.StartsWith($PathPrefix, [StringComparison]::Ordinal)) {
        throw "The release supplied an unexpected download URL: $Url"
    }
}

function Get-ReleaseAsset($Release, [string]$Name, [string]$Repository) {
    $assets = @($Release.assets | Where-Object { $_.name -ceq $Name })
    if ($Release.draft -or $Release.prerelease -or $assets.Count -ne 1) {
        throw "A unique stable release asset was not found: $Repository / $Name"
    }
    $asset = $assets[0]
    Assert-DownloadUrl $asset.browser_download_url 'github.com' "/$Repository/releases/download/"
    $digest = [string]$asset.digest
    $checksum = ''
    if ($digest) {
        if ($digest -notmatch '\Asha256:([a-fA-F0-9]{64})\z') { throw "Invalid SHA256 digest for $Name" }
        $checksum = $Matches[1].ToLowerInvariant()
    }
    $bodyMatches = [regex]::Matches([string]$Release.body, '(?m)^' + [regex]::Escape($Name) + ':\s*([a-fA-F0-9]{64})\s*$')
    if ($bodyMatches.Count -gt 1) { throw "Ambiguous release checksums for $Name" }
    if ($bodyMatches.Count -eq 1) {
        $bodyChecksum = $bodyMatches[0].Groups[1].Value.ToLowerInvariant()
        if ($checksum -and $checksum -cne $bodyChecksum) { throw "Release checksums disagree for $Name" }
        $checksum = $bodyChecksum
    }
    if (-not $checksum) { throw "The official release has no SHA256 checksum for $Name; no file was installed." }
    return @{ Url = [string]$asset.browser_download_url; Hash = $checksum }
}

function Save-VerifiedDownload([string]$Url, [string]$Destination, [string]$Hash, [string]$Algorithm = 'SHA256') {
    $length = if ($Algorithm -eq 'SHA512') { 128 } else { 64 }
    if ($Algorithm -notin @('SHA256', 'SHA512') -or $Hash -notmatch "\A[a-fA-F0-9]{$length}\z") {
        throw "Missing or invalid $Algorithm checksum."
    }
    Write-Output "Downloading: $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -TimeoutSec 300
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash
    if ($actual -ine $Hash) { throw "$Algorithm verification failed. The downloaded file will not be installed." }
    Write-Output "$Algorithm verified."
}

function Assert-DependencyVersion([string]$Path, [int]$TimeoutSeconds = 20) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $Path
    $start.Arguments = '--version'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($start)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit(1000) | Out-Null
            throw "The installed executable did not answer --version within $TimeoutSeconds seconds: $Path"
        }
        if (-not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($stdout, $stderr), 1000)) {
            throw "The installed executable did not close its --version output streams: $Path"
        }
        $versionText = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Trim()
        if ($process.ExitCode -ne 0) {
            throw "The native executable cannot start (exit $($process.ExitCode)): $versionText. Check its official Windows runtime requirements."
        }
        if (-not $versionText) { throw "The native executable returned no --version output: $Path" }
        Write-Output "Version check: $versionText"
    } finally { if ($process) { $process.Dispose() } }
}

function Install-OfficialDependency([string]$Name, [string]$Stage, [string]$BinDirectory, [string]$Architecture) {
    $headers = @{ 'User-Agent' = 'RemoteCode-dependency-setup'; Accept = 'application/vnd.github+json' }
    $stagedBinary = Join-Path $Stage 'download.exe'
    $leaf = @{ claude = 'claude.exe'; codex = 'codex.exe'; antigravity = 'agy.exe'; cloudflared = 'cloudflared.exe' }[$Name]
    switch ($Name) {
        'claude' {
            # Mirrors https://claude.ai/install.ps1, including the official platform manifest checksum.
            $base = 'https://downloads.claude.ai/claude-code-releases'
            $version = ([string](Invoke-RestMethod -Uri "$base/latest" -TimeoutSec 60)).Trim()
            if ($version -notmatch '\A\d+\.\d+\.\d+(-[a-zA-Z0-9.-]+)?\z') { throw 'The Claude release service did not return a version number.' }
            $platform = if ($Architecture -eq 'ARM64') { 'win32-arm64' } else { 'win32-x64' }
            $manifest = Invoke-RestMethod -Uri "$base/$version/manifest.json" -TimeoutSec 60
            $checksum = [string]$manifest.platforms.$platform.checksum
            Save-VerifiedDownload "$base/$version/$platform/claude.exe" $stagedBinary $checksum
        }
        'antigravity' {
            # The native agy CLI, not the Antigravity desktop/IDE package. Source: https://antigravity.google/cli/install.ps1
            $platform = if ($Architecture -eq 'ARM64') { 'windows_arm64' } else { 'windows_amd64' }
            $url = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/$platform.json"
            Write-Output "Release manifest: $url"
            $manifest = Invoke-RestMethod -Uri $url -TimeoutSec 60
            Assert-DownloadUrl $manifest.url 'storage.googleapis.com' '/antigravity-public/antigravity-cli/'
            Save-VerifiedDownload $manifest.url $stagedBinary ([string]$manifest.sha512) 'SHA512'
        }
        'codex' {
            $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/openai/codex/releases/latest' -Headers $headers -TimeoutSec 60
            $triple = if ($Architecture -eq 'ARM64') { 'aarch64-pc-windows-msvc' } else { 'x86_64-pc-windows-msvc' }
            $asset = Get-ReleaseAsset $release "codex-$triple.exe.zip" 'openai/codex'
            $archive = Join-Path $Stage 'codex.zip'
            Save-VerifiedDownload $asset.Url $archive $asset.Hash
            $expanded = Join-Path $Stage 'expanded'
            Expand-Archive -LiteralPath $archive -DestinationPath $expanded
            $sourceBinary = Join-Path $expanded "codex-$triple.exe"
            if (-not (Test-NativeExecutable $sourceBinary)) { throw 'The Codex archive does not contain its expected native executable.' }
            # Keep the command runner and sandbox setup beside the CLI, as in its official WinGet ZIP package.
            [IO.Directory]::CreateDirectory($BinDirectory) | Out-Null
            Copy-Item -Path (Join-Path $expanded '*') -Destination $BinDirectory -Recurse -Force
            Copy-Item -LiteralPath $sourceBinary -Destination (Join-Path $BinDirectory $leaf) -Force
            return
        }
        'cloudflared' {
            $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers $headers -TimeoutSec 60
            # Cloudflare publishes Windows amd64 binaries, which also run under Windows ARM64 emulation.
            $asset = Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared'
            Save-VerifiedDownload $asset.Url $stagedBinary $asset.Hash
        }
    }
    if (-not (Test-NativeExecutable $stagedBinary)) { throw 'The download is not a Windows native executable.' }
    [IO.Directory]::CreateDirectory($BinDirectory) | Out-Null
    Copy-Item -LiteralPath $stagedBinary -Destination (Join-Path $BinDirectory $leaf) -Force
}

function Invoke-DependencySetup {
    $package = @{ claude = 'Anthropic.ClaudeCode'; codex = 'OpenAI.Codex'; antigravity = 'Google.AntigravityCLI'; cloudflared = 'Cloudflare.cloudflared' }[$Target]
    $source = @{ claude = 'https://code.claude.com/docs/en/setup'; codex = 'https://github.com/openai/codex'; antigravity = 'https://antigravity.google/docs/getting-started'; cloudflared = 'https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/' }[$Target]
    Write-Output "Dependency: $Target"
    Write-Output "Official source: $source"
    $existing = Find-DependencyExecutable $Target
    if ($existing) {
        Write-Output "Already installed: $existing"
        if ($Plan) { Write-Output 'Plan only: no installation is needed; the existing executable will be used.' }
        elseif (-not $WhatIfPreference) { Assert-DependencyVersion $existing }
        return
    }
    $binDirectory = Join-Path $env:LOCALAPPDATA "RemoteCode\dependencies\$Target\bin"
    $winget = Get-Command winget.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $winget) {
        $wingetPath = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe'
        if (Test-Path -LiteralPath $wingetPath -PathType Leaf) { $winget = @{ Source = $wingetPath } }
    }
    $arguments = @('install', '--id', $package, '--exact', '--source', 'winget', '--scope', 'user', '--silent', '--disable-interactivity', '--accept-package-agreements', '--accept-source-agreements')
    if ($winget) { Write-Output ('Preferred installer: winget ' + ($arguments -join ' ')) }
    Write-Output "Official native fallback directory: $binDirectory"
    Write-Output 'Downloads are verified against the official release checksum. Account sign-in is a separate step.'
    if ($Plan) { Write-Output 'Plan only: no files downloaded, packages installed, or settings changed.'; return }
    if (-not $PSCmdlet.ShouldProcess($Target, 'Install this native dependency for the current Windows user')) { return }
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'This installer requires 64-bit Windows.' }
    if ($winget) {
        Write-Output "Installing $package with WinGet..."
        $savedErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $winget.Source @arguments 2>&1 | Out-Host
            $wingetExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedErrorAction }
        $existing = Find-DependencyExecutable $Target
        if ($existing) {
            Assert-DependencyVersion $existing
            Write-Output "Installed: $existing"
            if ($Target -ne 'cloudflared') {
                Write-Output ("To sign in, run this in PowerShell: & '" + $existing.Replace("'", "''") + "'")
            }
            return
        }
        Write-Output "WinGet did not produce a discoverable native executable (exit $wingetExit). Trying the official native download."
    } else { Write-Output 'WinGet is unavailable. Using the official native download.' }
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    if ($architecture -notin @('AMD64', 'ARM64')) { throw "Unsupported Windows architecture: $architecture" }
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $stage = [IO.Path]::GetFullPath((Join-Path $tempRoot ('remote-code-dependency-' + [Guid]::NewGuid().ToString('N'))))
    try {
        [IO.Directory]::CreateDirectory($stage) | Out-Null
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Install-OfficialDependency $Target $stage $binDirectory $architecture
        $existing = Find-DependencyExecutable $Target
        if (-not $existing) { throw 'The installer finished without a discoverable native executable.' }
        Assert-DependencyVersion $existing
        Write-Output "Installed: $existing"
        if ($Target -ne 'cloudflared') {
            Write-Output ("To sign in, run this in PowerShell: & '" + $existing.Replace("'", "''") + "'")
        }
    } finally {
        # This path is generated above; never recursively delete a dependency install or an arbitrary caller-supplied path.
        if ($stage.StartsWith($tempRoot + 'remote-code-dependency-', [StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $stage -PathType Container)) {
            Remove-Item -LiteralPath $stage -Recurse -Force
        }
    }
}

try {
    Invoke-DependencySetup
    exit 0
} catch {
    Write-Output "Installation failed: $($_.Exception.Message)"
    Write-Output 'Check network access and directory permissions, then retry. See the official source URL above for manual installation.'
    exit 1
}
