<#
.SYNOPSIS
    Exercises start-server.ps1 and backup-hourly.ps1 against mock servers in
    throwaway folders under the system temp folder. Touches no real server or save.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\selftest\run-selftest.ps1
#>
param([switch]$KeepFixtures)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$deploy = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $deploy 'BannerholdServer.psm1') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem

$shell = (Get-Process -Id $PID).Path
$mockServer = Join-Path $PSScriptRoot 'mock-server.ps1'
$mockRcon = Join-Path $PSScriptRoot 'mock-rcon.ps1'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("bannerhold deploy selftest " + [guid]::NewGuid().ToString('N').Substring(0, 8))
[void][System.IO.Directory]::CreateDirectory($root)
$script:failures = 0
$script:passes = 0

function Check([bool]$Condition, [string]$Name) {
    if ($Condition) { $script:passes++; Write-Host "  PASS  $Name" -ForegroundColor Green }
    else { $script:failures++; Write-Host "  FAIL  $Name" -ForegroundColor Red }
}

function Get-FreePort {
    $l = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop()
    return $p
}

function Quote([string]$s) { return "'" + $s.Replace("'", "''") + "'" }

function New-Fixture([string]$Name, [string]$Extra = '', [int]$OldBackups = 0) {
    $dir = Join-Path $root "srv $Name"
    $world = Join-Path $dir 'world'
    foreach ($d in @('region', 'playerdata', 'data')) { [void][System.IO.Directory]::CreateDirectory((Join-Path $world $d)) }
    $rng = New-Object System.Random 7
    $bytes = New-Object byte[] 300000; $rng.NextBytes($bytes)
    [System.IO.File]::WriteAllBytes((Join-Path $world 'region/r.0.0.mca'), $bytes)
    Set-Content -LiteralPath (Join-Path $world 'level.dat') -Value 'level'
    Set-Content -LiteralPath (Join-Path $world 'playerdata/player.dat') -Value 'player'
    Set-Content -LiteralPath (Join-Path $world 'data/hearthstead settlement.dat') -Value 'settlement'
    Set-Content -LiteralPath (Join-Path $world 'session.lock') -Value 'lock'
    $port = Get-FreePort
    Set-Content -LiteralPath (Join-Path $dir 'server.properties') -Value ("level-name=world`nserver-port=$port`n" + $Extra)
    Set-Content -LiteralPath (Join-Path $dir 'eula.txt') -Value 'eula=true'
    $backups = Join-Path $dir 'backups'
    [void][System.IO.Directory]::CreateDirectory((Join-Path $backups 'old'))
    for ($i = 1; $i -le $OldBackups; $i++) {
        Set-Content -LiteralPath (Join-Path $backups ('world-20260101-0000{0:D2}.zip' -f $i)) -Value "old $i"
    }
    # Things retention must never touch.
    Set-Content -LiteralPath (Join-Path $backups 'notes.zip') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $backups 'world-20250101-000000.zip.keep') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $backups 'old/world-20250101-000000.zip') -Value 'keep'
    return @{ Dir = $dir; World = $world; Backups = $backups; Port = $port }
}

function Get-Backups($Fixture) {
    return , @(Get-ChildItem -LiteralPath $Fixture.Backups -File | Where-Object { $_.Name -match '^world-\d{8}-\d{6}\.zip$' } | Sort-Object Name)
}

function Get-Lines([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return , @() }
    return , @(Get-Content -LiteralPath $Path)
}

function New-Link([string]$Link, [string]$Target) {
    [void][System.IO.Directory]::CreateDirectory($Target)
    if (Test-BhWindows) { [void](New-Item -ItemType Junction -Path $Link -Value $Target) }
    else { [void](New-Item -ItemType SymbolicLink -Path $Link -Value $Target) }
}

