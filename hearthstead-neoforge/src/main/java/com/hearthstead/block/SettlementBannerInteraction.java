package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;

/**
 * Hanging new colours on a settlement Banner. Right-clicking the stand, or the
 * cloth above it, with any vanilla banner swaps designs as one exact item
 * exchange: one banner leaves the hand and the banner it replaces (if a player
 * hung one) comes back. The founding colours were never an item, so replacing
 * them returns nothing. Only a player who may build at the Banner can do it.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class SettlementBannerInteraction {
    /** Cloth and pole region above the stand, relative to the block origin. */
    private static final double CLOTH_MIN_Y = 0.75;
    private static final double CLOTH_MAX_Y = 2.6;

    private SettlementBannerInteraction() {
    }

    public static SoundEvent raiseSound() {
        return ModSounds.BANNER_RAISE.get();
    }

    public static SoundEvent flutterSound() {
        return ModSounds.BANNER_FLUTTER.get();
    }

    public static boolean mayChangeColours(ServerPlayer player, BlockPos bannerPos) {
        return player.isAlive() && !player.isSpectator() && player.mayBuild()
            && player.serverLevel().mayInteract(player, bannerPos);
    }

    /** Result of a colour change, for callers and tests. */
    public enum Outcome { NOT_A_BANNER, DENIED, UNCHANGED, ADOPTED }

    /**
     * Performs the exchange for the banner in {@code hand}. Server only.
     * Exactly one banner leaves the hand; whatever flew before is handed back.
     */
    public static Outcome hangColours(ServerPlayer player, InteractionHand hand, HearthBlockEntity banner) {
        ItemStack held = player.getItemInHand(hand);
        if (!SettlementHeraldry.isBanner(held)) {
            return Outcome.NOT_A_BANNER;
        }
        BlockPos pos = banner.getBlockPos();
        if (!mayChangeColours(player, pos)) {
            player.displayClientMessage(
                Component.translatable("hearthstead.settlement_banner.heraldry_denied"), true);
            return Outcome.DENIED;
        }
        if (SettlementHeraldry.sameDesign(banner.getHeraldry(), held)
            && banner.effectiveDesign().equals(com.hearthstead.heraldry.VillageDesign.fromBanner(
                held, banner.effectiveDesign().shape()))) {
            player.displayClientMessage(
                Component.translatable("hearthstead.settlement_banner.heraldry_same"), true);
            return Outcome.UNCHANGED;
        }
        ItemStack incoming = held.split(1);
        ItemStack previous = banner.exchangeHeraldry(incoming);
        if (!previous.isEmpty()) {
            if (player.getItemInHand(hand).isEmpty()) {
                player.setItemInHand(hand, previous);
            } else {
                player.getInventory().placeItemBackInInventory(previous);
            }
        }
        player.containerMenu.broadcastChanges();
        ServerLevel level = player.serverLevel();
        level.playSound(null, pos.above(2), raiseSound(), SoundSource.BLOCKS, 0.9F, 1.05F);
        player.displayClientMessage(Component.translatable(previous.isEmpty()
            ? "hearthstead.settlement_banner.heraldry_adopted_default"
            : "hearthstead.settlement_banner.heraldry_adopted"), true);
        return Outcome.ADOPTED;
    }

    /** The clickable cloth-and-pole column above a Banner stand. */
    public static AABB clothBox(BlockPos pos) {
        return new AABB(pos.getX() + 0.08, pos.getY() + CLOTH_MIN_Y, pos.getZ() + 0.08,
            pos.getX() + 0.92, pos.getY() + CLOTH_MAX_Y, pos.getZ() + 0.92);
    }

    /** A Banner whose cloth the player's view ray meets, and how far along the ray. */
    public record ClothHit(HearthBlockEntity banner, double distance) {
    }

    @Nullable
    public static ClothHit clothUnderCrosshair(Level level, Player player) {
        double reach = player.blockInteractionRange();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        ClothHit best = null;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        BlockPos lastBase = null;
        // Step through the cells the ray crosses; the stand sits up to three
        // cells below the cloth point being looked at.
        for (double t = 0; t <= reach; t += 0.25) {
            Vec3 point = eye.add(look.scale(t));
            for (int dy = 0; dy <= 3; dy++) {
                probe.set(point.x, point.y - dy, point.z);
                if (lastBase != null && lastBase.equals(probe)) {
                    continue;
                }
                if (!level.isLoaded(probe)
                    || !(level.getBlockEntity(probe) instanceof HearthBlockEntity banner)) {
                    continue;
                }
                lastBase = probe.immutable();
                var clip = clothBox(lastBase).clip(eye, end);
                if (clip.isPresent()) {
                    double distance = clip.get().distanceTo(eye);
                    if (best == null || distance < best.distance()) {
                        best = new ClothHit(banner, distance);
                    }
                }
            }
        }
        return best;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!SettlementHeraldry.isBanner(event.getItemStack())) {
            return;
        }
        ClothHit hit = clothUnderCrosshair(event.getLevel(), player);
        if (hit == null) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            hangColours(serverPlayer, event.getHand(), hit.banner());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!SettlementHeraldry.isBanner(event.getItemStack())) {
            return;
        }
        BlockState target = event.getLevel().getBlockState(event.getPos());
        if (target.getBlock() instanceof HearthBlock) {
            return; // the stand itself handles this in useItemOn
        }
        ClothHit hit = clothUnderCrosshair(event.getLevel(), player);
        if (hit == null) {
            return;
        }
        double blockDistance = event.getHitVec().getLocation().distanceTo(player.getEyePosition());
        if (hit.distance() > blockDistance) {
            return; // the clicked block is in front of the cloth
        }
        if (player instanceof ServerPlayer serverPlayer) {
            hangColours(serverPlayer, event.getHand(), hit.banner());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }
}
