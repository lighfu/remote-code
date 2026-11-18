param([string]$Output='data/prebuilt-apk')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source=Join-Path $projectRoot 'android/app/build/outputs/apk/release'
$metadata=Get-Content -LiteralPath (Join-Path $source 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -cne 'net.ajisaiflow.remotecode' -or $metadata.elements.Count -ne 1) { throw 'Invalid release APK metadata.' }
$entry=$metadata.elements[0]
if ($entry.versionName -notmatch '\A[0-9]+\.[0-9]+\.[0-9]+\z' -or $entry.versionCode -lt 1 -or $entry.outputFile -cne 'app-release.apk') { throw 'Invalid release APK version.' }
$apk=Join-Path $source 'app-release.apk'
$target=[IO.Path]::GetFullPath((Join-Path $projectRoot $Output))
[IO.Directory]::CreateDirectory($target)|Out-Null
$name="remote-code-v$($entry.versionName).apk"
Copy-Item -LiteralPath $apk -Destination (Join-Path $target $name)
@{schemaVersion=1;repository='lighfu/remote-code';platform='android';applicationId=$metadata.applicationId;versionName=$entry.versionName;versionCode=$entry.versionCode;apk=@{name=$name;bytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()}}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $target 'remote-code-android.json') -Encoding utf8NoBOM
