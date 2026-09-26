package com.hearthstead.client.motion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Reads and writes Blockbench / GeckoLib bedrock animation JSON
 * ({@code format_version 1.8.0}).
 *
 * <p><b>Units.</b> The numbers in the file are exactly the numbers a Java
 * clip would pass to {@code KeyframeAnimations.degreeVec / posVec /
 * scaleVec}: rotation in degrees, position in pixels with Y UP, absolute
 * scale. That is the bedrock convention GeckoLib renders (its loader negates
 * rotation X/Y and its renderer mirrors X, which cancels vanilla's own
 * (-1, -1, 1) model flip), so a clip exported from Blockbench plays with the
 * same pose it previewed.
 *
 * <p><b>Keyframes.</b> {@code "t": [x, y, z]}, a bare number (uniform), or
 * {@code {"pre": [..], "post": [..], "lerp_mode": "linear|catmullrom|step",
 * "easing": "easeOutCubic", "easingArgs": [n]}}. The keyframe a segment
 * arrives at owns its interpolation (vanilla and GeckoLib rule). Molang
 * strings that are plain numbers are accepted; anything else reads as 0 and
 * is reported, never thrown.
 */
public final class BedrockClipCodec {

    private BedrockClipCodec() {
    }

    /** Parses every animation in one file; {@code rigHint} names the folder (settler/raider). */
    public static void read(JsonObject file, String rigHint, String sourceName,
                            BiConsumer<String, MotionClip> sink, List<String> warnings) {
        JsonObject animations = file.has("animations") && file.get("animations").isJsonObject()
            ? file.getAsJsonObject("animations") : null;
        if (animations == null) {
            warnings.add(sourceName + ": no \"animations\" object");
            return;
        }
        float fileStride = strideMeta(file);
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            String key = keyFor(entry.getKey(), rigHint);
            try {
                JsonObject animObject = entry.getValue().getAsJsonObject();
                MotionClip clip = readAnimation(key, sourceName, animObject, warnings);
                if (clip != null) {
                    float stride = strideMeta(animObject);
                    stride = Float.isNaN(stride) ? fileStride : stride;
                    if (!Float.isNaN(stride) && stride > 0.05F) {
                        clip.withStrideBlocks(stride);
                    }
                    MotionProp[] props = MotionProp.parse(animObject.get("hearthstead_props"),
                        sourceName + " / " + entry.getKey(), warnings);
                    if (props != null) {
                        clip.withProps(props);
                    }
                    ClipSound[] sounds = ClipSound.parse(animObject.get("hearthstead_sounds"),
                        sourceName + " / " + entry.getKey(), warnings);
                    if (sounds != null) {
                        clip.withSounds(sounds);
                    }
                    ClipParticle[] particles = ClipParticle.parse(animObject.get("hearthstead_particles"),
                        sourceName + " / " + entry.getKey(), warnings);
                    if (particles != null) {
                        clip.withParticles(particles);
                    }
                    sink.accept(key, clip);
                }
            } catch (RuntimeException failure) {
                warnings.add(sourceName + " / " + entry.getKey() + ": " + failure.getMessage());
            }
        }
    }

    /**
     * Optional gait metadata, at file or animation level (unknown keys are
     * ignored by Blockbench): {@code "hearthstead_meta": {"blocks_per_cycle": 1.08}}
     * -- the ground distance one loop of a locomotion clip covers with its
     * feet planted. Without it the runtime derives the stride from the leg arc.
     */
    private static float strideMeta(JsonObject object) {
        if (object.has("hearthstead_meta") && object.get("hearthstead_meta").isJsonObject()) {
            JsonObject meta = object.getAsJsonObject("hearthstead_meta");
            if (meta.has("blocks_per_cycle") && meta.get("blocks_per_cycle").isJsonPrimitive()) {
                try {
                    return meta.get("blocks_per_cycle").getAsFloat();
                } catch (RuntimeException ignored) {
                    return Float.NaN;
                }
            }
        }
        return Float.NaN;
    }

    /** "animation.settler.walk_laden" -> "settler/walk_laden"; the folder names the rig otherwise. */
    public static String keyFor(String animationName, String rigHint) {
        String name = animationName.trim().toLowerCase(Locale.ROOT);
        String[] parts = name.split("\\.");
        if (parts.length >= 3 && parts[0].equals("animation")) {
            StringBuilder rest = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) rest.append('_');
                rest.append(parts[i]);
            }
            return parts[1] + "/" + rest;
        }
        return rigHint + "/" + parts[parts.length - 1];
    }

    private static MotionClip readAnimation(String key, String source, JsonObject anim, List<String> warnings) {
        boolean looping = false;
        if (anim.has("loop")) {
            JsonElement loop = anim.get("loop");
            looping = loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean()
                ? loop.getAsBoolean()
                : "true".equalsIgnoreCase(loop.getAsString()) || "loop".equalsIgnoreCase(loop.getAsString());
        }
        float length = anim.has("animation_length") ? anim.get("animation_length").getAsFloat() : -1.0F;
        List<MotionClip.Track> tracks = new ArrayList<>();
        float lastKey = 0.0F;
        JsonObject bones = anim.has("bones") ? anim.getAsJsonObject("bones") : new JsonObject();
        for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
            if (!bone.getValue().isJsonObject()) {
                continue;
            }
            JsonObject channels = bone.getValue().getAsJsonObject();
            String boneName = bone.getKey();
            for (String channel : new String[] {"rotation", "position", "scale"}) {
                if (!channels.has(channel)) {
                    continue;
                }
                int target = channel.equals("rotation") ? MotionClip.ROTATION
                    : channel.equals("position") ? MotionClip.POSITION : MotionClip.SCALE;
                MotionClip.Track track = readTrack(boneName, target, channels.get(channel),
                    key + "/" + boneName + "." + channel, warnings);
                if (track != null) {
                    tracks.add(track);
                    lastKey = Math.max(lastKey, track.time(track.size() - 1));
                }
            }
        }
        if (length <= 0.0F) {
            length = Math.max(lastKey, 0.05F);
        }
        return new MotionClip(key, source, length, looping, tracks.toArray(new MotionClip.Track[0]));
    }

    private static MotionClip.Track readTrack(String bone, int target, JsonElement element,
                                              String where, List<String> warnings) {
        TreeMap<Float, JsonElement> frames = new TreeMap<>();
        if (element.isJsonObject() && !looksLikeKeyframeObject(element.getAsJsonObject())) {
            for (Map.Entry<String, JsonElement> frame : element.getAsJsonObject().entrySet()) {
                try {
                    frames.put(Float.parseFloat(frame.getKey().trim()), frame.getValue());
                } catch (NumberFormatException bad) {
                    warnings.add(where + ": bad timestamp " + frame.getKey());
                }
            }
        } else {
            frames.put(0.0F, element);
        }
        if (frames.isEmpty()) {
            return null;
        }
        int n = frames.size();
        float[] times = new float[n];
        float[] pre = new float[n * 3];
        float[] post = new float[n * 3];
        byte[] mode = new byte[n];
        Easing[] easing = new Easing[n];
        float[] args = new float[n];
        int i = 0;
        float[] scratch = new float[3];
        for (Map.Entry<Float, JsonElement> frame : frames.entrySet()) {
            times[i] = frame.getKey();
            JsonElement value = frame.getValue();
            mode[i] = MotionClip.LINEAR;
            args[i] = Float.NaN;
            if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                JsonElement postValue = obj.has("post") ? obj.get("post")
                    : obj.has("vector") ? obj.get("vector") : obj.get("pre");
                JsonElement preValue = obj.has("pre") ? obj.get("pre") : postValue;
                vector(preValue, target, scratch, where, warnings);
                System.arraycopy(scratch, 0, pre, i * 3, 3);
                vector(postValue, target, scratch, where, warnings);
                System.arraycopy(scratch, 0, post, i * 3, 3);
                if (obj.has("lerp_mode")) {
                    String lerp = obj.get("lerp_mode").getAsString().toLowerCase(Locale.ROOT);
                    if (lerp.equals("catmullrom")) mode[i] = MotionClip.CATMULLROM;
                    else if (lerp.equals("step")) mode[i] = MotionClip.STEP;
                }
                if (obj.has("easing")) {
                    String name = obj.get("easing").getAsString();
                    Easing curve = Easing.byName(name);
                    if (name.equalsIgnoreCase("catmullrom")) {
                        mode[i] = MotionClip.CATMULLROM;
                    } else if (curve == Easing.STEP) {
                        mode[i] = MotionClip.STEP;
                    } else if (curve != null) {
                        mode[i] = MotionClip.EASED;
                        easing[i] = curve;
                    } else {
                        warnings.add(where + ": unknown easing " + name);
                    }
                    if (obj.has("easingArgs") && obj.get("easingArgs").isJsonArray()
                        && obj.getAsJsonArray("easingArgs").size() > 0) {
                        args[i] = number(obj.getAsJsonArray("easingArgs").get(0), where, warnings);
                    }
                }
            } else {
                vector(value, target, scratch, where, warnings);
                System.arraycopy(scratch, 0, pre, i * 3, 3);
                System.arraycopy(scratch, 0, post, i * 3, 3);
            }
            i++;
        }
        return new MotionClip.Track(bone, target, times, pre, post, mode, easing, args);
    }

    private static boolean looksLikeKeyframeObject(JsonObject obj) {
        return obj.has("pre") || obj.has("post") || obj.has("vector");
    }

    /** File value -> Java additive offset (radians, y-down pixels, scale - 1). */
    private static void vector(JsonElement element, int target, float[] out, String where, List<String> warnings) {
        float x, y, z;
        if (element == null) {
            x = y = z = target == MotionClip.SCALE ? 1.0F : 0.0F;
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            x = array.size() > 0 ? number(array.get(0), where, warnings) : 0.0F;
            y = array.size() > 1 ? number(array.get(1), where, warnings) : 0.0F;
            z = array.size() > 2 ? number(array.get(2), where, warnings) : 0.0F;
        } else {
            x = y = z = number(element, where, warnings);
        }
        switch (target) {
            case MotionClip.ROTATION -> {
                out[0] = x * ((float) Math.PI / 180.0F);
                out[1] = y * ((float) Math.PI / 180.0F);
                out[2] = z * ((float) Math.PI / 180.0F);
            }
            case MotionClip.POSITION -> {
                out[0] = x;
                out[1] = -y;
                out[2] = z;
            }
            default -> {
                out[0] = x - 1.0F;
                out[1] = y - 1.0F;
                out[2] = z - 1.0F;
            }
        }
    }

    private static float number(JsonElement element, String where, List<String> warnings) {
        if (element == null || element.isJsonNull()) {
            return 0.0F;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return primitive.getAsFloat();
            }
            try {
                return Float.parseFloat(primitive.getAsString().trim());
            } catch (NumberFormatException molang) {
                warnings.add(where + ": molang not supported, read as 0: " + primitive.getAsString());
            }
        }
        return 0.0F;
    }

    // ------------------------------------------------------------ writing ---

    /** One clip as a complete bedrock animation file. */
    public static JsonObject write(String animationName, MotionClip clip) {
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.8.0");
        JsonObject animations = new JsonObject();
        animations.add(animationName, writeAnimation(clip));
        file.add("animations", animations);
        return file;
    }

    public static JsonObject writeAnimation(MotionClip clip) {
        JsonObject anim = new JsonObject();
        if (clip.looping()) {
            anim.addProperty("loop", true);
        }
        anim.addProperty("animation_length", round(clip.length()));
        if (!Float.isNaN(clip.strideBlocks())) {
            JsonObject meta = new JsonObject();
            meta.addProperty("blocks_per_cycle", round(clip.strideBlocks()));
            anim.add("hearthstead_meta", meta);
        }
        JsonObject bones = new JsonObject();
        for (MotionClip.Track track : clip.tracks()) {
            JsonObject bone = bones.has(track.bone()) ? bones.getAsJsonObject(track.bone()) : new JsonObject();
            String channel = track.target() == MotionClip.ROTATION ? "rotation"
                : track.target() == MotionClip.POSITION ? "position" : "scale";
            JsonObject frames = new JsonObject();
            for (int i = 0; i < track.size(); i++) {
                JsonArray pre = fileVector(track, i, true);
                JsonArray post = fileVector(track, i, false);
                byte mode = track.mode(i);
                JsonElement value;
                if (mode == MotionClip.LINEAR && pre.equals(post)) {
                    value = post;
                } else {
                    JsonObject obj = new JsonObject();
                    if (!pre.equals(post)) {
                        obj.add("pre", pre);
                    }
                    obj.add("post", post);
                    if (mode == MotionClip.CATMULLROM) {
                        obj.addProperty("lerp_mode", "catmullrom");
                    } else if (mode == MotionClip.STEP) {
                        obj.addProperty("lerp_mode", "step");
                    } else if (mode == MotionClip.EASED && track.easing(i) != null) {
                        obj.addProperty("easing", track.easing(i).jsonName());
                        if (!Float.isNaN(track.easingArg(i))) {
                            JsonArray args = new JsonArray();
                            args.add(round(track.easingArg(i)));
                            obj.add("easingArgs", args);
                        }
                    }
                    value = obj;
                }
                frames.add(formatTime(track.time(i)), value);
            }
            bone.add(channel, frames);
            bones.add(track.bone(), bone);
        }
        anim.add("bones", bones);
        return anim;
    }

    private static JsonArray fileVector(MotionClip.Track track, int i, boolean pre) {
        float x = pre ? track.pre(i, 0) : track.post(i, 0);
        float y = pre ? track.pre(i, 1) : track.post(i, 1);
        float z = pre ? track.pre(i, 2) : track.post(i, 2);
        JsonArray array = new JsonArray();
        switch (track.target()) {
            case MotionClip.ROTATION -> {
                array.add(round(x * 180.0F / (float) Math.PI));
                array.add(round(y * 180.0F / (float) Math.PI));
                array.add(round(z * 180.0F / (float) Math.PI));
            }
            case MotionClip.POSITION -> {
                array.add(round(x));
                array.add(round(-y));
                array.add(round(z));
            }
            default -> {
                array.add(round(x + 1.0F));
                array.add(round(y + 1.0F));
                array.add(round(z + 1.0F));
            }
        }
        return array;
    }

    private static String formatTime(float seconds) {
        String s = String.format(Locale.ROOT, "%.4f", seconds);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    private static double round(float value) {
        double rounded = Math.round(value * 10000.0) / 10000.0;
        return rounded == -0.0 ? 0.0 : rounded;
    }
}
