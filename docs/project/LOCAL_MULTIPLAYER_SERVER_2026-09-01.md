# Hearthstead local multiplayer server — Thursday package

## Outcome

Host the two-player Hearthstead demo for free on Tobias's Windows PC without
opening a public router port, while preserving exact mod identity, world safety
and a repeatable multiplayer test environment.

## Decision

- Dedicated NeoForge server, separate from the Minecraft client.
- Persistent runtime outside OneDrive at `C:\Users\tobia\HearthsteadServer`.
- Minecraft 1.21.1, NeoForge 21.1.248 and Java 21.
- 2 GB initial / 5 GB maximum server heap on the 32 GB system. Increase only
  after measuring a real two-client session.
- Server binds only to `127.0.0.1:25565`.
- Tailscale Serve forwards raw TCP 25565 privately inside the tailnet.
- Mojang online authentication and enforced whitelist stay enabled.
- No port forwarding, offline mode, RCON or public tunnel.

## Write lease

```text
LEASE ID: HS-SERVER-20260901-01
OWNER TASK PATH: /root
SUPERVISING CHIEF: Engineering & Systems
INTEGRATION OWNER: /root
AUTHORIZATION STATE: IMPLEMENTATION_ALREADY_AUTHORIZED
PLAYER OUTCOME: Tobias and one friend can run the exact Thursday demo privately and safely.
ALLOWED FILES/SYSTEMS: tools/local-server/*; this document; new owned roots C:\Users\tobia\HearthsteadServer and C:\Users\tobia\HearthsteadServerBackups
FORBIDDEN FILES/SYSTEMS: existing mod source; CurseForge profile; existing worlds; QA controller; router configuration
STARTING BRANCH/HEAD: integration/hearthstead-demo-recovery-20260830 @ 95425795c290
ROLLBACK BOUNDARY: newly created local-server files and the marker-owned external server root only
REQUIRED TESTS: PowerShell parse/status; official installer hash; exact artifact resolver; stopped backup verification; dedicated controller test; native two-client session
RELEASE CONDITION: no release claim until exact candidate identity and native multiplayer evidence pass
```

## User-owned steps

The automation deliberately cannot make these decisions for Tobias:

1. Read and accept the Minecraft EULA.
2. Enter the exact Minecraft Java usernames for the whitelist.
3. Sign in to Tailscale and share the server machine with the friend.
4. Approve any Windows administrator prompt needed for Tailscale installation.

## Acceptance gates

- Official NeoForge installer hash matches the official `.sha256` record.
- Java major version is exactly 21.
- Runtime version matches project `gradle.properties`.
- Exactly one Hearthstead server JAR exists and matches its atomic-install receipt.
- Candidate updates resolve through `artifact_identity.py`; no newest-file glob.
- Server refuses an unaccepted EULA, empty whitelist, invalid receipt or unsafe server properties.
- Server listens only on localhost; friend access uses private Tailscale TCP forwarding.
- A stopped-world backup contains `world/level.dat` and verifies before publication.
- Backup archives live outside the running server tree, extract into an isolated
  verification directory, and prove the included JAR matches its receipt.
- Dedicated server reaches ready state, restarts and preserves settlement SavedData.
- Final Thursday candidate passes exact-JAR two-player checks for join, reconnect,
  settlement state, inventory authority, UI/network sync, raid HUD and sleep denial.

## Current evidence boundary

The existing QA controller previously passed its dedicated headless boot,
founding, restart, persistence and client-classloading checks for an earlier
source fingerprint. That does not prove the final Thursday JAR or the persistent
Windows operator setup. Both must be checked again after the active demo code is
frozen and rebuilt.

## 2026-09-01 implementation checkpoint

- Write lease `HS-SERVER-20260901-01`: **RELEASED** after the bounded server
  package and external owned roots were created.
- NeoForge 21.1.248 runtime installed from the official Maven installer.
- Published installer SHA-256 and downloaded bytes match:
  `68eeab77059ba53df1812f1afa5bf530ab2566a3cdcd5f924aa6e71be42e410c`.
- Fresh identity-bound Hearthstead candidate built and installed:
  `hearthstead-0.2.0-g95425795c290-i7fdc101176cb787b5d05.jar`.
- Installed candidate SHA-256:
  `25f6073f2211847399d2dea202a2cf80ad928ff2241f06d0d2a5e51cc617ca2d`.
- `tools/hearthstead-qa quick`: **PASS** for the exact source fingerprint.
- `tools/hearthstead-qa dedicated`: **PASS** for the exact candidate; real
  server boot, settlement founding, clean restart, settler persistence,
  SavedData and absence of client-classloading errors were verified.
- PowerShell controller parse/status, runtime receipt, installer hash and
  installed-mod receipt checks: **PASS**.
- Tailscale 1.102.3 MSI is staged from the official package URL and matches
  SHA-256 `03ac8183c6e3ce276e9b44281ebe7e4c02aef28a971034ca170c4b665df42dce`.

Still user-owned and deliberately blocked:

- Minecraft EULA acceptance.
- Exact Tobias/friend Java usernames for the whitelist.
- Administrator confirmation for Tailscale installation, Tailscale sign-in and
  sharing the exact machine/port with the friend.
- Native Windows boot and real two-client Tailscale playtest of the final
  Thursday candidate.
