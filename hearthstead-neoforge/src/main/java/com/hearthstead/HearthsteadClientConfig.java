package com.hearthstead;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-player client config ({@code config/hearthstead-client.toml}).
 * Presentation only: nothing here changes gameplay or is sent to a server.
 */
public final class HearthsteadClientConfig {
    /** HUD corner the pickup notices stack from. */
    public enum PickupCorner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

    public static final boolean DEFAULT_PICKUP_ENABLED = true;
    public static final int DEFAULT_PICKUP_MAX_ROWS = 6;
    public static final double DEFAULT_PICKUP_DISPLAY_SECONDS = 4.0D;
    public static final PickupCorner DEFAULT_PICKUP_CORNER = PickupCorner.BOTTOM_LEFT;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue PICKUP_ENABLED;
    public static final ModConfigSpec.IntValue PICKUP_MAX_ROWS;
    public static final ModConfigSpec.DoubleValue PICKUP_DISPLAY_SECONDS;
    public static final ModConfigSpec.EnumValue<PickupCorner> PICKUP_CORNER;
    public static final ModConfigSpec.BooleanValue MOTION_ENGINE;
    public static final ModConfigSpec.BooleanValue MOTION_SECONDARY;
    public static final boolean DEFAULT_COMBAT_CAMERA_SHAKE = true;
    public static final ModConfigSpec.BooleanValue COMBAT_CAMERA_SHAKE;
    public static final ModConfigSpec.BooleanValue ALWAYS_SHOW_ORDER_MARKERS;
    public static final ModConfigSpec.BooleanValue FINISHER_GLOW_PARTICLES;
    public static final ModConfigSpec.BooleanValue FINISHER_FIRST_PERSON;
    public static final ModConfigSpec.BooleanValue CONVERSATION_CAMERA;
    public static final ModConfigSpec.BooleanValue ENCOUNTER_CINEMATICS;
    public static final ModConfigSpec.BooleanValue AMBIENT_BARKS;
    public static final ModConfigSpec.BooleanValue AMBIENT_MOTION;
    public static final ModConfigSpec.BooleanValue AMBIENT_PARTICLES;
    public static final boolean DEFAULT_PARTICLES_ENABLED = true;
    public static final int DEFAULT_PARTICLES_INTENSITY = 1;
    public static final ModConfigSpec.BooleanValue PARTICLES_ENABLED;
    public static final ModConfigSpec.IntValue PARTICLES_INTENSITY;
    public static final double DEFAULT_VOICE_VOLUME = 1.0D;
    public static final ModConfigSpec.DoubleValue VOICE_VOLUME;
    public static final ModConfigSpec.BooleanValue AMBIENCE_BEDS;
    public static final ModConfigSpec.ConfigValue<String> HEALTH_COUNTER;
    public static final ModConfigSpec.BooleanValue BANNERHOLD_MUSIC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Item pickup notices: a short \"+12 Oak Log\" feed in a HUD corner.")
            .push("pickupNotices");
        PICKUP_ENABLED = builder
            .comment("Show a notice whenever items enter your inventory.")
            .define("enabled", DEFAULT_PICKUP_ENABLED);
        PICKUP_MAX_ROWS = builder
            .comment("Most notice rows shown at once; older rows make way for new ones.")
            .defineInRange("maxRows", DEFAULT_PICKUP_MAX_ROWS, 1, 12);
        PICKUP_DISPLAY_SECONDS = builder
            .comment("Seconds a row stays fully visible after its last pickup before fading out.")
            .defineInRange("displaySeconds", DEFAULT_PICKUP_DISPLAY_SECONDS, 1.0D, 30.0D);
        PICKUP_CORNER = builder
            .comment("Screen corner the notices stack from.")
            .defineEnum("position", DEFAULT_PICKUP_CORNER);
        builder.pop();
        builder.comment("Settler motion engine: bending elbows/knees, eased Blockbench clips,",
                "layered blending and subtle procedural secondary motion. Presentation only.")
            .push("motion");
        MOTION_ENGINE = builder
            .comment("Use the motion engine. Off plays the original keyframes exactly as before.")
            .define("enabled", true);
        MOTION_SECONDARY = builder
            .comment("Procedural secondary motion: knee/elbow follow-through, turn lean, bag and tool lag.")
            .define("secondaryMotion", true);
        builder.pop();
        builder.comment("Combat presentation.").push("combat");
        ALWAYS_SHOW_ORDER_MARKERS = builder
            .comment("Always show soldier order icons; otherwise show while commanding, briefly after orders, or when attention is needed.")
            .define("alwaysShowOrderMarkers", false);
        COMBAT_CAMERA_SHAKE = builder
            .comment("Short camera shake when a Brute's club slams the ground near you.",
                "Also scaled by the vanilla Distortion Effects accessibility slider.")
            .define("cameraShake", DEFAULT_COMBAT_CAMERA_SHAKE);
        builder.pop();
        builder.comment("Finishers (executions). The red torso glow itself is always shown:",
                "it is the gameplay cue. Timing and camera are identical for every player.").push("finisher");
        FINISHER_GLOW_PARTICLES = builder
            .comment("Faint red sparks drift off a finishable enemy's glowing torso.")
            .define("glowParticles", true);
        FINISHER_FIRST_PERSON = builder
            .comment("In first person, your held weapon follows the execution move.")
            .define("firstPersonArms", true);
        builder.pop();
        builder.comment("Conversations with visitors and raid captains.").push("conversations");
        CONVERSATION_CAMERA = builder
            .comment("Ease the camera onto the person you talk to (over-the-shoulder framing).")
            .define("cameraFocus", true);
        ENCOUNTER_CINEMATICS = builder
            .comment("Encounter cinematics: a short skippable camera swoop and name card when you walk up",
                "to a visitor or raid captain. Off opens the conversation directly.")
            .define("encounterCinematics", true);
        builder.pop();
        builder.comment("Living village (needs [features] livingVillage on the server).").push("ambient");
        AMBIENT_BARKS = builder
            .comment("Short lines over settlers' heads (greetings, weather, work, a won raid).")
            .define("barks", true);
        AMBIENT_MOTION = builder
            .comment("Small gestures: waves, nods, cheers, shivers, hunching in the rain.")
            .define("gestures", true);
        AMBIENT_PARTICLES = builder
            .comment("Chimney smoke over working smithies and bakeries, butterflies and birdsong by fields.")
            .define("worldLife", true);
        builder.pop();
        builder.comment("Bannerhold particle moments: level-up sparkles, coins, embers at the Banner,",
                "fireflies, anvil sparks, flour and wood chips. Also follows the vanilla Particles option",
                "(Decreased halves them; Minimal keeps only a trace of order/summon feedback).").push("particles");
        PARTICLES_ENABLED = builder
            .comment("Show Bannerhold's own particles. Off turns every one of them off.")
            .define("enabled", DEFAULT_PARTICLES_ENABLED);
        PARTICLES_INTENSITY = builder
            .comment("0 = subtle (fewer, lower budget), 1 = normal, 2 = rich.")
            .defineInRange("intensity", DEFAULT_PARTICLES_INTENSITY, 0, 2);
        builder.pop();
        builder.comment("Bannerhold sound.").push("audio");
        VOICE_VOLUME = builder
            .comment("Volume of the gibberish character voices (conversation babble, emotes, village chatter).",
                "0 = silent, 1 = default.")
            .defineInRange("voiceVolume", DEFAULT_VOICE_VOLUME, 0.0D, 1.0D);
        AMBIENCE_BEDS = builder
            .comment("Quiet looping ambience around your settlement (village murmur, night crickets, rain on roofs,",
                "market bustle, workshop room tones). Follows the vanilla Ambient/Environment slider too.")
            .define("ambienceBeds", true);
        BANNERHOLD_MUSIC = builder
            .comment("Bannerhold's own soundtrack (title theme, village day/night, tavern jig, raid). Plays through",
                "the Music slider, sparsely like vanilla, never over vanilla music. Off = vanilla music only.")
            .define("bannerholdMusic", true);
        builder.pop();
        builder.comment("Heads-up display.").push("hud");
        HEALTH_COUNTER = builder
            .comment("Heart counter at the very top of the screen (just under any boss bar) for the settler,",
                "raider or mob you look at within 16 blocks: a small heart and \"14 / 20\", no box.",
                "It turns gold only while that enemy can be finished. top = on (default), off = never.",
                "An old \"lookAt\" value is read as top.")
            // Arrays.asList, not List.of: a fresh config's correction tests the
            // missing value with null, and List.of(...).contains(null) throws
            // (QA-CLIENT-01, PR5: a brand-new client died loading mods).
            .defineInList("healthCounter", "top", java.util.Arrays.asList("top", "off", "lookAt"));
        builder.pop();
        SPEC = builder.build();
    }

    private HearthsteadClientConfig() {
    }

    public static boolean pickupEnabled() {
        return read(PICKUP_ENABLED, DEFAULT_PICKUP_ENABLED);
    }

    public static int pickupMaxRows() {
        return read(PICKUP_MAX_ROWS, DEFAULT_PICKUP_MAX_ROWS);
    }

    public static long pickupHoldMillis() {
        return Math.round(read(PICKUP_DISPLAY_SECONDS, DEFAULT_PICKUP_DISPLAY_SECONDS) * 1000.0D);
    }

    public static PickupCorner pickupCorner() {
        return read(PICKUP_CORNER, DEFAULT_PICKUP_CORNER);
    }

    public static boolean motionEngine() {
        return read(MOTION_ENGINE, true);
    }

    public static boolean motionSecondary() {
        return read(MOTION_SECONDARY, true);
    }

    /** Safe before the client config has loaded (falls back to the default). */
    public static boolean alwaysShowOrderMarkers() {
        return read(ALWAYS_SHOW_ORDER_MARKERS, false);
    }

    public static boolean combatCameraShake() {
        return read(COMBAT_CAMERA_SHAKE, DEFAULT_COMBAT_CAMERA_SHAKE);
    }

    public static boolean finisherGlowParticles() {
        return read(FINISHER_GLOW_PARTICLES, true);
    }

    public static boolean finisherFirstPerson() {
        return read(FINISHER_FIRST_PERSON, true);
    }

    public static boolean conversationCamera() {
        return read(CONVERSATION_CAMERA, true);
    }

    public static boolean encounterCinematics() {
        return read(ENCOUNTER_CINEMATICS, true);
    }

    /** Multiplier for every babble/emote voice play (0..1). */
    public static float voiceVolume() {
        return (float) (double) read(VOICE_VOLUME, DEFAULT_VOICE_VOLUME);
    }

    public static boolean bannerholdMusic() {
        return read(BANNERHOLD_MUSIC, true);
    }

    public static boolean ambienceBeds() {
        return read(AMBIENCE_BEDS, true);
    }

    public static boolean ambientBarks() {
        return read(AMBIENT_BARKS, true);
    }

    public static boolean ambientMotion() {
        return read(AMBIENT_MOTION, true);
    }

    public static boolean ambientParticles() {
        return read(AMBIENT_PARTICLES, true);
    }

    public static boolean particlesEnabled() {
        return read(PARTICLES_ENABLED, DEFAULT_PARTICLES_ENABLED);
    }

    public static int particlesIntensity() {
        return read(PARTICLES_INTENSITY, DEFAULT_PARTICLES_INTENSITY);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value, T fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }
        try {
            T result = value.get();
            return result == null ? fallback : result;
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
