[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'install-dependencies.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
# Load function definitions only. Never execute the installer entry point or a real package manager.
foreach ($definition in $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
function Assert-True($Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Message
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$testRoot = [IO.Path]::GetFullPath((Join-Path $tempRoot ('remote-code-installer-tests-' + [Guid]::NewGuid().ToString('N'))))
$oldOverride = $env:CLAUDE_BIN
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $testExe = Join-Path $testRoot 'native.exe'
    [IO.File]::WriteAllBytes($testExe, [byte[]]@(0x4d, 0x5a, 0, 0))
    $wrapper = Join-Path $testRoot 'wrapper.exe'
    [IO.File]::WriteAllText($wrapper, '@echo off')
    Assert-True (Test-NativeExecutable $testExe) 'Native executable header was not recognized.'
    Assert-True (-not (Test-NativeExecutable $wrapper)) 'A script disguised as an executable was accepted.'
    $env:CLAUDE_BIN = $testExe
    Assert-True ((Find-DependencyExecutable 'claude') -ceq $testExe) 'Explicit native executable override was ignored.'
    $env:CLAUDE_BIN = $wrapper
    Assert-Rejected { Find-DependencyExecutable 'claude' } 'Invalid override should fail without installing elsewhere.'

    $hash = 'a' * 64
    $release = @{ draft = $false; prerelease = $false; body = ''; assets = @(@{
        name = 'cloudflared-windows-amd64.exe'; digest = "sha256:$hash"
        browser_download_url = 'https://github.com/cloudflare/cloudflared/releases/download/test/cloudflared-windows-amd64.exe'
    }) }
    $asset = Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared'
    Assert-True ($asset.Hash -ceq $hash) 'Release asset digest was not used.'
    $release.body = "cloudflared-windows-amd64.exe: $('b' * 64)"
    Assert-Rejected { Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared' } 'Conflicting release checksums were accepted.'
    $release.assets[0].digest = $null
    $release.body = "cloudflared-windows-amd64.exe: $hash`r`n"
    $asset = Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared'
    Assert-True ($asset.Hash -ceq $hash) 'Cloudflare release-body SHA256 fallback failed.'
    $release.body = ''
    Assert-Rejected { Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared' } 'Missing checksum was accepted.'
    $release.assets[0].digest = "sha256:$hash"
    $release.assets[0].browser_download_url = 'https://github.com/other/repository/releases/download/test/cloudflared-windows-amd64.exe'
    Assert-Rejected { Get-ReleaseAsset $release 'cloudflared-windows-amd64.exe' 'cloudflare/cloudflared' } 'A different repository was accepted.'
    Assert-Rejected { Assert-DownloadUrl 'https://storage.googleapis.com.example.org/antigravity-public/antigravity-cli/payload.exe' 'storage.googleapis.com' '/antigravity-public/antigravity-cli/' } 'Lookalike host was accepted.'
    Assert-Rejected { Assert-DownloadUrl 'http://storage.googleapis.com/antigravity-public/antigravity-cli/payload.exe' 'storage.googleapis.com' '/antigravity-public/antigravity-cli/' } 'Unencrypted download was accepted.'

    function Invoke-WebRequest { param($Uri, $OutFile, [switch]$UseBasicParsing, $TimeoutSec) [IO.File]::WriteAllText($OutFile, 'fixture payload') }
    $download = Join-Path $testRoot 'download.exe'
    Assert-Rejected { Save-VerifiedDownload 'https://example.invalid/payload' $download $hash } 'Mismatched download checksum was accepted.'
    $expectedHash = (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash
    Save-VerifiedDownload 'https://example.invalid/payload' $download $expectedHash | Out-Null

    # Exercise every official fallback with local binary/ZIP fixtures and closed network stubs.
    $fixtureExe = $testExe
    $fixtureHash = (Get-FileHash -LiteralPath $fixtureExe -Algorithm SHA256).Hash
    $fixtureSha512 = (Get-FileHash -LiteralPath $fixtureExe -Algorithm SHA512).Hash
    $zipContent = Join-Path $testRoot 'zip-content'
    [IO.Directory]::CreateDirectory($zipContent) | Out-Null
    Copy-Item -LiteralPath $fixtureExe -Destination (Join-Path $zipContent 'codex-x86_64-pc-windows-msvc.exe')
    Copy-Item -LiteralPath $fixtureExe -Destination (Join-Path $zipContent 'codex-command-runner.exe')
    $fixtureZip = Join-Path $testRoot 'codex-fixture.zip'
    Compress-Archive -Path (Join-Path $zipContent '*') -DestinationPath $fixtureZip
    $fixtureZipHash = (Get-FileHash -LiteralPath $fixtureZip -Algorithm SHA256).Hash
    function Invoke-WebRequest {
        param($Uri, $OutFile, [switch]$UseBasicParsing, $TimeoutSec)
        if ($Uri -like '*.zip') { Copy-Item -LiteralPath $fixtureZip -Destination $OutFile }
        else { Copy-Item -LiteralPath $fixtureExe -Destination $OutFile }
    }
    function Invoke-RestMethod {
        param($Uri, $TimeoutSec, $Headers)
        switch -Exact ($Uri) {
            'https://downloads.claude.ai/claude-code-releases/latest' { return '1.2.3' }
            'https://downloads.claude.ai/claude-code-releases/1.2.3/manifest.json' { return @{ platforms = @{ 'win32-x64' = @{ checksum = $fixtureHash } } } }
            'https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/windows_amd64.json' {
                return @{ version = '1.2.3'; url = 'https://storage.googleapis.com/antigravity-public/antigravity-cli/fixture/agy.exe'; sha512 = $fixtureSha512 }
            }
            'https://api.github.com/repos/openai/codex/releases/latest' {
                return @{ assets = @(@{ name = 'codex-x86_64-pc-windows-msvc.exe.zip'; digest = "sha256:$fixtureZipHash"; browser_download_url = 'https://github.com/openai/codex/releases/download/fixture/codex.zip' }) }
            }
            'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' {
                return @{ assets = @(@{ name = 'cloudflared-windows-amd64.exe'; digest = "sha256:$fixtureHash"; browser_download_url = 'https://github.com/cloudflare/cloudflared/releases/download/fixture/cloudflared.exe' }) }
            }
            default { throw "Unexpected network request in fixtures: $Uri" }
        }
    }
    $stage = Join-Path $testRoot 'stage'
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    foreach ($row in @(@('claude', 'claude.exe'), @('codex', 'codex.exe'), @('antigravity', 'agy.exe'), @('cloudflared', 'cloudflared.exe'))) {
        $bin = Join-Path $testRoot ($row[0] + '\bin')
        Install-OfficialDependency $row[0] $stage $bin 'AMD64' | Out-Null
        Assert-True (Test-NativeExecutable (Join-Path $bin $row[1])) "Official fallback did not install expected native executable: $($row[0])"
    }
    Assert-True (Test-NativeExecutable (Join-Path $testRoot 'codex\bin\codex-command-runner.exe')) 'Codex ZIP companion executable was dropped.'

    $versionExe = Join-Path $testRoot 'version-fixture.exe'
    Add-Type -OutputAssembly $versionExe -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.Threading;
public class DependencyVersionFixture {
    public static int Main(string[] args) {
        if (args.Length != 1 || args[0] != "--version") return 9;
        string name = Environment.GetCommandLineArgs()[0];
        if (name.Contains("timeout")) Thread.Sleep(5000);
        if (name.Contains("failure")) { Console.Error.WriteLine("fixture runtime failure"); return 7; }
        Console.WriteLine("fixture 1.2.3"); return 0;
    }
}
'@
    Assert-True ((Assert-DependencyVersion $versionExe | Out-String).Contains('fixture 1.2.3')) 'Post-install version check did not capture stdout.'
    $failureExe = Join-Path $testRoot 'failure-fixture.exe'
    $timeoutExe = Join-Path $testRoot 'timeout-fixture.exe'
    Copy-Item -LiteralPath $versionExe -Destination $failureExe
    Copy-Item -LiteralPath $versionExe -Destination $timeoutExe
    Assert-Rejected { Assert-DependencyVersion $failureExe } 'Nonzero native version check was accepted.'
    Assert-Rejected { Assert-DependencyVersion $timeoutExe 1 } 'Native version check did not enforce its time limit.'

    function Find-DependencyExecutable { param($Name) return $null }
    function Get-Command { param($Name, $CommandType, $ErrorAction) return @{ Source = 'mock-winget.exe' } }
    function Invoke-RestMethod { throw 'A dry run attempted a network request.' }
    $Plan = $true
    foreach ($row in @(@('claude', 'Anthropic.ClaudeCode'), @('codex', 'OpenAI.Codex'), @('antigravity', 'Google.AntigravityCLI'), @('cloudflared', 'Cloudflare.cloudflared'))) {
        $Target = $row[0]
        $output = Invoke-DependencySetup | Out-String
        Assert-True ($output.Contains('--id ' + $row[1] + ' --exact --source winget --scope user')) "Wrong exact user-scope package for $Target"
        Assert-True ($output.Contains('Plan only:')) "Plan did not stop before installation for $Target"
    }
    # Run the actual entry point with an empty install environment. WhatIf must stop before any installer/network work.
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $start.Arguments = '-NoProfile -File "' + $scriptPath + '" -Target claude -WhatIf'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($name in @('USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'ProgramFiles', 'ProgramFiles(x86)')) { $start.EnvironmentVariables[$name] = $testRoot }
    $start.EnvironmentVariables['PATH'] = Join-Path $env:SystemRoot 'System32'
    $start.EnvironmentVariables.Remove('CLAUDE_BIN')
    $process = [Diagnostics.Process]::Start($start)
    try {
        $resultText = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        Assert-True ($process.ExitCode -eq 0) "WhatIf entry point failed: $resultText"
        Assert-True (-not $resultText.Contains('Installation failed:')) "WhatIf entered a failed installation: $resultText"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'RemoteCode'))) 'WhatIf created an installation directory.'
    } finally { $process.Dispose() }
    Write-Output 'Dependency installer checks passed (isolated fixtures; no real downloads or installs).'
} finally {
    $env:CLAUDE_BIN = $oldOverride
    if ($testRoot.StartsWith($tempRoot + 'remote-code-installer-tests-', [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $testRoot -PathType Container)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