# Flips one byte inside the stored data of $EntryName, keeping length and recorded CRC.
function Invoke-CorruptEntry([string]$ZipPath, [string]$EntryName, [int]$Offset) {
    $bytes = [System.IO.File]::ReadAllBytes($ZipPath)
    $name = [System.Text.Encoding]::UTF8.GetBytes($EntryName)
    for ($i = 0; $i -lt $bytes.Length - 30; $i++) {
        if ($bytes[$i] -ne 0x50 -or $bytes[$i + 1] -ne 0x4B -or $bytes[$i + 2] -ne 3 -or $bytes[$i + 3] -ne 4) { continue }
        $nameLen = [System.BitConverter]::ToUInt16($bytes, $i + 26)
        $extraLen = [System.BitConverter]::ToUInt16($bytes, $i + 28)
        if ($nameLen -ne $name.Length) { continue }
        if ([System.Text.Encoding]::UTF8.GetString($bytes, $i + 30, $nameLen) -ne $EntryName) { continue }
        $at = $i + 30 + $nameLen + $extraLen + $Offset
        $bytes[$at] = $bytes[$at] -bxor 0xFF
        [System.IO.File]::WriteAllBytes($ZipPath, $bytes)
        return $true
    }
    return $false
}

function Start-Child([string]$Command, [hashtable]$Env = @{}) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $shell
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($Command))
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($k in $Env.Keys) { $psi.EnvironmentVariables[$k] = $Env[$k] }
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.AutoFlush = $true
    # Assertions read stdout only: Windows PowerShell 5.1 also serializes host output as
    # CLIXML onto stderr when it is redirected, which would double-count lines.
    $queue = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
    $errQueue = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
    $readers = @((Start-BhLineReader $proc.StandardOutput $queue), (Start-BhLineReader $proc.StandardError $errQueue))
    return @{ Process = $proc; Queue = $queue; ErrQueue = $errQueue; Readers = $readers; Lines = (New-Object System.Collections.Generic.List[string]); ErrLines = (New-Object System.Collections.Generic.List[string]) }
}

function Update-Child($Child) {
    $line = $null
    while ($Child.Queue.TryDequeue([ref]$line)) { $Child.Lines.Add($line); if ($env:SELFTEST_VERBOSE) { Write-Host "    | $line" } }
    while ($Child.ErrQueue.TryDequeue([ref]$line)) { $Child.ErrLines.Add($line) }
}

function Wait-Line($Child, [string]$Pattern, [int]$TimeoutSeconds = 30, [int]$Occurrence = 1) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Update-Child $Child
        if (@($Child.Lines | Where-Object { $_ -match $Pattern }).Count -ge $Occurrence) { return $true }
        if ($Child.Process.HasExited) { Start-Sleep -Milliseconds 300; Update-Child $Child; return (@($Child.Lines | Where-Object { $_ -match $Pattern }).Count -ge $Occurrence) }
        Start-Sleep -Milliseconds 100
    }
    return $false
}

function Wait-Exit($Child, [int]$TimeoutSeconds = 30) {
    if (-not $Child.Process.WaitForExit($TimeoutSeconds * 1000)) { $Child.Process.Kill(); Update-Child $Child; return $null }
    $Child.Process.WaitForExit()
    foreach ($r in $Child.Readers) { Stop-BhLineReader $r }
    Update-Child $Child
    return $Child.Process.ExitCode
}

function Dump-OnFail($Child, [int]$Before) {
    if ($script:failures -gt $Before) {
        Write-Host '    --- child stdout ---' -ForegroundColor DarkGray; $Child.Lines | ForEach-Object { Write-Host "    | $_" -ForegroundColor DarkGray }
        Write-Host '    --- child stderr ---' -ForegroundColor DarkGray; $Child.ErrLines | ForEach-Object { Write-Host "    | $_" -ForegroundColor DarkGray }
    }
}

function Start-Wrapper($Fixture, [string]$Mode = 'normal', [string]$Extra = '') {
    $delay = '-RestartDelaySeconds 1'
    if ($Extra -match 'RestartDelaySeconds') { $delay = '' }
    $cmd = @(
        '$ErrorActionPreference = ''Stop'';',
        '&', (Quote (Join-Path $deploy 'start-server.ps1')),
        '-ServerDir', (Quote $Fixture.Dir),
        $delay,
        $Extra,
        '-ServerCommand', ('@({0}, ''-NoProfile'', ''-ExecutionPolicy'', ''Bypass'', ''-File'', {1}, ''-Mode'', {2}, ''-StateDir'', {3})' -f (Quote $shell), (Quote $mockServer), (Quote $Mode), (Quote $Fixture.Dir)),
        '; exit $LASTEXITCODE'
    ) -join ' '
    return Start-Child $cmd
}

