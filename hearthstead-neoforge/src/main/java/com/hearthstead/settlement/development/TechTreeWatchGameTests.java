package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.entity.combat.role.RoleHiring;
import com.hearthstead.entity.combat.role.RuneSpell;
import com.hearthstead.gametest.BuilderTestKit;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.gear.GearTier;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.techtree.effects.WatchEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tech tree v3, Watch &amp; Defense branch (batch {@code techtree_watch}): one
 * test per node the branch lane made real. Each asserts the hook value is
 * the old one before the node is learned and the new one after, for this
 * settlement only (a second settlement in the same world keeps the base).
 * Claim nodes also prove the pre-v3 save migration (grandfatheredBy).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeWatchGameTests {

    public TechTreeWatchGameTests() {
    }

    // ---------------------------------------------------- commanders_horn ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void commandersHornCarriesFurtherHoldsLongerAndRallies(GameTestHelper helper) {
        WatchEffects.resetForTests();
        Fixture f = fixture(helper, "Horn");
        Fixture other = fixture(helper, "Horn Neighbour", new BlockPos(12, 1, 12));
        SettlerEntity guard = settler(helper, f, new BlockPos(6, 1, 6), "Horn Guard", Profession.GUARD);
        var level = helper.getLevel();
        double before = WatchEffects.orderEarshot(level, f.settlement, FieldOrderRules.EARSHOT);
        long graceBefore = WatchEffects.orderRaidGraceTicks(level, f.settlement,
            FieldOrderRules.AFTER_RAID_GRACE_TICKS);
        int ralliedBefore = WatchEffects.hornRally(level, f.settlement, null, List.of(guard));
        helper.assertTrue(before == 48.0D && graceBefore == 600L && ralliedBefore == 0
                && !WatchEffects.rallied(guard),
            "without the node: 48 blocks, 30 s grace, no rally (" + before + "/" + graceBefore + ")");

        Development.of(level, f.settlement).learnTech("commanders_horn");
        double after = WatchEffects.orderEarshot(level, f.settlement, FieldOrderRules.EARSHOT);
        long graceAfter = WatchEffects.orderRaidGraceTicks(level, f.settlement,
            FieldOrderRules.AFTER_RAID_GRACE_TICKS);
        helper.assertTrue(after == 80.0D && graceAfter == 1200L,
            "with the horn: 80 blocks and 60 s grace, got " + after + "/" + graceAfter);
        helper.assertTrue(FieldOrderRules.withinEarshot(70.0D * 70.0D, after)
                && !FieldOrderRules.withinEarshot(70.0D * 70.0D, before),
            "a soldier 70 blocks away hears the horn but not the bare voice");
        helper.assertTrue(WatchEffects.orderEarshot(level, other.settlement, FieldOrderRules.EARSHOT) == 48.0D,
            "the bonus is per settlement: the neighbour keeps 48 blocks");

        // The order lifetime with that grace: a raid order outlives the raid by 60 s.
        FieldOrderRules.Lifetime plain = new FieldOrderRules.Lifetime(0L, true);
        FieldOrderRules.Lifetime horn = new FieldOrderRules.Lifetime(0L, true).raidGrace(graceAfter);
        plain.update(100L, false);
        horn.update(100L, false);
        helper.assertTrue(plain.update(100L + 600L, false) && !horn.update(100L + 600L, false)
                && horn.update(100L + 1200L, false),
            "a horn order holds 60 s after the raid, a plain one 30 s");

        // The rally: +10% move speed on every listener, gone after 10 s.
        double baseSpeed = guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        int rallied = WatchEffects.hornRally(level, f.settlement, null, List.of(guard));
        double rallySpeed = guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        helper.assertTrue(rallied == 1 && WatchEffects.rallied(guard)
                && Math.abs(rallySpeed - baseSpeed * 1.1D) < 1.0E-6D,
            "one listener rallied to +10% speed: " + baseSpeed + " -> " + rallySpeed);
        WatchEffects.expireRally(guard, level.getGameTime() + WatchEffects.HORN_RALLY_TICKS - 1);
        helper.assertTrue(WatchEffects.rallied(guard), "the rally lasts the full 10 s");
        WatchEffects.expireRally(guard, level.getGameTime() + WatchEffects.HORN_RALLY_TICKS);
        helper.assertTrue(!WatchEffects.rallied(guard), "and ends after 10 s");
        helper.succeed();
    }

    // --------------------------------------------------------- barricades ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 100)
    public void barricadesDoubleTheLineAndCallEveryBuilder(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 4);
        var level = arena.level();
        Settlement s = arena.settlement();
        BlockPos a = helper.absolutePos(new BlockPos(2, 1, 12));
        BlockPos b = helper.absolutePos(new BlockPos(13, 1, 12));
        BuildPlanner.Plan shortPlan = BuildPlanner.planLine(level, s, a, b, BuildPlanner.BARRICADE, false, 0, null);
        helper.assertTrue(shortPlan.job() != null && columns(shortPlan.job()) == 5,
            "without the node a barricade line stops at 5 columns, got "
                + (shortPlan.job() == null ? shortPlan.validation().reasonKey() : columns(shortPlan.job())));

        // Raid warned + a rushed barricade nobody works: still no call without the node.
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        helper.assertTrue(s.raidLifecycle.initializeAtFounding(0L, 4, 2)
                && s.raidLifecycle.queueFirstPlan(plan) && BuildJobs.raidWarned(level, s),
            "fixture: the first raid is warned");
        BuildJob job = shortPlan.job();
        helper.assertTrue(BuildJobs.commit(level, s, job) == null, "fixture: the barricade is queued");
        job.rush = true;
        helper.assertTrue(!WatchEffects.barricadeCallsBuilders(level, s),
            "without the node Builders finish their own work first");

        Development.of(level, s).learnTech("barricades");
        // A second row: the queued 5-block barricade already owns row 12.
        BlockPos a2 = helper.absolutePos(new BlockPos(2, 1, 14));
        BlockPos b2 = helper.absolutePos(new BlockPos(13, 1, 14));
        BuildPlanner.Plan longPlan = BuildPlanner.planLine(level, s, a2, b2, BuildPlanner.BARRICADE, false, 0, null);
        helper.assertTrue(longPlan.job() != null && columns(longPlan.job()) == 10,
            "with Barricades the line reaches 10 columns, got "
                + (longPlan.job() == null ? longPlan.validation().reasonKey() : columns(longPlan.job())));
        helper.assertTrue(WatchEffects.barricadeCallsBuilders(level, s),
            "with Barricades a waiting rushed barricade calls every Builder off other work");
        job.claimant = UUID.randomUUID();
        job.leaseUntil = level.getGameTime() + 1000L;
        helper.assertTrue(!WatchEffects.barricadeCallsBuilders(level, s),
            "a barricade another Builder already works calls nobody else");
        helper.succeed();
    }

    private static int columns(BuildJob job) {
        Set<Long> cols = new HashSet<>();
        for (int i = 0; i < job.size(); i++) {
            BlockPos p = job.pos(i);
            cols.add(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL));
        }
        return cols.size();
    }

    // ---------------------------------------------------- shield_doctrine ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void shieldWallCutsFrontalDamageOnTheLine(GameTestHelper helper) {
        Fixture f = fixture(helper, "Shield");
        var level = helper.getLevel();
        SettlerEntity guard = settler(helper, f, new BlockPos(7, 1, 7), "Shield Guard", Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        guard.setYRot(0.0F);
        guard.setYBodyRot(0.0F);
        guard.setYHeadRot(0.0F);
        // Yaw 0 faces +Z: one raider in front, one behind.
        RaiderEntity front = raider(helper, f, new BlockPos(7, 1, 10));
        RaiderEntity back = raider(helper, f, new BlockPos(7, 1, 4));
        helper.assertTrue(guard.hasPhysicalOffhandShield(), "fixture: the guard carries a shield");
        helper.assertTrue(WatchEffects.shieldLineDamage(level, guard, front, 10.0F, true) == 10.0F,
            "without the node a braced guard takes full damage");

        DevelopmentState state = Development.of(level, f.settlement);
        village(state);
        state.learnDoctrine(DevelopmentNode.SHIELD_DOCTRINE, 0L);
        float frontal = WatchEffects.shieldLineDamage(level, guard, front, 10.0F, true);
        float flank = WatchEffects.shieldLineDamage(level, guard, back, 10.0F, true);
        float loose = WatchEffects.shieldLineDamage(level, guard, front, 10.0F, false);
        helper.assertTrue(Math.abs(frontal - 7.0F) < 1.0E-4F && flank == 10.0F && loose == 10.0F,
            "Shield Wall: -30% only from the front and only on the line, got "
                + frontal + "/" + flank + "/" + loose);
        guard.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        helper.assertTrue(WatchEffects.shieldLineDamage(level, guard, front, 10.0F, true) == 10.0F,
            "no shield, no shield wall");
        helper.assertTrue(DevelopmentBonuses.guardCombatXp(level, f.settlement, 100) == 125,
            "Shield Wall keeps its +25% guard XP");
        helper.succeed();
    }

    // ---------------------------------------------- spearmen / longswords ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void spearmenAndLongswordsClaimTheirHallsAndEmblems(GameTestHelper helper) {
        Fixture f = fixture(helper, "Halls");
        var level = helper.getLevel();
        DevelopmentState state = Development.of(level, f.settlement);
        village(state);
        state.learnDoctrine(DevelopmentNode.SHIELD_DOCTRINE, 0L);
        helper.assertTrue(!Development.isBuildingUnlocked(level, f.settlement, BuildingType.PIKE_YARD)
                && !Development.isEmblemUnlocked(level, f.settlement, Profession.SPEARMAN)
                && !Development.isBuildingUnlocked(level, f.settlement, BuildingType.SWORD_HALL)
                && !Development.isEmblemUnlocked(level, f.settlement, Profession.LONGSWORDSMAN),
            "in a v3 world Shield Wall alone no longer opens the Pike Yard or Sword Hall");
        state.learnTech("spearmen");
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.PIKE_YARD)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.SPEARMAN)
                && !Development.isBuildingUnlocked(level, f.settlement, BuildingType.SWORD_HALL),
            "Spearmen opens the Pike Yard and the Spearman emblem, nothing more");
        state.learnTech("longswords");
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.SWORD_HALL)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.LONGSWORDSMAN),
            "Longswords opens the Sword Hall and the Longswordsman emblem");

        // Old save: it owned Shield Doctrine before the v3 tree existed.
        DevelopmentState old = new DevelopmentState();
        village(old);
        old.learnDoctrine(DevelopmentNode.SHIELD_DOCTRINE, 0L);
        CompoundTag tag = old.writeNbt();
        tag.remove("TechNodes");
        tag.remove("TechGrandfathered");
        DevelopmentState loaded = DevelopmentState.readNbt(tag);
        helper.assertTrue(!loaded.quarantined() && loaded.hasTech("spearmen") && loaded.hasTech("longswords"),
            "a pre-v3 save with Shield Doctrine keeps its Pike Yard and Sword Hall (grandfathered)");
        DevelopmentState fresh = DevelopmentState.readNbt(loaded.writeNbt());
        helper.assertTrue(fresh.hasTech("spearmen") && !fresh.hasTech("rune_mage"),
            "the grant is stored once and never spreads to other nodes");
        helper.succeed();
    }

    // ------------------------------------------------ rune_mage/high_runes ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void runeMageClaimsTheRuneHallWithOneMage(GameTestHelper helper) {
        Fixture f = fixture(helper, "Runes");
        var level = helper.getLevel();
        DevelopmentState state = Development.of(level, f.settlement);
        village(state);
        state.learnDoctrine(DevelopmentNode.HEARTH_DOCTRINE, 0L);
        helper.assertTrue(!Development.isBuildingUnlocked(level, f.settlement, BuildingType.RUNE_HALL)
                && !Development.isEmblemUnlocked(level, f.settlement, Profession.RUNE_MAGE),
            "in a v3 world the Scholar doctrine alone no longer opens the Rune Hall");
        state.learnTech("rune_mage");
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.RUNE_HALL)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.RUNE_MAGE)
                && RoleHiring.mageCap(level, f.settlement) == 1,
            "Rune Mage opens the Rune Hall and the emblem; one mage");

        DevelopmentState old = new DevelopmentState();
        village(old);
        old.learnDoctrine(DevelopmentNode.HEARTH_DOCTRINE, 0L);
        CompoundTag tag = old.writeNbt();
        tag.remove("TechNodes");
        tag.remove("TechGrandfathered");
        DevelopmentState loaded = DevelopmentState.readNbt(tag);
        helper.assertTrue(!loaded.quarantined() && loaded.hasTech("rune_mage") && !loaded.hasTech("spearmen"),
            "a pre-v3 save with the Scholar doctrine keeps its Rune Hall (grandfathered)");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void highRunesAllowASecondMageAndLongerWards(GameTestHelper helper) {
        Fixture f = fixture(helper, "High Runes");
        var level = helper.getLevel();
        for (int i = 0; i < 25; i++) {
            f.settlement.putRecord(UUID.randomUUID(), "Villager " + i, Profession.NONE);
        }
        helper.assertTrue(RoleHiring.mageCap(level, f.settlement) == 1
                && WatchEffects.wardTicks(level, f.settlement, RuneSpell.WARD_DURATION_TICKS,
                    RuneSpell.WARD_DURATION_TICKS_HIGH_RUNES) == 100,
            "without High Runes: one mage even in a 25-settler town, 5 s wards");
        Development.of(level, f.settlement).learnTech("high_runes");
        helper.assertTrue(RoleHiring.mageCap(level, f.settlement) == 2
                && WatchEffects.wardTicks(level, f.settlement, RuneSpell.WARD_DURATION_TICKS,
                    RuneSpell.WARD_DURATION_TICKS_HIGH_RUNES) == 120,
            "High Runes: two mages, 6 s wards");
        helper.succeed();
    }

    // ---------------------------------------------------------- crossbows ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void crossbowsDrawSlowerAndHitHarder(GameTestHelper helper) {
        Fixture f = fixture(helper, "Bolts");
        var level = helper.getLevel();
        helper.assertTrue(WatchEffects.archerDrawTicks(level, f.settlement, 20) == 20
                && WatchEffects.archerDamageScale(level, f.settlement) == 1.0D,
            "without Crossbows: the bow's 20-tick draw and normal damage");
        Development.of(level, f.settlement).learnTech("crossbows");
        helper.assertTrue(WatchEffects.archerDrawTicks(level, f.settlement, 20) == 30
                && Math.abs(WatchEffects.archerDamageScale(level, f.settlement) - 1.4D) < 1.0E-9D
                && WatchEffects.crossbows(level, f.settlement),
            "Crossbows: 30-tick draw, x1.4 damage");
        helper.succeed();
    }

    // --------------------------------------------------------- watchfires ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void watchfiresSpeedUpTheShelterRun(GameTestHelper helper) {
        Fixture f = fixture(helper, "Fires");
        SettlerEntity farmer = settler(helper, f, new BlockPos(6, 1, 6), "Runner", Profession.FARMER);
        helper.assertTrue(WatchEffects.shelterSpeed(farmer, 1.25D) == 1.25D,
            "without Watchfires the shelter run is x1.25");
        Development.of(helper.getLevel(), f.settlement).learnTech("watchfires");
        helper.assertTrue(Math.abs(WatchEffects.shelterSpeed(farmer, 1.25D) - 1.5625D) < 1.0E-9D,
            "Watchfires: 25% faster, got " + WatchEffects.shelterSpeed(farmer, 1.25D));
        helper.succeed();
    }

    // ------------------------------------------- palisade / stone_walls ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void palisadeDoublesAndStoneWallsTripleTheBlows(GameTestHelper helper) {
        Fixture f = fixture(helper, "Walls");
        var level = helper.getLevel();
        BlockPos palisade = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 3));
        BlockPos loose = helper.absolutePos(new BlockPos(1, 1, 5));
        defense(level, f.settlement, BuildPlanner.PALISADE, palisade);
        defense(level, f.settlement, BuildPlanner.STONE, stone);
        helper.assertTrue(WatchEffects.wallHits(level, f.settlement, palisade, 6) == 6
                && WatchEffects.wallHits(level, f.settlement, stone, 6) == 6,
            "without the nodes every wall block takes 6 blows");
        Development.of(level, f.settlement).learnTech("palisade");
        helper.assertTrue(WatchEffects.wallHits(level, f.settlement, palisade, 6) == 12
                && WatchEffects.wallHits(level, f.settlement, stone, 6) == 6
                && WatchEffects.wallHits(level, f.settlement, loose, 6) == 6,
            "Palisade: 12 blows on palisade segments only");
        Development.of(level, f.settlement).learnTech("stone_walls");
        helper.assertTrue(WatchEffects.wallHits(level, f.settlement, stone, 6) == 18
                && WatchEffects.wallHits(level, f.settlement, palisade, 6) == 12
                && WatchEffects.wallHits(level, f.settlement, loose, 6) == 6,
            "Stone Walls: 18 blows on stone segments");
        helper.succeed();
    }

    // --------------------------------------------------------- earthworks ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void earthworksSlowRaidersNearTheLine(GameTestHelper helper) {
        WatchEffects.resetForTests();
        Fixture f = fixture(helper, "Ditch");
        var level = helper.getLevel();
        helper.setBlock(new BlockPos(8, 1, 8), net.minecraft.world.level.block.Blocks.SPRUCE_LOG);
        defense(level, f.settlement, BuildPlanner.PALISADE, helper.absolutePos(new BlockPos(8, 1, 8)));
        RaiderEntity near = raider(helper, f, new BlockPos(8, 1, 10));
        RaiderEntity far = raider(helper, f, new BlockPos(8, 1, 14));
        helper.assertTrue(!WatchEffects.earthworksPulse(near)
                && !near.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
            "without Ditch & Stakes raiders at the line are not slowed");
        Development.of(level, f.settlement).learnTech("earthworks");
        helper.assertTrue(WatchEffects.earthworksPulse(near) && !WatchEffects.earthworksPulse(far),
            "Ditch & Stakes: the raider 2 blocks from the line is slowed, the one 6 away is not");
        MobEffectInstance slow = near.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        helper.assertTrue(slow != null && slow.getAmplifier() == WatchEffects.EARTHWORKS_AMPLIFIER
                && !far.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
            "Slowness II on the near raider only");
        // The line is mined away: the Builder's record stays, but nothing is slowed.
        helper.setBlock(new BlockPos(8, 1, 8), net.minecraft.world.level.block.Blocks.AIR);
        near.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        helper.assertTrue(!WatchEffects.earthworksPulse(near) && !near.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
            "a removed defense block no longer slows raiders");
        helper.succeed();
    }

    // ------------------------------------------------- veteran_techniques ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void veteranTechniquesSharpenTheMoveset(GameTestHelper helper) {
        Fixture f = fixture(helper, "Veterans");
        SettlerEntity guard = settler(helper, f, new BlockPos(6, 1, 6), "Veteran", Profession.GUARD);
        helper.assertTrue(WatchEffects.shieldBashCooldown(guard, 80) == 80
                && WatchEffects.comboWindow(guard, 6) == 6
                && WatchEffects.guardMovePower(guard, GuardMove.HEAVY) == 1.0D,
            "without the node: 80-tick bash, 6-tick window, plain heavy");
        Development.of(helper.getLevel(), f.settlement).learnTech("veteran_techniques");
        helper.assertTrue(WatchEffects.shieldBashCooldown(guard, 80) == 56
                && WatchEffects.comboWindow(guard, 6) == 10
                && Math.abs(WatchEffects.guardMovePower(guard, GuardMove.HEAVY) - 1.3D) < 1.0E-9D
                && WatchEffects.guardMovePower(guard, GuardMove.LIGHT_A) == 1.0D,
            "Veteran Techniques: 56-tick bash, 10-tick window, heavy x1.3 (lights unchanged)");
        helper.succeed();
    }

    // ----------------------------------------------------- master_armoury ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void masterArmouryOpensDiamondWithTheCastleCharter(GameTestHelper helper) {
        Fixture f = fixture(helper, "Armoury");
        var level = helper.getLevel();
        DevelopmentState state = Development.of(level, f.settlement);
        state.learnTech("castle_charter");
        int diamond = 1 << GearTier.DIAMOND.level();
        int netherite = 1 << GearTier.NETHERITE.level();
        helper.assertTrue((GearGate.knowledgeMask(level, f.settlement) & diamond) == 0,
            "the Castle Charter alone does not open diamond");
        state.learnTech("master_armoury");
        int mask = GearGate.knowledgeMask(level, f.settlement);
        helper.assertTrue((mask & diamond) != 0 && (mask & netherite) == 0,
            "Master Armoury + Castle Charter: diamond, not yet netherite (mask " + mask + ")");
        state.learnTech("kingdom_crown");
        helper.assertTrue((GearGate.knowledgeMask(level, f.settlement) & netherite) != 0,
            "Master Armoury + Kingdom Crown: netherite");
        helper.succeed();
    }

    // ------------------------------------------------------------ knights ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void knightsHitHarderAndStandFirm(GameTestHelper helper) {
        Fixture f = fixture(helper, "Knights");
        SettlerEntity sergeant = settler(helper, f, new BlockPos(5, 1, 5), "Sergeant", Profession.GUARD);
        sergeant.attributes().pinForTest(Attribute.STRENGTH, 60);
        SettlerEntity recruit = settler(helper, f, new BlockPos(9, 1, 9), "Recruit", Profession.GUARD);
        recruit.attributes().pinForTest(Attribute.STRENGTH, 0);
        helper.assertTrue(WatchEffects.guardMovePower(sergeant, GuardMove.LIGHT_A) == 1.0D
                && !WatchEffects.resistsKnockback(sergeant, false)
                && knockedBack(sergeant),
            "without the Order a sergeant hits normally and is knocked back");
        Development.of(helper.getLevel(), f.settlement).learnTech("knights");
        helper.assertTrue(Math.abs(WatchEffects.guardMovePower(sergeant, GuardMove.LIGHT_A) - 1.25D) < 1.0E-9D
                && WatchEffects.resistsKnockback(sergeant, false)
                && !knockedBack(sergeant),
            "Order of Knights: a sergeant deals +25% and cannot be knocked back");
        helper.assertTrue(WatchEffects.guardMovePower(recruit, GuardMove.LIGHT_A) == 1.0D
                && knockedBack(recruit),
            "a recruit is not a knight yet");
        helper.succeed();
    }

    // -------------------------------------------------------- pike_square ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void pikeSquareBracedSpearsStrikeHarderAndHold(GameTestHelper helper) {
        Fixture f = fixture(helper, "Pikes");
        SettlerEntity spear = settler(helper, f, new BlockPos(6, 1, 6), "Pike", Profession.SPEARMAN);
        helper.assertTrue(WatchEffects.braceStrikeShare(spear) == 1.0D
                && !WatchEffects.resistsKnockback(spear, true),
            "without Pike Square: normal brace strike, knockback applies");
        Development.of(helper.getLevel(), f.settlement).learnTech("pike_square");
        helper.assertTrue(WatchEffects.braceStrikeShare(spear) == 1.5D
                && WatchEffects.resistsKnockback(spear, true)
                && !WatchEffects.resistsKnockback(spear, false),
            "Pike Square: brace strike x1.5, no knockback while braced (only while braced)");
        helper.succeed();
    }

    // ------------------------------------------------- captains_commission ---

    @GameTest(template = "empty16", batch = "techtree_watch", timeoutTicks = 60)
    public void captainsCommissionMakesTheTopSergeantTheHero(GameTestHelper helper) {
        Fixture f = fixture(helper, "Commission");
        var level = helper.getLevel();
        com.hearthstead.entity.combat.captain.CaptainConfig.overrideEnabledForTests(null);
        SettlerEntity sergeant = settler(helper, f, new BlockPos(6, 1, 6), "Aldric", Profession.GUARD);
        sergeant.attributes().pinForTest(Attribute.STRENGTH, 60);
        com.hearthstead.entity.combat.captain.CaptainStatus.invalidate(f.settlement.id);
        helper.assertTrue(!com.hearthstead.entity.combat.captain.CaptainStatus.commissioned(level, f.settlement)
                && !com.hearthstead.entity.combat.captain.CaptainStatus.isHero(sergeant),
            "without the Commission the top sergeant is not the hero Captain");
        Development.of(level, f.settlement).learnTech("captains_commission");
        com.hearthstead.entity.combat.captain.CaptainStatus.invalidate(f.settlement.id);
        helper.assertTrue(com.hearthstead.entity.combat.captain.CaptainStatus.commissioned(level, f.settlement)
                && com.hearthstead.entity.combat.captain.CaptainStatus.isHero(sergeant),
            "with the Commission the top sergeant is the hero Captain");
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    private static boolean knockedBack(SettlerEntity settler) {
        settler.setDeltaMovement(Vec3.ZERO);
        settler.knockback(0.8D, 1.0D, 0.0D);
        boolean moved = settler.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6D;
        settler.setDeltaMovement(Vec3.ZERO);
        return moved;
    }

    private static void defense(net.minecraft.server.level.ServerLevel level, Settlement settlement,
                                String segment, BlockPos pos) {
        BuildSiteSavedData.get(level).recordDefense(settlement.id, new BuildSiteSavedData.DefenseWork(
            UUID.randomUUID(), segment, new long[] {pos.asLong()}, List.of()));
    }

    private static RaiderEntity raider(GameTestHelper helper, Fixture f, BlockPos at) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), at);
        raider.setNoAi(true);
        raider.assign(UUID.randomUUID(), f.settlement.id, RaidObjective.KORN, 1.0F, false);
        return raider;
    }

    private static SettlerEntity settler(GameTestHelper helper, Fixture f, BlockPos at, String name,
                                         Profession profession) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), at);
        settler.setNoAi(true);
        settler.bindTo(f.settlement.id, f.settlement.center);
        settler.setProfessionProjection(profession);
        f.settlement.putRecord(settler.getUUID(), name, profession);
        return settler;
    }

    private static void village(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        return fixture(helper, name, new BlockPos(3, 1, 3));
    }

    private static Fixture fixture(GameTestHelper helper, String name, BlockPos relative) {
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
