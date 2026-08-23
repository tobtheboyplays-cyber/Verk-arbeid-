package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** Hungry settlers fetch a meal from the communal hearth stores. */
public class EatFromHearthGoal extends Goal {
    private final SettlerEntity settler;
    private int cooldown;
    private int repathTimer;
    private int eatTicks;
    private ItemStack meal = ItemStack.EMPTY;
    private boolean done;

    public EatFromHearthGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!settler.isBound()) {
            return false;
        }
        float hunger = settler.getHunger();
        boolean hungry = hunger < 40
            || (settler.dayPhase() == SettlerEntity.DayPhase.EVENING && hunger < 75);
        if (!hungry) {
            return false;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null || hearth.countFoodUnits() <= 0) {
            cooldown = 100;
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        done = false;
        eatTicks = 0;
        meal = ItemStack.EMPTY;
        path();
    }

    private void path() {
        BlockPos hearth = settler.getHearthPos();
        if (hearth != null) {
            settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
                hearth.getZ() + 0.5, 1.0);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !done && settler.isBound() && settler.getHearthPos() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5, hearthPos.getY() + 0.6,
            hearthPos.getZ() + 0.5);

        if (eatTicks > 0) {
            // Mid-meal.
            eatTicks--;
            if (eatTicks % 6 == 0 && settler.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, settler.blockPosition(),
                    SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7F,
                    0.9F + settler.getRandom().nextFloat() * 0.2F);
                if (!meal.isEmpty()) {
                    serverLevel.sendParticles(
                        new ItemParticleOption(ParticleTypes.ITEM, meal),
                        settler.getX(), settler.getY() + 1.3, settler.getZ(),
                        3, 0.1, 0.1, 0.1, 0.05);
                }
            }
            if (eatTicks == 0) {
                FoodProperties food = meal.isEmpty() ? null : meal.getFoodProperties(settler);
                int nutrition = food != null ? food.nutrition() : 2;
                settler.setHunger(settler.getHunger() + nutrition * 8.0F);
                settler.addMorale(2.0F);
                done = true;
            }
            return;
        }

        if (settler.blockPosition().distSqr(hearthPos) <= 6.25) {
            HearthBlockEntity hearth = settler.hearth();
            ItemStack extracted = hearth != null ? hearth.extractBestFood() : ItemStack.EMPTY;
            if (extracted.isEmpty()) {
                done = true;
                return;
            }
            meal = extracted;
            eatTicks = 40;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.EATING);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            path();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        cooldown = 100;
        meal = ItemStack.EMPTY;
    }
}
