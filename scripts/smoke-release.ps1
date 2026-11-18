param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference = 'Stop'
$distribution = (Resolve-Path -LiteralPath $Directory).Path
$archives = @(Get-ChildItem -LiteralPath $distribution -Filter '*.zip' -File)
if ($archives.Count -ne 1) { throw 'Expected one native release archive.' }
$fixture = Join-Path $distribution ('.smoke-' + [Guid]::NewGuid().ToString('N'))
$child = $null
$oldPath = $env:PATH
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archives[0].FullName)
    try {
        $forbidden = @($archive.Entries | Where-Object { $_.FullName -match '(?i)(^|/)(node_modules|runtime)/|(^|/)(node|claude|claudecode|codex|agy|antigravity|cloudflared)\.exe$|\.(ts|mjs|cjs)$' })
        if ($forbidden.Count) { throw 'Distribution contains Node.js, provider binaries or JS/TS sources.' }
    } finally { $archive.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($archives[0].FullName, $fixture)
    $state = Get-Content -LiteralPath (Join-Path $fixture 'current.json') -Raw | ConvertFrom-Json
    $nativeManifest = Get-Content -LiteralPath (Join-Path $distribution 'remote-code-win32-x64.json') -Raw | ConvertFrom-Json
    $exe = Join-Path $fixture "releases/$($state.version)/remote-code.exe"
    $installer = Join-Path $fixture "releases/$($state.version)/scripts/install-dependencies.ps1"
    if (!(Test-Path -LiteralPath $installer -PathType Leaf)) { throw 'Dependency installer is missing from the server archive.' }
    $scriptErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($installer, [ref]$null, [ref]$scriptErrors) | Out-Null
    if ($scriptErrors.Count) { throw 'Packaged dependency installer has syntax errors.' }
    if ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $nativeManifest.executable.sha256) { throw 'Native executable digest mismatch.' }
    $env:PATH = Join-Path $env:WINDIR 'System32'
    $help = & (Join-Path $fixture 'remote-code.exe') --help
    if ($LASTEXITCODE -ne 0 -or ($help -join "`n") -notmatch 'Remote Code') { throw 'Native executable cannot start without Node.js/Go on PATH.' }
    $data = Join-Path $fixture 'data'
    [IO.Directory]::CreateDirectory($data) | Out-Null
    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $portProbe.Start(); $port = $portProbe.LocalEndpoint.Port; $portProbe.Stop()
    $token = [Guid]::NewGuid().ToString('N')
    @{ port=$port; token=$token; usbReverse=$false; quickTunnel=$false; allowLan=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $data 'config.json') -Encoding utf8NoBOM
    $child = Start-Process -FilePath $exe -ArgumentList @('--serve','--data',('"' + $data + '"'),'--no-qr') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixture 'stdout.log') -RedirectStandardError (Join-Path $fixture 'stderr.log')
    $healthy = $false
    for ($attempt=0; $attempt -lt 80; $attempt++) {
        if ($child.HasExited) { throw 'Native server exited during startup.' }
        try {
            $meta = Invoke-RestMethod "http://127.0.0.1:$port/api/meta" -Headers @{ Authorization="Bearer $token" } -TimeoutSec 1
            if ($meta.version -ceq $state.version -and $meta.name -ceq 'remote-code') { $healthy=$true; break }
        } catch { }
        Start-Sleep -Milliseconds 100
    }
    if (!$healthy) { throw 'Native server did not become healthy.' }
    $app = Invoke-RestMethod "http://127.0.0.1:$port/api/app" -Headers @{ Authorization="Bearer $token" } -TimeoutSec 2
    if (!$app.available -or $app.sha256 -notmatch '\A[a-f0-9]{64}\z') { throw 'Bundled APK metadata unavailable.' }
    $apkPath = Join-Path $fixture 'downloaded.apk'
    Invoke-WebRequest "http://127.0.0.1:$port/api/app/apk" -Headers @{ Authorization="Bearer $token" } -OutFile $apkPath -TimeoutSec 10
    if ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $app.sha256) { throw 'Downloaded APK digest mismatch.' }
    Write-Output "Verified native startup and APK transfer without Node.js or Go on PATH (port $port)."
} finally {
    $env:PATH = $oldPath
    if ($child -and !$child.HasExited) { Stop-Process -Id $child.Id -Force; $child.WaitForExit() }
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    if (!$resolvedFixture.StartsWith($distribution + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolvedFixture) -notmatch '\A\.smoke-[a-f0-9]{32}\z') { throw 'Unsafe smoke cleanup path.' }
    if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
