package com.hearthstead.settlement.builder;

/**
 * Rotation and mirror of a blueprint footprint, as pure integer math.
 *
 * <p>One transform is shared by the ghost preview and the build job, so what
 * the player confirmed is exactly what gets built. Conventions match vanilla
 * {@code StructureTemplate.transform}: the mirror is applied FIRST (x flip,
 * vanilla {@code Mirror.FRONT_BACK}), then {@code rotation} clockwise
 * quarter-turns seen from above (+x east, +z south; vanilla
 * {@code Rotation.CLOCKWISE_90} maps (x, z) to (-z, x)). The result is
 * translated back so the transformed footprint again starts at (0, 0), which
 * means a transformed position always lies in
 * {@code [0, rotatedSizeX) x [0, rotatedSizeZ)}.
 *
 * <p>No Minecraft types here: the whole table is covered by plain JUnit.
 */
public record BlueprintTransform(int rotation, boolean mirror, int sizeX, int sizeZ) {

    public BlueprintTransform {
        if (sizeX <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("footprint must be positive");
        }
        rotation = Math.floorMod(rotation, 4);
    }

    public static BlueprintTransform identity(int sizeX, int sizeZ) {
        return new BlueprintTransform(0, false, sizeX, sizeZ);
    }

    /** Same footprint, one more clockwise quarter-turn. */
    public BlueprintTransform rotatedClockwise() {
        return new BlueprintTransform(rotation + 1, mirror, sizeX, sizeZ);
    }

    public BlueprintTransform rotatedCounterClockwise() {
        return new BlueprintTransform(rotation + 3, mirror, sizeX, sizeZ);
    }

    public BlueprintTransform toggledMirror() {
        return new BlueprintTransform(rotation, !mirror, sizeX, sizeZ);
    }

    /** Footprint width along world x after the transform. */
    public int rotatedSizeX() {
        return (rotation & 1) == 0 ? sizeX : sizeZ;
    }

    /** Footprint depth along world z after the transform. */
    public int rotatedSizeZ() {
        return (rotation & 1) == 0 ? sizeZ : sizeX;
    }

    /** Transformed x of a template-local (x, z). */
    public int x(int x, int z) {
        int mx = mirror ? sizeX - 1 - x : x;
        return switch (rotation) {
            case 1 -> sizeZ - 1 - z;
            case 2 -> sizeX - 1 - mx;
            case 3 -> z;
            default -> mx;
        };
    }

    /** Transformed z of a template-local (x, z). */
    public int z(int x, int z) {
        int mx = mirror ? sizeX - 1 - x : x;
        return switch (rotation) {
            case 1 -> mx;
            case 2 -> sizeZ - 1 - z;
            case 3 -> sizeX - 1 - mx;
            default -> z;
        };
    }

    /**
     * Inverse: the template-local x of a transformed (tx, tz). Used by the
     * ghost to ask "which template cell is under the cursor".
     */
    public int inverseX(int tx, int tz) {
        int mx = switch (rotation) {
            case 1 -> tz;
            case 2 -> sizeX - 1 - tx;
            case 3 -> sizeX - 1 - tz;
            default -> tx;
        };
        return mirror ? sizeX - 1 - mx : mx;
    }

    public int inverseZ(int tx, int tz) {
        return switch (rotation) {
            case 1 -> sizeZ - 1 - tx;
            case 2 -> sizeZ - 1 - tz;
            case 3 -> tx;
            default -> tz;
        };
    }

    /**
     * A horizontal facing index (0 north, 1 east, 2 south, 3 west -- the
     * clockwise order) after the transform. The mirror flips east/west.
     */
    public int facing(int facing) {
        int f = Math.floorMod(facing, 4);
        if (mirror && (f == 1 || f == 3)) {
            f = f == 1 ? 3 : 1;
        }
        return (f + rotation) & 3;
    }
}
