package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.command.HearthsteadCommand;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Hearthstead.MODID)
public final class CommonEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HearthsteadCommand.register(event.getDispatcher());
    }

    /** Zombies see settlers as prey — guards exist for a reason. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Zombie zombie) {
            GoalSelector targets = ObfuscationReflectionHelper.getPrivateValue(
                Mob.class, zombie, "f_21346_"); // targetSelector
            if (targets != null) {
                targets.addGoal(3, new NearestAttackableTargetGoal<>(
                    zombie, SettlerEntity.class, 10, true, false, null));
            }
        }
    }

    /** Settlers never trample the farmland they tend. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof SettlerEntity) {
            event.setCanceled(true);
        }
    }

    private CommonEvents() {
    }
}
