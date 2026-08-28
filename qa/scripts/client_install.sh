#!/usr/bin/env bash
# Idempotent shared production-style NeoForge CLIENT install -- the client
# twin of server_install.sh's fast path. One cache shared by every fast
# client boot, avoiding the `./gradlew runClient` route's per-boot gradle
# config/task-graph-eval (and, on a cold daemon or after a source change,
# recompile + dev-env artifact reassembly) tax.
#
# Produces a directory shaped like a real Minecraft Launcher install
# (launcher_profiles.json, versions/<mc>/, versions/neoforge-<ver>/,
# libraries/, assets/) via the REAL NeoForge installer's `--install-client`
# action, plus a generated launch.py that resolves the vanilla + neoforge
# version-json chain (standard Mojang launcher variable substitution) into
# a concrete `java` command. See live2.sh for how it's invoked, and the
# "How this works" notes below each stage for what was verified live.
#
# Caching discipline (mirrors server_install.sh): a completion marker file
# named installed-<neoforge_version> gates a full re-run; a second call
# with the same neoforge_version is a fast no-op. `--force` wipes and
# rebuilds.
#
# The big win is asset/library REUSE: the dev workspace's Gradle
# NeoForm-runtime cache (~/.gradle/caches/neoformruntime) already holds the
# full ~800MB vanilla asset set, the vanilla client jar and the vanilla
# version json (downloaded once by `./gradlew jar`/`runClient`), and
# Gradle's module cache (~/.gradle/caches/modules-2) already holds every
# vanilla+NeoForge library jar the dev classpath needed. This script copies
# (or symlinks, for the large read-only assets/objects tree) those instead
# of re-downloading them, and only lets the network provide the residual
# NeoForge-specific delta the real installer's own binary-patch pipeline
# needs (small: tens of MB, not the ~800MB asset set).
#
# Args: <mod-dir> [--force]
set -eu
MOD="$1"
FORCE="${2:-}"
INSTALL_DIR="${HSQA_CLIENT_INSTALL_DIR:-/tmp/claude-0/hsqa-client-install}"
NEO_VERSION=$(grep -oP 'neoforge_version=\K.*' "$MOD/gradle.properties")
GRADLE_CACHE="${HSQA_GRADLE_CACHE:-/root/.gradle/caches}"
NFRT="$GRADLE_CACHE/neoformruntime"
MODULES="$GRADLE_CACHE/modules-2/files-2.1"

if [ "$FORCE" = "--force" ]; then
    rm -rf "$INSTALL_DIR"
fi

if [ -f "$INSTALL_DIR/installed-$NEO_VERSION" ] && [ -d "$INSTALL_DIR/libraries" ] \
   && [ -f "$INSTALL_DIR/launch.py" ]; then
    echo "client install cached: $INSTALL_DIR (neoforge $NEO_VERSION)"
    exit 0
fi

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
rm -rf versions libraries assets launcher_profiles.json installed-* installer.jar* install.log launch.py
echo "installing NeoForge $NEO_VERSION CLIENT into $INSTALL_DIR ..."

# The real installer refuses to run against a directory that doesn't already
# look like a Minecraft Launcher install (net.minecraftforge.installer.
# actions.ClientInstall checks for this file by name before doing anything
# else) -- a bare {"profiles":{}} satisfies that check.
echo '{"profiles":{}}' > launcher_profiles.json

echo "downloading installer ..."
curl -sS -o installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar"
[ -s installer.jar ] || { echo "FAIL: installer_download -- installer.jar empty/missing after curl"; exit 1; }

# MC_VERSION is read from the installer's own embedded install_profile.json
# ("minecraft" field) rather than hardcoded, so this keeps working if
# gradle.properties' neoforge_version ever moves to a different Minecraft
# release.
MC_VERSION=$(unzip -p installer.jar install_profile.json | python3 -c "import json,sys; print(json.load(sys.stdin)['minecraft'])" 2>/dev/null || true)
[ -n "$MC_VERSION" ] || { echo "FAIL: mc_version -- could not read minecraft version from installer.jar's install_profile.json"; exit 1; }
echo "resolved minecraft version: $MC_VERSION (from installer profile)"

mkdir -p "versions/$MC_VERSION" assets/indexes

