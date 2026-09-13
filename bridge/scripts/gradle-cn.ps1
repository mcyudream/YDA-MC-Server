param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

# Builds the Velocity + Fabric bridge with a mirror-downloaded Gradle, matching mods/scripts/gradle-cn.ps1.
# Unlike that script this one does not hardcode a JDK path: it uses JAVA_HOME when set, and otherwise
# looks for a JDK 25 next to the checkout or in the usual install locations.
$ErrorActionPreference = 'Stop'
$GradleVersion = '9.5.1'
$MirrorBase = $env:GRADLE_DIST_MIRROR
if ([string]::IsNullOrWhiteSpace($MirrorBase)) {
    $MirrorBase = 'https://mirrors.cloud.tencent.com/gradle'
}

$CacheDir = Join-Path $env:TEMP 'yudream-gradle'
$ZipPath = Join-Path $CacheDir "gradle-$GradleVersion-bin.zip"
$DistDir = Join-Path $CacheDir "gradle-$GradleVersion"
$GradleBat = Join-Path $DistDir 'bin\gradle.bat'

New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

if (!(Test-Path $GradleBat)) {
    if (Test-Path $ZipPath) {
        Remove-Item -LiteralPath $ZipPath -Force
    }
    $DownloadUrl = "$MirrorBase/gradle-$GradleVersion-bin.zip"
    Write-Host "Downloading Gradle $GradleVersion from $DownloadUrl"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing $DownloadUrl -OutFile $ZipPath
    Expand-Archive -Force $ZipPath $CacheDir
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or !(Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $candidates = @(
        (Join-Path $PSScriptRoot '..\..\.toolchain\jdk25'),
        (Join-Path $PSScriptRoot '..\..\.toolchain\jdk21')
    ) + (Get-ChildItem "$env:USERPROFILE\.jdks", 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })

    $resolved = $null
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\javac.exe'))) {
            $resolved = (Resolve-Path $candidate).Path
            break
        }
    }
    if (-not $resolved) {
        throw 'No JDK found. Set JAVA_HOME to a JDK 25 install (Minecraft 26.2 needs Java 25).'
    }
    $env:JAVA_HOME = $resolved
}

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
& $GradleBat @GradleArgs
exit $LASTEXITCODE
