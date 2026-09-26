package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.command.HearthsteadCommand;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.network.BlessingNetwork;
import com.hearthstead.network.InspectionViewers;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.settlement.raid.RaidBossBarService;
import com.hearthstead.settlement.raid.RaidThreatBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.lang.reflect.Field;

@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CommonEvents {
    private static Field targetSelectorField;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HearthsteadCommand.register(event.getDispatcher());
    }

    /** Eagerly releases the player's bounded Blessing screen proof. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            BlessingNetwork.forget(player);
            InspectionViewers.forget(player);
            RaidBossBarService.forget(player);
            com.hearthstead.settlement.workzone.WorkZoneService.forget(player);
        }
    }

    /** Integrated and dedicated server lifecycles must leave no static session map. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BlessingNetwork.clear(event.getServer());
        InspectionViewers.clear(event.getServer());
        com.hearthstead.settlement.workzone.WorkZoneService.clear(event.getServer());
        CombatTerminalEvents.clear(event.getServer());
        RaidBossBarService.clear(event.getServer());
        com.hearthstead.settlement.defense.AlarmBell.clear();
    }

    /** Settlers never trample the farmland they tend. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof SettlerEntity) {
            event.setCanceled(true);
        }
    }

    /** Clears an abandoned field-drop lease independently of its worker. */
    @SubscribeEvent
    public static void onGroundCollectionItemTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof ItemEntity item) {
            GroundCollectionSession.expireOwnershipLease(item);
        }
    }

    /**
     * Drops every warehouse index when a level unloads. The cache is static
     * and holds block positions, so without this a single-player client that
     * leaves one world and opens another carries the first world's chest
     * positions with it.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel) {
            WarehouseStorage.clearAll();
            RaidBossBarService.clearLevel((ServerLevel) event.getLevel());
            RaidThreatBoard.clear((ServerLevel) event.getLevel());
        }
    }

    /** Drives budgeted room scans and building revalidation. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Conservation retries are independent of settlement scanning and
            // run first so an unrelated building-manager failure cannot leave
            // accepted physical-item transfers permanently unserviced.
            DeferredItemMaterializationSavedData.retryLoaded(serverLevel);
            SettlementSavedData data = SettlementSavedData.get(serverLevel);
            data.buildingManager.tick(serverLevel, data);
            RaidBossBarService.tick(serverLevel);
            // Logistics M1: budgeted crafting-order resolver (per settlement
            // once per CraftingOrderService.SCAN_INTERVAL ticks).
            com.hearthstead.settlement.request.CraftingOrderService.tick(serverLevel);
        }
    }

    /** A placed bed, door or light inside a settlement wakes the scanner. */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            maybeRequestScan(serverLevel, event.getPos(), event.getPlacedBlock());
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            maybeRequestScan(serverLevel, event.getPos(), event.getState());
        }
    }

    private static void maybeRequestScan(ServerLevel level, BlockPos pos,
                                         BlockState state) {
        SettlementSavedData data = SettlementSavedData.get(level);
        // The plaques decide what a block change means; this just tells the
        // ones near enough to care that something moved.
        data.buildingManager.nudgeNear(level, pos);
    }

    /** Zombies see settlers as prey — guards exist for a reason. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof SettlerEntity settler) {
            settler.reRegisterWithSettlement();
        }
        if (event.getEntity() instanceof Zombie zombie) {
            GoalSelector targets = targetSelector(zombie);
            if (targets != null) {
                targets.addGoal(3, new NearestAttackableTargetGoal<>(
                    zombie, SettlerEntity.class, 10, true, false, null));
            }
        }
    }

    /** NeoForge runs official mappings at runtime, so plain reflection works. */
    private static GoalSelector targetSelector(Mob mob) {
        try {
            if (targetSelectorField == null) {
                targetSelectorField = Mob.class.getDeclaredField("targetSelector");
                targetSelectorField.setAccessible(true);
            }
            return (GoalSelector) targetSelectorField.get(mob);
        } catch (ReflectiveOperationException e) {
            Hearthstead.LOGGER.warn("Could not access Mob.targetSelector", e);
            return null;
        }
    }

    private CommonEvents() {
    }
}
