# Bannerhold dedicated server kit (Sunday 27 Sep, 18:00)

For a fresh co-op survival world on a Windows dedicated server: NeoForge 21.1.248,
Minecraft 1.21.1, Java 21, Normal difficulty, PvP on.

| File | What it is |
|---|---|
| `server.properties.sunday` | Template. Copy it to `<server>\server.properties`. |
| `start-server.ps1` | Starts the server (6 GB, G1GC), restarts it after a crash, backs up every hour through the console. |
| `backup-hourly.ps1` | The same backup over RCON, for a server started some other way. |
| `BannerholdServer.psm1` | Shared code for both scripts. Keep it next to them. |
| `selftest/` | Mock server and RCON plus a test runner. Touches no real server or save. |

No file here contains a path, password or address. You give those as parameters.

## Everyday use

```powershell
cd <this folder>
powershell -ExecutionPolicy Bypass -File .\start-server.ps1 -ServerDir 'D:\Bannerhold Server'
```

- Type server commands in that window as usual (`whitelist add Name`, `op Name`, `list`).
- `stop` stops the server for good. Never just close the window: that skips the final save.
- `backup-now` makes a backup immediately.
- Backups go to `<server>\backups\world-yyyyMMdd-HHmmss.zip`. The newest 12 are kept (12 hours).
- The wrapper's own log is `backups\server-wrapper.log`. The server's log is `logs\latest.log`.
- If the server has not exited 120 s after `stop`, the wrapper kills it and logs that.
- The script turns off QuickEdit in the classic console window: there, a mouse click freezes all output until Esc is pressed. Still, don't click-select text in the server window.

### What the scripts guarantee

