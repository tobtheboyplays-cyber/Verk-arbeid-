package com.hearthstead.client.motion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * A display-only item a clip (usually a break-time variant) puts in a hand
 * for part of its duration: a flint whetstone, a mug, a waterskin, a crust.
 * It is drawn by the settler renderer only while that clip plays and inside
 * its window; it is never an inventory item and never touches the settler's
 * real equipment.
 *
 * <p>JSON (inside the animation object, or in the sidecar
 * {@code assets/hearthstead/motion/props.json} keyed by clip key):
 * <pre>
 * "hearthstead_props": [
 *   {"hand": "offhand", "item": "minecraft:flint", "from": 1.2, "to": 7.8},
 *   {"hand": "mainhand", "item": "minecraft:bread", "from": 0.5, "to": 3.0, "hide_real": true}
 * ]
 * </pre>
 * {@code hand}: "mainhand" (right) or "offhand" (left). {@code from/to}:
 * seconds of the clip's own local time. {@code hide_real}: hide the real
 * item in that hand during the window (default false; without it the prop is
 * only drawn when that hand is empty).
 */
public final class MotionProp {
    public static final int MAINHAND = 0;
    public static final int OFFHAND = 1;

    private final int hand;
    private final ResourceLocation itemId;
    private final float from;
    private final float to;
    private final boolean hideReal;
    /** Draw the prop even though that hand's real item is shown elsewhere (e.g. the archer's
     *  bow rendered in the LEFT hand while the right hand pulls an arrow from the quiver). */
    private boolean overReal;
    private ItemStack stack;

    public MotionProp(int hand, ResourceLocation itemId, float from, float to, boolean hideReal) {
        this.hand = hand;
        this.itemId = itemId;
        this.from = from;
        this.to = to;
        this.hideReal = hideReal;
    }

    public int hand() { return hand; }
    public float from() { return from; }
    public float to() { return to; }
    public boolean hideReal() { return hideReal; }
    public boolean overReal() { return overReal; }
    public ResourceLocation itemId() { return itemId; }

    public boolean activeAt(float localSeconds) {
        return localSeconds >= from && localSeconds <= to;
    }

    /** Cached display stack (created on first use, on the render thread). */
    public ItemStack stack() {
        if (stack == null) {
            Item item = BuiltInRegistries.ITEM.get(itemId);
            stack = new ItemStack(item == null ? Items.AIR : item);
        }
        return stack;
    }

    static MotionProp[] parse(JsonElement element, String where, List<String> warnings) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        List<MotionProp> out = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject obj = entry.getAsJsonObject();
            try {
                String handName = obj.has("hand") ? obj.get("hand").getAsString() : "offhand";
                int hand = handName.equalsIgnoreCase("mainhand") || handName.equalsIgnoreCase("right")
                    ? MAINHAND : OFFHAND;
                ResourceLocation id = ResourceLocation.parse(obj.get("item").getAsString());
                float from = obj.has("from") ? obj.get("from").getAsFloat() : 0.0F;
                float to = obj.has("to") ? obj.get("to").getAsFloat() : Float.MAX_VALUE;
                boolean hide = obj.has("hide_real") && obj.get("hide_real").getAsBoolean();
                MotionProp prop = new MotionProp(hand, id, from, to, hide);
                prop.overReal = obj.has("over_real") && obj.get("over_real").getAsBoolean();
                out.add(prop);
            } catch (RuntimeException bad) {
                warnings.add(where + ": bad hearthstead_props entry " + obj);
            }
        }
        return out.isEmpty() ? null : out.toArray(new MotionProp[0]);
    }
}
