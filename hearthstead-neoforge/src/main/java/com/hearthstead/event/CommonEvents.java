package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.command.HearthsteadCommand;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.lang.reflect.Field;

@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CommonEvents {
    private static Field targetSelectorField;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HearthsteadCommand.register(event.getDispatcher());
    }

    /** Settlers never trample the farmland they tend. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof SettlerEntity) {
            event.setCanceled(true);
        }
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
