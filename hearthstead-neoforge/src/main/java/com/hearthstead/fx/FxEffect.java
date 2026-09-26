package com.hearthstead.fx;

import java.util.Locale;

/**
 * Every Bannerhold particle moment (particles &amp; juice lane, 26 Sep).
 *
 * <p>Two kinds:
 * <ul>
 *   <li><b>Networked</b> moments start on the server (a skill level-up, a
 *       learned tech node, a finished building...). The server sends ONE
 *       {@code FxPayload} (effect id + position) to nearby players that
 *       negotiated the channel; the client draws every particle itself.</li>
 *   <li><b>Client</b> moments are derived on the client from state it
 *       already has (night at the Banner, a fisher's cast, a summoned
 *       soldier arriving) and never touch the network.</li>
 * </ul>
 *
 * <p><b>Wire format:</b> {@link #wireId} travels in the payload. Ids are
 * explicit and never reused; append new moments with a new id.
 *
 * <p><b>Sound, in one place:</b> each moment has ONE sound slot. The client
 * plays {@code hearthstead:fx.<key>} when the resource pack defines it in
 * sounds.json (the premium sound pass drops files in with no Java edit),
 * otherwise the vanilla {@link #fallbackSound} at {@link #volume} and
 * {@link #pitch}. An empty fallback means the moment is silent. This class
 * holds plain strings only, so it is safe on a dedicated server and in unit
 * tests.
 */
public enum FxEffect {
    // ---- progress (networked)
    SKILL_LEVEL_UP(1, true, 32, "block.note_block.chime", 0.45F, 1.6F),
    TECH_LEARNED(2, true, 48, "block.note_block.bell", 0.8F, 0.9F),
    BUILDING_LEVEL_UP(3, true, 48, "block.note_block.chime", 0.7F, 1.0F),
    WAREHOUSE_LEVEL_UP(4, true, 48, "block.note_block.chime", 0.8F, 0.8F),
    JOURNEY_CHAPTER(5, true, 48, "ui.toast.challenge_complete", 0.35F, 1.1F),
    // ---- rewards (networked)
    CRAFT_GLINT(6, true, 24, "block.amethyst_block.chime", 0.7F, 1.5F),
    CRAFT_LEGENDARY(7, true, 40, "block.amethyst_block.resonate", 1.0F, 1.2F),
    COIN_SALE(8, true, 24, "item.armor.equip_gold", 0.6F, 1.5F),
    BUILD_DONE(9, true, 48, "entity.player.levelup", 0.5F, 0.85F),
    RAID_WON(10, true, 64, "entity.firework_rocket.twinkle_far", 0.9F, 1.0F),
    SETTLER_WELCOME(11, true, 32, "", 0.0F, 1.0F),
    // ---- hero Captain (networked; CaptainFx plays the Captain's own sounds, so these are silent)
    CAPTAIN_PROMOTED(12, true, 48, "", 0.0F, 1.0F),
    CAPTAIN_WINDUP(13, true, 40, "", 0.0F, 1.0F),
    CAPTAIN_IMPACT(14, true, 48, "", 0.0F, 1.0F),
    // ---- feedback (client only)
    SUMMON_ARRIVAL(20, false, 32, "item.armor.equip_chain", 0.5F, 1.2F),
    PATROL_WAYPOINT(21, false, 24, "block.amethyst_block.hit", 0.5F, 1.4F),
    ORDER_CONFIRMED(22, false, 32, "", 0.0F, 1.0F),
    // 23 retired: the "!" shimmer (conversation lane's glow badge replaced it). Never reuse.
    // ---- atmosphere (client only, silent: existing ambience owns the sound)
    BANNER_EMBER(40, false, 32, "", 0.0F, 1.0F),
    FIREFLY(41, false, 24, "", 0.0F, 1.0F),
    CANDLE_MOTE(42, false, 16, "", 0.0F, 1.0F),
    WORKSHOP_DUST(43, false, 20, "", 0.0F, 1.0F),
    BREW_STEAM(44, false, 24, "", 0.0F, 1.0F),
    FISH_SPLASH(45, false, 32, "", 0.0F, 1.0F);

    private static final FxEffect[] BY_WIRE = new FxEffect[64];

    static {
        for (FxEffect effect : values()) {
            if (effect.wireId <= 0 || effect.wireId >= BY_WIRE.length || BY_WIRE[effect.wireId] != null) {
                throw new IllegalStateException("FxEffect wire id clash or out of range: " + effect);
            }
            BY_WIRE[effect.wireId] = effect;
        }
    }

    private final int wireId;
    private final boolean networked;
    private final double range;
    private final String fallbackSound;
    private final float volume;
    private final float pitch;

    FxEffect(int wireId, boolean networked, double range, String fallbackSound, float volume, float pitch) {
        this.wireId = wireId;
        this.networked = networked;
        this.range = range;
        this.fallbackSound = fallbackSound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public int wireId() {
        return wireId;
    }

    /** True for server-started moments carried by one payload. */
    public boolean networked() {
        return networked;
    }

    /** Blocks: the server sends to players this close; the client culls beyond it. */
    public double range() {
        return range;
    }

    /** Lower-case id, e.g. {@code skill_level_up}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The premium sound id the client prefers: {@code hearthstead:fx.<key>}. */
    public String soundId() {
        return "fx." + key();
    }

    /** Vanilla stand-in sound path (minecraft namespace), or "" for silent. */
    public String fallbackSound() {
        return fallbackSound;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    /** The effect for a wire id, or null for an unknown/garbage id. */
    public static FxEffect byWireId(int id) {
        return id > 0 && id < BY_WIRE.length ? BY_WIRE[id] : null;
    }
}