# ---- Reuse cache stage 1: vanilla version json + client jar --------------
# How this works (verified live via javap on ClientInstall.class): before
# downloading either file, the installer checks File.exists() at exactly
# these paths and skips the network call entirely when found -- this is the
# installer's OWN supported fast path, not a workaround around it. Missing
# either file here just means the installer will fetch it itself below.
NFRT_JSON="$NFRT/artifacts/minecraft_${MC_VERSION}_version_manifest.json"
NFRT_JAR="$NFRT/artifacts/minecraft_${MC_VERSION}_client.jar"
if [ -f "$NFRT_JSON" ]; then
    cp "$NFRT_JSON" "versions/$MC_VERSION/$MC_VERSION.json"
    echo "reused vanilla version json from Gradle NeoForm-runtime cache"
fi
if [ -f "$NFRT_JAR" ]; then
    cp "$NFRT_JAR" "versions/$MC_VERSION/$MC_VERSION.jar"
    echo "reused vanilla client jar from Gradle NeoForm-runtime cache"
fi

# ---- Reuse cache stage 2: the full vanilla asset set ----------------------
# assets/objects is a READ-ONLY, content-addressed tree the installer never
# touches (installing libraries/a version profile is its whole job; asset
# delivery is normally the launcher's job, which is why the assets/ layout
# below is prepared by this script, not by --install-client). Symlinked, not
# copied: it is ~800MB and fully shared/immutable, so there's nothing to gain
# from duplicating it onto disk.
if [ -f "versions/$MC_VERSION/$MC_VERSION.json" ]; then
    ASSET_IDX=$(python3 -c "import json; print(json.load(open('versions/$MC_VERSION/$MC_VERSION.json'))['assetIndex']['id'])" 2>/dev/null || true)
    if [ -n "$ASSET_IDX" ] && [ -f "$NFRT/assets/indexes/$ASSET_IDX.json" ]; then
        cp "$NFRT/assets/indexes/$ASSET_IDX.json" "assets/indexes/$ASSET_IDX.json"
        [ -e assets/objects ] || ln -sfn "$NFRT/assets/objects" assets/objects
        echo "reused asset index $ASSET_IDX + objects tree from Gradle NeoForm-runtime cache"
    fi
fi

# ---- Reuse cache stage 3: vanilla libraries (linux-applicable only) -------
# How this works: Gradle's module cache stores each maven artifact under
# files-2.1/<group>/<artifact>/<version>/<sha1>/<file> -- and that <sha1>
# directory component IS the same sha1 the version json's downloads.artifact
# lists (verified byte-for-byte). So the source path for any library is
# computable directly from the version json, no search needed. Anything
# genuinely missing here (or not applicable to another OS) is reconciled
# below, after the installer has run.
if [ -f "versions/$MC_VERSION/$MC_VERSION.json" ]; then
    mkdir -p libraries
    python3 - "$INSTALL_DIR" "$MODULES" "$MC_VERSION" <<'PYEOF'
import json, os, shutil, sys
install_dir, modules, mc = sys.argv[1], sys.argv[2], sys.argv[3]

def os_allows(rules):
    if not rules:
        return True
    allow = False
    for r in rules:
        act = r.get("action") == "allow"
        osr = r.get("os")
        if osr is None:
            allow = act
        elif osr.get("name") == "linux":
            allow = act
    return allow

d = json.load(open(f"{install_dir}/versions/{mc}/{mc}.json"))
seeded = 0
for lib in d["libraries"]:
    if not os_allows(lib.get("rules")):
        continue
    art = lib.get("downloads", {}).get("artifact")
    if not art:
        continue
    parts = lib["name"].split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    filename = os.path.basename(art["path"])
    src = f"{modules}/{group}/{artifact}/{version}/{art['sha1']}/{filename}"
    dst = f"{install_dir}/libraries/{art['path']}"
    if os.path.exists(dst) or not os.path.exists(src):
        continue
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)
    seeded += 1
print(f"reused {seeded} vanilla library jars from Gradle module cache")
PYEOF
fi

# ---- Run the real installer for the NeoForge-specific delta --------------
# What's left for it to do: fetch its own ~47 libraries (cpw.mods.*,
# fancymodloader, mixin, asm, etc. -- small, tens of MB) and run its local
# binary-patch processors (NeoForm data + client mappings) to produce the
# actual patched game classes as
# libraries/net/minecraft/client/<mc>-<neoform>/client-...-srg.jar. Verified
# live: with the above pre-seeding this completes in well under a minute
# and makes zero network calls for the ~800MB asset set or the vanilla
# jar/libraries.
echo "running NeoForge installer (fetches its own libraries + patches the game jar) ..."
if ! java -jar installer.jar --install-client "$INSTALL_DIR" > install.log 2>&1; then
    echo "FAIL: neoforge_install -- installer exited non-zero, see $INSTALL_DIR/install.log"
    exit 1
