package com.hearthstead.client.ui2.map;

/**
 * Pan and zoom state for the realm map, in world blocks and GUI pixels.
 *
 * <p>The camera eases toward a target with a short exponential approach
 * (about 90 ms to settle most of the way), so wheel steps, keyboard pans and
 * "center on Banner" glide instead of jumping; a drag moves both current
 * and target together so the map stays glued to the cursor. Zoom levels are
 * pixel-perfect: every level is an integer number of physical pixels per
 * block at the current GUI scale. Pure; time is passed in.
 */
public final class RealmMapCamera {
    /** GUI pixels per block at the largest step ("4x"). */
    public static final float MAX_ZOOM = 4.0F;
    static final float SETTLE_MS = 90.0F;

    private double centerX;
    private double centerZ;
    private double targetX;
    private double targetZ;
    private float zoom = 1.0F;
    private float targetZoom = 1.0F;
    private long lastMs = Long.MIN_VALUE;
    private boolean snap = true;
    private float[] steps = {1.0F, 2.0F, 3.0F, 4.0F};
    private double minX = -Double.MAX_VALUE;
    private double minZ = -Double.MAX_VALUE;
    private double maxX = Double.MAX_VALUE;
    private double maxZ = Double.MAX_VALUE;

    /**
     * Pixel-perfect zoom steps for a GUI scale: every k/guiScale below 1x
     * that still shows at least the whole {@code fitZoom} area (plus the
     * largest one below it), then 1x, 2x, 3x and 4x.
     */
    public static float[] zoomSteps(int guiScale, float fitZoom) {
        int scale = Math.max(1, guiScale);
        float[] tmp = new float[scale + 4];
        int n = 0;
        int firstK = 1;
        for (int k = 1; k < scale; k++) {
            if (k / (float) scale <= fitZoom + 1.0E-4F) firstK = k;
        }
        for (int k = firstK; k < scale; k++) tmp[n++] = k / (float) scale;
        for (int z = 1; z <= (int) MAX_ZOOM; z++) tmp[n++] = z;
        float[] out = new float[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    public void setSteps(float[] zoomSteps) {
        if (zoomSteps == null || zoomSteps.length == 0) return;
        steps = zoomSteps.clone();
        targetZoom = nearestStep(targetZoom);
        if (snap) zoom = targetZoom;
    }

    public float[] steps() {
        return steps;
    }

    /** Pan limits for the camera centre (world blocks). */
    public void setBounds(double minX, double minZ, double maxX, double maxZ) {
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxZ = Math.max(minZ, maxZ);
        targetX = clamp(targetX, this.minX, this.maxX);
        targetZ = clamp(targetZ, this.minZ, this.maxZ);
    }

    /** Places the camera immediately (first frame, or motion disabled). */
    public void jumpTo(double x, double z, float zoomLevel) {
        targetX = centerX = clamp(x, minX, maxX);
        targetZ = centerZ = clamp(z, minZ, maxZ);
        targetZoom = zoom = nearestStep(zoomLevel);
        snap = false;
    }

    public void glideTo(double x, double z) {
        targetX = clamp(x, minX, maxX);
        targetZ = clamp(z, minZ, maxZ);
    }

    /** A drag by screen pixels: current and target move together. */
    public void dragBy(double dxPixels, double dyPixels) {
        double dx = dxPixels / zoom;
        double dz = dyPixels / zoom;
        centerX = clamp(centerX - dx, minX, maxX);
        centerZ = clamp(centerZ - dz, minZ, maxZ);
        targetX = centerX;
        targetZ = centerZ;
    }

    /** Keyboard/eased pan by screen pixels at the target zoom. */
    public void panBy(double dxPixels, double dyPixels) {
        targetX = clamp(targetX + dxPixels / targetZoom, minX, maxX);
        targetZ = clamp(targetZ + dyPixels / targetZoom, minZ, maxZ);
    }

    /**
     * Steps zoom by {@code delta} levels, keeping the world point under
     * ({@code anchorDx}, {@code anchorDy}) -- offsets from the view centre in
     * pixels -- fixed on screen once the glide settles.
     */
    public boolean zoomBy(int delta, double anchorDx, double anchorDy) {
        int index = stepIndex(targetZoom);
        int next = Math.max(0, Math.min(steps.length - 1, index + delta));
        if (next == index) return false;
        float newZoom = steps[next];
        double worldX = targetX + anchorDx / targetZoom;
        double worldZ = targetZ + anchorDy / targetZoom;
        targetZoom = newZoom;
        targetX = clamp(worldX - anchorDx / newZoom, minX, maxX);
        targetZ = clamp(worldZ - anchorDy / newZoom, minZ, maxZ);
        return true;
    }

    public void update(long nowMs, boolean motion) {
        if (lastMs == Long.MIN_VALUE || !motion) {
            centerX = targetX;
            centerZ = targetZ;
            zoom = targetZoom;
            lastMs = nowMs;
            return;
        }
        long dt = Math.max(0L, Math.min(100L, nowMs - lastMs));
        lastMs = nowMs;
        double k = 1.0D - Math.exp(-dt / (double) SETTLE_MS);
        centerX += (targetX - centerX) * k;
        centerZ += (targetZ - centerZ) * k;
        // Zoom eases in log space so each step feels the same size.
        double lz = Math.log(zoom);
        lz += (Math.log(targetZoom) - lz) * k;
        zoom = (float) Math.exp(lz);
        if (Math.abs(targetX - centerX) < 0.01D) centerX = targetX;
        if (Math.abs(targetZ - centerZ) < 0.01D) centerZ = targetZ;
        if (Math.abs(targetZoom - zoom) < 0.002F) zoom = targetZoom;
    }

    public boolean settled() {
        return centerX == targetX && centerZ == targetZ && zoom == targetZoom;
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public double targetX() {
        return targetX;
    }

    public double targetZ() {
        return targetZ;
    }

    public float zoom() {
        return zoom;
    }

    public float targetZoom() {
        return targetZoom;
    }

    public int zoomIndex() {
        return stepIndex(targetZoom);
    }

    /** Screen x (GUI px) of a world x for a view whose centre is at {@code viewCenterX}. */
    public double screenX(double worldX, double viewCenterX) {
        return viewCenterX + (worldX - centerX) * zoom;
    }

    public double screenY(double worldZ, double viewCenterY) {
        return viewCenterY + (worldZ - centerZ) * zoom;
    }

    public double worldX(double screenX, double viewCenterX) {
        return centerX + (screenX - viewCenterX) / zoom;
    }

    public double worldZ(double screenY, double viewCenterY) {
        return centerZ + (screenY - viewCenterY) / zoom;
    }

    private int stepIndex(float value) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            float d = Math.abs(steps[i] - value);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private float nearestStep(float value) {
        return steps[stepIndex(value)];
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
