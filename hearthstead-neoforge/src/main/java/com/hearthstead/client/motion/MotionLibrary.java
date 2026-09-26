package com.hearthstead.client.motion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The clip registry the runtime samples from.
 *
 * <p>Resolution for a Java {@link AnimationDefinition}: when the engine is on
 * and {@code assets/hearthstead/animations/<rig>/*.json} contains an
 * animation named {@code animation.<rig>.<constant>}, that authored clip
 * plays; otherwise the definition's converted legacy clip plays. With the
 * engine off every call short-circuits to vanilla {@code KeyframeAnimations}
 * in the model -- that is the "before" footage and the kill switch.
 *
 * <p>Reloads with every resource reload, so F3+T picks up a clip saved from
 * Blockbench without restarting the game.
 */
public final class MotionLibrary extends SimplePreparableReloadListener<Map<String, MotionClip>> {
    private static final Logger LOG = LoggerFactory.getLogger("hearthstead/motion");
    public static final MotionLibrary INSTANCE = new MotionLibrary();

    private static volatile Map<String, MotionClip> overrides = Collections.emptyMap();
    private static volatile List<String> lastWarnings = Collections.emptyList();
    private static volatile int generation;
    private static volatile JsonObject pendingTuning;

    private MotionLibrary() {
    }

    /** Clip key of a definition (cached through the bridge), or null. */
    public static String keyOf(AnimationDefinition def) {
        return LegacyClipBridge.keyOf(def);
    }

    /** Authored override for a legacy definition, or null. */
    public static MotionClip override(AnimationDefinition def) {
        if (overrides.isEmpty()) {
            return null;
        }
        String key = LegacyClipBridge.keyOf(def);
        return key == null ? null : overrides.get(key);
    }

    public static MotionClip override(String key) {
        return overrides.get(key);
    }

    /** Clip the engine plays for this definition (authored override or converted legacy). */
    public static MotionClip resolve(AnimationDefinition def) {
        // Render-thread cache: one identity lookup per sampled clip per frame.
        java.util.Map<AnimationDefinition, MotionClip> cache = RESOLVED;
        if (resolvedGeneration != generation) {
            cache.clear();
            resolvedGeneration = generation;
        }
        MotionClip clip = cache.get(def);
        if (clip == null) {
            MotionClip authored = override(def);
            clip = authored != null ? authored : LegacyClipBridge.clipOf(def);
            cache.put(def, clip);
        }
        return clip;
    }

    private static final java.util.Map<AnimationDefinition, MotionClip> RESOLVED =
        new java.util.IdentityHashMap<>();
    private static int resolvedGeneration = -1;

    /** Variant separator: {@code animation.settler.idle_lumberer__v2} is variant 2 of idle_lumberer. */
    public static final String VARIANT_MARK = "__v";

    private static volatile Map<String, MotionClip[]> variants = Collections.emptyMap();

    /** The variants (not including the base) of a clip key, in number order, or null. */
    public static MotionClip[] variants(String baseKey) {
        return baseKey == null ? null : variants.get(baseKey);
    }

    /** "settler/idle_lumberer__v2" -> "settler/idle_lumberer"; a base key maps to itself. */
    public static String baseKeyOf(String key) {
        int mark = key.lastIndexOf(VARIANT_MARK);
        if (mark > 0) {
            String tail = key.substring(mark + VARIANT_MARK.length());
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                return key.substring(0, mark);
            }
        }
        return key;
    }

    private static Map<String, MotionClip[]> buildVariants(Map<String, MotionClip> loaded) {
        Map<String, java.util.TreeMap<Integer, MotionClip>> grouped = new HashMap<>();
        for (Map.Entry<String, MotionClip> entry : loaded.entrySet()) {
            String base = baseKeyOf(entry.getKey());
            if (!base.equals(entry.getKey())) {
                int number = Integer.parseInt(entry.getKey().substring(
                    entry.getKey().lastIndexOf(VARIANT_MARK) + VARIANT_MARK.length()));
                grouped.computeIfAbsent(base, k -> new java.util.TreeMap<>()).put(number, entry.getValue());
            }
        }
        Map<String, MotionClip[]> out = new HashMap<>();
        grouped.forEach((base, byNumber) -> out.put(base, byNumber.values().toArray(new MotionClip[0])));
        return Collections.unmodifiableMap(out);
    }

    public static Map<String, MotionClip> overrides() {
        return overrides;
    }

    public static List<String> warnings() {
        return lastWarnings;
    }

    /** Bumped on every reload so cached per-entity data can notice. */
    public static int generation() {
        return generation;
    }

    /** Synchronous reload for {@code /hsmotion reload}; F3+T uses the normal async path. */
    public void reloadNow(ResourceManager manager) {
        apply(prepare(manager, net.minecraft.util.profiling.InactiveProfiler.INSTANCE), manager,
            net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
    }

    @Override
    protected Map<String, MotionClip> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, MotionClip> loaded = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Map<ResourceLocation, Resource> files = manager.listResources("animations",
            location -> location.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            ResourceLocation location = file.getKey();
            if (!Hearthstead.MODID.equals(location.getNamespace())) {
                continue;
            }
            String[] path = location.getPath().split("/");
            String rig = path.length >= 3 ? path[1] : "settler";
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                BedrockClipCodec.read(json, rig, location.toString(), loaded::put, warnings);
            } catch (Exception failure) {
                warnings.add(location + ": " + failure.getMessage());
            }
        }
        JsonObject tuning = null;
        try {
            var resource = manager.getResource(Hearthstead.id("motion/tuning.json"));
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    tuning = JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
        } catch (Exception failure) {
            warnings.add("motion/tuning.json: " + failure.getMessage());
        }
        pendingTuning = tuning;
        // Sidecar props (wins over props inside the clip JSON, survives Blockbench re-export).
        try {
            var resource = manager.getResource(Hearthstead.id("motion/props.json"));
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    JsonObject sidecar = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : sidecar.entrySet()) {
                        MotionClip clip = loaded.get(entry.getKey());
                        if (clip == null) {
                            warnings.add("motion/props.json: no clip " + entry.getKey());
                            continue;
                        }
                        MotionProp[] props = MotionProp.parse(entry.getValue(),
                            "motion/props.json " + entry.getKey(), warnings);
                        if (props != null) {
                            clip.withProps(props);
                        }
                    }
                }
            }
        } catch (Exception failure) {
            warnings.add("motion/props.json: " + failure.getMessage());
        }
        // Sidecar timeline sounds (same shape as hearthstead_sounds; survives Blockbench re-export).
        try {
            var resource = manager.getResource(Hearthstead.id("motion/sounds.json"));
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    JsonObject sidecar = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : sidecar.entrySet()) {
                        MotionClip clip = loaded.get(entry.getKey());
                        if (clip == null) {
                            warnings.add("motion/sounds.json: no clip " + entry.getKey());
                            continue;
                        }
                        ClipSound[] sounds = ClipSound.parse(entry.getValue(),
                            "motion/sounds.json " + entry.getKey(), warnings);
                        if (sounds != null) {
                            clip.withSounds(sounds);
                        }
                    }
                }
            }
        } catch (Exception failure) {
            warnings.add("motion/sounds.json: " + failure.getMessage());
        }
        // Sidecar contact cues (particles + impacts). A base key also covers its
        // __vN variants: variants keep the base's contact times by contract.
        try {
            var resource = manager.getResource(Hearthstead.id("motion/particles.json"));
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    JsonObject sidecar = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : sidecar.entrySet()) {
                        String key = entry.getKey();
                        MotionClip clip = loaded.get(key);
                        if (clip == null) {
                            warnings.add("motion/particles.json: no clip " + key);
                            continue;
                        }
                        ClipParticle[] cues = ClipParticle.parse(entry.getValue(),
                            "motion/particles.json " + key, warnings);
                        if (cues == null) {
                            continue;
                        }
                        clip.withParticles(cues);
                        for (Map.Entry<String, MotionClip> other : loaded.entrySet()) {
                            if (other.getKey().startsWith(key + VARIANT_MARK)
                                && other.getValue().particles() == null) {
                                other.getValue().withParticles(cues);
                            }
                        }
                    }
                }
            }
        } catch (Exception failure) {
            warnings.add("motion/particles.json: " + failure.getMessage());
        }
        lastWarnings = warnings;
        return loaded;
    }

    @Override
    protected void apply(Map<String, MotionClip> loaded, ResourceManager manager, ProfilerFiller profiler) {
        overrides = Collections.unmodifiableMap(new HashMap<>(loaded));
        variants = buildVariants(loaded);
        MotionTuning.load(pendingTuning);
        generation++;
        LOG.info("Loaded {} authored motion clip(s): {}", loaded.size(), loaded.keySet());
        for (String warning : lastWarnings) {
            LOG.warn("motion clip: {}", warning);
        }
    }
}