fi
grep -q "Successfully installed client" install.log \
    || { echo "FAIL: neoforge_install -- installer did not report success, see $INSTALL_DIR/install.log"; exit 1; }
echo "install ok"

NEOFORGE_ID="neoforge-$NEO_VERSION"
[ -f "versions/$NEOFORGE_ID/$NEOFORGE_ID.json" ] \
    || { echo "FAIL: neoforge_profile -- versions/$NEOFORGE_ID/$NEOFORGE_ID.json missing after install"; exit 1; }
[ -f "versions/$MC_VERSION/$MC_VERSION.json" ] \
    || { echo "FAIL: vanilla_profile -- versions/$MC_VERSION/$MC_VERSION.json missing after install"; exit 1; }
[ -f "versions/$MC_VERSION/$MC_VERSION.jar" ] \
    || { echo "FAIL: vanilla_jar -- versions/$MC_VERSION/$MC_VERSION.jar missing after install"; exit 1; }

# ---- Reconcile: any vanilla library still missing (cache miss above, or
# the installer didn't fetch it either since it only manages its own 47)
# gets fetched directly, sha1-verified, from Mojang's public library CDN.
# In practice this is a handful of small files at most (proven live: only
# one, lwjgl-opengl's non-natives jar, out of 56 linux-applicable vanilla
# libraries, needed this fallback).
MISSING=$(python3 - "$INSTALL_DIR" "$MC_VERSION" <<'PYEOF'
import json, os, sys
install_dir, mc = sys.argv[1], sys.argv[2]

def os_allows(rules):
    if not rules:
        return True
    allow = False
    for r in rules:
        act = r.get("action") == "allow"
        osr = r.get("os")
        if osr is None:
            allow = act
        elif osr.get("name") == "linux":
            allow = act
    return allow

d = json.load(open(f"{install_dir}/versions/{mc}/{mc}.json"))
for lib in d["libraries"]:
    if not os_allows(lib.get("rules")):
        continue
    art = lib.get("downloads", {}).get("artifact")
    if not art:
        continue
    dst = f"{install_dir}/libraries/{art['path']}"
    if not os.path.exists(dst):
        print(f"{art['path']}\t{art['sha1']}")
PYEOF
)
if [ -n "$MISSING" ]; then
    while IFS=$'\t' read -r LPATH LSHA1; do
        [ -z "$LPATH" ] && continue
        echo "fetching missing vanilla library: $LPATH"
        mkdir -p "libraries/$(dirname "$LPATH")"
        curl -sS -o "libraries/$LPATH" "https://libraries.minecraft.net/$LPATH"
        GOT=$(sha1sum "libraries/$LPATH" | cut -d' ' -f1)
        [ "$GOT" = "$LSHA1" ] || { echo "FAIL: library_fetch -- $LPATH sha1 mismatch (got $GOT, want $LSHA1)"; exit 1; }
    done <<< "$MISSING"
fi

# ---- Generate the launcher-arg resolver -----------------------------------
# Standard Mojang launcher variable substitution over the vanilla (parent)
# + neoforge (child, inheritsFrom) version-json chain: merged, deduplicated
# classpath, concatenated jvm/game argument arrays, mainClass from the
# child. Two behaviours below were proven necessary live, not assumed:
#   - the merged classpath must be DEDUPLICATED by resolved path (parent and
#     child both redeclare several libraries -- gson, guava, log4j -- at the
#     same version; BootstrapLauncher's module-layer builder throws
#     IllegalStateException: Duplicate key otherwise).
#   - the vanilla PRIMARY jar (<mc>.jar) must NOT go on the classpath for a
#     modded launch: FancyModLoader locates the actual patched game classes
#     itself (as a "production client provider", scanned from
#     $library_directory using --fml.mcVersion/--fml.neoFormVersion) and
#     putting the raw vanilla jar on the module path too makes the JPMS
#     resolver throw ResolutionException (two modules both export
#     net.minecraft.*) -- a split package, not a cosmetic duplicate.
#   - any argument gated by a launcher "features" rule (demo user, custom
#     resolution, quick play) must be treated as DENIED: this harness sets
#     none of those launcher feature flags, and --width/--height/
#     --quickPlayMultiplayer are appended explicitly by the caller instead
#     (matching build.gradle's own client run config, which does the same).
cat > launch.py <<'PYEOF'
#!/usr/bin/env python3
"""Resolve this NeoForge production-style client install's version-json
chain into a concrete java command line and exec it. Written by
client_install.sh -- see its comments for what each design choice fixed."""
import argparse, json, os, sys, uuid