function Start-BackupScript($Fixture, [int]$RconPort, [string]$Password, [string]$Extra = '') {
    # Invoked the way the README shows (& .\backup-hourly.ps1), with the default error preference.
    $cmd = "& $(Quote (Join-Path $deploy 'backup-hourly.ps1')) -ServerDir $(Quote $Fixture.Dir) -RconPort $RconPort -TimeoutSeconds 10 $Extra; exit `$LASTEXITCODE"
    return Start-Child $cmd @{ BANNERHOLD_RCON_PASSWORD = $Password }
}

function Start-MockRcon($Fixture, [int]$Port, [string]$Mode) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $shell
    $psi.Arguments = Join-BhArguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $mockRcon, '-Port', "$Port", '-Password', 'selftest-only', '-Mode', $Mode, '-StateDir', $Fixture.Dir)
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    [void]$proc.StandardOutput.ReadLine()
    return $proc
}

Write-Host "Fixtures: $root"

# ------------------------------------------------------------------ 0
Write-Host "`n[0] console acknowledgement matching (whole log line, NeoForge and vanilla formats)"
$flush = '(Saved the game|Unable to save the game.*)'
$on = '(Automatic saving is now enabled|Saving is already turned on)'
$done = 'Done \(\d+(?:[.,]\d+)?s\)! For help, type "help"'
$accept = @(
    @('[18:12:54] [Server thread/INFO] [minecraft/MinecraftServer]: Saved the game', $flush, 'Saved the game'),
    @('[18:12:54] [Server thread/INFO]: Saved the game', $flush, 'Saved the game'),
    @("[18:12:54] [Server thread/INFO] [minecraft/MinecraftServer]: Saved the game`r", $flush, 'Saved the game'),
    @('[18:12:54] [Server thread/INFO] [minecraft/MinecraftServer]: Unable to save the game (is there enough disk space?)', $flush, 'Unable to save the game (is there enough disk space?)'),
    @('[18:12:54] [Server thread/INFO] [minecraft/MinecraftServer]: Automatic saving is now enabled', $on, 'Automatic saving is now enabled'),
    @('[18:12:54] [Server thread/INFO]: Saving is already turned on', $on, 'Saving is already turned on'),
    @('[18:12:53] [Server thread/INFO] [minecraft/DedicatedServer]: Done (7.283s)! For help, type "help"', $done, 'Done (7.283s)! For help, type "help"'),
    @('[18:12:53] [Server thread/INFO] [minecraft/DedicatedServer]: Done (7,283s)! For help, type "help"', $done, 'Done (7,283s)! For help, type "help"')
)
foreach ($c in $accept) { Check ((Test-BhConsoleLine $c[0] $c[1]) -eq $c[2]) "accepts: $($c[0].TrimEnd())" }
$reject = @(
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Mallory> ]: Saved the game', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: [Not Secure] <Mallory> Saved the game', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Mallory> [20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Saved the game', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: [Rcon: Saved the game]', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: [Tobias: Saved the game]', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Saved the game, said Mallory', $flush),
    @('[20:00:00] [Worker-Main-1/INFO] [minecraft/MinecraftServer]: Saved the game', $flush),
    @('[20:00:00] [Server thread/WARN] [minecraft/MinecraftServer]: Saved the game', $flush),
    @('<Mallory> ]: Saved the game', $flush),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Mallory> ]: Automatic saving is now enabled', $on),
    @('[20:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Mallory> ]: Done (1.0s)! For help, type "help"', $done)
)
foreach ($c in $reject) { Check ($null -eq (Test-BhConsoleLine $c[0] $c[1])) "rejects: $($c[0])" }

