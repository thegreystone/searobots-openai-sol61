param(
    [string[]]$Logs = @('target/final-default.log', 'target/final-gemini.log',
                      'target/final-claude.log', 'target/final-codex.log'),
    [string]$Output = 'benchmarks/holdout.csv',
    [int]$ExpectedGamesPerOpponent = 33
)

$ErrorActionPreference = 'Stop'
$rows = @()
foreach ($log in $Logs) {
    $opponent = ''
    foreach ($line in Get-Content -LiteralPath $log) {
        if ($line -match '^SPARRING:.*? vs (.+?) \[') {
            $opponent = $Matches[1]
        }
        if ($line -match '^\s*(\d+)\s+([0-9a-f]{16})\s+(\S+)\s+(WIN|LOSS|DRAW)\s+(\S+)\s+(\S+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)\s+(\d+)') {
            $rows += [pscustomobject][ordered]@{
                Opponent = $opponent
                Game = [int]$Matches[1]
                Seed = $Matches[2]
                Spawn = $Matches[3]
                Outcome = $Matches[4]
                Reason = $Matches[5]
                SimulatedTime = $Matches[6]
                SolHp = [int]$Matches[7]
                OpponentHp = [int]$Matches[8]
                SolTorpedoes = [int]$Matches[9]
                OpponentTorpedoes = [int]$Matches[10]
                SolCpuMs = [int]$Matches[11]
            }
        }
    }
}
if ($rows.Count -eq 0) { throw 'No completed games found.' }
foreach ($group in $rows | Group-Object Opponent) {
    if ($ExpectedGamesPerOpponent -gt 0 -and $group.Count -ne $ExpectedGamesPerOpponent) {
        throw "$($group.Name): expected $ExpectedGamesPerOpponent games, found $($group.Count)."
    }
}
$parent = Split-Path -Parent $Output
if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$rows | Sort-Object Opponent,Game | Export-Csv -LiteralPath $Output -NoTypeInformation -Encoding UTF8
$rows | Group-Object Opponent | ForEach-Object {
    [pscustomobject]@{
        Opponent = $_.Name
        Wins = @($_.Group | Where-Object Outcome -eq 'WIN').Count
        Losses = @($_.Group | Where-Object Outcome -eq 'LOSS').Count
        Draws = @($_.Group | Where-Object Outcome -eq 'DRAW').Count
        PeakCpuMs = ($_.Group | Measure-Object SolCpuMs -Maximum).Maximum
    }
} | Format-Table -AutoSize
