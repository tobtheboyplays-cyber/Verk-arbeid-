package com.hearthstead.settlement.builder;

/**
 * The order a Builder works a site in. Declaration order IS build order and
 * the ordinal is persisted in a job's step list, so append only.
 */
public enum BuildPhase {
    /** Natural terrain, plants and fluids out of the way, top-down. */
    CLEAR("clear"),
    /** Soil/cobble under the foundation where the ground falls away. */
    FILL("fill"),
    /** Everything at or below the blueprint's ground level. */
    FOUNDATION("foundation"),
    /** Walls, floors, frames, stairs between floors: bottom-up. */
    STRUCTURE("structure"),
    /** Everything above the eave line: bottom-up, so a roof closes last. */
    ROOF("roof"),
    /** Fragile and attached things, furniture: doors, glass, lights, beds. */
    INTERIOR("interior"),
    /** Redstone: wire, repeaters, levers, plates -- after what they sit on. */
    REDSTONE("redstone"),
    /** The plaque, and taking temporary scaffolding back down. */
    FINISH("finish"),
    /** A dismantle job's removals (reverse of a build, top-down). */
    DISMANTLE("dismantle");

    private final String key;

    BuildPhase(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static BuildPhase byOrdinal(int ordinal) {
        BuildPhase[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : STRUCTURE;
    }

    /** Phases that remove blocks rather than place them. */
    public boolean removes() {
        return this == CLEAR || this == DISMANTLE;
    }
}