Write-Host "`n[0b] a damaged archive is never promoted"
$f = New-Fixture 'corrupt'
$archive = New-BhWorldArchive $f.World $f.Backups 'world'
Check (Invoke-CorruptEntry $archive.Partial 'world/region/r.0.0.mca' 1000) 'flipped one byte in the stored data (same length, same recorded CRC)'
$accepted = $true
try { Complete-BhWorldArchive $archive } catch { $accepted = $false }
Check (-not $accepted) 'Complete-BhWorldArchive rejects it'
Check (-not (Test-Path -LiteralPath $archive.Final) -and -not (Test-Path -LiteralPath $archive.Partial)) 'no .zip and no .partial left'
# The reviewer's exact case: a stored (uncompressed) entry.
$partial = Join-Path $f.Backups 'world-20990101-000000.zip.partial'
$fs = [System.IO.File]::Open($partial, 'CreateNew', 'ReadWrite', 'None')
$zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
$entry = $zip.CreateEntry('world/level.dat', [System.IO.Compression.CompressionLevel]::NoCompression)
$es = $entry.Open(); $payload = [System.Text.Encoding]::ASCII.GetBytes('ORIGINAL-WORLD-DATA'); $es.Write($payload, 0, $payload.Length); $es.Dispose()
$zip.Dispose(); $fs.Dispose()
$sha = [System.Security.Cryptography.SHA256]::Create()
$stored = @{ Partial = $partial; Final = ($partial -replace '\.partial$', ''); Files = 1; Hashes = @{ 'world/level.dat' = [System.BitConverter]::ToString($sha.ComputeHash($payload)) } }
Check (Invoke-CorruptEntry $partial 'world/level.dat' 5) 'flipped one byte of a stored entry'
$accepted = $true
try { Complete-BhWorldArchive $stored } catch { $accepted = $false }
Check (-not $accepted -and -not (Test-Path -LiteralPath $stored.Final)) 'stored-entry corruption rejected'
$archive = New-BhWorldArchive $f.World $f.Backups 'world'
Complete-BhWorldArchive $archive
Check (Test-Path -LiteralPath $archive.Final) 'an undamaged archive is promoted'

# ------------------------------------------------------------------ 1
Write-Host "`n[1] console backup succeeds, retention keeps 12, intentional stop"
$before = $script:failures
$f = New-Fixture 'backup ok' -OldBackups 13
$w = Start-Wrapper $f 'normal' '-BackupIntervalMinutes 0.05'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
Check (Wait-Line $w 'backup written' 40) 'scheduled backup is written'
$w.Process.StandardInput.WriteLine('stop')
$code = Wait-Exit $w
Check ($code -eq 0) "exit code 0 after stop (got $code)"
Check ((@($w.Lines | Where-Object { $_ -match 'not restarting' }).Count -eq 1) -and (@($w.Lines | Where-Object { $_ -match 'restarting in' }).Count -eq 0)) 'no restart after an intentional stop'
Check ((Get-Lines (Join-Path $f.Dir 'mock-starts.log')).Count -eq 1) 'server started exactly once'
$zips = Get-Backups $f
Check ($zips.Count -eq 12) "exactly 12 backups kept (got $($zips.Count))"
Check (-not (Test-Path -LiteralPath (Join-Path $f.Backups 'world-20260101-000001.zip')) -and -not (Test-Path -LiteralPath (Join-Path $f.Backups 'world-20260101-000002.zip'))) 'the two oldest were removed'
Check ((Test-Path -LiteralPath (Join-Path $f.Backups 'notes.zip')) -and (Test-Path -LiteralPath (Join-Path $f.Backups 'world-20250101-000000.zip.keep')) -and (Test-Path -LiteralPath (Join-Path $f.Backups 'old/world-20250101-000000.zip'))) 'unrelated files and subfolders untouched'
$newest = $zips[-1]
Check ($newest.Name -notlike 'world-20260101-*') 'newest backup is the new one'
$z = [System.IO.Compression.ZipFile]::OpenRead($newest.FullName)
$names = @($z.Entries | ForEach-Object { $_.FullName }); $z.Dispose()
Check (($names -contains 'world/level.dat') -and ($names -contains 'world/region/r.0.0.mca') -and ($names -contains 'world/data/hearthstead settlement.dat')) 'archive holds the world files (incl. a name with spaces)'
Check ($names -notcontains 'world/session.lock') 'session.lock is not archived'
$cmds = Get-Lines (Join-Path $f.Dir 'mock-commands.log')
Check (($cmds -join '|') -like 'save-off|save-all flush|save-on*') "command order save-off, save-all flush, save-on (got $($cmds -join ', '))"
Check ((Get-Content -LiteralPath (Join-Path $f.Dir 'mock-saving.txt')) -eq 'on') 'saving is on at the end'
Dump-OnFail $w $before

