package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Effort;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernBard;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.hearthstead.settlement.techtree.effects.CommonsEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.PlaqueState;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * Commons &amp; Household branch (batch {@code techtree_commons}): for every
 * node with its own effect, the hook reads "off" before the node is learned
 * and "on" after, per settlement. Plus old-save migration of the plans and
 * emblems this branch moved. Synchronous; never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeCommonsGameTests {

    private static final String BATCH = "techtree_commons";

    public TechTreeCommonsGameTests() {
    }

    // ------------------------------------------------------------ ring 1

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void warmHearthLiftsMoraleOnceADayAtTheFire(GameTestHelper helper) {
        Fixture f = fixture(helper, "Warm");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        SettlerEntity settler = settler(helper, f.settlement, 6, 6);
        settler.addMorale(-100.0F);
        helper.assertTrue(!CommonsEffects.grantWarmth(helper.getLevel(), settler)
                && CommonsEffects.warmChance(settler, 0.4F) == 0.4F,
            "no warmth lift and no extra fire chance before Warm Hearth");
        state.unlockUpgrade(PostRaidUpgrade.WARM_HEARTH);
        float before = settler.getMorale();
        boolean first = CommonsEffects.grantWarmth(helper.getLevel(), settler);
        boolean second = CommonsEffects.grantWarmth(helper.getLevel(), settler);
        helper.assertTrue(first && !second && settler.getMorale() > before,
            "Warm Hearth: +3 morale once per day at the fire (" + before + " -> " + settler.getMorale() + ")");
        helper.assertTrue(Math.abs(CommonsEffects.warmChance(settler, 0.4F) - 0.6F) < 1.0E-4F,
            "Warm Hearth: settlers gather at the fire more often");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void sturdyBedsAndManorsLiftResidentsOfBetterHomes(GameTestHelper helper) {
        Fixture f = fixture(helper, "Homes");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 1, 8));
        Building house = new Building(UUID.randomUUID(), BuildingType.HOUSE, anchor.above(), anchor,
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        house.valid = true;
        house.level = 3;
        BlockPos bed = anchor.offset(1, 0, 1);
        house.beds.add(bed);
        f.settlement.buildings.add(house);
        try {
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed) == 0,
                "no home bonus before Sturdy Beds");
            state.unlockUpgrade(PostRaidUpgrade.STURDY_BEDS);
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed)
                    == CommonsEffects.COTTAGE_MORALE,
                "Sturdy Beds: +2 in a level-2+ home");
            house.level = 1;
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed) == 0,
                "a bare level-1 home gets nothing");
            house.level = 3;
            state.learnTech("manors");
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed)
                    == CommonsEffects.COTTAGE_MORALE,
                "Manors: a level-3 House is a Townhouse, not yet a Manor");
            // Home tiers (owner 26 Sep): a House's Manor is level 4 (HomeTier.MANOR).
            house.level = com.hearthstead.building.HomeTier.MANOR.level();
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed)
                    == CommonsEffects.COTTAGE_MORALE + CommonsEffects.MANOR_MORALE,
                "Manors: another +2 in a Manor (House level 4)");
        } finally {
            f.settlement.buildings.remove(house);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void featherQuiltsWakeWellRestedUnlessAlarmed(GameTestHelper helper) {
        Fixture f = fixture(helper, "Quilts");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity settler = settler(helper, f.settlement, 6, 6);
        helper.assertTrue(CommonsEffects.wakeRefillFraction(settler) == 1.0F,
            "a plain wake refills 100% before Feather Quilts");
        state.unlockUpgrade(PostRaidUpgrade.FEATHER_QUILTS);
        helper.assertTrue(CommonsEffects.wakeRefillFraction(settler) == CommonsEffects.WELL_RESTED_FRACTION,
            "Feather Quilts: Well Rested wakes at 110%");
        f.settlement.alertUntilGameTime = helper.getLevel().getGameTime() + 200L;
        helper.assertTrue(CommonsEffects.wakeRefillFraction(settler) == 1.0F,
            "no Well Rested while the village is alarmed");
        f.settlement.alertUntilGameTime = 0L;
        Effort effort = Effort.full();
        effort.refillFull(0, CommonsEffects.WELL_RESTED_FRACTION);
        helper.assertTrue(effort.left(0) == 22 && effort.capacity(0) == 20,
            "the pool really holds 110% (22/20), got " + effort.describe(0));
        effort.refillFull(0);
        helper.assertTrue(effort.left(0) == 20, "a normal refill is still 100%");
        helper.succeed();
    }

    // ------------------------------------------------------------ ring 2

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void hearthDoctrineClaimsTheLibraryAndSpeedsStudy(GameTestHelper helper) {
        Fixture f = fixture(helper, "Library");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.LIBRARY)
                && TechTree.bonus(helper.getLevel(), f.settlement, TechTree.STUDY_SPEED) == 0.0D,
            "no Library and no study speed before the Hearth Doctrine");
        state.learnDoctrine(DevelopmentNode.HEARTH_DOCTRINE, 0L);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.LIBRARY)
                && TechTree.bonus(helper.getLevel(), f.settlement, TechTree.STUDY_SPEED)
                    >= CommonsEffects.STUDY_SPEED_PERCENT,
            "Hearth Doctrine: Library plan and +25% study speed");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void songbookAndRevelsRaiseTheBardLift(GameTestHelper helper) {
        Fixture f = fixture(helper, "Bard");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        float base = TavernBard.EVENING_LIFT;
        helper.assertTrue(CommonsEffects.bardLift(helper.getLevel(), f.settlement, base) == base,
            "bard lift is 5 before the Songbook");
        state.learnTech("bards_songbook");
        helper.assertTrue(CommonsEffects.bardLift(helper.getLevel(), f.settlement, base) == 8.0F,
            "Bard's Songbook: 5 -> 8");
        helper.assertTrue(CommonsEffects.payTavernBonus(helper.getLevel(), f.settlement, 2) == 0
                && count(f.hearth, ModItems.GOLD_COIN.get()) == 0,
            "no house Coins before the Hall of Revels");
        state.learnTech("hall_of_revels");
        helper.assertTrue(CommonsEffects.bardLift(helper.getLevel(), f.settlement, base) == 16.0F,
            "Hall of Revels: bard lift doubled");
        helper.assertTrue(CommonsEffects.payTavernBonus(helper.getLevel(), f.settlement, 2) == 2
                && count(f.hearth, ModItems.GOLD_COIN.get()) == 2,
            "Hall of Revels: 2 Coins paid earn 2 more in the Banner");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void kitchenAndAlehouseClaimTheirPlansAndTheAleLifts(GameTestHelper helper) {
        Boolean trades = null;
        ExtendedTrades.overrideForTests(Boolean.TRUE);
        try {
            Fixture f = fixture(helper, "Kitchen");
            DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
            village(state);
            state.unlock(DevelopmentNode.HALL_AND_LEARNING);
            SettlerEntity guest = settler(helper, f.settlement, 6, 6);
            helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.KITCHEN)
                    && !Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.COOK)
                    && !Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.BREWERY)
                    && CommonsEffects.aleMorale(guest, 3.0F) == 3.0F,
                "Kitchen, Cook and Brewery moved off Hall and Learning; ale lifts 3");
            helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.LIBRARY),
                "the School keeps its Library");
            state.learnTech("kitchen_and_hall");
            state.learnTech("alehouse");
            helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.KITCHEN)
                    && Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.DINING_HALL)
                    && Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.COOK)
                    && Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.BREWERY)
                    && Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.BREWER),
                "Kitchen and Hall / Alehouse grant their plans and emblems");
            helper.assertTrue(CommonsEffects.aleMorale(guest, 3.0F) == CommonsEffects.ALEHOUSE_ALE_MORALE,
                "Alehouse: a pint lifts 5");
        } finally {
            ExtendedTrades.overrideForTests(trades);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void warFeastEatsTheStoresAndStrengthensGuards(GameTestHelper helper) {
        Fixture f = fixture(helper, "Feast");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity guard = settler(helper, f.settlement, 6, 6);
        guard.setProfessionProjection(Profession.GUARD);
        guard.setNoAi(true);
        put(f.hearth, Items.BREAD, 11);
        put(f.hearth, ModItems.ALE.get(), 4);
        helper.assertTrue(!CommonsEffects.onRaidWarning(helper.getLevel(), f.settlement)
                && count(f.hearth, Items.BREAD) == 11,
            "no feast before War Feast is learned");
        Pig plain = helper.spawn(EntityType.PIG, new BlockPos(9, 1, 9));
        plain.setNoAi(true);
        plain.hurt(helper.getLevel().damageSources().mobAttack(guard), 4.0F);
        state.learnTech("war_feast");
        helper.assertTrue(!CommonsEffects.onRaidWarning(helper.getLevel(), f.settlement)
                && count(f.hearth, Items.BREAD) == 11 && count(f.hearth, ModItems.ALE.get()) == 4
                && !CommonsEffects.warFeastActive(helper.getLevel(), f.settlement),
            "one meal short: nothing is eaten, no feast");
        put(f.hearth, Items.BREAD, 1);
        helper.assertTrue(CommonsEffects.onRaidWarning(helper.getLevel(), f.settlement)
                && count(f.hearth, Items.BREAD) == 0 && count(f.hearth, ModItems.ALE.get()) == 0
                && CommonsEffects.warFeastActive(helper.getLevel(), f.settlement),
            "War Feast: 12 meals + 4 ale eaten once, feast active");
        helper.assertTrue(CommonsEffects.guardRecoverFraction(guard, 0.30F)
                == CommonsEffects.WAR_FEAST_RECOVER_FRACTION,
            "feasted guards hold until 15% health");
        Pig fed = helper.spawn(EntityType.PIG, new BlockPos(11, 1, 11));
        fed.setNoAi(true);
        fed.hurt(helper.getLevel().damageSources().mobAttack(guard), 4.0F);
        float plainLoss = plain.getMaxHealth() - plain.getHealth();
        float fedLoss = fed.getMaxHealth() - fed.getHealth();
        helper.assertTrue(Math.abs(plainLoss - 4.0F) < 0.01F && Math.abs(fedLoss - 4.4F) < 0.01F,
            "feasted guard blows deal +10% (" + plainLoss + " -> " + fedLoss + ")");
        plain.discard();
        fed.discard();
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void townhousesAndManorsRaiseHouseCapacity(GameTestHelper helper) {
        Fixture f = fixture(helper, "Capacity");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        helper.assertTrue(CommonsEffects.houseCapacity(helper.getLevel(), f.settlement, BuildingType.HOUSE) == 4,
            "a House holds 4 before Townhouses");
        state.learnTech("two_storey_houses");
        helper.assertTrue(CommonsEffects.houseCapacity(helper.getLevel(), f.settlement, BuildingType.HOUSE) == 6
                && CommonsEffects.houseCapacity(helper.getLevel(), f.settlement, BuildingType.LODGING) == 8,
            "Townhouses: House 6, Lodging unchanged");
        state.learnTech("manors");
        helper.assertTrue(CommonsEffects.houseCapacity(helper.getLevel(), f.settlement, BuildingType.HOUSE) == 8,
            "Manors: House 8");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void houseBedCapLimitsNewClaimsButNeverEvicts(GameTestHelper helper) {
        Fixture f = fixture(helper, "BedCap");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 1, 8));
        Building house = new Building(UUID.randomUUID(), BuildingType.HOUSE, anchor.above(), anchor,
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(anchor, anchor.offset(6, 2, 6)));
        house.valid = true;
        for (int i = 0; i < 6; i++) {
            house.beds.add(anchor.offset(i, 0, 0));
        }
        f.settlement.buildings.add(house);
        try {
            // Five residents already sleep here (an old save, or before the cap).
            java.util.List<SettlerEntity> sleepers = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                SettlerEntity s = settler(helper, f.settlement, 2 + i, 12);
                s.setNoAi(true);
                s.claimBed(house.beds.get(i));
                sleepers.add(s);
            }
            helper.assertTrue(f.settlement.validBedCount() == 6,
                "before the cap is known every bed counts (old behaviour)");
            CommonsEffects.refreshHousing(helper.getLevel(), f.settlement);
            helper.assertTrue(f.settlement.houseBedCap == 4 && f.settlement.validBedCount() == 4
                    && f.settlement.capacity() == 4,
                "a 6-bed House counts 4 without Townhouses, got " + f.settlement.validBedCount());
            boolean kept = true;
            for (int i = 0; i < 5; i++) {
                kept &= house.beds.get(i).equals(sleepers.get(i).getClaimedBed());
            }
            helper.assertTrue(kept, "nobody is evicted by the cap");
            helper.assertTrue(BuildingManager.findFreeBed(helper.getLevel(), f.settlement) == null,
                "a House at its cap offers no new bed");
            state.learnTech("two_storey_houses");
            CommonsEffects.refreshHousing(helper.getLevel(), f.settlement);
            BlockPos free = BuildingManager.findFreeBed(helper.getLevel(), f.settlement);
            helper.assertTrue(f.settlement.houseBedCap == 6 && f.settlement.validBedCount() == 6
                    && house.beds.get(5).equals(free),
                "Townhouses: the House counts 6 and the sixth bed is free");
            state.learnTech("manors");
            CommonsEffects.refreshHousing(helper.getLevel(), f.settlement);
            helper.assertTrue(f.settlement.houseBedCap == 8
                    && "bed_cap.8".equals(CommonsEffects.bedCapLineId(f.settlement, house)),
                "Manors: cap 8 and the plaque names it");
        } finally {
            f.settlement.buildings.remove(house);
            f.settlement.houseBedCap = 0;
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void chapelIsLearnedAndGivesThanksAfterAVictory(GameTestHelper helper) {
        Fixture f = fixture(helper, "Chapel");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity settler = settler(helper, f.settlement, 6, 6);
        settler.addMorale(-100.0F);
        helper.assertTrue(CommonsEffects.onRaidWon(helper.getLevel(), f.settlement) == 0,
            "no thanksgiving before the Chapel");
        fund(f.hearth, TechTreeData.get().node("wayside_shrine"));
        TechTree.Result learned = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "wayside_shrine", state.revision(), null);
        helper.assertTrue(learned == TechTree.Result.LEARNED, "the Chapel is learnable, got " + learned);
        float before = settler.getMorale();
        helper.assertTrue(CommonsEffects.onRaidWon(helper.getLevel(), f.settlement) >= 1
                && settler.getMorale() > before,
            "Chapel: morale for everyone after a raid held");
        float chapel = settler.getMorale() - before;
        state.learnTech("cathedral");
        settler.addMorale(-100.0F);
        float mid = settler.getMorale();
        CommonsEffects.onRaidWon(helper.getLevel(), f.settlement);
        float both = settler.getMorale() - mid;
        helper.assertTrue(both > chapel,
            "Cathedral: a bigger lift on top (" + chapel + " -> " + both + ")");
        helper.assertTrue(TechTree.learn(helper.getLevel(), f.settlement, f.hearth, "alehouse",
                state.revision(), null) == TechTree.Result.EXCLUDED,
            "Chapel and Alehouse are a pick-one");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void infirmaryHealsPatientsInsideAndHealerIsClaimed(GameTestHelper helper) {
        Fixture f = fixture(helper, "Infirmary");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        Building ward = GameTestFixtures.register(helper, f.settlement, BuildingType.INFIRMARY, 6, 6);
        SettlerEntity patient = settler(helper, f.settlement, 7, 7);
        patient.setNoAi(true);
        patient.setHealth(8.0F);
        helper.assertTrue(ward.contains(patient.blockPosition()), "the patient stands inside the ward");
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.INFIRMARY)
                && CommonsEffects.infirmaryPass(helper.getLevel(), f.settlement) == 0
                && patient.getHealth() == 8.0F,
            "no Infirmary plan and no healing before the node");
        state.learnTech("infirmary");
        int healed = CommonsEffects.infirmaryPass(helper.getLevel(), f.settlement);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.INFIRMARY)
                && healed >= 1 && patient.getHealth() == 9.0F,
            "Infirmary: plan unlocked and +1 HP per pass (health " + patient.getHealth() + ")");
        if (com.hearthstead.settlement.development.JobEmblemCatalog.forProfession(Profession.HEALER) != null) {
            helper.assertTrue(!Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.HEALER),
                "the Healer emblem moved off the Village Charter");
            state.learnTech("battle_healer");
            helper.assertTrue(Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.HEALER),
                "Battle Healers: Healer emblem");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------ ring 3+

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void greatTavernDoublesMinstrelsAndCaravans(GameTestHelper helper) {
        Fixture f = fixture(helper, "Inn");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        helper.assertTrue(CommonsEffects.eventWeightScale(helper.getLevel(), f.settlement,
                WorldEventType.MINSTRELS) == 1.0D,
            "normal weights before the Great Tavern");
        state.learnTech("great_tavern");
        helper.assertTrue(CommonsEffects.eventWeightScale(helper.getLevel(), f.settlement, WorldEventType.MINSTRELS) == 2.0D
                && CommonsEffects.eventWeightScale(helper.getLevel(), f.settlement, WorldEventType.CARAVAN) == 2.0D
                && CommonsEffects.eventWeightScale(helper.getLevel(), f.settlement, WorldEventType.WOLF_PACK) == 1.0D,
            "Great Tavern: Minstrels and Caravans x2, others unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void harvestFeastEatsTheStoresAndSpeedsWorkshops(GameTestHelper helper) {
        Fixture f = fixture(helper, "Harvest");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity settler = settler(helper, f.settlement, 6, 6);
        settler.addMorale(-100.0F);
        put(f.hearth, Items.BREAD, 48);
        put(f.hearth, ModItems.ALE.get(), 16);
        long day = 1000L;
        helper.assertTrue(!CommonsEffects.harvestFeastAtDawn(helper.getLevel(), f.settlement, day)
                && count(f.hearth, Items.BREAD) == 48
                && CommonsEffects.craftPace(helper.getLevel(), f.settlement.id) == 1.0D,
            "no feast and normal pace before the Harvest Feast");
        state.learnTech("harvest_feast");
        float before = settler.getMorale();
        helper.assertTrue(CommonsEffects.harvestFeastAtDawn(helper.getLevel(), f.settlement, day)
                && count(f.hearth, Items.BREAD) == 0 && count(f.hearth, ModItems.ALE.get()) == 0
                && settler.getMorale() > before
                && CommonsEffects.craftPace(helper.getLevel(), f.settlement.id) == CommonsEffects.FESTIVAL_CRAFT_PACE,
            "Harvest Feast: 48 bread + 16 ale eaten, morale up, workshops 10% faster");
        put(f.hearth, Items.BREAD, 48);
        put(f.hearth, ModItems.ALE.get(), 16);
        helper.assertTrue(!CommonsEffects.harvestFeastAtDawn(helper.getLevel(), f.settlement, day + 1)
                && !CommonsEffects.harvestFeastAtDawn(helper.getLevel(), f.settlement, day + 3)
                && count(f.hearth, Items.BREAD) == 48,
            "the next feast waits 4 days");
        helper.assertTrue(CommonsEffects.harvestFeastAtDawn(helper.getLevel(), f.settlement, day + 4),
            "the feast returns on day 4");
        helper.succeed();
    }

    /**
     * Regression (Codex P2): feast deadlines live in the DAY-TIME domain, so
     * a 2x day length (game time runs twice as long per day) or sleeping
     * never ends a festival early or late. Pure arithmetic: never moves the
     * shared test level's clock.
     */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 20)
    public void feastDeadlinesFollowTheDayClockNotGameTime(GameTestHelper helper) {
        long day = 24_000L * 7;
        long dawnFeast = day + 100L;                       // Harvest Feast at dawn
        long until = CommonsEffects.nextDawn(dawnFeast);
        // With a 2x day, 23 900 game ticks later the day clock is only at noon.
        long noonWith2xDay = dawnFeast + 23_900L / 2;
        helper.assertTrue(until == day + 24_000L
                && CommonsEffects.activeUntilDawn(until, noonWith2xDay)
                && CommonsEffects.activeUntilDawn(until, day + 23_999L)
                && !CommonsEffects.activeUntilDawn(until, day + 24_000L),
            "the festival lasts until the next dawn on the day clock, whatever the day length");
        helper.assertTrue(!CommonsEffects.activeUntilDawn(until, day + 30_000L),
            "sleeping past dawn ends it");
        helper.assertTrue(!CommonsEffects.activeUntilDawn(until, 100L),
            "a clock set far back never makes it last for days");
        long duskWarning = day + 12_500L;
        long feastEnd = CommonsEffects.warFeastDawn(duskWarning);
        helper.assertTrue(feastEnd == day + 48_000L
                && CommonsEffects.activeUntilDawn(feastEnd, day + 24_000L + 18_000L)
                && !CommonsEffects.activeUntilDawn(feastEnd, day + 48_000L),
            "a War Feast warned at dusk lasts through the next raid night until the dawn after it");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void hallOfHeroesSoftensGriefAndCheersRecruits(GameTestHelper helper) {
        Fixture f = fixture(helper, "Heroes");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity recruit = settler(helper, f.settlement, 6, 6);
        recruit.addMorale(-100.0F);
        helper.assertTrue(CommonsEffects.griefScale(helper.getLevel(), f.settlement) == 1.0F,
            "full grief before the Hall of Heroes");
        CommonsEffects.onRecruited(helper.getLevel(), f.settlement, recruit);
        helper.assertTrue(recruit.getMorale() == 0.0F, "no recruit morale before the Hall");
        state.learnTech("hall_of_heroes");
        CommonsEffects.onRecruited(helper.getLevel(), f.settlement, recruit);
        helper.assertTrue(CommonsEffects.griefScale(helper.getLevel(), f.settlement) == 0.5F
                && recruit.getMorale() > 0.0F,
            "Hall of Heroes: grief halved and recruits +5 morale");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void schoolRecruitsArriveOneTradeLevelAhead(GameTestHelper helper) {
        Fixture f = fixture(helper, "School");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        SettlerEntity early = settler(helper, f.settlement, 6, 6);
        CommonsEffects.onRecruited(helper.getLevel(), f.settlement, early);
        helper.assertTrue(early.tradeSkills().level(Profession.FARMER) == 1,
            "an unschooled recruit starts at trade level 1");
        state.unlock(DevelopmentNode.HALL_AND_LEARNING);
        SettlerEntity schooled = settler(helper, f.settlement, 8, 8);
        CommonsEffects.onRecruited(helper.getLevel(), f.settlement, schooled);
        helper.assertTrue(schooled.tradeSkills().level(Profession.FARMER) == CommonsEffects.SCHOOL_TRADE_LEVEL
                && schooled.tradeSkills().level(Profession.COURIER) == CommonsEffects.SCHOOL_TRADE_LEVEL
                && schooled.tradeSkills().xp(Profession.FARMER) == SkillLevels.xpForLevel(2),
            "School: every trade at level 2");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void preV3SavesKeepTheirMovedPlansAndEmblems(GameTestHelper helper) {
        DevelopmentState state = new DevelopmentState();
        village(state);
        state.unlock(DevelopmentNode.HALL_AND_LEARNING);
        CompoundTag old = state.writeNbt();
        old.remove("TechNodes");
        old.remove("TechGrandfathered");
        DevelopmentState loaded = DevelopmentState.readNbt(old);
        helper.assertTrue(!loaded.quarantined()
                && loaded.hasTech("kitchen_and_hall") && loaded.hasTech("alehouse")
                && loaded.hasTech("infirmary") && loaded.hasTech("battle_healer"),
            "an old save with Hall and Learning + the Village Charter keeps Kitchen, Brewery, Infirmary and Healer");
        DevelopmentState fresh = new DevelopmentState();
        village(fresh);
        fresh.unlock(DevelopmentNode.HALL_AND_LEARNING);
        DevelopmentState reloaded = DevelopmentState.readNbt(fresh.writeNbt());
        helper.assertTrue(!reloaded.hasTech("kitchen_and_hall") && !reloaded.hasTech("infirmary"),
            "a v3 save is never granted moved nodes twice");
        helper.succeed();
    }

    // ------------------------------------------ Well, Market, School plans

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void wellMarketAndSchoolPlansFollowTheirNodesAndWork(GameTestHelper helper) {
        Fixture f = fixture(helper, "Plans");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        for (BuildingType type : new BuildingType[] {BuildingType.WELL, BuildingType.MARKET, BuildingType.SCHOOL}) {
            helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), f.settlement, type)
                    && CommonsEffects.planSource(type) != null,
                type.id() + " is locked and the plaque can name the node that opens it");
        }
        helper.assertTrue(CommonsEffects.planSource(BuildingType.WELL).getString()
                .equals(TechTreeData.get().node("home").displayName().getString())
                && CommonsEffects.planSource(BuildingType.MARKET).getString()
                    .equals(TechTreeData.get().node("hospitality").displayName().getString())
                && CommonsEffects.planSource(BuildingType.SCHOOL).getString()
                    .equals(TechTreeData.get().node("hall_and_learning").displayName().getString()),
            "Well -> Houses & Lodging, Market -> Tavern & Trade, School -> Families & School");
        state.unlock(DevelopmentNode.HOME);
        state.unlock(DevelopmentNode.HOSPITALITY);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.WELL)
                && Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.MARKET),
            "Houses & Lodging opens the Well House; Tavern & Trade opens the Market");
        village(state);
        state.unlock(DevelopmentNode.HALL_AND_LEARNING);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.SCHOOL),
            "Families & School opens the School");

        // Their jobs: Well morale, Market purse, School level 3.
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 1, 8));
        BlockPos bed = anchor.offset(1, 0, 1);
        Building house = building(BuildingType.HOUSE, anchor);
        house.beds.add(bed);
        f.settlement.buildings.add(house);
        Building well = building(BuildingType.WELL, anchor.offset(0, 0, 4));
        Building market = building(BuildingType.MARKET, anchor.offset(4, 0, 0));
        Building school = building(BuildingType.SCHOOL, anchor.offset(4, 0, 4));
        try {
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed) == 0
                    && CommonsEffects.marketPurse(helper.getLevel(), f.settlement) == 0,
                "no Well or Market yet: no bonus");
            SettlerEntity plain = settler(helper, f.settlement, 5, 5);
            CommonsEffects.onRecruited(helper.getLevel(), f.settlement, plain);
            f.settlement.buildings.add(well);
            f.settlement.buildings.add(market);
            f.settlement.buildings.add(school);
            SettlerEntity schooled = settler(helper, f.settlement, 6, 5);
            CommonsEffects.onRecruited(helper.getLevel(), f.settlement, schooled);
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, bed) == CommonsEffects.WELL_MORALE
                    && CommonsEffects.marketPurse(helper.getLevel(), f.settlement) == CommonsEffects.MARKET_PURSE,
                "a valid Well House: +2 morale; a valid Market: +6 merchant Coins");
            helper.assertTrue(plain.tradeSkills().level(Profession.FARMER) == 2
                    && schooled.tradeSkills().level(Profession.FARMER) == 3,
                "a valid School raises recruits from trade level 2 to 3");
        } finally {
            f.settlement.buildings.remove(well);
            f.settlement.buildings.remove(market);
            f.settlement.buildings.remove(school);
            f.settlement.buildings.remove(house);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 300)
    public void marketPlanIsRefusedUntilTavernAndTradeThenTheRoomValidates(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Commons Market", helper.absolutePos(new BlockPos(1, 1, 1)));
        s.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.HOME);
        // A 9x9 stone room with 4 barrels, 2 chests, 2 torches and a door.
        BlockPos o = new BlockPos(1, 0, 1);
        int size = 9;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(o.offset(4, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(4, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (BlockPos p : new BlockPos[] {o.offset(2, 1, 2), o.offset(2, 1, 6), o.offset(6, 1, 2), o.offset(6, 1, 6)}) {
            helper.setBlock(p, Blocks.BARREL);
        }
        helper.setBlock(o.offset(4, 1, 2), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 6), Blocks.CHEST);
        helper.setBlock(o.offset(1, 2, 1), Blocks.TORCH);
        helper.setBlock(o.offset(7, 2, 1), Blocks.TORCH);
        BlockPos plaqueRel = o.offset(5, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel().getBlockEntity(plaquePos);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Runnable use = () -> {
            ItemStack plan = PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.MARKET);
            player.setItemInHand(InteractionHand.MAIN_HAND, plan);
            helper.getLevel().getBlockState(plaquePos).useItemOn(plan, helper.getLevel(), player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(plaquePos), Direction.SOUTH, plaquePos, false));
        };
        use.run();
        helper.assertTrue(plaque.state() == PlaqueState.EMPTY
                && CommonsEffects.planSource(BuildingType.MARKET) != null,
            "before Tavern & Trade the plaque refuses the Market plan and names the node, got " + plaque.state());
        state.unlock(DevelopmentNode.HOSPITALITY);
        use.run();
        helper.assertTrue(plaque.state() != PlaqueState.EMPTY,
            "after Tavern & Trade the plaque takes the Market plan");
        helper.succeedWhen(() -> {
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
                "the Market room validates, got " + plaque.state());
        });
    }

    private static Building building(BuildingType type, BlockPos anchor) {
        Building b = new Building(UUID.randomUUID(), type, anchor.above(), anchor,
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(anchor, anchor.offset(2, 2, 2)));
        b.valid = true;
        return b;
    }

    // ------------------------------------------------------------ fixtures

    private static void founding(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.HOME);
        state.unlock(DevelopmentNode.HOSPITALITY);
    }

    private static void village(DevelopmentState state) {
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName("Commons " + x);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        return settler;
    }

    private static void fund(HearthBlockEntity hearth, TechNodeDef def) {
        for (DevelopmentNode.Cost cost : TechCosts.costs(def)) {
            put(hearth, cost.item(), cost.count());
        }
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Commons " + name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
            if (existing.is(item) && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
