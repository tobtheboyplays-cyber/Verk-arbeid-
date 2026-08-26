package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

public enum SettlerActivity {
    IDLE("idle"),
    WORK_FARM("work_farm"),
    WORK_CHOP("work_chop"),
    EATING("eating"),
    RESTING("resting"),
    PATROLLING("patrolling"),
    COMBAT("combat"),
    FLEEING("fleeing"),
    TRAVELING("traveling"),
    CELEBRATING("celebrating"),
    // Appended for SLICE ANIM-1 -- ordinals of the values above must never
    // shift, this is the wire format (SettlerEntity.DATA_ACTIVITY).
    WORK_PLANT("work_plant"),
    WORK_HARVEST("work_harvest"),
    WORK_WATER("work_water"),
    WORK_LIMB("work_limb"),
    HAULING_LOG("hauling_log"),
    SLEEPING("sleeping"),
    // Appended for SLICE A2a (catalogue §0.7) -- same wire-format rule.
    CARRYING("carrying"),
    SORTING("sorting"),
    // Appended for SLICE CHAINS-1 -- same wire-format rule: never reorder.
    //
    // These are keyed to the MOTION, not the job title (D-015). A butcher and
    // a tanner both cleave; a smith and a mason both strike. Eleven trades map
    // onto six real actions, and none of them is a generic work loop.
    WORK_KNEAD("work_knead"),
    WORK_CLEAVE("work_cleave"),
    WORK_STOKE("work_stoke"),
    WORK_HAMMER("work_hammer"),
    WORK_SAW("work_saw"),
    WORK_WEAVE("work_weave"),
    // D-016 signature motions: the one thing each trade does that nobody
    // else does. Same wire-format rule -- append only.
    GATHERING_LOG("gathering_log"),
    WORK_OVEN("work_oven"),
    WORK_SOW("work_sow"),
    WORK_MINE("work_mine"),
    // D-016 completion (VISUAL-2): the last five trades that still shared a
    // neighbour's motion get their own. Same wire-format rule -- append only.
    WORK_STIR("work_stir"),
    WORK_PLANE("work_plane"),
    WORK_CHISEL("work_chisel"),
    WORK_FLETCH("work_fletch"),
    WORK_SCRAPE("work_scrape"),
    // TRADES-1 (SURVIVAL_AUDIT F1): the three Ring-1 gathering trades this
    // roster was missing (herder/fisher/hunter). Same wire-format rule --
    // append only, never reorder. Three different verbs, three different
    // clips: shearing is not fishing is not loosing a shot.
    WORK_SHEAR("work_shear"),
    WORK_FISH("work_fish"),
    WORK_HUNT("work_hunt");

    public static final SettlerActivity[] BY_ID = values();

    private final String key;

    SettlerActivity(String key) {
        this.key = key;
    }

    public byte id() {
        return (byte) ordinal();
    }

    public String key() {
        return key;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.activity." + key);
    }

    public static SettlerActivity byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : IDLE;
    }
}
