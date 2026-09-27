package com.hearthstead.settlement.builder;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The one headline a build site shows: what is happening right now, or
 * exactly what is in the way, with numbers or a position. MineColonies'
 * biggest complaint is a builder that idles without saying why; every wait
 * in {@code BuilderWorkGoal} sets one of these.
 *
 * <p>The ordinal is synced (site snapshots), so append only. Lang keys are
 * {@code hearthstead.builder.status.<key>} with the args in order.
 */
public enum BuildStatus {
    /** Queued behind another site, or no Builder employed. */
    QUEUED("queued"),
    /** No Builder hired at a valid Builder's Hut. */
    NO_BUILDER("no_builder"),
    /** args: count, item name, warehouse count, on-the-way count */
    WAITING_FOR("waiting_for"),
    /** Walking to the hut / warehouse to load materials. */
    FETCHING("fetching"),
    /** args: done, total */
    CLEARING("clearing"),
    /** args: done, total */
    FILLING("filling"),
    /** args: layer, layers */
    BUILDING_LAYER("building_layer"),
    /** args: layer, layers */
    ROOFING("roofing"),
    /** Doors, glass, lights, furniture. args: done, total */
    FITTING("fitting"),
    /** args: done, total */
    REDSTONE("redstone"),
    /** Hanging the plaque and fitting its plan. */
    FINISHING("finishing"),
    /** args: x, y, z */
    BLOCKED_PLAYER_BLOCK("blocked_player_block"),
    /** args: count */
    SKIPPED("skipped"),
    /** Paused by a player. */
    PAUSED("paused"),
    /** The raid is on: sheltering. */
    SHELTERING("sheltering"),
    /** Barricades first, a raid is coming. */
    RUSHING("rushing"),
    /** args: done, total */
    DISMANTLING("dismantling"),
    DONE("done"),
    CANCELLED("cancelled"),
    /** Night / rest hours. */
    RESTING("resting"),
    /** args: x, y, z -- the site is in an unloaded chunk or out of range. */
    UNREACHABLE("unreachable"),
    /** The warehouse and hut have none and no Courier can bring it. args: count, item */
    NEEDS_PLAYER("needs_player"),
    /** args: done, total -- hanging a ladder to reach high work. */
    SCAFFOLDING("scaffolding"),
    /** args: left -- taking the ladders back down. */
    UNSCAFFOLDING("unscaffolding"),
    /** args: done, total -- pouring water into a basin or well. */
    POURING("pouring"),
    /** args: count, item -- making it at the hut from the village's stock. */
    CRAFTING("crafting");

    private final String key;

    BuildStatus(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** By saved key; unknown keys read as QUEUED (the site simply re-reports). */
    public static BuildStatus byKey(String key) {
        for (BuildStatus status : values()) {
            if (status.key.equals(key)) {
                return status;
            }
        }
        return QUEUED;
    }

    public static BuildStatus byOrdinal(int ordinal) {
        BuildStatus[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : QUEUED;
    }

    /** The player-facing line. Args are rendered as plain text. */
    public Component describe(List<String> args) {
        Object[] objects = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            // Item ids travel as ids so the client names them in its language.
            if (a.startsWith("item:")) {
                objects[i] = Component.translatable(itemKey(a.substring(5)));
            } else {
                objects[i] = a;
            }
        }
        return Component.translatable("hearthstead.builder.status." + key, objects);
    }

    private static String itemKey(String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl == null) {
            return id;
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl).getDescriptionId();
    }
}
