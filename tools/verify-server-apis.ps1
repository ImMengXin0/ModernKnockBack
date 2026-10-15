param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [Parameter(Mandatory = $true)][string]$CoreDirectory,
    [string]$VanillaBundle = ''
)
$ErrorActionPreference = 'Stop'
$dependencyFile = "$Root/target/test-dependencies.txt"
if (!(Test-Path -LiteralPath $dependencyFile)) { throw 'Run mvn dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-dependencies.txt first' }
$testLibraries = @(([System.IO.File]::ReadAllText($dependencyFile).Trim() -split ';') | Where-Object {
    [System.IO.Path]::GetFileName($_) -match '^(mockito-core|byte-buddy|byte-buddy-agent|objenesis)-'
})
$sources = @(Get-ChildItem -LiteralPath "$Root/src/main/java" -Filter '*.java' -Recurse | ForEach-Object FullName)
$bundles = @{ leaf = 'leaf-1.21.11-178.jar'; asp = 'asp-server.jar'; folia = 'folia-1.21.11-14.jar' }
foreach ($core in @('leaf', 'asp', 'folia')) {
    $directory = "$Root/target/compat/$core/runtime"
    New-Item -ItemType Directory -Force "$directory/cache" | Out-Null
    if ($VanillaBundle -and !(Test-Path -LiteralPath "$directory/cache/mojang_1.21.11.jar")) {
        Copy-Item -LiteralPath $VanillaBundle -Destination "$directory/cache/mojang_1.21.11.jar"
    }
    # Apply server AND library patches; simply extracting META-INF/libraries omits patched Netty.
    # patchonly exits before any Minecraft server starts. The vanilla bundle is downloaded if absent.
    Push-Location -LiteralPath $directory
    try {
        & java '-Dpaperclip.patchonly=true' -jar (Join-Path $CoreDirectory $bundles[$core])
        if ($LASTEXITCODE -ne 0) { throw "$core patch extraction failed" }
    } finally { Pop-Location }
    $libraries = @(Get-ChildItem -LiteralPath "$directory/libraries" -Filter '*.jar' -Recurse | ForEach-Object FullName)
    if ($libraries.Count -eq 0) { throw "No bundled libraries found for $core" }
    $output = "$directory/compiled"
    New-Item -ItemType Directory -Force $output | Out-Null
    & javac '-J-Duser.language=en' '-J-Duser.country=US' -encoding UTF-8 --release 21 -proc:none -classpath ($libraries -join ';') -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw "$core API compilation failed" }
    Write-Output "$core 1.21.11: all plugin sources compiled against the server's actual runtime libraries"
    $native = @(Get-ChildItem -LiteralPath "$directory/versions" -Filter '*.jar' -Recurse | ForEach-Object FullName)
    $probeClasspath = (@($output) + $native + $libraries + $testLibraries) -join ';'
    & javac '-J-Duser.language=en' '-J-Duser.country=US' -encoding UTF-8 --release 21 -proc:none -classpath $probeClasspath -d $output "$Root/tools/ServerBridgeProbe.java"
    if ($LASTEXITCODE -ne 0) { throw "$core bridge probe compilation failed" }
    # Java argument file avoids Windows' command-line limit; it is not shell code.
    @('-classpath', ('"' + $probeClasspath.Replace('\', '/') + '"'), 'cn.mx.modernkb.compat.ServerBridgeProbe') |
        Set-Content -LiteralPath "$directory/probe.args" -Encoding utf8NoBOM
    Push-Location -LiteralPath $directory
    try {
        & java '@probe.args'
        if ($LASTEXITCODE -ne 0) { throw "$core native bridge probe failed" }
    } finally { Pop-Location }
    Write-Output "$core 1.21.11: projectile collision regression, player push exemptions, velocity symbols, team packets and channel lifecycle passed"
}
