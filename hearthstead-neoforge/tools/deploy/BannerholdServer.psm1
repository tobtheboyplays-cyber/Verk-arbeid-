# Shared helpers for start-server.ps1 and backup-hourly.ps1.
# Runs on Windows PowerShell 5.1 and PowerShell 7. Keep this file ASCII-only:
# Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#
# Nothing in here stores a path, password or address. Everything comes in as
# parameters from the calling script.

Set-StrictMode -Version 2
# Module functions do not inherit the caller's preference: a failed cmdlet must stop here too.
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:LogFile = $null
# One log line of the dedicated server's console, NeoForge or vanilla format:
#   [18:12:54] [Server thread/INFO] [minecraft/MinecraftServer]: Saved the game
#   [18:12:54] [Server thread/INFO]: Saved the game
# The whole message must be the expected text. Chat is always logged as "<name> ...",
# "[Not Secure] <name> ...", "[Rcon: ...]" or "[name: ...]", so it can never match.
$script:ConsolePrefix = '^\[[^\]]+\] \[Server thread/INFO\](?: \[[^\]\s]+\])?: '
$script:AnsiEscape = New-Object System.Text.RegularExpressions.Regex (([string][char]27) + '\[[0-9;?]*[A-Za-z]')

# ---------------------------------------------------------------- logging

function Set-BhLogFile {
    param([string]$Path)
    $script:LogFile = $Path
}

function Write-BhLog {
    param([string]$Message, [ValidateSet('INFO', 'WARN', 'ERROR')][string]$Level = 'INFO')
    $line = '{0} [bannerhold] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    $color = 'Cyan'
    if ($Level -eq 'WARN') { $color = 'Yellow' }
    if ($Level -eq 'ERROR') { $color = 'Red' }
    Write-Host $line -ForegroundColor $color
    if ($script:LogFile) {
        try { Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8 } catch { }
    }
}

# ---------------------------------------------------------------- paths

function Test-BhWindows {
    return [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
}

function Resolve-BhFullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    # Relative paths resolve against the PowerShell location, not the process directory.
    $p = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $full = [System.IO.Path]::GetFullPath($p)
    $root = [System.IO.Path]::GetPathRoot($full)
    if ($full.Length -gt $root.Length) { $full = $full.TrimEnd('\', '/') }
    return $full
}

# True when $Child is $Parent or lies somewhere below it.
function Test-BhPathInside {
    param([string]$Child, [string]$Parent)
    $sep = [System.IO.Path]::DirectorySeparatorChar
    $c = (Resolve-BhFullPath $Child).TrimEnd('\', '/') + $sep
    $p = (Resolve-BhFullPath $Parent).TrimEnd('\', '/') + $sep
    $cmp = [System.StringComparison]::Ordinal
    if (Test-BhWindows) { $cmp = [System.StringComparison]::OrdinalIgnoreCase }
    return $c.StartsWith($p, $cmp)
}

# The path itself or the nearest existing ancestor that is a junction, symbolic link or
# mount point, or $null. Lexical containment checks only mean something without them.
function Get-BhReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)
    $p = Resolve-BhFullPath $Path
    while ($p) {
        if (Test-Path -LiteralPath $p) {
            if ([System.IO.File]::GetAttributes($p) -band [System.IO.FileAttributes]::ReparsePoint) { return $p }
        }
        $parent = [System.IO.Path]::GetDirectoryName($p)
        if (-not $parent -or $parent -eq $p) { break }
        $p = $parent
    }
    return $null
}

function Assert-BhPlainPath {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$What = 'the path')
    $rp = Get-BhReparsePoint $Path
    if ($rp) { throw "$What goes through a junction or symbolic link ($rp); refusing. Use a plain folder." }
}

# Every file below $Root. Refuses junctions and links inside it, so nothing outside can be archived.
function Get-BhWorldFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    $files = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    $dirs = New-Object System.Collections.Generic.Stack[string]
    $dirs.Push($Root)
    while ($dirs.Count -gt 0) {
        $dir = New-Object System.IO.DirectoryInfo ($dirs.Pop())
        foreach ($entry in $dir.GetFileSystemInfos()) {
            if ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                throw "the world contains a junction or symbolic link ($($entry.FullName)); refusing to back it up"
            }
            if ($entry -is [System.IO.DirectoryInfo]) { $dirs.Push($entry.FullName) } else { $files.Add($entry) }
        }
    }
    return , ($files.ToArray())
}

