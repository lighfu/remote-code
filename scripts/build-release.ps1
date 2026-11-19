param(
    [string]$Output = 'data/releases',
    [string]$Version = '0.19.10',
    [string]$ApkManifest = 'data/prebuilt-apk/remote-code-android.json',
    [string]$ApkRelease = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
$staging = $null
try {
    if ($Version -notmatch '\A(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\z') { throw 'Invalid release version.' }
    $outputRoot = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $projectRoot $Output }))
    [IO.Directory]::CreateDirectory($outputRoot) | Out-Null
    $staging = Join-Path $outputRoot ('.native-build-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($staging) | Out-Null
    if ($ApkRelease) {
        if ($ApkRelease -notmatch '\Av(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\z') { throw 'Invalid APK release tag.' }
        $release = Invoke-RestMethod "https://api.github.com/repos/lighfu/remote-code/releases/tags/$ApkRelease"
        if ($release.draft -or $release.prerelease -or $release.tag_name -cne $ApkRelease) { throw 'APK must use a stable upstream release.' }
        $apkDirectory = Join-Path $staging 'apk'
        [IO.Directory]::CreateDirectory($apkDirectory) | Out-Null
        $metadataAsset = @($release.assets | Where-Object name -CEQ 'remote-code-android.json')
        if ($metadataAsset.Count -ne 1) { throw 'APK metadata asset missing or duplicated.' }
        $metadataPath = Join-Path $apkDirectory 'remote-code-android.json'
        & gh release download $ApkRelease --repo lighfu/remote-code --pattern remote-code-android.json --dir $apkDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Could not download published APK metadata.' }
        if ($metadataAsset[0].digest -cne ('sha256:' + (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant())) { throw 'Published APK metadata SHA-256 mismatch.' }
        $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        $apkAsset = @($release.assets | Where-Object name -CEQ $metadata.apk.name)
        if ($apkAsset.Count -ne 1 -or $apkAsset[0].digest -cne ('sha256:' + $metadata.apk.sha256)) { throw 'Published APK asset does not match metadata.' }
        & gh release download $ApkRelease --repo lighfu/remote-code --pattern $metadata.apk.name --dir $apkDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Could not download published APK.' }
        $ApkManifest = $metadataPath
    }
    $manifestPath = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($ApkManifest)) { $ApkManifest } else { Join-Path $projectRoot $ApkManifest }))
    $app = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($app.schemaVersion -ne 1 -or $app.repository -cne 'lighfu/remote-code' -or $app.platform -cne 'android' -or $app.applicationId -cne 'net.ajisaiflow.remotecode' -or $app.versionName -notmatch '\A[0-9]+\.[0-9]+\.[0-9]+\z' -or $app.versionCode -lt 1 -or $app.apk.name -cne "remote-code-v$($app.versionName).apk" -or $app.apk.bytes -lt 1 -or $app.apk.bytes -gt 67108864 -or $app.apk.sha256 -notmatch '\A[a-f0-9]{64}\z') { throw 'Invalid Android release manifest.' }
    $apkPath = Join-Path (Split-Path -Parent $manifestPath) $app.apk.name
    $apkInfo = Get-Item -LiteralPath $apkPath
    if (($apkInfo.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $apkInfo.Length -ne $app.apk.bytes -or (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $app.apk.sha256) { throw 'APK SHA-256 or size mismatch.' }
    $payload = Join-Path $staging 'payload'
    $application = Join-Path $payload "releases/$Version"
    $android = Join-Path $application 'android'
    [IO.Directory]::CreateDirectory($android) | Out-Null
    $executable = Join-Path $application 'remote-code.exe'
    $previousTarget = @{ GOOS=$env:GOOS; GOARCH=$env:GOARCH; CGO_ENABLED=$env:CGO_ENABLED }
    try {
        $env:GOOS = 'windows'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
        & go build -trimpath -ldflags "-s -w -X github.com/lighfu/remote-code/internal/host.Version=$Version" -o $executable ./cmd/remote-code
        if ($LASTEXITCODE -ne 0) { throw 'Native server build failed.' }
    } finally {
        foreach ($key in $previousTarget.Keys) { [Environment]::SetEnvironmentVariable($key, $previousTarget[$key], 'Process') }
    }
    Copy-Item -LiteralPath $executable -Destination (Join-Path $payload 'remote-code.exe')
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $android $app.apk.name)
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $android 'remote-code-android.json')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'start.cmd') -Destination (Join-Path $payload 'start.cmd')
    $installerScripts = Join-Path $application 'scripts'
    [IO.Directory]::CreateDirectory($installerScripts) | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/install-dependencies.ps1') -Destination $installerScripts
    @{ schemaVersion=2; repository='lighfu/remote-code'; launcherProtocol=3; runtime='native-go' } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $payload 'portable.json') -Encoding utf8NoBOM
    @{ schemaVersion=2; version=$Version; highestVersion=$Version; previousVersion=$null; failedVersions=@() } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $payload 'current.json') -Encoding utf8NoBOM
    @"
