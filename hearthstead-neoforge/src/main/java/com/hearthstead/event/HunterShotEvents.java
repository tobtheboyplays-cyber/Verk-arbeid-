package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.item.CarcassData;
import com.hearthstead.item.CarcassItem;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/** Arrow-local Hunter provenance and physical death-drop handoff. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class HunterShotEvents {
    private static final String SCHEMA = "HearthsteadHunterShotSchema";
    private static final String PROJECTILE = "HearthsteadHunterShotProjectile";
    private static final String HUNTER = "HearthsteadHunterShotHunter";
    private static final String TARGET = "HearthsteadHunterShotTarget";
    private static final String SETTLEMENT = "HearthsteadHunterShotSettlement";
    private static final String LODGE = "HearthsteadHunterShotLodge";
    private static final String DEATH_COMMITTED = "HearthsteadHunterDeathCommitted";

    public static boolean issue(AbstractArrow arrow, SettlerEntity hunter,
                                Animal target, Building lodge) {
        Settlement settlement = hunter == null ? null : hunter.settlement();
        if (arrow == null || hunter == null || target == null || lodge == null
            || settlement == null || arrow.level() != hunter.level()
            || target.level() != hunter.level()
            || lodge.type != BuildingType.HUNTERS_LODGE || !lodge.valid
            || !lodge.workers.contains(hunter.getUUID())
            || !HunterWorkGoal.mayHarvest(hunter, target)) {
            return false;
        }
        CompoundTag data = arrow.getPersistentData();
        data.putInt(SCHEMA, 1);
        data.putUUID(PROJECTILE, arrow.getUUID());
        data.putUUID(HUNTER, hunter.getUUID());
        data.putUUID(TARGET, target.getUUID());
        data.putUUID(SETTLEMENT, settlement.id);
        data.putUUID(LODGE, lodge.id);
        data.putBoolean(DEATH_COMMITTED, false);
        return inspect(arrow) != null;
    }

    public static boolean claims(AbstractArrow arrow) {
        return arrow != null && arrow.getPersistentData().contains(SCHEMA);
    }

    @Nullable
    public static Inspection inspect(AbstractArrow arrow) {
        if (!claims(arrow)) return null;
        CompoundTag data = arrow.getPersistentData();
        if (data.getInt(SCHEMA) != 1 || !data.hasUUID(PROJECTILE)
            || !data.hasUUID(HUNTER) || !data.hasUUID(TARGET)
            || !data.hasUUID(SETTLEMENT) || !data.hasUUID(LODGE)
            || !arrow.getUUID().equals(data.getUUID(PROJECTILE))) {
            return null;
        }
        return new Inspection(data.getUUID(PROJECTILE), data.getUUID(HUNTER),
            data.getUUID(TARGET), data.getUUID(SETTLEMENT), data.getUUID(LODGE),
            data.getBoolean(DEATH_COMMITTED));
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)
            || !claims(arrow)) {
            return;
        }
        Inspection shot = inspect(arrow);
        SettlerEntity hunter = event.getSource().getEntity()
                instanceof SettlerEntity settler ? settler : null;
        Animal target = event.getEntity() instanceof Animal animal ? animal : null;
        Building lodge = validLodge(arrow, hunter, shot);
        if (shot == null || hunter == null || target == null || lodge == null
            || arrow.getOwner() != hunter
            || !shot.targetId().equals(target.getUUID())
            || !HunterWorkGoal.mayHarvest(hunter, target)) {
            event.setCanceled(true);
            arrow.discard();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        Inspection shot = inspect(arrow);
        SettlerEntity hunter = event.getSource().getEntity()
                instanceof SettlerEntity settler ? settler : null;
        Building lodge = validLodge(arrow, hunter, shot);
        if (shot == null || shot.deathCommitted() || hunter == null || lodge == null
            || arrow.getOwner() != hunter
            || !shot.targetId().equals(event.getEntity().getUUID())) {
            return;
        }
        arrow.getPersistentData().putBoolean(DEATH_COMMITTED, true);
        // CARCASS, NOT LOOT. The vanilla roll's stacks become the carcass's
        // recorded yield and leave the drop list in this same event, so the
        // one carcass item is the only authority for that loot: no path can
        // both land the meat and later butcher it again. Stacks beyond the
        // bounded yield row (never for vanilla passives) keep their ordinary
        // leased drop rather than being deleted.
        replaceWithCarcass(event);
        for (ItemEntity drop : event.getDrops()) {
            GroundCollectionSession.leaseExisting(hunter, drop);
        }
        hunter.train(Employment.trainedBy(BuildingType.HUNTERS_LODGE), 1.0F);
        com.hearthstead.entity.SkillLevels.completeUnit(hunter, 1,
            Employment.trainedBy(BuildingType.HUNTERS_LODGE));
        hunter.spendEffort(1);
    }

    /**
     * Moves up to {@link CarcassData#MAX_YIELD_STACKS} real drops into one
     * carcass ItemEntity at the body, in place. Returns the carcass, or null
     * when the kill rolled no loot at all (a baby, or an unlucky rabbit):
     * then nothing is created and nothing was removed.
     */
    @Nullable
    public static ItemEntity replaceWithCarcass(LivingDropsEvent event) {
        java.util.List<ItemEntity> taken = new java.util.ArrayList<>();
        java.util.List<net.minecraft.world.item.ItemStack> yield = new java.util.ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            if (taken.size() >= CarcassData.MAX_YIELD_STACKS) {
                break;
            }
            if (drop != null && !drop.getItem().isEmpty()
                && !CarcassItem.isCarcass(drop.getItem())) {
                taken.add(drop);
                yield.add(drop.getItem().copy());
            }
        }
        if (yield.isEmpty()) {
            return null;
        }
        net.minecraft.world.entity.LivingEntity body = event.getEntity();
        ItemEntity carcass = new ItemEntity(body.level(), body.getX(), body.getY() + 0.2D,
            body.getZ(), CarcassItem.create(CarcassData.of(body.getType(), yield)));
        carcass.setDeltaMovement(0.0D, 0.1D, 0.0D);
        carcass.setDefaultPickUpDelay();
        event.getDrops().removeAll(taken);
        event.getDrops().add(carcass);
        return carcass;
    }

    @Nullable
    private static Building validLodge(AbstractArrow arrow,
                                       @Nullable SettlerEntity hunter,
                                       @Nullable Inspection shot) {
        if (!(arrow.level() instanceof ServerLevel) || hunter == null
            || shot == null || !hunter.isAlive()
            || hunter.level() != arrow.level()
            || hunter.getProfession() != Profession.HUNTER
            || !shot.hunterId().equals(hunter.getUUID())) {
            return null;
        }
        Settlement settlement = hunter.settlement();
        if (settlement == null || !shot.settlementId().equals(settlement.id)) {
            return null;
        }
        Building employer = Employment.employerOf(settlement, hunter.getUUID());
        return employer != null && employer.valid
            && employer.type == BuildingType.HUNTERS_LODGE
            && shot.lodgeId().equals(employer.id) ? employer : null;
    }

    public record Inspection(UUID projectileId, UUID hunterId, UUID targetId,
                             UUID settlementId, UUID lodgeId,
                             boolean deathCommitted) {
    }

    private HunterShotEvents() {
    }
}
