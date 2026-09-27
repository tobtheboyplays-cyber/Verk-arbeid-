package com.hearthstead.settlement.work;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ai.WorkSoundSync;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * TRADER lane: the timing contract of one deal, shared by the server
 * (TraderWorkGoal: sale commit, sounds, goods display) and the authored clip
 * {@code animation.settler.trader_deal} (TraderMotionAnimations.TRADER_DEAL,
 * 10.0 s, one-shot). Tick 0 is the tick the Trader enters
 * {@code SettlerActivity.TRADING}; the client starts the clip on that same
 * synced activity change, and WorkSoundSync delivers every sound in the same
 * network flush, so each accent lands on its frame.
 *
 * <pre>
 *  0.0-1.2 s  greet: a small bow, open hand
 *  1.2-2.6 s  show the goods laid out on the counter
 *  2.6-4.2 s  haggle: a "no" head shake, a counter-offer finger, then a nod
 *  4.2-6.2 s  count the coins into the purse (tally at 4.60 / 5.05 / 5.50 s)
 *  6.5 s      handshake / goods over the counter = THE SALE (commit tick 130)
 *  7.0-9.6 s  write it into the ledger (tally at 7.80 / 8.40 / 9.00 s)
 *  9.6-10 s   close the book, settle
 * </pre>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TraderDealScene {
    /** Whole scene, ticks (= clip length 10.0 s). */
    public static final int DEAL_TICKS = 200;
    /** Handshake / handover beat: TraderSaleService.execute runs on this tick (6.5 s). */
    public static final int COMMIT_TICK = 130;
    /** Coins counted into the purse (ledger tally). */
    public static final int[] COUNT_TICKS = {92, 101, 110};
    /** Pen strokes in the ledger after the sale (ledger tally). */
    public static final int[] WRITE_TICKS = {156, 168, 180};
    /** The goods leave the counter with the merchant on the handover. */
    public static final int GOODS_OFF_TICK = COMMIT_TICK;

    /** Scoreboard tag of the counter goods display (never an item, never saved across a restart). */
    public static final String DISPLAY_TAG = "hearthstead_trader_goods";
    private static final String DISPLAY_OWNER = "HearthsteadTraderGoodsOwner";

    private TraderDealScene() {
    }

    /** True on the scene ticks that carry a ledger-tally accent. */
    public static boolean tallyAt(int clock) {
        for (int t : COUNT_TICKS) {
            if (t == clock) return true;
        }
        for (int t : WRITE_TICKS) {
            if (t == clock) return true;
        }
        return false;
    }

    /** Plays the scene's accent for this clock tick, if it has one. */
    public static void sound(ServerLevel level, Vec3 at, int clock) {
        if (tallyAt(clock)) {
            // Counting and writing: the same ledger voice, the pen a touch lower.
            boolean writing = clock >= WRITE_TICKS[0];
            WorkSoundSync.play(level, at.x, at.y + 1.0, at.z, ModSounds.WORK_LEDGER_TALLY.get(),
                0.5F, writing ? 0.92F : 1.06F);
        } else if (clock == 2) {
            WorkSoundSync.play(level, at.x, at.y + 1.0, at.z, ModSounds.BAG_UP.get(), 0.25F, 1.2F);
        }
    }

    // ------------------------------------------------------------------ goods display

    /** Lays one of the goods on the counter top (display only). Returns the display's UUID or null. */
    @Nullable
    public static UUID showGoods(ServerLevel level, TraderCounter.Spot spot, ItemStack goods, Entity owner) {
        clearGoods(level, spot.counter(), owner.getUUID());
        if (goods.isEmpty()) {
            return null;
        }
        Vec3 top = spot.counterTop();
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item_display");
        tag.put("item", goods.copyWithCount(1).save(level.registryAccess()));
        tag.putString("item_display", "fixed");
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floats(0.0F, 0.02F, 0.0F));
        // lie flat on the counter, turned a little toward the merchant
        transformation.put("left_rotation", floats(-0.7071068F, 0.0F, 0.0F, 0.7071068F));
        transformation.put("right_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("scale", floats(0.45F, 0.45F, 0.45F));
        tag.put("transformation", transformation);
        Entity made = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(top.x, top.y, top.z, owner.getYRot(), 0.0F);
            return e;
        });
        if (!(made instanceof Display.ItemDisplay display)) {
            return null;
        }
        display.addTag(DISPLAY_TAG);
        display.getPersistentData().putUUID(DISPLAY_OWNER, owner.getUUID());
        return level.addFreshEntity(display) ? display.getUUID() : null;
    }

    /** Removes every goods display this Trader left at this counter. */
    public static void clearGoods(ServerLevel level, BlockPos counter, UUID owner) {
        if (!level.hasChunkAt(counter)) {
            return;
        }
        for (Display.ItemDisplay display : level.getEntitiesOfClass(Display.ItemDisplay.class,
            new AABB(counter).inflate(1.5), d -> d.getTags().contains(DISPLAY_TAG))) {
            CompoundTag data = display.getPersistentData();
            if (!data.hasUUID(DISPLAY_OWNER) || owner.equals(data.getUUID(DISPLAY_OWNER))) {
                display.discard();
            }
        }
    }

    /** A goods display never survives a save/load: it is only drawn during a live deal. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity() instanceof Display.ItemDisplay display
            && display.getTags().contains(DISPLAY_TAG)) {
            event.setCanceled(true);
        }
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }
}