Remote Code $Version - Windows x64

1. Extract into a new folder.
2. Run start.cmd.
3. Open 5 Setup to install the agents you use and cloudflared for Quick Tunnel.
4. Sign into each installed agent on this PC.
5. Install the Android APK from its QR, then scan the connection QR in the app.

Node.js, Java and Go are not required on this PC.
Setup uses WinGet when available, or each provider's official Windows downloads.
The installer can also be run manually from releases/$Version/scripts/install-dependencies.ps1.
Data stays in data/. Keep this folder when updating.
Server updates are offered at startup and require confirmation. Running servers are never replaced automatically.
"@ | Set-Content -LiteralPath (Join-Path $payload 'README.txt') -Encoding utf8NoBOM
    $licenses = Join-Path $application 'licenses'
    [IO.Directory]::CreateDirectory($licenses) | Out-Null
    $goRoot = (& go env GOROOT).Trim()
    Copy-Item -LiteralPath (Join-Path $goRoot 'LICENSE') -Destination (Join-Path $licenses 'Go-LICENSE.txt')
    $modules = & go list -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' all
    if ($LASTEXITCODE -ne 0) { throw 'Could not collect module licenses.' }
    foreach ($module in $modules) {
        $parts = $module.Split('|')
        if (!$parts[1] -or !$parts[2]) { continue }
        $license = Get-ChildItem -LiteralPath $parts[2] -File | Where-Object { $_.Name -match '\A(LICENSE|COPYING)(\..*)?\z' } | Select-Object -First 1
        if (!$license) { throw "Dependency license missing: $($parts[0])" }
        Copy-Item -LiteralPath $license.FullName -Destination (Join-Path $licenses (($parts[0] -replace '[^a-zA-Z0-9._-]', '_') + '-LICENSE.txt'))
    }
    $modules | Set-Content -LiteralPath (Join-Path $licenses 'modules.txt') -Encoding utf8NoBOM
    $archiveName = "remote-code-v$Version-win32-x64.zip"
    $archivePath = Join-Path $outputRoot $archiveName
    if (Test-Path -LiteralPath $archivePath) { throw 'Release archive already exists; use a new output directory.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($payload, $archivePath, [IO.Compression.CompressionLevel]::SmallestSize, $false)
    $archive = Get-Item -LiteralPath $archivePath
    $nativeManifest = @{
        schemaVersion=2; repository='lighfu/remote-code'; platform='win32-x64'; version=$Version; tag="v$Version"; launcherProtocol=3; runtime='native-go'
        archive=@{ name=$archiveName; bytes=$archive.Length; sha256=(Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() }
        executable=@{ name='remote-code.exe'; bytes=(Get-Item -LiteralPath $executable).Length; sha256=(Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $nativeManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'remote-code-win32-x64.json') -Encoding utf8NoBOM
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $outputRoot 'remote-code-android.json')
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $outputRoot $app.apk.name)
    $names = @($archiveName, $app.apk.name, 'remote-code-android.json', 'remote-code-win32-x64.json')
    $names | ForEach-Object { (Get-FileHash -LiteralPath (Join-Path $outputRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_ } | Set-Content -LiteralPath (Join-Path $outputRoot 'SHA256SUMS.txt') -Encoding utf8NoBOM
    Write-Output "Native release: $archivePath ($($archive.Length) bytes)"
} finally {
    if ($staging) {
        $resolvedStage = [IO.Path]::GetFullPath($staging)
        if (!$resolvedStage.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolvedStage) -notmatch '\A\.native-build-[a-f0-9]{32}\z') { throw 'Unsafe staging cleanup path.' }
        if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
    }
    Pop-Location
}
