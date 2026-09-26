package com.hearthstead.entity.combat.captain;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Server authority for the Captain panel: rename, weapon loadout, cosmetics.
 *
 * <p><b>A loadout is physical.</b> Switching needs the loadout's real weapon
 * items, taken from the asking player's inventory first and then from the
 * settlement's Armoury and Warehouse chests (what the smith and armourer
 * made). Every needed item is FOUND before anything moves; then the old kit
 * goes back to the player (dropped at their feet if their inventory is full)
 * and the new kit goes into his hands, one real item each. Nothing is
 * conjured, nothing is lost. A switch then re-arms for
 * {@link CaptainState#REARM_TICKS} before any special can fire.
 */
public final class CaptainService {
    public static final double REACH = 8.0D;

    private CaptainService() {
    }

    public static void handle(ServerPlayer player, CaptainPayloads.Action action) {
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(action.entityId()) instanceof SettlerEntity settler)
            || !settler.isAlive() || player.distanceToSqr(settler) > REACH * REACH
            || player.isSpectator()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "hearthstead.captain.refused.reach"), true);
            return;
        }
        String feedback = switch (action.kind()) {
            case CaptainPayloads.Action.REFRESH -> "";
            case CaptainPayloads.Action.RENAME -> rename(settler, action.text());
            case CaptainPayloads.Action.LOADOUT -> switchLoadout(player, settler,
                CaptainLoadout.byWireId(action.value()));
            case CaptainPayloads.Action.CAPE -> cosmetic(settler, s -> s.setCapeColour(action.value()));
            case CaptainPayloads.Action.PLUME -> cosmetic(settler, s -> s.setPlume(action.value() != 0));
            case CaptainPayloads.Action.DISMISS_PROMPT -> cosmetic(settler, s -> s.setPromptPending(false));
            default -> "hearthstead.captain.refused.unknown";
        };
        CaptainNetwork.sendTo(player, settler, feedback);
        if (!feedback.isEmpty() && !feedback.startsWith("hearthstead.captain.refused")) {
            CaptainNetwork.broadcastState(settler);
        }
    }

    // ------------------------------------------------------------ rename

    public static String sanitizeName(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        char[] cs = raw.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c == (char) 0xA7) {
                i++;   // a formatting code: drop the marker and its letter
            } else if (c >= 0x20 && c != 0x7f) {
                out.append(c);
            }
        }
        String s = out.toString().trim().replaceAll("\\s+", " ");
        return s.length() > CaptainPayloads.MAX_NAME ? s.substring(0, CaptainPayloads.MAX_NAME).trim() : s;
    }

    static String rename(SettlerEntity settler, String raw) {
        if (!CaptainStatus.isHero(settler)) {
            return "hearthstead.captain.refused.not_hero";
        }
        String name = sanitizeName(raw);
        if (name.isEmpty()) {
            return "hearthstead.captain.refused.name";
        }
        settler.setSettlerName(name);
        Settlement settlement = settler.settlement();
        if (settlement != null) {
            for (Settlement.SettlerRecord r : settlement.settlers) {
                if (r.entityId.equals(settler.getUUID())) {
                    r.name = name;
                }
            }
            if (settler.level() instanceof ServerLevel level) {
                com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
            }
        }
        return "hearthstead.captain.renamed";
    }

    private static String cosmetic(SettlerEntity settler, java.util.function.Consumer<CaptainState> change) {
        if (!CaptainStatus.isHero(settler)) {
            return "hearthstead.captain.refused.not_hero";
        }
        CaptainState cs = CaptainWorld.stateOf(settler);
        change.accept(cs);
        CaptainWorld.save(settler, cs);
        return "hearthstead.captain.saved";
    }

    // ----------------------------------------------------------- loadout

    /** Where one needed item was found (null source = the player's inventory). */
    private record Found(@Nullable Container container, int slot, boolean player) {
    }

    /** The two hand requirements of a loadout (off == null means "must be empty"). */
    static Predicate<ItemStack> mainNeed(CaptainLoadout l) {
        return switch (l) {
            case SWORD_SHIELD -> s -> s.is(ItemTags.SWORDS);
            case DUAL_SWORDS -> s -> s.is(CaptainLoadout.DUAL_SWORD_TAG) || s.is(ItemTags.SWORDS);
            case GREAT_AXE -> s -> s.is(CaptainLoadout.GREAT_AXE_TAG);
            case BOW -> s -> s.is(Items.BOW) || s.is(CaptainLoadout.BOW_TAG);
            case HALBERD -> s -> s.is(CaptainLoadout.HALBERD_TAG);
            case WARHAMMER -> s -> s.is(CaptainLoadout.WARHAMMER_TAG);
        };
    }

    @Nullable
    static Predicate<ItemStack> offNeed(CaptainLoadout l) {
        return switch (l) {
            case SWORD_SHIELD -> s -> s.is(Items.SHIELD);
            case DUAL_SWORDS -> s -> s.is(CaptainLoadout.DUAL_SWORD_TAG) || s.is(ItemTags.SWORDS);
            default -> null;
        };
    }

    public static String switchLoadout(ServerPlayer player, SettlerEntity captain, CaptainLoadout target) {
        if (!CaptainStatus.isHero(captain)) {
            return "hearthstead.captain.refused.not_hero";
        }
        if (!CaptainConfig.loadoutAllowed(target)) {
            return "hearthstead.captain.refused.locked";
        }
        ServerLevel level = (ServerLevel) captain.level();
        CaptainState cs = CaptainWorld.stateOf(captain);
        if (CaptainKit.heldLoadout(captain) == target) {
            cs.switchTo(target, level.getGameTime());
            CaptainWorld.save(captain, cs);
            return "hearthstead.captain.switched";
        }
        List<Found> used = new ArrayList<>();
        Found main = find(level, player, captain.settlement(), mainNeed(target), used);
        if (main == null) {
            return "hearthstead.captain.refused.missing_main";
        }
        used.add(main);
        Predicate<ItemStack> offPred = offNeed(target);
        Found off = null;
        if (offPred != null) {
            off = find(level, player, captain.settlement(), offPred, used);
            if (off == null) {
                return "hearthstead.captain.refused.missing_off";
            }
            used.add(off);
        }
        // Everything is found: now move, one real item each.
        ItemStack newMain = take(player, main);
        ItemStack newOff = off == null ? ItemStack.EMPTY : take(player, off);
        if (target == CaptainLoadout.BOW) {
            newOff = takeArrows(player);
        }
        ItemStack oldMain = captain.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack oldOff = captain.getItemBySlot(EquipmentSlot.OFFHAND);
        captain.setItemSlot(EquipmentSlot.MAINHAND, newMain);
        captain.setItemSlot(EquipmentSlot.OFFHAND, newOff);
        captain.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        captain.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
        give(player, oldMain);
        give(player, oldOff);
        cs.switchTo(target, level.getGameTime());
        CaptainWorld.save(captain, cs);
        return "hearthstead.captain.switched";
    }

    @Nullable
    private static Found find(ServerLevel level, ServerPlayer player, @Nullable Settlement settlement,
                              Predicate<ItemStack> need, List<Found> used) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && need.test(s) && available(null, i, true, s, used)) {
                return new Found(null, i, true);
            }
        }
        if (settlement == null) {
            return null;
        }
        for (BuildingType type : new BuildingType[] {BuildingType.ARMOURY, BuildingType.WAREHOUSE}) {
            for (Building b : settlement.buildings) {
                if (!b.valid || b.type != type) {
                    continue;
                }
                for (BlockPos pos : WarehouseIndex.containers(level, b)) {
                    if (level.getBlockEntity(pos) instanceof Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            ItemStack s = c.getItem(i);
                            if (!s.isEmpty() && need.test(s) && available(c, i, false, s, used)) {
                                return new Found(c, i, false);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** A slot already reserved for the other hand only counts if it still has a spare item. */
    private static boolean available(@Nullable Container c, int slot, boolean player, ItemStack s,
                                     List<Found> used) {
        int reserved = 0;
        for (Found f : used) {
            if (f.player() == player && f.slot() == slot && f.container() == c) {
                reserved++;
            }
        }
        return s.getCount() > reserved;
    }

    private static ItemStack take(ServerPlayer player, Found f) {
        if (f.player()) {
            ItemStack s = player.getInventory().getItem(f.slot());
            ItemStack one = s.split(1);
            player.getInventory().setChanged();
            return one;
        }
        ItemStack one = f.container().removeItem(f.slot(), 1);
        f.container().setChanged();
        return one;
    }

    private static ItemStack takeArrows(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.ARROW)) {
                ItemStack arrows = s.split(Math.min(64, s.getCount()));
                inv.setChanged();
                return arrows;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    /** Test/QA seam: equip a kit directly (items are the caller's responsibility). */
    public static void equipForTests(SettlerEntity captain, ItemStack main, ItemStack off) {
        captain.setItemInHand(InteractionHand.MAIN_HAND, main);
        captain.setItemInHand(InteractionHand.OFF_HAND, off);
        CaptainLoadout l = CaptainLoadout.of(main, off);
        if (l != null) {
            CaptainState cs = CaptainWorld.stateOf(captain);
            cs.switchTo(l, Long.MIN_VALUE / 2);
            CaptainWorld.save(captain, cs);
        }
    }
}
