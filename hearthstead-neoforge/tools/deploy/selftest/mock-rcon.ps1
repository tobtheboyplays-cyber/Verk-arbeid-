# Stand-in for Minecraft's RCON listener, used only by run-selftest.ps1.
# Same packet format and replies as Minecraft 1.21.1 (RconClient/RconConsoleSource).
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Password,
    [ValidateSet('normal', 'flushfail', 'nosaveon')][string]$Mode = 'normal',
    [Parameter(Mandatory = $true)][string]$StateDir
)
$ErrorActionPreference = 'Stop'
$commandLog = Join-Path $StateDir 'rcon-commands.log'
$stateFile = Join-Path $StateDir 'rcon-saving.txt'

function Read-Exact($Stream, [int]$Count) {
    $buf = New-Object byte[] $Count
    $off = 0
    while ($off -lt $Count) {
        $n = $Stream.Read($buf, $off, $Count - $off)
        if ($n -le 0) { return $null }
        $off += $n
    }
    return , $buf
}

function Send-Packet($Stream, [int]$Id, [int]$Type, [string]$Body) {
    $payload = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 10 + $payload.Length
    $buf = New-Object byte[] (4 + $length)
    [System.BitConverter]::GetBytes([int]$length).CopyTo($buf, 0)
    [System.BitConverter]::GetBytes([int]$Id).CopyTo($buf, 4)
    [System.BitConverter]::GetBytes([int]$Type).CopyTo($buf, 8)
    $payload.CopyTo($buf, 12)
    $Stream.Write($buf, 0, $buf.Length)
}

$saving = $true
Set-Content -LiteralPath $stateFile -Value 'on'
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
[Console]::Out.WriteLine("mock rcon listening on $Port")
while ($true) {
    $client = $listener.AcceptTcpClient()
    $stream = $client.GetStream()
    $authed = $false
    try {
        while ($true) {
            $head = Read-Exact $stream 4
            if (-not $head) { break }
            $length = [System.BitConverter]::ToInt32($head, 0)
            $data = Read-Exact $stream $length
            if (-not $data) { break }
            $id = [System.BitConverter]::ToInt32($data, 0)
            $type = [System.BitConverter]::ToInt32($data, 4)
            $body = [System.Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
            if ($type -eq 3) {
                if ($body -ceq $Password) { $authed = $true; Send-Packet $stream $id 2 '' }
                else { Add-Content -LiteralPath $commandLog -Value 'AUTH-FAILED'; Send-Packet $stream -1 2 '' }
                continue
            }
            if (-not $authed) { Send-Packet $stream -1 2 ''; continue }
            Add-Content -LiteralPath $commandLog -Value $body
            $reply = ''
            switch ($body) {
                'save-off' {
                    if ($saving) { $saving = $false; Set-Content -LiteralPath $stateFile -Value 'off'; $reply = "Automatic saving is now disabled`n" }
                    else { $reply = "Saving is already turned off`n" }
                }
                'save-all flush' {
                    if ($Mode -eq 'flushfail') { $reply = "Saving the game (this may take a moment!)`nUnable to save the game (is there enough disk space?)`n" }
                    else { $reply = "Saving the game (this may take a moment!)`nSaved the game`n" }
                }
                'save-on' {
                    if ($Mode -eq 'nosaveon') { throw 'drop the connection' }
                    if (-not $saving) { $saving = $true; Set-Content -LiteralPath $stateFile -Value 'on'; $reply = "Automatic saving is now enabled`n" }
                    else { $reply = "Saving is already turned on`n" }
                }
                default { $reply = "Unknown or incomplete command, see below for error`n" }
            }
            Send-Packet $stream $id 0 $reply
        }
    } catch {
    } finally {
        $client.Close()
    }
}
