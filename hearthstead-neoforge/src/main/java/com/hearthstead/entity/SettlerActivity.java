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
    CELEBRATING("celebrating");

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
