package com.hearthstead.client.motion;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Migration path: every hand-written Java {@link AnimationDefinition} becomes
 * a {@link MotionClip} with identical numbers, so the new runtime plays every
 * existing clip exactly as vanilla {@code KeyframeAnimations} did (proven by
 * {@code LegacyClipBridgeTest}, which samples every clip against vanilla).
 *
 * <p>Clips are keyed by {@code <rig>/<lower-case constant name>}, e.g.
 * {@code settler/chop}. A bedrock JSON animation named
 * {@code animation.settler.chop} overrides exactly that key; anything with no
 * JSON override keeps playing its converted legacy data.
 */
public final class LegacyClipBridge {
    private static final Map<AnimationDefinition, MotionClip> CLIPS = new IdentityHashMap<>();
    private static final Map<String, AnimationDefinition> BY_KEY = new LinkedHashMap<>();
    private static final Map<AnimationDefinition, String> KEY_OF = new IdentityHashMap<>();
    private static boolean indexed;

    private LegacyClipBridge() {
    }

    /** Registers every public static AnimationDefinition constant of a holder class. */
    public static synchronized void index(String rig, Class<?> holder) {
        for (Field field : holder.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || field.getType() != AnimationDefinition.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                AnimationDefinition def = (AnimationDefinition) field.get(null);
                if (def == null || KEY_OF.containsKey(def)) {
                    continue;
                }
                String key = rig + "/" + field.getName().toLowerCase(Locale.ROOT);
                KEY_OF.put(def, key);
                BY_KEY.put(key, def);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // A constant that cannot be read simply stays on the vanilla path.
            }
        }
    }

    private static void ensureIndexed() {
        if (!indexed) {
            indexed = true;
            index("settler", com.hearthstead.client.model.SettlerAnimations.class);
            index("raider", com.hearthstead.client.model.RaiderAnimations.class);
            index("settler", com.hearthstead.client.model.GuardMovesetAnimations.class);
            index("raider", com.hearthstead.client.model.RaiderMovesetAnimations.class);
            index("settler", com.hearthstead.client.model.CraftMotionAnimations.class);
            index("settler", com.hearthstead.client.model.TavernMotionAnimations.class);
            index("settler", com.hearthstead.client.model.RoleMotionAnimations.class);
            index("settler", com.hearthstead.client.model.CaptainMotionAnimations.class);
            index("settler", com.hearthstead.client.model.GuardGreetingAnimations.class);
            index("settler", com.hearthstead.client.model.TradeMotionAnimations.class);
            index("settler", com.hearthstead.client.model.ArcherMotionAnimations.class);
            index("settler", com.hearthstead.client.model.HunterMotionAnimations.class);
            index("settler", com.hearthstead.client.model.GuardDrillAnimations.class);
            index("settler", com.hearthstead.client.model.TraderMotionAnimations.class);
            index("goblin", com.hearthstead.client.model.GoblinAnimations.class);
        }
    }

    /** {@code settler/chop} for SettlerAnimations.CHOP, or null for an unregistered definition. */
    public static synchronized String keyOf(AnimationDefinition def) {
        ensureIndexed();
        return KEY_OF.get(def);
    }

    public static synchronized Map<String, AnimationDefinition> allKeys() {
        ensureIndexed();
        return new LinkedHashMap<>(BY_KEY);
    }

    /** Converted clip, cached for the lifetime of the definition. */
    public static synchronized MotionClip clipOf(AnimationDefinition def) {
        MotionClip clip = CLIPS.get(def);
        if (clip == null) {
            ensureIndexed();
            String key = KEY_OF.get(def);
            clip = convert(key == null ? "anonymous" : key, def);
            CLIPS.put(def, clip);
        }
        return clip;
    }

    public static MotionClip convert(String key, AnimationDefinition def) {
        List<MotionClip.Track> tracks = new ArrayList<>();
        for (Map.Entry<String, List<AnimationChannel>> bone : def.boneAnimations().entrySet()) {
            for (AnimationChannel channel : bone.getValue()) {
                int target = targetOf(channel.target());
                Keyframe[] keys = channel.keyframes();
                int n = keys.length;
                float[] times = new float[n];
                float[] values = new float[n * 3];
                byte[] modes = new byte[n];
                Easing[] easing = new Easing[n];
                float[] args = new float[n];
                for (int i = 0; i < n; i++) {
                    Keyframe frame = keys[i];
                    times[i] = frame.timestamp();
                    values[i * 3] = frame.target().x();
                    values[i * 3 + 1] = frame.target().y();
                    values[i * 3 + 2] = frame.target().z();
                    modes[i] = frame.interpolation() == AnimationChannel.Interpolations.CATMULLROM
                        ? MotionClip.CATMULLROM : MotionClip.LINEAR;
                    args[i] = Float.NaN;
                }
                tracks.add(new MotionClip.Track(bone.getKey(), target, times, values, values,
                    modes, easing, args));
            }
        }
        return new MotionClip(key, "java", def.lengthInSeconds(), def.looping(),
            tracks.toArray(new MotionClip.Track[0]));
    }

    private static int targetOf(AnimationChannel.Target target) {
        if (target == AnimationChannel.Targets.POSITION) {
            return MotionClip.POSITION;
        }
        if (target == AnimationChannel.Targets.SCALE) {
            return MotionClip.SCALE;
        }
        return MotionClip.ROTATION;
    }
}