def os_allows(rules):
    if not rules:
        return True
    allow = False
    for r in rules:
        if "features" in r:
            return False
        act = r.get("action") == "allow"
        osr = r.get("os")
        if osr is None:
            allow = act
        elif osr.get("name") == "linux":
            allow = act
    return allow

def load(v, install_dir):
    return json.load(open(f"{install_dir}/versions/{v}/{v}.json"))

def offline_uuid(name):
    # Same algorithm vanilla uses for offline/legacy accounts.
    return str(uuid.uuid3(uuid.NAMESPACE_OID, f"OfflinePlayer:{name}")).replace("-", "")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--install-dir", required=True)
    ap.add_argument("--mc-version", required=True)
    ap.add_argument("--neoforge-id", required=True)
    ap.add_argument("--game-dir", required=True)
    ap.add_argument("--username", default="Dev")
    ap.add_argument("--width", default="1280")
    ap.add_argument("--height", default="720")
    ap.add_argument("--join", default="")
    ap.add_argument("--print-only", action="store_true")
    args = ap.parse_args()

    install_dir = os.path.abspath(args.install_dir)
    parent = load(args.mc_version, install_dir)
    child = load(args.neoforge_id, install_dir)

    lib_dir = f"{install_dir}/libraries"
    natives_dir = f"{args.game_dir}/natives"
    os.makedirs(natives_dir, exist_ok=True)
    os.makedirs(args.game_dir, exist_ok=True)

    cp, seen = [], set()
    def add(p):
        if p not in seen:
            seen.add(p)
            cp.append(p)
    for lib in parent["libraries"]:
        if not os_allows(lib.get("rules")):
            continue
        art = lib.get("downloads", {}).get("artifact")
        if art:
            add(f"{lib_dir}/{art['path']}")
    # NOT the vanilla primary jar here -- see client_install.sh's comment.
    for lib in child["libraries"]:
        art = lib.get("downloads", {}).get("artifact")
        if art:
            add(f"{lib_dir}/{art['path']}")
    missing = [p for p in cp if not os.path.exists(p)]
    if missing:
        sys.stderr.write("FAIL: launch_classpath -- missing jars:\n  " + "\n  ".join(missing) + "\n")
        sys.exit(1)

    subs = {
        "natives_directory": natives_dir,
        "launcher_name": "hsqa-fastboot",
        "launcher_version": "1",
        "classpath": ":".join(cp),
        "classpath_separator": ":",
        "library_directory": lib_dir,
        "version_name": args.neoforge_id,
        "auth_player_name": args.username,
        "game_directory": args.game_dir,
        "assets_root": f"{install_dir}/assets",
        "assets_index_name": parent["assetIndex"]["id"],
        "auth_uuid": offline_uuid(args.username),
        "auth_access_token": "0",
        "clientid": str(uuid.uuid4()),
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
    }

    def sub(s):
        for k, v in subs.items():
            s = s.replace("${%s}" % k, v)
        return s

    def collect_args(version_json, kind):
        out = []
        for a in version_json["arguments"][kind]:
            if isinstance(a, str):
                out.append(sub(a))
            else:
                if not os_allows(a.get("rules")):
                    continue
                val = a["value"]
                out.extend(sub(x) for x in val) if isinstance(val, list) else out.append(sub(val))
        return out

    jvm_args = collect_args(parent, "jvm") + collect_args(child, "jvm")
    game_args = collect_args(parent, "game") + collect_args(child, "game")
    game_args += ["--width", args.width, "--height", args.height]
    if args.join:
        # A real vanilla game argument (added upstream in 23w41a), not a
        # mod-specific hook -- the same one build.gradle's client run passes
        # via HSQA_JOIN for the gradle-driven route.
        game_args += ["--quickPlayMultiplayer", args.join]

    cmd = ["java"] + jvm_args + [child["mainClass"]] + game_args
    if args.print_only:
        print(" ".join(cmd))
        return
    os.execvp("java", cmd)

if __name__ == "__main__":
    main()
PYEOF
chmod +x launch.py

touch "installed-$NEO_VERSION"
echo "client install ok: $INSTALL_DIR (neoforge $NEO_VERSION, minecraft $MC_VERSION)"