function Read-BhServerProperties {
    param([Parameter(Mandatory = $true)][string]$ServerDir)
    $props = @{}
    $file = Join-Path $ServerDir 'server.properties'
    if (-not (Test-Path -LiteralPath $file)) { return $props }
    foreach ($line in (Get-Content -LiteralPath $file -Encoding UTF8)) {
        if ($line -match '^\s*([^#!=\s][^=]*?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
    }
    return $props
}

# The world folder named by level-name, which must lie inside the server folder.
function Get-BhWorldDir {
    param([Parameter(Mandatory = $true)][string]$ServerDir)
    $server = Resolve-BhFullPath $ServerDir
    $props = Read-BhServerProperties $server
    $name = 'world'
    if ($props.ContainsKey('level-name') -and $props['level-name'].Trim()) { $name = $props['level-name'].Trim() }
    $world = Resolve-BhFullPath (Join-Path $server $name)
    if (-not (Test-BhPathInside $world $server) -or (Test-BhPathInside $server $world)) {
        throw "level-name '$name' does not point to a folder inside $server; refusing."
    }
    Assert-BhPlainPath $world 'the world folder'
    return $world
}

# Holds the file open with no sharing; the OS drops the lock if the process dies.
function Open-BhExclusiveLock {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        return [System.IO.File]::Open($Path, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    } catch {
        return $null
    }
}

function Test-BhPortInUse {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# ---------------------------------------------------------------- RCON

function ConvertFrom-BhSecureString {
    param([System.Security.SecureString]$Secure)
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Read-BhExact {
    param($Stream, [int]$Count)
    $buf = New-Object byte[] $Count
    $off = 0
    while ($off -lt $Count) {
        $n = $Stream.Read($buf, $off, $Count - $off)
        if ($n -le 0) { throw 'RCON connection closed by the server' }
        $off += $n
    }
    return , $buf
}

function Send-BhRconPacket {
    param($Session, [int]$Type, [string]$Body)
    $id = $Session.NextId
    $Session.NextId++
    $payload = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $payload.Length + 2
    # Minecraft reads a request with a single socket read: send it in one write.
    $buf = New-Object byte[] (4 + $length)
    [System.BitConverter]::GetBytes([int]$length).CopyTo($buf, 0)
    [System.BitConverter]::GetBytes([int]$id).CopyTo($buf, 4)
    [System.BitConverter]::GetBytes([int]$Type).CopyTo($buf, 8)
    $payload.CopyTo($buf, 12)
    $Session.Stream.Write($buf, 0, $buf.Length)
    $Session.Stream.Flush()
    return $id
}

function Read-BhRconPacket {
    param($Session)
    $length = [System.BitConverter]::ToInt32((Read-BhExact $Session.Stream 4), 0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "RCON packet with a bad length ($length)" }
    $data = Read-BhExact $Session.Stream $length
    return @{
        Id   = [System.BitConverter]::ToInt32($data, 0)
        Type = [System.BitConverter]::ToInt32($data, 4)
        Body = [System.Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
    }
}

function Close-BhRcon {
    param($Session)
    if ($Session -and $Session.Client) { try { $Session.Client.Close() } catch { } }
}

function Connect-BhRcon {
    param([string]$HostName, [int]$Port, [System.Security.SecureString]$Password, [int]$TimeoutSeconds = 30)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutSeconds * 1000)) { throw "no RCON answer on ${HostName}:$Port" }
        $client.EndConnect($iar)
    } catch {
        $client.Close()
        throw "cannot reach RCON on ${HostName}:$Port ($($_.Exception.Message)). Is enable-rcon=true and the server running?"
    }
    $client.ReceiveTimeout = $TimeoutSeconds * 1000
    $client.SendTimeout = $TimeoutSeconds * 1000
    $session = @{ Client = $client; Stream = $client.GetStream(); NextId = 1 }
    try {
        $plain = ConvertFrom-BhSecureString $Password
        try { $id = Send-BhRconPacket $session 3 $plain } finally { $plain = $null }
        $reply = Read-BhRconPacket $session
    } catch {
        Close-BhRcon $session
        throw "RCON login failed: $($_.Exception.Message)"
    }
    if ($reply.Id -ne $id) {
        Close-BhRcon $session
        throw 'RCON login refused: wrong password (compare with rcon.password in server.properties)'
    }
    return $session
}

function Invoke-BhRconCommand {
    param($Session, [string]$Command)
    $id = Send-BhRconPacket $Session 2 $Command
    $reply = Read-BhRconPacket $Session
    if ($reply.Id -ne $id) { throw "RCON reply for request $($reply.Id), expected $id" }
    return $reply.Body
}

# ---------------------------------------------------------------- server console (stdin/stdout)

# Quote arguments the way the Windows C runtime (and .NET on Linux) splits them.
function Join-BhArguments {
    param([string[]]$Arguments)
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($a in $Arguments) {
        if ($a -ne '' -and $a -notmatch '[\s"]') { $parts.Add($a); continue }
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.Append('"')
        $slashes = 0
        foreach ($ch in $a.ToCharArray()) {
            if ($ch -eq '\') { $slashes++; continue }
            if ($ch -eq '"') { [void]$sb.Append('\', 2 * $slashes + 1); [void]$sb.Append('"') }
            else { [void]$sb.Append('\', $slashes); [void]$sb.Append($ch) }
            $slashes = 0
        }
        [void]$sb.Append('\', 2 * $slashes)
        [void]$sb.Append('"')
        $parts.Add($sb.ToString())
    }
    return ($parts -join ' ')
}

# Drains a TextReader into a queue on a background runspace, so a full pipe
# can never block the Minecraft server's logging while this script is busy.
function Start-BhLineReader {
    param($Reader, $Queue)
    $ps = [System.Management.Automation.PowerShell]::Create()
    [void]$ps.AddScript('param($r, $q) while ($true) { $l = $r.ReadLine(); if ($null -eq $l) { break }; $q.Enqueue($l) }')
    [void]$ps.AddArgument($Reader)
    [void]$ps.AddArgument($Queue)
    $handle = $ps.BeginInvoke()
    return @{ PowerShell = $ps; Handle = $handle }
}

function Stop-BhLineReader {
    param($ReaderInfo, [int]$WaitMilliseconds = 5000)
    if (-not $ReaderInfo) { return }
    [void]$ReaderInfo.Handle.AsyncWaitHandle.WaitOne($WaitMilliseconds)
    if ($ReaderInfo.Handle.IsCompleted) { $ReaderInfo.PowerShell.Dispose() }
}

function Start-BhServerProcess {
    param([Parameter(Mandatory = $true)][string]$FileName, [string[]]$Arguments, [Parameter(Mandatory = $true)][string]$WorkingDirectory)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FileName
    $psi.Arguments = Join-BhArguments $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $proc = [System.Diagnostics.Process]::Start($psi)
    # Minecraft reads its console as UTF-8; .NET Framework would write the OEM code page.
    $writer = New-Object System.IO.StreamWriter($proc.StandardInput.BaseStream, (New-Object System.Text.UTF8Encoding $false))
    $writer.AutoFlush = $true
    $queue = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
    $readers = @((Start-BhLineReader $proc.StandardOutput $queue), (Start-BhLineReader $proc.StandardError $queue))
    return @{ Process = $proc; Input = $writer; Queue = $queue; Readers = $readers; Ready = $false }
}

# The message of a server log line if the whole message matches $Pattern, else $null.
function Test-BhConsoleLine {
    param([string]$Line, [string]$Pattern)
    $m = [regex]::Match($Line.TrimEnd(), $script:ConsolePrefix + '(?<msg>' + $Pattern + ')$')
    if ($m.Success) { return $m.Groups['msg'].Value }
    return $null
}

# Prints everything the server wrote since the last call and returns those lines (ANSI stripped).
function Receive-BhConsole {
    param($Console)
    $new = New-Object System.Collections.Generic.List[string]
    $line = $null
    while ($Console.Queue.TryDequeue([ref]$line)) {
        Write-Host $line
        $clean = $script:AnsiEscape.Replace($line, '')
        if (-not $Console.Ready -and (Test-BhConsoleLine $clean 'Done \(\d+(?:[.,]\d+)?s\)! For help, type "help"')) { $Console.Ready = $true }
        $new.Add($clean)
    }
    return , ($new.ToArray())
}

function Send-BhConsoleLine {
    param($Console, [string]$Line)
    if ($Console.Process.HasExited) { return $false }
    try { $Console.Input.WriteLine($Line); return $true } catch { return $false }
}

function Test-BhConsoleOutputDone {
    param($Console)
    foreach ($r in $Console.Readers) { if (-not $r.Handle.IsCompleted) { return $false } }
    return $Console.Queue.IsEmpty
}

# ---------------------------------------------------------------- command channel (RCON or console)

function New-BhRconChannel {
    param([string]$HostName, [int]$Port, [System.Security.SecureString]$Password, [int]$TimeoutSeconds = 120)
    return @{ Kind = 'Rcon'; HostName = $HostName; Port = $Port; Password = $Password; Timeout = $TimeoutSeconds; Session = $null }
}

function New-BhConsoleChannel {
    param($Console)
    return @{ Kind = 'Console'; Console = $Console }
}

# Checks the channel can talk to the server before anything is changed.
function Open-BhChannel {
    param($Channel)
    if ($Channel.Kind -eq 'Rcon') {
        if (-not $Channel.Session) {
            $Channel.Session = Connect-BhRcon $Channel.HostName $Channel.Port $Channel.Password $Channel.Timeout
        }
        return
    }
    if ($Channel.Console.Process.HasExited) { throw 'the server process is not running' }
    if (-not $Channel.Console.Ready) { throw 'the server has not finished starting' }
}

function Close-BhChannel {
    param($Channel)
    if ($Channel.Kind -eq 'Rcon') { Close-BhRcon $Channel.Session; $Channel.Session = $null }
}

# Sends one command and returns the acknowledging text that matched $Pattern, or $null.
function Invoke-BhChannelCommand {
    param($Channel, [string]$Command, [string]$Pattern, [int]$TimeoutSeconds)
    if ($Channel.Kind -eq 'Rcon') {
        try {
            if (-not $Channel.Session) {
                $Channel.Session = Connect-BhRcon $Channel.HostName $Channel.Port $Channel.Password $Channel.Timeout
            }
            $Channel.Session.Client.ReceiveTimeout = [Math]::Max(1, $TimeoutSeconds) * 1000
            $reply = $script:AnsiEscape.Replace((Invoke-BhRconCommand $Channel.Session $Command), '')
        } catch {
            Write-BhLog "RCON '$Command' failed: $($_.Exception.Message)" WARN
            Close-BhChannel $Channel
            return $null
        }
        Write-BhLog ("RCON '{0}' -> {1}" -f $Command, ($reply.Trim() -replace '\s*\n\s*', ' | '))
        if ($reply -match ('(?m)^\s*(?:' + $Pattern + ')')) { return $Matches[0].Trim() }
        return $null
    }

    $console = $Channel.Console
    Write-BhLog "console> $Command"
    if (-not (Send-BhConsoleLine $console $Command)) { return $null }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($line in (Receive-BhConsole $console)) {
            $msg = Test-BhConsoleLine $line $Pattern
            if ($msg) { return $msg }
        }
        if ($console.Process.HasExited -and (Test-BhConsoleOutputDone $console)) { return $null }
        Start-Sleep -Milliseconds 100
    }
    return $null
}

# ---------------------------------------------------------------- backup

function Get-BhBackupPattern {
    param([string]$Prefix, [string]$Suffix = '')
    return '^' + [regex]::Escape($Prefix) + '-\d{8}-\d{6}\.zip' + [regex]::Escape($Suffix) + '$'
}

# Writes the world into <prefix>-<stamp>.zip.partial and checks its central directory.
function New-BhWorldArchive {
    param([string]$WorldDir, [string]$BackupDir, [string]$Prefix)
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $final = Join-Path $BackupDir ('{0}-{1}.zip' -f $Prefix, $stamp)
    $partial = $final + '.partial'
    if (Test-Path -LiteralPath $final) { throw "a backup named $final already exists" }
    $root = $WorldDir.TrimEnd('\', '/')
    $files = Get-BhWorldFiles $root
    $expected = @{}
    $hashes = @{}
    $buffer = New-Object byte[] 81920
    $fs = $null
    $zip = $null
    $ok = $false
    try {
        $fs = [System.IO.File]::Open($partial, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        foreach ($file in $files) {
            # The running server keeps session.lock locked; it is never part of a restore.
            if ($file.Name -eq 'session.lock') { continue }
            $rel = $file.FullName.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
            $name = '{0}/{1}' -f $Prefix, $rel
            $in = $null
            try {
                # The server keeps region files open for writing: share read/write/delete or Windows refuses.
                $in = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
            } catch {
                if (-not (Test-Path -LiteralPath $file.FullName)) {
                    Write-BhLog "skipped $rel (removed while the backup ran)" WARN
                    continue
                }
                throw "cannot read $rel : $($_.Exception.Message)"
            }
            try {
                $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
                try { $entry.LastWriteTime = New-Object System.DateTimeOffset($file.LastWriteTime) } catch { }
                $out = $entry.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try {
                    while (($n = $in.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        [void]$sha.TransformBlock($buffer, 0, $n, $null, 0)
                        $out.Write($buffer, 0, $n)
                    }
                    [void]$sha.TransformFinalBlock($buffer, 0, 0)
                    $hashes[$name] = [System.BitConverter]::ToString($sha.Hash)
                } finally {
                    $out.Dispose()
                    $sha.Dispose()
                }
                $expected[$name] = $in.Position
            } finally {
                $in.Dispose()
            }
        }
        $zip.Dispose(); $zip = $null
        $fs.Flush($true)
        $fs.Dispose(); $fs = $null

        $check = [System.IO.Compression.ZipFile]::OpenRead($partial)
        try {
            if ($check.Entries.Count -ne $expected.Count) { throw "archive has $($check.Entries.Count) entries, expected $($expected.Count)" }
            foreach ($e in $check.Entries) {
                if (-not $expected.ContainsKey($e.FullName) -or $expected[$e.FullName] -ne $e.Length) { throw "archive entry $($e.FullName) does not match what was written" }
            }
        } finally {
            $check.Dispose()
        }
        $ok = $true
    } finally {
        if ($zip) { try { $zip.Dispose() } catch { } }
        if ($fs) { try { $fs.Dispose() } catch { } }
        if (-not $ok -and (Test-Path -LiteralPath $partial)) { Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue }
    }
    return @{ Partial = $partial; Final = $final; Files = $expected.Count; Hashes = $hashes }
}

# Decompresses every entry and compares its SHA-256 with the bytes read from the world
# (.NET Framework does not check ZIP CRCs), then renames .partial to .zip.
function Complete-BhWorldArchive {
    param($Archive)
    try {
        $check = [System.IO.Compression.ZipFile]::OpenRead($Archive.Partial)
        try {
            if ($check.Entries.Count -ne $Archive.Hashes.Count) { throw "archive has $($check.Entries.Count) entries, expected $($Archive.Hashes.Count)" }
            $buffer = New-Object byte[] 81920
            foreach ($e in $check.Entries) {
                if (-not $Archive.Hashes.ContainsKey($e.FullName)) { throw "unexpected archive entry $($e.FullName)" }
                $s = $e.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try {
                    while (($n = $s.Read($buffer, 0, $buffer.Length)) -gt 0) { [void]$sha.TransformBlock($buffer, 0, $n, $null, 0) }
                    [void]$sha.TransformFinalBlock($buffer, 0, 0)
                    $actual = [System.BitConverter]::ToString($sha.Hash)
                } finally {
                    $s.Dispose()
                    $sha.Dispose()
                }
                if ($actual -ne $Archive.Hashes[$e.FullName]) { throw "archive entry $($e.FullName) is damaged (content hash differs)" }
            }
        } finally {
            $check.Dispose()
        }
        Move-Item -LiteralPath $Archive.Partial -Destination $Archive.Final -ErrorAction Stop
        if (-not (Test-Path -LiteralPath $Archive.Final)) { throw "$($Archive.Final) is missing after the rename" }
    } catch {
        Remove-Item -LiteralPath $Archive.Partial -Force -ErrorAction SilentlyContinue
        throw
    }
}

# Deletes the oldest completed backups beyond $Keep. Only files directly in
# $BackupDir named exactly <prefix>-yyyyMMdd-HHmmss.zip are ever candidates.
function Remove-BhOldBackups {
    param([string]$BackupDir, [string]$Prefix, [int]$Keep)
    $rx = Get-BhBackupPattern $Prefix
    $all = @(Get-ChildItem -LiteralPath $BackupDir -File -Force | Where-Object { $_.Name -match $rx -and -not ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) } | Sort-Object Name -Descending)
    $removed = @()
    if ($all.Count -gt $Keep) {
        foreach ($old in $all[$Keep..($all.Count - 1)]) {
            Remove-Item -LiteralPath $old.FullName -Force
            $removed += $old.Name
        }
    }
    return , $removed
}

function Invoke-BhBackup {
    param(
        [Parameter(Mandatory = $true)]$Channel,
        [Parameter(Mandatory = $true)][string]$WorldDir,
        [Parameter(Mandatory = $true)][string]$BackupDir,
        [int]$Keep = 12,
        [int]$TimeoutSeconds = 120
    )
    $result = @{ Ok = $false; Refused = $false; Archive = $null; SavingRestored = $true; Removed = @(); Message = '' }
    try {
        if ($Keep -lt 1) { throw 'Keep must be at least 1' }
        $WorldDir = Resolve-BhFullPath $WorldDir
        $BackupDir = Resolve-BhFullPath $BackupDir
        if (-not (Test-Path -LiteralPath (Join-Path $WorldDir 'level.dat'))) { throw "no level.dat in $WorldDir; is this the world folder?" }
        Assert-BhPlainPath $WorldDir 'the world folder'
        Assert-BhPlainPath $BackupDir 'the backup folder'
        if ((Test-BhPathInside $BackupDir $WorldDir) -or (Test-BhPathInside $WorldDir $BackupDir)) { throw 'the backup folder and the world folder must not contain each other' }
        [void][System.IO.Directory]::CreateDirectory($BackupDir)
        Assert-BhPlainPath $BackupDir 'the backup folder'
        # Finds links inside the world before anything is sent to the server.
        $worldFiles = Get-BhWorldFiles $WorldDir
    } catch {
        $result.Refused = $true
        $result.Message = $_.Exception.Message
        Write-BhLog "backup refused: $($result.Message)" ERROR
        return $result
    }

    $prefix = Split-Path $WorldDir -Leaf
    # One lock per world (next to it, in the server folder), whatever backup folder each caller uses.
    $lock = Open-BhExclusiveLock (Join-Path (Split-Path $WorldDir -Parent) '.bannerhold-backup.lock')
    if (-not $lock) {
        $result.Refused = $true
        $result.Message = 'another backup of this world is already running'
        Write-BhLog "backup refused: $($result.Message)" ERROR
        return $result
    }
    try {
        # Leftovers of an interrupted run; safe to remove while holding the lock.
        $partialRx = Get-BhBackupPattern $prefix '.partial'
        Get-ChildItem -LiteralPath $BackupDir -File -Force | Where-Object { $_.Name -match $partialRx } | ForEach-Object {
            Write-BhLog "removing unfinished archive $($_.Name) from an earlier run" WARN
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
        }

        try {
            [long]$worldBytes = ($worldFiles | Measure-Object -Property Length -Sum).Sum
            $drive = New-Object System.IO.DriveInfo ([System.IO.Path]::GetPathRoot($BackupDir))
            if ($drive.AvailableFreeSpace -lt $worldBytes) {
                $result.Refused = $true
                $result.Message = "not enough free space for a backup ($([math]::Round($worldBytes / 1MB)) MB world)"
            }
        } catch {
            Write-BhLog "could not check free disk space: $($_.Exception.Message)" WARN
        }
        if (-not $result.Refused) {
            try { Open-BhChannel $Channel } catch {
                $result.Refused = $true
                $result.Message = "cannot talk to the server: $($_.Exception.Message)"
            }
        }
        if ($result.Refused) {
            Write-BhLog "backup refused, nothing was changed on the server: $($result.Message)" ERROR
            return $result
        }

        Write-BhLog "backup started: $WorldDir -> $BackupDir"
        $attemptedOff = $false
        $archive = $null
        $cleanSaveOn = $false
        $shortTimeout = [Math]::Min(30, $TimeoutSeconds)
        try {
            $attemptedOff = $true
            $ack = Invoke-BhChannelCommand $Channel 'save-off' '(Automatic saving is now disabled|Saving is already turned off)' $shortTimeout
            if (-not $ack) { throw 'save-off was not acknowledged' }
            if ($ack -like 'Saving is already*') { Write-BhLog 'saving was already off before this backup; it will be turned back on afterwards' WARN }
            $ack = Invoke-BhChannelCommand $Channel 'save-all flush' '(Saved the game|Unable to save the game.*)' $TimeoutSeconds
            if (-not $ack) { throw "save-all flush was not acknowledged within $TimeoutSeconds s" }
            if ($ack -notlike 'Saved the game*') { throw "the server could not save: $ack" }
            $archive = New-BhWorldArchive $WorldDir $BackupDir $prefix
        } catch {
            $result.Message = $_.Exception.Message
        } finally {
            if ($attemptedOff) {
                $on = $null
                for ($i = 1; $i -le 3 -and -not $on; $i++) {
                    $on = Invoke-BhChannelCommand $Channel 'save-on' '(Automatic saving is now enabled|Saving is already turned on)' $shortTimeout
                    if (-not $on -and $i -lt 3) { Start-Sleep -Seconds ([Math]::Min(5, $shortTimeout)) }
                }
                if (-not $on) {
                    $result.SavingRestored = $false
                    Write-BhLog 'SAVING IS STILL OFF. Type save-on in the server console now.' ERROR
                }
                # Anything but our own save-on switching saving back on means the server may have
                # written the world while it was being zipped (stop, crash, someone's save-on).
                $cleanSaveOn = ($on -like 'Automatic saving is now enabled*')
            }
        }

        if ($archive -and -not $cleanSaveOn) {
            Remove-Item -LiteralPath $archive.Partial -Force -ErrorAction SilentlyContinue
            $result.Message = 'saving did not stay off for the whole archive (server stopped, crashed or someone typed save-on); archive discarded'
            $archive = $null
        }
        if ($archive) {
            try {
                Complete-BhWorldArchive $archive
                $result.Archive = $archive.Final
            } catch {
                $result.Message = "archive check failed: $($_.Exception.Message)"
            }
            if ($result.Archive) {
                try { $result.Removed = Remove-BhOldBackups $BackupDir $prefix $Keep }
                catch { Write-BhLog "could not remove old backups: $($_.Exception.Message)" WARN }
            }
        }
        $result.Ok = [bool]$result.Archive -and $result.SavingRestored
        if ($result.Archive) {
            Write-BhLog ("backup written: {0} ({1} files); removed {2} old backup(s)" -f (Split-Path $result.Archive -Leaf), $archive.Files, $result.Removed.Count)
        } else {
            Write-BhLog "backup FAILED, no new archive and no old backup deleted: $($result.Message)" ERROR
        }
        return $result
    } finally {
        $lock.Dispose()
    }
}

Export-ModuleMember -Function *-Bh*
