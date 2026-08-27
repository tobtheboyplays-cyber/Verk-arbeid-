package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
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
 * <p>The observer performs no QA work, file IO or logging in a normal game
 * launch. It activates only when {@code HSQA_CLIENT_OBSERVER=1} is present in
 * the client process environment.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class QaClientObserver {
    private static final boolean ENABLED =
        "1".equals(System.getenv("HSQA_CLIENT_OBSERVER"));
    private static final String REQUEST_FILE = "hsqa-frame-request";
    private static final int AUXILIARY_GLFW_BUTTON = 3;
    private static final Pattern VALID_NONCE =
        Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private static long postOrdinal;
    private static String pendingNonce;
    private static long acknowledgeAtPost;
    private static String lastAcceptedNonce;
    private static boolean requestIoErrorLogged;
    private static boolean invalidNonceLogged;

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (!ENABLED) {
            return;
        }

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

        Minecraft minecraft = Minecraft.getInstance();
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

    private static String readNonce(Path request) {
        try {
            if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) {
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

    private static void logInvalidNonceOnce() {
        if (!invalidNonceLogged) {
            invalidNonceLogged = true;
            Hearthstead.LOGGER.error("HSQA_FRAME_ERROR code=invalid_nonce");
        }
    }

    private static void acknowledge(Minecraft minecraft, String nonce) {
        String screen = minecraft.screen == null
            ? "null"
            : minecraft.screen.getClass().getSimpleName();
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
        Hearthstead.LOGGER.info(
            "HSQA_FRAME_ACK nonce={} screen={} grabbed={} auxButtonBound={} yaw={} pitch={}",
            nonce, screen, grabbed, auxiliaryBindings, yaw, pitch);
    }

    private QaClientObserver() {
    }
}
