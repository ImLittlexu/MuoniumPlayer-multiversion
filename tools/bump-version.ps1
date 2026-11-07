[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$AlignNewTrees)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$trees = @('1.20.1 recode', '1.21.1', '1.21.10', '1.21.11 recode', '26.1.2', '26.2', '26.3')
$targets = @()
foreach ($tree in $trees) {
    $targets += @{ Path = "$tree/gradle.properties"; Pattern = '(?m)^(mod_version=)([0-9]+\.[0-9]+\.[0-9]+)(\r?)$' }
    $targets += @{ Path = "$tree/src/main/java/com/muoniumplayer/MuoniumPlayerClient.java"; Pattern = '(public static final String MOD_VERSION = ")([0-9]+\.[0-9]+\.[0-9]+)(";)' }
    $targets += @{ Path = "$tree/src/test/java/com/muoniumplayer/core/update/UpdateCheckStaticTest.java"; Pattern = '(private static final String MOD_VERSION = ")([0-9]+\.[0-9]+\.[0-9]+)(";)' }
}
$targets += @{ Path = 'muonium-common/src/main/java/com/muoniumplayer/core/MuoniumPlayerExtension.java'; Pattern = '(public static final String VERSION = ")([0-9]+\.[0-9]+\.[0-9]+)(";)' }
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)
$records = @()
$current = $null
# Validate the complete set before writing any file.
foreach ($target in $targets) {
    $path = Join-Path $repoRoot $target.Path
    $text = [IO.File]::ReadAllText($path, $utf8)
    $regex = [regex]::new($target.Pattern)
    $matches = $regex.Matches($text)
    if ($matches.Count -ne 1) { throw "Expected one version in $($target.Path), found $($matches.Count)" }
    $version = $matches[0].Groups[2].Value
    if ($null -eq $current) { $current = $version }
    if ($version -ne $current) {
        $newTree = $target.Path.StartsWith('1.21.10/') -or $target.Path.StartsWith('26.1.2/')
        if (-not ($AlignNewTrees -and $newTree -and $version -eq '2.4.2')) {
            throw "Version mismatch in $($target.Path): $version vs $current"
        }
    }
    $records += @{ Path = $path; Text = $text; Regex = $regex }
}
$parts = $current.Split('.')
$patch = [int]$parts[2]
if ($patch -eq [int]::MaxValue) { throw 'Patch version exhausted' }
$next = if ($AlignNewTrees) { $current } else { "$($parts[0]).$($parts[1]).$($patch + 1)" }
if ($PSCmdlet.ShouldProcess($repoRoot, "Bump mod version $current -> $next in $($records.Count) files")) {
    foreach ($record in $records) {
        $updated = $record.Regex.Replace($record.Text, [System.Text.RegularExpressions.MatchEvaluator]{
            param($match)
            $match.Groups[1].Value + $next + $match.Groups[3].Value
        })
        [IO.File]::WriteAllText($record.Path, $updated, $utf8)
    }
    Write-Output "$current -> $next ($($records.Count) files)"
}
