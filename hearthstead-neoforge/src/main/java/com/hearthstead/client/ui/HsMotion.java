package com.hearthstead.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * Tiny, time-based motion helpers for the original Hearthstead UI.
 *
 * <p>Every helper is driven by {@link Util#getMillis()} so it is frame-rate
 * independent, and every helper settles on the exact resting value; callers
 * skip all extra drawing once settled so the resting frame is unchanged.
 * {@link #enabled} switches all motion off (values snap to their targets).</p>
 */
public final class HsMotion {

    /** Global switch; false snaps every animation to its resting value. */
    public static volatile boolean enabled = true;

    private HsMotion() {
    }

    public static float easeOutCubic(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        float inv = 1.0F - c;
        return 1.0F - inv * inv * inv;
    }

    static long now() {
        return Util.getMillis();
    }

    /** True while the primary mouse button is physically held down. */
    public static boolean mouseHeld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
            GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /**
     * Runs {@code body} with a translucent shader colour. Batched fills and
     * text are flushed on both sides so the alpha actually applies to them.
     */
    public static void withAlpha(GuiGraphics g, float alpha, Runnable body) {
        if (alpha >= 0.999F) {
            body.run();
            return;
        }
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, Math.max(0.0F, alpha));
        try {
            body.run();
            g.flush();
        } finally {
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Draws a two-state face (idle/hover) blended by {@code p}. */
    public interface Face {
        void draw(boolean hovered);
    }

    /**
     * p == 0 draws only the idle face, p == 1 only the hover face (identical to
     * the original snapping draw); in between the hover face is laid over the
     * idle face at alpha p.
     */
    public static void blendHover(GuiGraphics g, float p, Face face) {
        if (p <= 0.001F) {
            face.draw(false);
        } else if (p >= 0.999F) {
            face.draw(true);
        } else {
            face.draw(false);
            withAlpha(g, p, () -> face.draw(true));
        }
    }

    private static final java.util.Map<Long, SmoothBar> BARS = new java.util.HashMap<>();

    /**
     * Smoothed ratio for a bar identified by its on-screen rectangle. A new
     * rectangle shows its first value instantly; the table is cleared on every
     * first-open intro so values never carry over between screen sessions.
     */
    public static float smoothBar(int x, int y, int w, int h, float target) {
        if (!enabled) return target;
        long key = ((long) (x & 0xFFFF) << 48) | ((long) (y & 0xFFFF) << 32)
            | ((long) (w & 0xFFFF) << 16) | (h & 0xFFFF);
        if (BARS.size() > 512) BARS.clear();
        return BARS.computeIfAbsent(key, k -> new SmoothBar()).update(target);
    }

    public static void resetBars() {
        BARS.clear();
    }

    /** One-shot 0 to 1 progress, used for a screen's first-open intro. */
    public static final class ScreenIntro {
        private final long start;
        private final long duration;

        public ScreenIntro() {
            this(140L);
        }

        public ScreenIntro(long durationMs) {
            this.start = now();
            this.duration = Math.max(1L, durationMs);
            resetBars();
        }

        public float progress() {
            if (!enabled) return 1.0F;
            return easeOutCubic((now() - start) / (float) duration);
        }

        public boolean done() {
            return progress() >= 1.0F;
        }

        /**
         * Renders {@code body} slid {@code (1-p)*slidePx} px down and faded in.
         * Once settled the body runs with no transform, flush or colour change.
         */
        public void render(GuiGraphics g, float slidePx, Runnable body) {
            float p = progress();
            if (p >= 1.0F) {
                body.run();
                return;
            }
            g.pose().pushPose();
            try {
                g.pose().translate(0.0F, (1.0F - p) * slidePx, 0.0F);
                withAlpha(g, p, body);
            } finally {
                g.pose().popPose();
            }
        }
    }

    /** Boolean-driven 0 to 1 tween (hover), eased out, ~120 ms, continuous on reversal. */
    public static final class Tween {
        private final long duration;
        private float from;
        private float to;
        private long start = -1L;

        public Tween() {
            this(120L);
        }

        public Tween(long durationMs) {
            this.duration = Math.max(1L, durationMs);
        }

        public float update(boolean target) {
            long t = now();
            float goal = target ? 1.0F : 0.0F;
            if (!enabled) {
                from = to = goal;
                start = t;
                return goal;
            }
            if (start < 0L) {
                from = 0.0F;
                to = 0.0F;
                start = t;
            }
            if (goal != to) {
                from = current(t);
                to = goal;
                start = t;
            }
            return current(t);
        }

        private float current(long t) {
            float p = easeOutCubic((t - start) / (float) duration);
            if (p >= 1.0F) return to;
            return from + (to - from) * p;
        }
    }

    /** Integer counter that rolls to new values; the first value is instant. */
    public static final class CountUp {
        private final long duration;
        private boolean seeded;
        private int from;
        private int to;
        private long start;

        public CountUp() {
            this(250L);
        }

        public CountUp(long durationMs) {
            this.duration = Math.max(1L, durationMs);
        }

        public int update(int target) {
            long t = now();
            if (!seeded || !enabled) {
                seeded = true;
                from = to = target;
                start = t;
                return target;
            }
            if (target != to) {
                from = current(t);
                to = target;
                start = t;
            }
            return current(t);
        }

        private int current(long t) {
            float p = easeOutCubic((t - start) / (float) duration);
            if (p >= 1.0F) return to;
            return Math.round(from + (to - from) * p);
        }
    }

    /** Smoothed ratio for progress bars; the first value is instant. */
    public static final class SmoothBar {
        private final long duration;
        private boolean seeded;
        private float from;
        private float to;
        private long start;

        public SmoothBar() {
            this(180L);
        }

        public SmoothBar(long durationMs) {
            this.duration = Math.max(1L, durationMs);
        }

        public float update(float target) {
            long t = now();
            if (!seeded || !enabled) {
                seeded = true;
                from = to = target;
                start = t;
                return target;
            }
            if (Float.compare(target, to) != 0) {
                from = current(t);
                to = target;
                start = t;
            }
            return current(t);
        }

        private float current(long t) {
            float p = easeOutCubic((t - start) / (float) duration);
            if (p >= 1.0F) return to;
            return from + (to - from) * p;
        }
    }
}
