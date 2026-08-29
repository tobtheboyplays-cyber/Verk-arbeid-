package com.hearthstead.client.debug;

import com.hearthstead.Hearthstead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * QA-only per-frame cost meter for this mod's screens.
 *
 * <h2>Why this exists</h2>
 *
 * <p>"The UI drops frames" is a claim, and this repository does not accept
 * claims — it accepts evidence (see {@code qa/PROTOCOL.md}). A successful
 * compile says nothing about what a screen costs per frame, and eyeballing
 * the F3 counter conflates the screen's cost with the world's. This measures
 * the screen alone, inside the real frame loop, on the real render thread:
 * {@link ScreenEvent.Render.Pre} to {@link ScreenEvent.Render.Post} is
 * exactly the span Minecraft spends drawing the open screen, so the delta is
 * the number a UI change is answerable for.
 *
 * <h2>Two numbers, because one of them hides</h2>
 *
 * <p>Wall time per frame is the symptom a player feels. Bytes allocated per
 * frame is the cause they feel later: a screen that allocates a fresh layout
 * object, a fresh {@code Component}, and a fresh list every frame does not
 * necessarily show up as a slow frame — it shows up as a young-generation
 * collection a few seconds after the screen opens, which reads to the player
 * as "the UI stutters". Both are recorded, per screen class, so a fix can be
 * proven against the one it targeted.
 *
 * <h2>Players never pay for this</h2>
 *
 * <p>{@link #ENABLED} is a {@code static final} read of a system property, so
 * with the flag absent every handler below folds to a constant-false branch
 * the JIT removes outright. The profiler is switched on only by the QA
 * harness, with {@code -Dhearthstead.uiprofile=true}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class UiProfiler {

    /** QA-only. Absent for players, so every handler below is dead code. */
    private static final boolean ENABLED = Boolean.getBoolean("hearthstead.uiprofile");

    /** Frames discarded after a screen opens, so JIT warmup is not measured. */
    private static final int WARMUP_FRAMES = 40;
    /** Frames per emitted report line. At 60fps that is a report every ~2s. */
    private static final int WINDOW_FRAMES = 120;

    private static final com.sun.management.ThreadMXBean ALLOC_BEAN = allocBean();

    private static String currentScreen = "";
    private static int seen;
    private static int samples;
    private static final long[] NANOS = new long[WINDOW_FRAMES];
    private static long allocAtWindowStart;
    private static long frameStartNanos;

    private static com.sun.management.ThreadMXBean allocBean() {
        if (!ENABLED) {
            return null;
        }
        // Allocation accounting is a HotSpot extension, not part of the
        // java.lang.management contract; on a JVM without it the profiler
        // still reports timings and simply says so about bytes.
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOC_BEAN == null ? -1L : ALLOC_BEAN.getCurrentThreadAllocatedBytes();
    }

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!ENABLED) {
            return;
        }
        String name = event.getScreen().getClass().getSimpleName();
        if (!name.equals(currentScreen)) {
            // A different screen is a different measurement. Reset rather
            // than blend two screens' costs into one meaningless average.
            currentScreen = name;
            seen = 0;
            samples = 0;
            allocAtWindowStart = allocatedBytes();
        }
        frameStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!ENABLED) {
            return;
        }
        long elapsed = System.nanoTime() - frameStartNanos;
        seen++;
        if (seen <= WARMUP_FRAMES) {
            // Still warming: keep the allocation baseline moving with it, so
            // the first reported window measures steady state, not classload.
            allocAtWindowStart = allocatedBytes();
            return;
        }
        if (samples < WINDOW_FRAMES) {
            NANOS[samples++] = elapsed;
        }
        if (samples >= WINDOW_FRAMES) {
            report(event.getScreen());
            samples = 0;
            allocAtWindowStart = allocatedBytes();
        }
    }

    private static void report(Screen screen) {
        long[] sorted = Arrays.copyOf(NANOS, samples);
        Arrays.sort(sorted);
        long total = 0;
        for (long n : sorted) {
            total += n;
        }
        double mean = total / (double) samples / 1_000_000.0;
        double p50 = sorted[samples / 2] / 1_000_000.0;
        double p95 = sorted[(int) (samples * 0.95)] / 1_000_000.0;
        double max = sorted[samples - 1] / 1_000_000.0;

        long allocNow = allocatedBytes();
        double allocKb = (ALLOC_BEAN == null || allocAtWindowStart < 0)
            ? -1.0
            : (allocNow - allocAtWindowStart) / 1024.0 / samples;

        // One grep-able line per window; qa/scripts parses exactly this shape.
        Hearthstead.LOGGER.info(String.format(java.util.Locale.ROOT,
            "[uiprofile] screen=%s frames=%d mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f "
                + "max_ms=%.3f alloc_kb_per_frame=%.2f fps=%d scale=%.0f size=%dx%d",
            currentScreen, samples, mean, p50, p95, max, allocKb,
            Minecraft.getInstance().getFps(),
            Minecraft.getInstance().getWindow().getGuiScale(),
            screen.width, screen.height));
    }

    private UiProfiler() {
    }
}
