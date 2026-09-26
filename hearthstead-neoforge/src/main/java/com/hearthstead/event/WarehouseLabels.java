package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseSorting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Fixed display frames identify assigned storage; the icon is never saleable stock. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WarehouseLabels {
    private static final String CHEST = "HearthsteadStorageLabelChest";
    private WarehouseLabels() {}

    public static ItemFrame ensure(ServerLevel level, BlockPos chest) {
        var group = WarehouseSorting.assignedGroup(level.getBlockEntity(chest));
        if (group == null) return null;
        var state = level.getBlockState(chest);
        // One sign for the combined inventory, not two adjacent identical signs.
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) return null;
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, new AABB(chest).inflate(2))) {
            if (frame.getPersistentData().contains(CHEST)
                    && frame.getPersistentData().getLong(CHEST) == chest.asLong()) {
                // A reclaimed (re-labelled) empty chest keeps its frame but
                // must show its new group, never the old icon.
                if (!frame.getItem().is(group.icon())) {
                    ItemStack icon = new ItemStack(group.icon());
                    icon.set(DataComponents.CUSTOM_NAME, Component.literal(group.label()));
                    frame.setItem(icon, false);
                }
                return frame;
            }
        }
        Direction front = state.hasProperty(ChestBlock.FACING) ? state.getValue(ChestBlock.FACING) : Direction.NORTH;
        for (Direction face : new Direction[]{front, front.getClockWise(), front.getCounterClockWise(), front.getOpposite()}) {
            BlockPos space = chest.relative(face);
            if (!level.hasChunkAt(space) || !level.getBlockState(space).isAir()) continue;
            if (!level.getEntitiesOfClass(ItemFrame.class, new AABB(space)).isEmpty()) continue;
            ItemFrame frame = new ItemFrame(level, space, face);
            var frameData = new net.minecraft.nbt.CompoundTag();
            frame.saveWithoutId(frameData);
            frameData.putBoolean("Fixed", true);
            frame.load(frameData);
            ItemStack icon = new ItemStack(group.icon());
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(group.label()));
            frame.setItem(icon, false);
            frame.getPersistentData().putLong(CHEST, chest.asLong());
            if (level.addFreshEntity(frame)) return frame;
        }
        return null;
    }

    @SubscribeEvent
    public static void openChest(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof ItemFrame frame) || !(event.getLevel() instanceof ServerLevel level)
                || !frame.getPersistentData().contains(CHEST)) return;
        BlockPos chest = BlockPos.of(frame.getPersistentData().getLong(CHEST));
        if (!level.hasChunkAt(chest) || event.getEntity().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(chest)) > 25
                || WarehouseSorting.assignedGroup(level.getBlockEntity(chest)) == null) return;
        var menu = level.getBlockState(chest).getMenuProvider(level, chest);
        if (menu != null) event.getEntity().openMenu(menu);
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.CONSUME);
    }

    @SubscribeEvent
    public static void maintain(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(frame.level() instanceof ServerLevel level)
                || frame.tickCount % 40 != 0 || !frame.getPersistentData().contains(CHEST)) return;
        BlockPos chest = BlockPos.of(frame.getPersistentData().getLong(CHEST));
        if (!level.hasChunkAt(chest)) return;
        var group = WarehouseSorting.assignedGroup(level.getBlockEntity(chest));
        var state = level.getBlockState(chest);
        if (group == null || state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) {
            frame.discard(); // Decorative frames never drop their representative icon.
        }
    }
}