# ------------------------------------------------------------------ 2
Write-Host "`n[2] flush failure (with a spoofed chat line): no archive, saving restored"
$before = $script:failures
$f = New-Fixture 'flush fail' -OldBackups 12
$w = Start-Wrapper $f 'flushfail-spoof'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup FAILED' 30) 'backup reports failure'
Check (@($w.Lines | Where-Object { $_ -match 'could not save' }).Count -ge 1) 'failure names the server save error, not the chat line'
$cmds = Get-Lines (Join-Path $f.Dir 'mock-commands.log')
Check (($cmds -join '|') -eq 'save-off|save-all flush|save-on') "save-on sent after the failed flush (got $($cmds -join ', '))"
Check ((Get-Content -LiteralPath (Join-Path $f.Dir 'mock-saving.txt')) -eq 'on') 'saving is on again'
Check ((Get-Backups $f).Count -eq 12 -and (Get-Backups $f)[0].Name -eq 'world-20260101-000001.zip') 'all 12 old backups kept'
Check (@(Get-ChildItem -LiteralPath $f.Backups -Filter '*.partial').Count -eq 0) 'no partial archive left'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

# ------------------------------------------------------------------ 3
Write-Host "`n[3] compression failure (locked world file): no archive, old backups kept, then recovery"
$before = $script:failures
$f = New-Fixture 'zip fail' -OldBackups 12
$w = Start-Wrapper $f 'normal'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$held = [System.IO.File]::Open((Join-Path $f.World 'region/r.0.0.mca'), 'Open', 'ReadWrite', 'None')
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup FAILED' 30) 'backup reports failure'
Check (@($w.Lines | Where-Object { $_ -match 'cannot read region/r\.0\.0\.mca' }).Count -ge 1) 'failure names the unreadable file'
Check ((Get-Lines (Join-Path $f.Dir 'mock-commands.log')) -join '|' -eq 'save-off|save-all flush|save-on') 'save-on sent after the failed archive'
Check ((Get-Content -LiteralPath (Join-Path $f.Dir 'mock-saving.txt')) -eq 'on') 'saving is on again'
Check ((Get-Backups $f).Count -eq 12 -and (Get-Backups $f)[0].Name -eq 'world-20260101-000001.zip') 'all 12 old backups kept'
Check (@(Get-ChildItem -LiteralPath $f.Backups -Filter '*.partial').Count -eq 0) 'no partial archive left'
$held.Dispose()
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup written' 30) 'next backup succeeds once the file is readable'
Check ((Get-Backups $f).Count -eq 12 -and (Get-Backups $f)[0].Name -eq 'world-20260101-000002.zip') 'retention then removes exactly the oldest'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

Write-Host "`n[3b] saving switched back on by someone else during the zip: archive discarded"
$before = $script:failures
$f = New-Fixture 'saveon already' -OldBackups 12
$w = Start-Wrapper $f 'saveon-already'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'archive discarded' 30) 'archive discarded'
Check ((Get-Backups $f).Count -eq 12 -and (Get-Backups $f)[0].Name -eq 'world-20260101-000001.zip') 'all 12 old backups kept'
Check (@(Get-ChildItem -LiteralPath $f.Backups -Filter '*.partial').Count -eq 0) 'no partial archive left'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

Write-Host "`n[3c] server hangs after stop: killed after the timeout, not restarted"
$before = $script:failures
$f = New-Fixture 'hang on stop'
$w = Start-Wrapper $f 'hang-on-stop' '-StopTimeoutSeconds 10'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('stop')
Check (Wait-Line $w 'killing it' 30) 'killed after the stop timeout'
Check ((Wait-Exit $w 30) -eq 0) 'wrapper exits 0'
Check ((Get-Lines (Join-Path $f.Dir 'mock-starts.log')).Count -eq 1) 'not restarted'
Dump-OnFail $w $before

