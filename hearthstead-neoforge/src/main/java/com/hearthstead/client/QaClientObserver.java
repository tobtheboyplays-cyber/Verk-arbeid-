package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Dormant release-QA observer for the physical-input harness.
 *
 * <p>A server chat line is not an input barrier: Minecraft runs network tasks
 * near the start of a frame, applies accumulated mouse movement later, and
 * polls GLFW events at the end. When explicitly enabled by the isolated QA
 * client, this observer notices a nonce file at {@link RenderFrameEvent.Post},
 * waits for two <em>later</em> post-render events, then reports screen, grab and
 * camera state. Those two intervening frames necessarily include an
 * updateDisplay/poll cycle followed by handleAccumulatedMovement, so the ack
 * cannot overtake an XTEST event queued before the nonce file was written.
 *
 * <p>The observer performs no QA work or logging in a normal game launch. It
 * checks one game-directory activation marker once, then stays dormant unless
 * either that strict one-shot marker is consumed or
 * {@code HSQA_CLIENT_OBSERVER=1} plus a valid
 * {@code HSQA_CLIENT_SESSION} reach the client process.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class QaClientObserver {
    private static final boolean ENVIRONMENT_REQUESTED =
        "1".equals(System.getenv("HSQA_CLIENT_OBSERVER"));
    private static final String ENVIRONMENT_SESSION =
        System.getenv("HSQA_CLIENT_SESSION");
    private static final boolean NATIVE_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");
    private static final String REQUEST_FILE = "hsqa-frame-request";
    private static final String ACTIVATION_FILE = "hsqa-native-observer-once.txt";
    private static final String ACTIVATION_HEADER = "HEARTHSTEAD_NATIVE_QA_V2";
    private static final int AUXILIARY_GLFW_BUTTON = 3;
    private static final int FRAME_SAMPLE_CAPACITY = 360;
    private static final long SLOW_FRAME_NANOS = 33_333_333L;
    private static final long VERY_SLOW_FRAME_NANOS = 100_000_000L;
    private static final Pattern VALID_NONCE =
        Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern VALID_SESSION =
        Pattern.compile("[A-Za-z0-9_-]{8,80}");
    private static final Pattern INVALID_STATE_CHARACTER =
        Pattern.compile("[^A-Za-z0-9_.,:=/+\\-]");

    private static long postOrdinal;
    private static String pendingNonce;
    private static long acknowledgeAtPost;
    private static String lastAcceptedNonce;
    private static boolean requestIoErrorLogged;
    private static boolean invalidNonceLogged;
    private static final long[] frameNanos = new long[FRAME_SAMPLE_CAPACITY];
    private static int frameSampleCount;
    private static int frameSampleCursor;
    private static long previousFramePostNanos;
    private static String observedScreenClass = "uninitialised";
    private static String uiTransition = "observer_start";
    private static String cachedRuntimeJarSha256;
    private static String cachedRuntimeJarSource = "unavailable";
    private static String cachedGameDirectoryToken = "unavailable";
    private static String cachedRuntimeJarPathToken = "unavailable";
    private static boolean activationChecked;
    private static boolean active;
    private static String qaSession = "none";
    private static String observerSource = "disabled";

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureActivated(minecraft)) {
            return;
        }

        sampleFrameTiming(minecraft);
        long thisPost = ++postOrdinal;

        if (pendingNonce != null) {
            if (thisPost < acknowledgeAtPost) {
                return;
            }
            String nonce = pendingNonce;
            try {
                acknowledge(Minecraft.getInstance(), nonce);
            } catch (RuntimeException exception) {
                Hearthstead.LOGGER.error(
                    "HSQA_FRAME_ERROR code=snapshot_error", exception);
            } finally {
                pendingNonce = null;
            }
            return;
        }

        Path request = minecraft.gameDirectory.toPath()
            .toAbsolutePath()
            .normalize()
            .resolve(REQUEST_FILE);
        String nonce = readNonce(request);
        if (nonce == null || nonce.equals(lastAcceptedNonce)) {
            return;
        }

        lastAcceptedNonce = nonce;
        pendingNonce = nonce;
        acknowledgeAtPost = thisPost + 2;
    }

    /**
     * Starts a fresh frame-time window for an interaction that does not change
     * screen class (tab, popout, pan, zoom or scroll). This is a no-op outside
     * the explicitly instrumented QA client.
     */
    public static void markUiTransition(String label) {
        if (!active) {
            return;
        }
        resetFrameWindow(safeToken(label));
    }

    private static void sampleFrameTiming(Minecraft minecraft) {
        long now = System.nanoTime();
        String screenClass = minecraft.screen == null
            ? "null"
            : minecraft.screen.getClass().getName();
        if (!screenClass.equals(observedScreenClass)) {
            observedScreenClass = screenClass;
            String simpleName = minecraft.screen == null
                ? "none"
                : minecraft.screen.getClass().getSimpleName();
            resetFrameWindow("screen_" + safeToken(simpleName));
            previousFramePostNanos = now;
            return;
        }

        if (previousFramePostNanos != 0L) {
            long elapsed = now - previousFramePostNanos;
            if (elapsed > 0L) {
                frameNanos[frameSampleCursor] = elapsed;
                frameSampleCursor = (frameSampleCursor + 1) % FRAME_SAMPLE_CAPACITY;
                frameSampleCount = Math.min(frameSampleCount + 1,
                    FRAME_SAMPLE_CAPACITY);
            }
        }
        previousFramePostNanos = now;
    }

    private static void resetFrameWindow(String label) {
        frameSampleCount = 0;
        frameSampleCursor = 0;
        previousFramePostNanos = 0L;
        uiTransition = label;
    }

    private static String readNonce(Path request) {
        try {
            if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)
                    || !isPhysicalRegularFile(request)) {
                return null;
            }
            long size = Files.size(request);
            if (size < 1 || size > 96) {
                logInvalidNonceOnce();
                return null;
            }

            String nonce = Files.readString(request, StandardCharsets.UTF_8).strip();
            if (!VALID_NONCE.matcher(nonce).matches()) {
                logInvalidNonceOnce();
                return null;
            }

            requestIoErrorLogged = false;
            invalidNonceLogged = false;
            return nonce;
        } catch (IOException exception) {
            if (!requestIoErrorLogged) {
                requestIoErrorLogged = true;
                Hearthstead.LOGGER.error(
                    "HSQA_FRAME_ERROR code=request_io", exception);
            }
            return null;
        }
    }

    /**
     * Enable the observer either through an inherited environment request or
     * through one strict, expiring marker in the selected game directory.
     * The fallback exists because CurseForge may insert another launcher
     * process that does not preserve the parent environment. The marker is
     * deleted before activation; a malformed, expired, symlinked or
     * non-deletable marker fails closed and cannot silently enable QA.
     */
    private static boolean ensureActivated(Minecraft minecraft) {
        if (activationChecked) {
            return active;
        }
        activationChecked = true;
        Path marker = minecraft.gameDirectory.toPath().toAbsolutePath()
            .normalize().resolve(ACTIVATION_FILE);
        if (ENVIRONMENT_REQUESTED) {
            try {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(marker);
                    Hearthstead.LOGGER.error(
                        "HSQA_FRAME_ERROR code=ambiguous_activation markerRemoved=true");
                    return false;
                }
            } catch (IOException exception) {
                Hearthstead.LOGGER.error(
                    "HSQA_FRAME_ERROR code=ambiguous_activation_cleanup", exception);
                return false;
            }
            if (ENVIRONMENT_SESSION == null
                    || !VALID_SESSION.matcher(ENVIRONMENT_SESSION).matches()) {
                Hearthstead.LOGGER.error(
                    "HSQA_FRAME_ERROR code=invalid_environment_session");
                return false;
            }
            active = true;
            qaSession = ENVIRONMENT_SESSION;
            observerSource = "environment";
            Hearthstead.LOGGER.info(
                "HSQA_OBSERVER_ENABLED source={} qaSession={} markerConsumed=false "
                    + "markerCreatedEpochSeconds=none markerExpiresEpochSeconds=none",
                observerSource, qaSession);
            return true;
        }
        if (ENVIRONMENT_SESSION != null) {
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code=session_without_environment_activation");
            return false;
        }

        try {
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!isPhysicalRegularFile(marker) || Files.size(marker) > 256L) {
                return rejectActivationMarker(marker, "invalid_activation_marker");
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 4 || !ACTIVATION_HEADER.equals(lines.get(0))
                    || !lines.get(1).startsWith("session=")
                    || !lines.get(2).startsWith("createdEpochSeconds=")
                    || !lines.get(3).startsWith("expiresEpochSeconds=")) {
                return rejectActivationMarker(marker, "invalid_activation_marker");
            }
            String session = lines.get(1).substring("session=".length());
            long created = Long.parseLong(lines.get(2).substring(
                "createdEpochSeconds=".length()));
            long expires = Long.parseLong(lines.get(3).substring(
                "expiresEpochSeconds=".length()));
            long now = System.currentTimeMillis() / 1000L;
            if (!VALID_SESSION.matcher(session).matches()
                    || created > now || now - created > 300L
                    || expires <= now || expires - created > 3600L) {
                return rejectActivationMarker(
                    marker, "expired_or_invalid_activation_marker");
            }
            Files.delete(marker);
            active = true;
            qaSession = session;
            observerSource = "one_shot_marker";
            Hearthstead.LOGGER.info(
                "HSQA_OBSERVER_ENABLED source={} qaSession={} markerConsumed=true "
                    + "markerCreatedEpochSeconds={} markerExpiresEpochSeconds={}",
                observerSource, qaSession, created, expires);
            return true;
        } catch (NumberFormatException exception) {
            return rejectActivationMarker(marker, "invalid_activation_marker_number");
        } catch (IOException exception) {
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code=activation_marker_io", exception);
            return false;
        }
    }

    private static boolean rejectActivationMarker(Path marker, String code) {
        try {
            Files.delete(marker);
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code={} markerRemoved=true", code);
        } catch (IOException exception) {
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code={} markerRemoved=false", code, exception);
        }
        return false;
    }

    private static void logInvalidNonceOnce() {
        if (!invalidNonceLogged) {
            invalidNonceLogged = true;
            Hearthstead.LOGGER.error("HSQA_FRAME_ERROR code=invalid_nonce");
        }
    }

    private static void acknowledge(Minecraft minecraft, String nonce) {
        String runtimeJarSha256 = runtimeJarSha256(minecraft);
        String screen = minecraft.screen == null
            ? "null"
            : minecraft.screen.getClass().getSimpleName();
        String screenClass = minecraft.screen == null
            ? "null"
            : minecraft.screen.getClass().getName();
        boolean grabbed = minecraft.mouseHandler.isMouseGrabbed();
        String auxiliaryBindings = Arrays.stream(minecraft.options.keyMappings)
            .filter(mapping -> mapping.matchesMouse(AUXILIARY_GLFW_BUTTON))
            .map(KeyMapping::getName)
            .sorted()
            .collect(Collectors.joining(","));
        if (auxiliaryBindings.isEmpty()) {
            auxiliaryBindings = "none";
        }

        float yaw = minecraft.player == null ? Float.NaN : minecraft.player.getYRot();
        float pitch = minecraft.player == null ? Float.NaN : minecraft.player.getXRot();
        String playerPos = minecraft.player == null
            ? "unavailable"
            : minecraft.player.getX() + "," + minecraft.player.getY() + ","
                + minecraft.player.getZ();
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        String language = minecraft.getLanguageManager().getSelected();
        String hitType = minecraft.hitResult == null
            ? "null"
            : minecraft.hitResult.getType().name().toLowerCase(java.util.Locale.ROOT);
        String hitBlock = "none";
        if (minecraft.hitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            hitBlock = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
        String blessingTitle = I18n.get("hearthstead.blessing.title");
        boolean blessingTitleResolved =
            !"hearthstead.blessing.title".equals(blessingTitle);
        boolean blessingTitleLanguageMatch = switch (language) {
            case "en_us" -> "Choose a Blessing Seal".equals(blessingTitle);
            case "nb_no" -> "Velg et velsignelsessegl".equals(blessingTitle);
            default -> false;
        };
        String uiState = minecraft.screen instanceof QaUiInspectable inspectable
            ? safeToken(inspectable.qaUiState())
            : "unavailable";
        FrameStats frameStats = snapshotFrameStats();
        boolean integratedServer = minecraft.getSingleplayerServer() != null;
        String worldPathToken = worldPathToken(minecraft);
        long observedEpochMillis = System.currentTimeMillis();
        Hearthstead.LOGGER.info(
            "HSQA_FRAME_ACK nonce={} qaSession={} observerSource={} nativeWindows={} "
                + "runtimeJarSha256={} runtimeJarSource={} gameDirectoryToken={} "
                + "runtimeJarPathToken={} worldPathToken={} integratedServer={} "
                + "observedEpochMillis={} screen={} screenClass={} grabbed={} "
                + "auxButtonBound={} yaw={} pitch={} playerPos={} hitType={} hitBlock={} "
                + "guiScale={} gui={}x{} framebuffer={}x{} "
                + "language={} blessingTitleResolved={} blessingTitleLanguageMatch={} "
                + "uiState={} uiTransition={} frameSamples={} frameP50Ms={} "
                + "frameP95Ms={} frameP99Ms={} frameMaxMs={} framesOver33Ms={} "
                + "framesOver100Ms={}",
            nonce, qaSession, observerSource, NATIVE_WINDOWS, runtimeJarSha256,
            cachedRuntimeJarSource, cachedGameDirectoryToken,
            cachedRuntimeJarPathToken, worldPathToken, integratedServer,
            observedEpochMillis,
            screen, screenClass, grabbed, auxiliaryBindings, yaw, pitch,
            playerPos, hitType, hitBlock,
            window.getGuiScale(), window.getGuiScaledWidth(),
            window.getGuiScaledHeight(), window.getWidth(), window.getHeight(),
            language, blessingTitleResolved, blessingTitleLanguageMatch,
            uiState, uiTransition, frameStats.samples(), frameStats.p50Ms(),
            frameStats.p95Ms(), frameStats.p99Ms(), frameStats.maxMs(),
            frameStats.over33Ms(), frameStats.over100Ms());
    }

    /**
     * Hash the code source that is actually executing inside this client.
     * The release gate compares this value with both the candidate and the
     * separately installed JAR; hashing those files after a run is not proof
     * that either one supplied the classes used by Minecraft.
     */
    private static String runtimeJarSha256(Minecraft minecraft) {
        if (cachedRuntimeJarSha256 != null) {
            return cachedRuntimeJarSha256;
        }
        try {
            Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath()
                .normalize();
            if (!isPhysicalDirectory(gameDirectory)) {
                cachedRuntimeJarSha256 = "unavailable";
                return cachedRuntimeJarSha256;
            }
            gameDirectory = gameDirectory.toRealPath();
            Path modsDirectory = gameDirectory.resolve("mods");
            if (!isPhysicalDirectory(modsDirectory)) {
                cachedRuntimeJarSha256 = "unavailable";
                return cachedRuntimeJarSha256;
            }
            modsDirectory = modsDirectory.toRealPath();
            cachedGameDirectoryToken = pathToken(gameDirectory);

            Path codeSourcePath = null;
            var codeSource = QaClientObserver.class.getProtectionDomain().getCodeSource();
            if (codeSource != null
                    && "file".equalsIgnoreCase(codeSource.getLocation().getProtocol())) {
                Path candidate = Path.of(codeSource.getLocation().toURI());
                if (isInstalledModJar(candidate, modsDirectory)) {
                    codeSourcePath = candidate.toRealPath();
                }
            }

            Path modListPath = null;
            var modFileInfo = ModList.get().getModFileById(Hearthstead.MODID);
            if (modFileInfo != null) {
                Path candidate = modFileInfo.getFile().getFilePath();
                if (isInstalledModJar(candidate, modsDirectory)) {
                    modListPath = candidate.toRealPath();
                }
            }

            Path source;
            if (codeSourcePath != null && modListPath != null
                    && !Files.isSameFile(codeSourcePath, modListPath)) {
                Hearthstead.LOGGER.error(
                    "HSQA_FRAME_ERROR code=runtime_jar_source_mismatch codeSource={} modList={}",
                    codeSourcePath, modListPath);
                cachedRuntimeJarSha256 = "unavailable";
                return cachedRuntimeJarSha256;
            }
            if (codeSourcePath != null) {
                source = codeSourcePath;
                cachedRuntimeJarSource = "code_source";
            } else if (modListPath != null) {
                source = modListPath;
                cachedRuntimeJarSource = "mod_list";
            } else {
                cachedRuntimeJarSha256 = "unavailable";
                return cachedRuntimeJarSha256;
            }
            cachedRuntimeJarPathToken = pathToken(source);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(source)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            cachedRuntimeJarSha256 = HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code=runtime_jar_hash", exception);
            cachedRuntimeJarSha256 = "unavailable";
        }
        return cachedRuntimeJarSha256;
    }

    /**
     * Accept only a physical JAR in an external {@code mods} directory. Union
     * filesystem roots, transformed caches and development class directories
     * must never become release identity evidence.
     */
    private static boolean isInstalledModJar(Path candidate, Path modsDirectory)
            throws IOException {
        if (candidate == null
                || !"file".equalsIgnoreCase(
                    candidate.getFileSystem().provider().getScheme())
                || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                || !isPhysicalRegularFile(candidate)
                || candidate.getFileName() == null
                || !candidate.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".jar")) {
            return false;
        }
        Path parent = candidate.toAbsolutePath().normalize().getParent();
        return parent != null && parent.getFileName() != null
            && "mods".equalsIgnoreCase(parent.getFileName().toString())
            && Files.isSameFile(parent, modsDirectory);
    }

    private static boolean isPhysicalDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && !attributes.isSymbolicLink()
            && !attributes.isOther() && !Files.isSymbolicLink(path);
    }

    private static boolean isPhysicalRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isRegularFile() && !attributes.isSymbolicLink()
            && !attributes.isOther() && !Files.isSymbolicLink(path);
    }

    private static String worldPathToken(Minecraft minecraft) {
        try {
            if (minecraft.getSingleplayerServer() == null) {
                return "unavailable";
            }
            Path world = minecraft.getSingleplayerServer()
                .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (!isPhysicalDirectory(world)) {
                return "unavailable";
            }
            return pathToken(world.toRealPath());
        } catch (Exception exception) {
            Hearthstead.LOGGER.error(
                "HSQA_FRAME_ERROR code=world_path_identity", exception);
            return "unavailable";
        }
    }

    private static String pathToken(Path path) throws Exception {
        String canonical = path.toRealPath().toString().replace('\\', '/');
        if (NATIVE_WINDOWS) {
            canonical = canonical.toLowerCase(Locale.ROOT);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(
            digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static FrameStats snapshotFrameStats() {
        int samples = frameSampleCount;
        if (samples == 0) {
            return FrameStats.EMPTY;
        }
        long[] sorted = Arrays.copyOf(frameNanos, samples);
        Arrays.sort(sorted);
        int slow = 0;
        int verySlow = 0;
        for (long sample : sorted) {
            if (sample > SLOW_FRAME_NANOS) {
                slow++;
            }
            if (sample > VERY_SLOW_FRAME_NANOS) {
                verySlow++;
            }
        }
        return new FrameStats(samples, millis(percentile(sorted, 0.50D)),
            millis(percentile(sorted, 0.95D)),
            millis(percentile(sorted, 0.99D)),
            millis(sorted[sorted.length - 1]), slow, verySlow);
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0D) / 100.0D;
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return INVALID_STATE_CHARACTER.matcher(value).replaceAll("_");
    }

    private record FrameStats(int samples, double p50Ms, double p95Ms,
                              double p99Ms, double maxMs, int over33Ms,
                              int over100Ms) {
        private static final FrameStats EMPTY =
            new FrameStats(0, 0.0D, 0.0D, 0.0D, 0.0D, 0, 0);
    }

    private QaClientObserver() {
    }
}
