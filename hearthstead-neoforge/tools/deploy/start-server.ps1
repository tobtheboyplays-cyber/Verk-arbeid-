<#
.SYNOPSIS
    Starts the Bannerhold (NeoForge 1.21.1) dedicated server with a 6 GB G1GC heap,
    restarts it after a crash, and backs the world up every hour.

.DESCRIPTION
    Type server commands in this window as usual. They are passed to the server.
      stop        stops the server for good (no restart).
      backup-now  runs a backup immediately (handled here, not sent to the server).

    Restart rules:
      * exit code 0 and no new file in crash-reports\  -> intentional stop, no restart
        (console "stop" or an operator's /stop).
      * a new crash report or a non-zero exit code     -> restart after -RestartDelaySeconds.
      * more than -MaxRestarts crashes within -RestartWindowMinutes -> give up.
    A second copy of this script for the same folder, or any server already
    listening on server-port, is refused, so there is never a duplicate instance.

    Backups (-Backup):
      Console (default)  through this window's server console; no RCON needed.
      Rcon               through RCON (needs enable-rcon and -RconPassword).
      None               no backups from this script.
    See backup-hourly.ps1 for the backup guarantees.

    No path, password or address is stored in this file.

.PARAMETER ServerDir
    The NeoForge server folder (contains server.properties, libraries\, mods\). Required.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\start-server.ps1 -ServerDir 'D:\Bannerhold Server'

.NOTES
    Exit codes: 0 stopped on purpose; 3 refused to start; 4 gave up after repeated crashes;
    5 the wrapper itself failed (see the last error line).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [ValidatePattern('^\d+[MG]$')][string]$Heap = '6G',
    [string]$Java = 'java',
    [string]$NeoForgeVersion = '21.1.248',
    [ValidateSet('Console', 'Rcon', 'None')][string]$Backup = 'Console',
    [ValidateRange(0.01, 10080.0)][double]$BackupIntervalMinutes = 60,
    [ValidateRange(1, 1000)][int]$BackupKeep = 12,
    [string]$BackupDir,
    [System.Security.SecureString]$RconPassword,
    [ValidateRange(1, 100)][int]$MaxRestarts = 3,
    [ValidateRange(1, 1440)][int]$RestartWindowMinutes = 10,
    [ValidateRange(0, 600)][int]$RestartDelaySeconds = 15,
    # After "stop", how long the server may take to exit before it is killed.
    [ValidateRange(10, 3600)][int]$StopTimeoutSeconds = 120,
    # Replaces the java command line entirely (program, then its arguments). For tests or a custom launcher.
    [string[]]$ServerCommand
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BannerholdServer.psm1') -Force

# Aikar's G1 flags for heaps below 12 GB (https://docs.papermc.io/paper/aikars-flags).
$G1Flags = @(
    '-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200',
    '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC', '-XX:+AlwaysPreTouch',
    '-XX:G1NewSizePercent=30', '-XX:G1MaxNewSizePercent=40', '-XX:G1HeapRegionSize=8M',
    '-XX:G1ReservePercent=20', '-XX:G1HeapWastePercent=5', '-XX:G1MixedGCCountTarget=4',
    '-XX:InitiatingHeapOccupancyPercent=15', '-XX:G1MixedGCLiveThresholdPercent=90',
    '-XX:G1RSetUpdatingPauseTimePercent=5', '-XX:SurvivorRatio=32', '-XX:+PerfDisableSharedMem',
    '-XX:MaxTenuringThreshold=1'
)

function Stop-WithCode([int]$Code, [string]$Message) {
    if ($Code -eq 0) { Write-BhLog $Message } else { Write-BhLog $Message ERROR }
    exit $Code
}

# ---------------------------------------------------------------- checks (nothing is started yet)

try {
    $ServerDir = Resolve-BhFullPath $ServerDir
    if (-not (Test-Path -LiteralPath $ServerDir -PathType Container)) { throw "server folder not found: $ServerDir" }
} catch {
    Stop-WithCode 3 "refused: $($_.Exception.Message)"
}

$instanceLock = Open-BhExclusiveLock (Join-Path $ServerDir '.bannerhold-server.lock')
if (-not $instanceLock) { Stop-WithCode 3 "refused: start-server.ps1 is already running for $ServerDir" }

try {
    $props = Read-BhServerProperties $ServerDir
    if (-not (Test-Path -LiteralPath (Join-Path $ServerDir 'server.properties'))) { throw 'no server.properties (copy server.properties.sunday there first)' }
    $eula = Join-Path $ServerDir 'eula.txt'
    if (-not (Test-Path -LiteralPath $eula) -or -not (Select-String -LiteralPath $eula -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet)) {
        throw 'eula.txt does not say eula=true (read https://aka.ms/MinecraftEULA and set it yourself)'
    }
    $port = 25565
    if ($props.ContainsKey('server-port') -and $props['server-port'] -match '^\d+$') { $port = [int]$props['server-port'] }
    if (Test-BhPortInUse $port) { throw "something is already listening on port $port; is a server already running?" }
    $worldDir = Get-BhWorldDir $ServerDir
    if (-not $BackupDir) { $BackupDir = Join-Path $ServerDir 'backups' }
    $BackupDir = Resolve-BhFullPath $BackupDir
    [void][System.IO.Directory]::CreateDirectory($BackupDir)
    Set-BhLogFile (Join-Path $BackupDir 'server-wrapper.log')

    if ($Backup -eq 'Rcon') {
        if (-not ($props.ContainsKey('enable-rcon') -and $props['enable-rcon'] -eq 'true')) { throw '-Backup Rcon needs enable-rcon=true in server.properties' }
        if (-not $RconPassword) {
            if ($env:BANNERHOLD_RCON_PASSWORD) { $RconPassword = ConvertTo-SecureString $env:BANNERHOLD_RCON_PASSWORD -AsPlainText -Force }
            else { throw '-Backup Rcon needs -RconPassword or BANNERHOLD_RCON_PASSWORD' }
        }
        $rconPort = 25575
        if ($props.ContainsKey('rcon.port') -and $props['rcon.port'] -match '^\d+$') { $rconPort = [int]$props['rcon.port'] }
    }

    if ($ServerCommand) {
        $program = $ServerCommand[0]
        $arguments = @($ServerCommand | Select-Object -Skip 1)
    } else {
        $argsFile = 'unix_args.txt'
        if (Test-BhWindows) { $argsFile = 'win_args.txt' }
        $argsRel = "libraries/net/neoforged/neoforge/$NeoForgeVersion/$argsFile"
        if (-not (Test-Path -LiteralPath (Join-Path $ServerDir $argsRel))) {
            throw "missing $argsRel; install the server first: java -jar neoforge-$NeoForgeVersion-installer.jar --installServer"
        }
        if (-not (Get-Command $Java -ErrorAction SilentlyContinue)) { throw "java not found ('$Java'); install Java 21 or pass -Java <path to java.exe>" }
        $program = $Java
        $arguments = @("-Xms$Heap", "-Xmx$Heap") + $G1Flags + @("@$argsRel", 'nogui')
    }
} catch {
    $instanceLock.Dispose()
    Stop-WithCode 3 "refused: $($_.Exception.Message)"
}

# ---------------------------------------------------------------- run loop

$inputQueue = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
$inputReader = Start-BhLineReader ([Console]::In) $inputQueue
$crashTimes = New-Object System.Collections.Generic.List[datetime]
$crashDir = Join-Path $ServerDir 'crash-reports'

function Get-CrashReports {
    if (-not (Test-Path -LiteralPath $crashDir)) { return @() }
    return @(Get-ChildItem -LiteralPath $crashDir -File | ForEach-Object { $_.Name })
}

# A failed backup is logged and never takes the wrapper (and its console) down.
function Invoke-WrapperBackup($Console) {
    if ($Backup -eq 'Rcon') {
        $channel = New-BhRconChannel '127.0.0.1' $rconPort $RconPassword 120
    } else {
        $channel = New-BhConsoleChannel $Console
    }
    try {
        [void](Invoke-BhBackup -Channel $channel -WorldDir $worldDir -BackupDir $BackupDir -Keep $BackupKeep)
    } catch {
        Write-BhLog "backup FAILED with an unexpected error: $($_.Exception.Message)" ERROR
    } finally {
        Close-BhChannel $channel
    }
}

# In the classic console window a mouse click starts a selection that freezes all output,
# and with it this script (possibly between save-off and save-on). Turn QuickEdit off.
function Disable-QuickEdit {
    if (-not (Test-BhWindows) -or [Console]::IsInputRedirected) { return }
    try {
        Add-Type -Namespace Bannerhold -Name ConsoleMode -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern System.IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool GetConsoleMode(System.IntPtr h, out uint mode);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetConsoleMode(System.IntPtr h, uint mode);
'@
        $h = [Bannerhold.ConsoleMode]::GetStdHandle(-10)
        $mode = [uint32]0
        if ([Bannerhold.ConsoleMode]::GetConsoleMode($h, [ref]$mode)) {
            # Clear ENABLE_QUICK_EDIT_MODE (0x40), keep ENABLE_EXTENDED_FLAGS (0x80) so it sticks.
            [void][Bannerhold.ConsoleMode]::SetConsoleMode($h, (($mode -band (-bnot [uint32]0x40)) -bor [uint32]0x80))
        }
    } catch {
        Write-BhLog 'could not turn off QuickEdit; do not click inside this window' WARN
    }
}

$exitCode = 0
$console = $null
Disable-QuickEdit
try {
    Write-BhLog ("starting in {0}: {1} {2}" -f $ServerDir, $program, (Join-BhArguments $arguments))
    Write-BhLog "backups: $Backup every $BackupIntervalMinutes min, keep $BackupKeep, into $BackupDir"
    while ($true) {
        $crashesBefore = Get-CrashReports
        $console = Start-BhServerProcess -FileName $program -Arguments $arguments -WorkingDirectory $ServerDir
        $stopRequested = $false
        $stopDeadline = $null
        $inputClosed = $false
        $nextBackup = $null

        while (-not $console.Process.HasExited) {
            $wasReady = $console.Ready
            [void](Receive-BhConsole $console)
            if ($console.Ready -and -not $wasReady) {
                Write-BhLog 'server is ready'
                if ($Backup -ne 'None') { $nextBackup = (Get-Date).AddMinutes($BackupIntervalMinutes) }
            }

            $line = $null
            while (-not $inputClosed -and $inputQueue.TryDequeue([ref]$line)) {
                if ($null -eq $line) { $inputClosed = $true; break }
                if ($line.Trim() -eq 'backup-now') {
                    if ($Backup -eq 'None') { Write-BhLog 'backups are off (-Backup None)' WARN }
                    elseif (-not $console.Ready) { Write-BhLog 'the server is not ready yet' WARN }
                    else { Invoke-WrapperBackup $console }
                    continue
                }
                if ($line.Trim() -match '^/?stop$' -and -not $stopRequested) {
                    $stopRequested = $true
                    $stopDeadline = (Get-Date).AddSeconds($StopTimeoutSeconds)
                }
                [void](Send-BhConsoleLine $console $line)
            }
            if ($stopDeadline -and (Get-Date) -ge $stopDeadline -and -not $console.Process.HasExited) {
                Write-BhLog "the server did not exit within $StopTimeoutSeconds s after stop; killing it" ERROR
                try { $console.Process.Kill() } catch { }
                $stopDeadline = $null
            }
            if ($inputReader.Handle.IsCompleted -and $inputQueue.IsEmpty) { $inputClosed = $true }

            if ($nextBackup -and (Get-Date) -ge $nextBackup -and -not $stopRequested) {
                Invoke-WrapperBackup $console
                $nextBackup = (Get-Date).AddMinutes($BackupIntervalMinutes)
            }
            Start-Sleep -Milliseconds 100
        }

        $console.Process.WaitForExit()
        foreach ($r in $console.Readers) { Stop-BhLineReader $r }
        [void](Receive-BhConsole $console)
        $code = $console.Process.ExitCode
        $newCrashes = @(Get-CrashReports | Where-Object { $crashesBefore -notcontains $_ })
        $crashed = ($code -ne 0) -or ($newCrashes.Count -gt 0)

        if (-not $crashed) {
            Write-BhLog "server stopped (exit code 0, no crash report); not restarting"
            break
        }
        Write-BhLog ("server CRASHED (exit code {0}; new crash report: {1})" -f $code, ($(if ($newCrashes.Count) { $newCrashes -join ', ' } else { 'none' }))) ERROR
        if ($stopRequested) {
            Write-BhLog 'it crashed while stopping on request; not restarting'
            $exitCode = 0
            break
        }
        $now = Get-Date
        for ($i = $crashTimes.Count - 1; $i -ge 0; $i--) {
            if ($crashTimes[$i] -lt $now.AddMinutes(-$RestartWindowMinutes)) { $crashTimes.RemoveAt($i) }
        }
        $crashTimes.Add($now)
        if ($crashTimes.Count -gt $MaxRestarts) {
            Write-BhLog "$($crashTimes.Count) crashes within $RestartWindowMinutes minutes: giving up. Read the newest file in crash-reports\ and logs\latest.log before starting again." ERROR
            $exitCode = 4
            break
        }
        Write-BhLog "restarting in $RestartDelaySeconds s (crash $($crashTimes.Count) of at most $MaxRestarts in $RestartWindowMinutes min; Ctrl+C to cancel)" WARN
        Start-Sleep -Seconds $RestartDelaySeconds
    }
} catch {
    Write-BhLog "wrapper error: $($_.Exception.Message)" ERROR
    $exitCode = 5
    # Never leave a server running without its console: stop it properly first.
    if ($console -and -not $console.Process.HasExited) {
        Write-BhLog 'stopping the server because the wrapper is exiting' ERROR
        [void](Send-BhConsoleLine $console 'stop')
        if (-not $console.Process.WaitForExit($StopTimeoutSeconds * 1000)) { try { $console.Process.Kill() } catch { } }
    }
} finally {
    $instanceLock.Dispose()
    # Environment.Exit, not exit: the console reader thread is blocked in ReadLine and would keep the process alive.
    [System.Environment]::Exit($exitCode)
}