Write-Host "`n[3d] acknowledgements: slow flush accepted, too-slow flush abandoned, spoofed save-on not believed"
$before = $script:failures
$f = New-Fixture 'flush slow' -OldBackups 1
$w = Start-Wrapper $f 'flush-slow' '-BackupTimeoutSeconds 5'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup written' 30) 'flush acknowledged after 2 s (timeout 5 s): backup written'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

$before = $script:failures
$f = New-Fixture 'flush too slow' -OldBackups 1
$w = Start-Wrapper $f 'flush-too-slow' '-BackupTimeoutSeconds 5'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup FAILED' 40) 'flush not acknowledged within 5 s: backup failed'
Check ((Get-Content -LiteralPath (Join-Path $f.Dir 'mock-saving.txt')) -eq 'on') 'saving is on again (late save-on acknowledged)'
Check ((Get-Backups $f).Count -eq 1) 'old backup kept, nothing new'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

$before = $script:failures
$f = New-Fixture 'save-on spoof' -OldBackups 1
$w = Start-Wrapper $f 'saveon-spoof' '-BackupTimeoutSeconds 5'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'SAVING IS STILL OFF' 60) 'chat lines claiming save-on are not believed'
Check (Wait-Line $w 'backup FAILED' 30) 'archive discarded'
Check ((Get-Backups $f).Count -eq 1) 'old backup kept, nothing new'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'
Dump-OnFail $w $before

# ------------------------------------------------------------------ 4
Write-Host "`n[4] crash (report written, exit 0) is restarted; stop afterwards ends it"
$before = $script:failures
$f = New-Fixture 'crash once'
$w = Start-Wrapper $f 'crash-once'
Check (Wait-Line $w 'server CRASHED' 30) 'crash detected from the new crash report'
Check (Wait-Line $w 'server is ready' 30) 'server restarted and ready'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'exit code 0 after stop'
Check ((Get-Lines (Join-Path $f.Dir 'mock-starts.log')).Count -eq 2) 'started exactly twice'
Dump-OnFail $w $before

Write-Host "`n[5] non-zero exit (watchdog) is restarted"
$before = $script:failures
$f = New-Fixture 'exit one'
$w = Start-Wrapper $f 'exit1-once'
Check (Wait-Line $w 'exit code 1' 30) 'non-zero exit detected'
Check (Wait-Line $w 'server is ready' 30 2) 'server restarted and ready'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'exit code 0 after stop'
Dump-OnFail $w $before

Write-Host "`n[6] crash loop gives up"
$before = $script:failures
$f = New-Fixture 'crash loop'
$w = Start-Wrapper $f 'crash-always' '-MaxRestarts 2 -RestartDelaySeconds 0'
$code = Wait-Exit $w 60
Check ($code -eq 4) "exit code 4 (got $code)"
Check ((Get-Lines (Join-Path $f.Dir 'mock-starts.log')).Count -eq 3) 'started 3 times (1 + 2 restarts)'
Dump-OnFail $w $before

# ------------------------------------------------------------------ 7
Write-Host "`n[7] no duplicate instance"
$before = $script:failures
$f = New-Fixture 'duplicate'
$w = Start-Wrapper $f 'normal'
Check (Wait-Line $w 'server is ready') 'first instance ready'
$w2 = Start-Wrapper $f 'normal'
$code = Wait-Exit $w2
Check ($code -eq 3) "second instance refused with exit code 3 (got $code)"
Check (@($w2.Lines | Where-Object { $_ -match 'already running' }).Count -ge 1) 'refusal says it is already running'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'first instance stops cleanly'
Check ((Get-Lines (Join-Path $f.Dir 'mock-starts.log')).Count -eq 1) 'the server was started only once'
Dump-OnFail $w2 $before

