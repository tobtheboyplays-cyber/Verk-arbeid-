package com.hearthstead.settlement.builder;

import javax.annotation.Nullable;

/**
 * Where a blueprint's ground layer goes (owner, 27 Sep: "every building the
 * Builder made stood one block above the ground ... phase it down so he does
 * not have to find 192 dirt").
 *
 * <p>Every shipped building has a full ground layer at template
 * {@code y = ground_level}: a grass apron, the plank floor and the cobble
 * footing. The ghost used to put that layer on the AIR block above the
 * clicked surface, so the apron was laid as ~100 dirt on top of the grass
 * and the door stood one step up. Now the ground layer goes INTO the
 * terrain surface: grass and dirt already there satisfy the apron (soil is
 * equivalent in {@link MaterialRules#compare}, never replaced, never
 * charged), the floor and footing replace the natural surface, and the door
 * sill is level with the ground outside.
 *
 * <p>Defense-line pieces (palisade and stone wall segments, their gates) and
 * barricades keep standing on the surface: they line up with the drawn
 * lines, which stand on the surface too.
 */
public final class BlueprintGround {

    private BlueprintGround() {
    }

    /** Whether the ground layer is sunk into the terrain surface. */
    public static boolean sinks(BlueprintMeta.Kind kind, @Nullable String segment, @Nullable String buildingType) {
        if (kind == BlueprintMeta.Kind.BARRICADE) {
            return false;
        }
        if (kind == BlueprintMeta.Kind.DEFENSE) {
            // A typed piece (a watchtower, the stone gatehouse) is a building;
            // a bare wall segment or gate matches the drawn lines.
            return segment == null || buildingType != null;
        }
        return true;
    }

    public static boolean sinks(BlueprintMeta meta) {
        return sinks(meta.kind(), meta.segment(), meta.buildingType());
    }

    /**
     * How far below the free cell above the clicked surface the template's
     * {@code y = 0} goes: ground_level (a Mine's shaft, a basin) plus one when
     * the ground layer sinks into the surface. The client ghost subtracts it.
     */
    public static int placementDepth(int groundLevel, boolean sinks) {
        return Math.max(0, groundLevel) + (sinks ? 1 : 0);
    }

    public static int placementDepth(BlueprintMeta meta) {
        return placementDepth(meta.groundLevel(), sinks(meta));
    }

    /**
     * A ground-layer soil cell of a sunk blueprint: on ordinary ground the
     * world already holds it (grass, dirt, podzol, a path), so the catalog
     * does not list it. Farmland is not soil here -- it is laid on purpose.
     */
    public static boolean groundSoil(String blockId, int localY, int groundLevel, boolean sinks) {
        return sinks && localY == Math.max(0, groundLevel) && MaterialRules.isSoil(blockId);
    }
}
