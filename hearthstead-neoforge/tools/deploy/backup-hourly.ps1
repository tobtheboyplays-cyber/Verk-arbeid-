<#
.SYNOPSIS
    Backs up a running Bannerhold server world over RCON: save-off, save-all flush,
    zip, save-on, keep the newest 12.

.DESCRIPTION
    Standalone backup through RCON (enable-rcon=true in server.properties).
    start-server.ps1 runs the same backup through the server console instead and
    needs no RCON; use this script when the server is started some other way.

    Order: save-off -> save-all flush (waits for "Saved the game") -> zip to
    <world>-yyyyMMdd-HHmmss.zip.partial -> save-on (always, also after a failure)
    -> verify every entry -> rename to .zip -> delete the oldest beyond -Keep.
    An incomplete archive is removed and never counts toward -Keep, so a failed
    run never deletes a good backup. Only files named exactly
    <world>-yyyyMMdd-HHmmss.zip directly inside the backup folder are ever deleted.

    No path, password or address is stored in this file.

.PARAMETER ServerDir
    The server folder (contains server.properties). Required, no default.
    The world is the level-name folder inside it.

.PARAMETER BackupDir
    Where archives go. Default: <ServerDir>\backups.

.PARAMETER RconPassword
    rcon.password from server.properties, as a SecureString. If omitted the
    BANNERHOLD_RCON_PASSWORD environment variable is used, otherwise you are asked.

.PARAMETER IntervalMinutes
    0 (default) = one backup and exit. 60 = keep running, one backup every hour.

.EXAMPLE
    .\backup-hourly.ps1 -ServerDir 'D:\Bannerhold Server'
.EXAMPLE
    .\backup-hourly.ps1 -ServerDir 'D:\Bannerhold Server' -IntervalMinutes 60

.NOTES
    Exit codes: 0 backup written; 1 backup failed (saving was restored);
    2 saving could NOT be turned back on; 3 refused before touching the server.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [string]$BackupDir,
    [ValidateRange(1, 1000)][int]$Keep = 12,
    [string]$RconHost = '127.0.0.1',
    [ValidateRange(0, 65535)][int]$RconPort = 0,
    [System.Security.SecureString]$RconPassword,
    [ValidateRange(0, 10080)][double]$IntervalMinutes = 0,
    [ValidateRange(5, 3600)][int]$TimeoutSeconds = 120
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BannerholdServer.psm1') -Force

try {
    $ServerDir = Resolve-BhFullPath $ServerDir
    if (-not (Test-Path -LiteralPath (Join-Path $ServerDir 'server.properties'))) { throw "no server.properties in $ServerDir" }
    $worldDir = Get-BhWorldDir $ServerDir
    if (-not $BackupDir) { $BackupDir = Join-Path $ServerDir 'backups' }
    $BackupDir = Resolve-BhFullPath $BackupDir
    # Validate before the folder or a log file is created in it.
    Assert-BhBackupTarget $worldDir $BackupDir
    [void][System.IO.Directory]::CreateDirectory($BackupDir)
    Set-BhLogFile (Join-Path $BackupDir 'backup.log')

    $props = Read-BhServerProperties $ServerDir
    if ($RconPort -eq 0) {
        $RconPort = 25575
        if ($props.ContainsKey('rcon.port') -and $props['rcon.port'] -match '^\d+$') { $RconPort = [int]$props['rcon.port'] }
    }
    if (-not ($props.ContainsKey('enable-rcon') -and $props['enable-rcon'] -eq 'true')) {
        Write-BhLog 'server.properties does not say enable-rcon=true; RCON will probably not answer' WARN
    }
    if (-not $RconPassword) {
        if ($env:BANNERHOLD_RCON_PASSWORD) {
            $RconPassword = ConvertTo-SecureString $env:BANNERHOLD_RCON_PASSWORD -AsPlainText -Force
        } elseif (-not [Console]::IsInputRedirected) {
            $RconPassword = Read-Host -AsSecureString 'RCON password (rcon.password in server.properties)'
        } else {
            throw 'no RCON password: pass -RconPassword or set BANNERHOLD_RCON_PASSWORD'
        }
    }
} catch {
    Write-BhLog "refused: $($_.Exception.Message)" ERROR
    exit 3
}

$channel = New-BhRconChannel $RconHost $RconPort $RconPassword $TimeoutSeconds
while ($true) {
    $started = Get-Date
    $result = Invoke-BhBackup -Channel $channel -WorldDir $worldDir -BackupDir $BackupDir -Keep $Keep -TimeoutSeconds $TimeoutSeconds
    Close-BhChannel $channel
    $code = 0
    if (-not $result.Ok) { $code = 1 }
    if ($result.Refused) { $code = 3 }
    if (-not $result.SavingRestored) { $code = 2 }
    if ($IntervalMinutes -le 0) { exit $code }

    $next = $started.AddMinutes($IntervalMinutes)
    Write-BhLog ("next backup at {0:HH:mm}" -f $next)
    while ((Get-Date) -lt $next) { Start-Sleep -Seconds 1 }
}