$f = New-Fixture 'port busy'
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback, $f.Port)
$listener.Start()
$w = Start-Wrapper $f 'normal'
$code = Wait-Exit $w
$listener.Stop()
Check ($code -eq 3 -and @($w.Lines | Where-Object { $_ -match 'already listening' }).Count -ge 1) "refuses when server-port is taken (got $code)"
Check (-not (Test-Path -LiteralPath (Join-Path $f.Dir 'mock-starts.log'))) 'nothing was started'

# ------------------------------------------------------------------ 8
Write-Host "`n[8] refusals: missing eula, world outside the server folder, backup folder inside the world"
$f = New-Fixture 'no eula'
Set-Content -LiteralPath (Join-Path $f.Dir 'eula.txt') -Value 'eula=false'
$w = Start-Wrapper $f 'normal'
Check ((Wait-Exit $w) -eq 3) 'no eula=true: refused'
$f = New-Fixture 'escape'
Set-Content -LiteralPath (Join-Path $f.Dir 'server.properties') -Value "level-name=../srv backup ok/world`nserver-port=$($f.Port)"
$w = Start-Wrapper $f 'normal'
Check ((Wait-Exit $w) -eq 3) 'level-name outside the server folder: wrapper refuses'
$b = Start-BackupScript $f 1 'x'
Check ((Wait-Exit $b) -eq 3) 'level-name outside the server folder: backup script refuses'
$f = New-Fixture 'nested'
$b = Start-BackupScript $f 1 'x' ('-BackupDir ' + (Quote (Join-Path $f.World 'backups')))
Check ((Wait-Exit $b) -eq 3) 'backup folder inside the world: refused'

# ------------------------------------------------------------------ 8b
Write-Host "`n[8b] junctions / symbolic links cannot move the world or backups"
$f = New-Fixture 'linked world'
Remove-Item -LiteralPath $f.World -Recurse -Force
New-Link $f.World (Join-Path $root 'outside world')
Set-Content -LiteralPath (Join-Path $root 'outside world/level.dat') -Value 'level'
$w = Start-Wrapper $f 'normal'
$code = Wait-Exit $w
Check ($code -eq 3 -and @($w.Lines | Where-Object { $_ -match 'junction or symbolic link' }).Count -ge 1) "world folder is a link: wrapper refuses (got $code)"
$b = Start-BackupScript $f 1 'x'
Check ((Wait-Exit $b) -eq 3) 'world folder is a link: backup script refuses'

$f = New-Fixture 'linked backups'
$target = Join-Path $f.World 'inside'
$link = Join-Path $f.Dir 'backups-link'
New-Link $link $target
$b = Start-BackupScript $f 1 'x' ('-BackupDir ' + (Quote $link))
Check ((Wait-Exit $b) -eq 3) 'backup folder is a link into the world: backup script refuses'
$b = Start-BackupScript $f 1 'x' ('-BackupDir ' + (Quote (Join-Path $link 'child')))
Check ((Wait-Exit $b) -eq 3) 'not-yet-existing folder below that link: backup script refuses'
$w = Start-Wrapper $f 'normal' ('-BackupDir ' + (Quote $link))
Check ((Wait-Exit $w) -eq 3) 'backup folder is a link into the world: wrapper refuses'
$w = Start-Wrapper $f 'normal' ('-BackupDir ' + (Quote (Join-Path $link 'child')))
Check ((Wait-Exit $w) -eq 3) 'not-yet-existing folder below that link: wrapper refuses'
Check (@(Get-ChildItem -LiteralPath $target -Force).Count -eq 0) 'link target received no folder, log or archive'
Check (-not (Test-Path -LiteralPath (Join-Path $f.Dir 'mock-starts.log'))) 'no server was started'

$f = New-Fixture 'link inside world' -OldBackups 1
New-Link (Join-Path $f.World 'data/elsewhere') (Join-Path $root 'outside data')
Set-Content -LiteralPath (Join-Path $root 'outside data/secret.txt') -Value 'not part of the world'
$w = Start-Wrapper $f 'normal'
Check (Wait-Line $w 'server is ready') 'server reaches ready'
$w.Process.StandardInput.WriteLine('backup-now')
Check (Wait-Line $w 'backup refused' 30) 'link inside the world: backup refused'
Check ((Get-Lines (Join-Path $f.Dir 'mock-commands.log')).Count -eq 0) 'nothing was sent to the server'
Check ((Get-Backups $f).Count -eq 1) 'old backup kept, nothing new'
$w.Process.StandardInput.WriteLine('stop')
Check ((Wait-Exit $w) -eq 0) 'stops cleanly'