- **Backup order.**
  1. `save-off`.
  2. `save-all flush`, waiting until the server really prints `Saved the game`. Only a whole server log line counts (`[time] [Server thread/INFO] [logger]: Saved the game`); a player's chat line, whatever it contains, never does.
  3. The world is zipped to a `.zip.partial` file.
  4. `save-on`. This always runs, also after any failure; it is tried 3 times and prints `SAVING IS STILL OFF` if it never succeeds.
     Unless the server answers exactly `Automatic saving is now enabled`, the zip is thrown away: the server may have written the world while it was being zipped (a stop, a crash, or someone typing `save-on`).
  5. Every file in the zip is read back and its SHA-256 compared with the bytes read from the world (Windows' .NET does not check ZIP CRCs itself).
  6. The file is renamed to `.zip`.
  7. The oldest backups beyond 12 are deleted.
- **Failure safety.**
  - A failed or incomplete backup leaves no file behind and never deletes an older one.
  - Only files named exactly `world-yyyyMMdd-HHmmss.zip`, directly in the backup folder, are ever deleted.
  - Refused before anything is touched: a world folder outside the server folder, a backup folder inside the world, and any junction or symbolic link on the server, world or backup path or inside the world. Keep the server in a plain folder (not OneDrive).
- **Restarts.**
  - A new file in `crash-reports\`, or a non-zero exit code, means a crash. The server restarts after 15 s.
  - A real Minecraft crash exits with code 0, so the crash report is what gives it away.
  - After 4 crashes within 10 minutes the script gives up (exit code 4) instead of looping.
  - `stop` or an operator's `/stop` gives exit code 0 with no crash report, so there is no restart.
- **No duplicates.**
  - A second `start-server.ps1` for the same folder is refused.
  - It also refuses to start if something already listens on `server-port`.

## Pre-flight checklist (do this on Saturday, not Sunday at 17:55)

**1. Back up what exists first**
- [ ] Copy every existing world to another drive or USB stick before installing anything:
  - the launcher's `saves` folder (usually `%APPDATA%\.minecraft\saves`, or your launcher instance folder);
  - any old server folders.
- [ ] Open one copied world's folder and check that `level.dat` is there.

**2. Server machine**
- [ ] `java -version` prints 21.x. If not, install a Java 21 JDK (for example Temurin 21).
- [ ] There is RAM for 6 GB of server heap plus Windows. If the same PC also runs a game client, 16 GB total is the practical minimum.
- [ ] Make a new, empty folder, for example `D:\Bannerhold Server`.
- [ ] Download `neoforge-21.1.248-installer.jar` from neoforged.net and run it in that folder:
  `java -jar neoforge-21.1.248-installer.jar --installServer`
- [ ] Copy `server.properties.sunday` to `server.properties` in that folder.
  - Fill in `level-seed=` if you want a specific seed.
- [ ] Read the Minecraft EULA, then create `eula.txt` containing `eula=true`.

**3. Mods: identical on server and clients**

| Mod | Version | Server | Clients | Notes |
|---|---|---|---|---|
| NeoForge | 21.1.248 | yes | yes | Loader. Every client uses the same version. |
| Bannerhold (`hearthstead-*.jar`) | one agreed build | yes | yes | The *same* jar file on all machines. |
| *(any other mod)* | | | | Fill in the table. Client-only mods (minimap, shaders, UI) stay off the server. |

- [ ] Bannerhold itself needs only NeoForge and Minecraft 1.21.1, per its `neoforge.mods.toml`.
- [ ] Compare the Bannerhold jar across machines. `Get-FileHash .\mods\hearthstead-*.jar` must print the same hash everywhere.

**4. Network and access**
- [ ] Router: forward **TCP 25565 only** to the server PC.
  - Never forward 25575 (RCON).
  - Share your public address with the friends privately, not in the repo or chat logs you publish.
- [ ] Windows Firewall: add an inbound rule for **TCP 25565 only**, not a blanket "allow Java" (that would also open RCON on 25575 to your LAN if you ever enable it). As admin:
  `New-NetFirewallRule -DisplayName 'Bannerhold 25565' -Direction Inbound -Protocol TCP -LocalPort 25565 -Action Allow`
- [ ] The template turns the whitelist on. After the first start, type in the console:
  - `whitelist add <each player>`
  - `op <owner>`

**5. First start and first-join test**
- [ ] Start with `start-server.ps1` and wait for `server is ready`.
- [ ] Type `backup-now`. Check that `backups\` holds a new `world-*.zip` and that it contains `world\level.dat`.
- [ ] The owner joins, then one friend joins.
  - Both see each other.
  - `list` shows both.
  - PvP damage works between them.
  - A plaque can be hung and read by both.
- [ ] Type `stop`. The window says `not restarting`. Start again: the same world loads.
- [ ] Do one restore drill (below) on a copy, not on the live folder.

**6. Sunday**
- [ ] 17:30: start the server.
- [ ] 17:50: `backup-now`.
- [ ] 18:00: play.

## Restoring a backup

1. `stop` the server and wait for the window to say `not restarting`.
2. Rename `world` to `world-broken-<date>`. Don't delete it yet.
3. Unzip the chosen `backups\world-*.zip` into the server folder. It contains a `world\` folder.
4. Start the server again.

## Optional: RCON instead of the console

Use this only if the server is not started with `start-server.ps1`.

1. In `server.properties`, set `enable-rcon=true` and a long random `rcon.password`, on the server PC only.
2. Run:
   ```powershell
   .\backup-hourly.ps1 -ServerDir 'D:\Bannerhold Server' -IntervalMinutes 60
   ```
   - It asks for the password.
   - For unattended runs, set `BANNERHOLD_RCON_PASSWORD` for that user instead. Windows stores a user variable in plain text (like `server.properties` itself), so only do this on the server PC.
   - Or, from a PowerShell prompt (not `powershell -File`, which cannot pass a SecureString): `.\backup-hourly.ps1 -ServerDir '...' -RconPassword (Read-Host -AsSecureString)`.

- With `start-server.ps1 -Backup Rcon` the wrapper uses RCON for its own backups.
- Exit codes of `backup-hourly.ps1`:
  - 0: backup written.
  - 1: failed; saving was restored.
  - 2: saving could **not** be turned back on.
  - 3: refused before touching the server.

## Settings added beyond the requested ones

The requested values are kept exactly:
`difficulty=normal`, `pvp=true`, `simulation-distance=12`, `view-distance=10`, `spawn-protection=0`,
`allow-flight=false`, `max-players=4`, and an empty `level-seed=`.

These were added for an internet-facing server. Remove any you don't want:
- `online-mode=true`, `white-list=true`, `enforce-whitelist=true`: only the invited players can join.
- `level-name=world`, `server-port=25565`, `server-ip=` (empty), `gamemode=survival`, `hardcore=false`: the defaults, written out.
- `enable-rcon=false`, an empty `rcon.password`: RCON is off unless you choose it.

The JVM flags in `start-server.ps1` are Aikar's G1GC set for heaps below 12 GB, with `-Xms6G -Xmx6G`.
`-XX:+AlwaysPreTouch` claims the full 6 GB at start. That is expected.

## Self-test

```powershell
powershell -ExecutionPolicy Bypass -File .\selftest\run-selftest.ps1
```

It runs 120 checks with a mock server and a mock RCON in a temp folder:
- backup success and retention;
- log-line matching against real NeoForge and vanilla lines and spoofed chat lines;
- a damaged archive of the same length (never promoted);
- flush failure (including spoofed chat lines), slow and too-slow flush, spoofed save-on;
- a locked world file;
- save-on recovery, and discarding a zip when saving did not stay off;
- a server that hangs after `stop`;
- crash and watchdog restarts, and the crash-loop limit;
- intentional stop and duplicate-instance refusal;
- paths with spaces, path-safety refusals, and junctions or symbolic links;
- RCON login failure.
