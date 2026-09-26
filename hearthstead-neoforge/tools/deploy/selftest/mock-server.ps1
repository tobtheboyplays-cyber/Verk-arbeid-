# Stand-in for the Minecraft server console, used only by run-selftest.ps1.
# Prints log lines in the dedicated server's format and answers save-off,
# save-all flush, save-on and stop the way Minecraft 1.21.1 does.
param(
    [ValidateSet('normal', 'flushfail-spoof', 'saveon-already', 'hang-on-stop', 'crash-once', 'crash-always', 'exit1-once')][string]$Mode = 'normal',
    [Parameter(Mandatory = $true)][string]$StateDir
)
$ErrorActionPreference = 'Stop'
$commandLog = Join-Path $StateDir 'mock-commands.log'
$stateFile = Join-Path $StateDir 'mock-saving.txt'
$startsFile = Join-Path $StateDir 'mock-starts.log'

function Out([string]$Message) {
    [Console]::Out.WriteLine(('[{0}] [Server thread/INFO] [minecraft/DedicatedServer]: {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message))
    [Console]::Out.Flush()
}

Add-Content -LiteralPath $startsFile -Value (Get-Date -Format o)
$starts = @(Get-Content -LiteralPath $startsFile).Count
Out 'Starting minecraft server version 1.21.1'

if ($Mode -eq 'crash-always' -or ($Mode -eq 'crash-once' -and $starts -eq 1)) {
    # A real crash writes a report and leaves the JVM with exit code 0.
    $dir = Join-Path (Get-Location) 'crash-reports'
    [void][System.IO.Directory]::CreateDirectory($dir)
    $report = Join-Path $dir ('crash-{0}-{1}-server.txt' -f (Get-Date -Format 'yyyy-MM-dd_HH.mm.ss'), $starts)
    Set-Content -LiteralPath $report -Value 'mock crash'
    Out "This crash report has been saved to: $report"
    exit 0
}

Out 'Done (0.123s)! For help, type "help"'
if ($Mode -eq 'exit1-once' -and $starts -eq 1) {
    Start-Sleep -Milliseconds 500
    exit 1
}

$saving = $true
Set-Content -LiteralPath $stateFile -Value 'on'
while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { exit 0 }
    Add-Content -LiteralPath $commandLog -Value $line.Trim()
    switch ($line.Trim()) {
        'save-off' {
            if ($saving) { $saving = $false; Set-Content -LiteralPath $stateFile -Value 'off'; Out 'Automatic saving is now disabled' }
            else { Out 'Saving is already turned off' }
        }
        'save-all flush' {
            Out 'Saving the game (this may take a moment!)'
            if ($Mode -eq 'flushfail-spoof') {
                # A player's chat line must never count as the acknowledgement.
                Out '[Not Secure] <Mallory> Saved the game'
                Out 'Unable to save the game (is there enough disk space?)'
            } else {
                Out 'Saved the game'
            }
            if ($Mode -eq 'saveon-already') {
                # Someone else turns saving back on while the backup is zipping.
                $saving = $true; Set-Content -LiteralPath $stateFile -Value 'on'
            }
        }
        'save-on' {
            if (-not $saving) { $saving = $true; Set-Content -LiteralPath $stateFile -Value 'on'; Out 'Automatic saving is now enabled' }
            else { Out 'Saving is already turned on' }
        }
        'stop' {
            Out 'Stopping the server'
            if ($Mode -eq 'hang-on-stop') { Start-Sleep -Seconds 3600 }
            exit 0
        }
        default { Out 'Unknown or incomplete command, see below for error' }
    }
}