$f = New-Fixture 'ancestor link'
$alias = Join-Path $root 'alias to server'
New-Link $alias $f.Dir
$w = Start-Wrapper @{ Dir = $alias } 'normal'
Check ((Wait-Exit $w) -eq 3) 'server folder reached through a link: refused'

# ------------------------------------------------------------------ 9
Write-Host "`n[9] RCON backups (backup-hourly.ps1)"
$before = $script:failures
$f = New-Fixture 'rcon ok' "enable-rcon=true`n" 12
$port = Get-FreePort
$mock = Start-MockRcon $f $port 'normal'
$b = Start-BackupScript $f $port 'selftest-only'
$code = Wait-Exit $b
Check ($code -eq 0) "backup over RCON succeeds (got $code)"
Check ((Get-Backups $f).Count -eq 12 -and (Get-Backups $f)[0].Name -eq 'world-20260101-000002.zip') 'new archive written, oldest removed'
Check ((Get-Lines (Join-Path $f.Dir 'rcon-commands.log')) -join '|' -eq 'save-off|save-all flush|save-on') 'RCON command order'
$mock.Kill()
Dump-OnFail $b $before

$before = $script:failures
$f = New-Fixture 'rcon flush fail' "enable-rcon=true`n" 3
$mock = Start-MockRcon $f $port 'flushfail'
$b = Start-BackupScript $f $port 'selftest-only'
$code = Wait-Exit $b
Check ($code -eq 1) "flush failure over RCON: exit code 1 (got $code)"
Check ((Get-Lines (Join-Path $f.Dir 'rcon-commands.log')) -join '|' -eq 'save-off|save-all flush|save-on') 'save-on still sent'
Check ((Get-Content -LiteralPath (Join-Path $f.Dir 'rcon-saving.txt')) -eq 'on') 'saving is on again'
Check ((Get-Backups $f).Count -eq 3) 'old backups untouched'
$mock.Kill()
Dump-OnFail $b $before

$before = $script:failures
$f = New-Fixture 'rcon bad password' "enable-rcon=true`n" 3
$mock = Start-MockRcon $f $port 'normal'
$b = Start-BackupScript $f $port 'wrong-password'
$code = Wait-Exit $b
Check ($code -eq 3) "wrong password: refused with exit code 3 (got $code)"
Check ((Get-Lines (Join-Path $f.Dir 'rcon-commands.log')) -join '|' -eq 'AUTH-FAILED') 'no command reached the server'
$mock.Kill()
Dump-OnFail $b $before

$before = $script:failures
$f = New-Fixture 'rcon no save-on' "enable-rcon=true`n" 3
$mock = Start-MockRcon $f $port 'nosaveon'
$b = Start-BackupScript $f $port 'selftest-only'
$code = Wait-Exit $b 90
Check ($code -eq 2) "save-on cannot be confirmed: exit code 2 (got $code)"
Check (@($b.Lines | Where-Object { $_ -match 'SAVING IS STILL OFF' }).Count -ge 1) 'loud warning that saving is still off'
Check (@((Get-Lines (Join-Path $f.Dir 'rcon-commands.log')) | Where-Object { $_ -eq 'save-on' }).Count -eq 3) 'save-on retried 3 times'
Check ((Get-Backups $f).Count -eq 3 -and @(Get-ChildItem -LiteralPath $f.Backups -Filter '*.partial').Count -eq 0) 'unconfirmed archive discarded, old backups kept'
$mock.Kill()
Dump-OnFail $b $before

Write-Host ("`n{0} passed, {1} failed" -f $script:passes, $script:failures)
if ($script:failures -eq 0 -and -not $KeepFixtures) { Remove-Item -LiteralPath $root -Recurse -Force }
else { Write-Host "Fixtures kept in: $root" }
if ($script:failures -gt 0) { exit 1 }
exit 0
