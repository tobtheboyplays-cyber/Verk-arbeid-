package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.CaptainRallyRules;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidPressure;
import com.hearthstead.settlement.raid.RaidTelegraph;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE A3 step 3 — the raider itself.
 *
 * <p>The thing these guard against is the failure both references shipped:
 * raiders that are indistinguishable from each other and from the player's
 * own guards, scaled from the player's stat sheet rather than from anything
 * the enemy has done.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Raidholm",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * A captain is a different creature, not a bigger health bar: more
     * health AND more damage, and the renderer keys its helm, pauldron and
     * scale off the same flag, so you can read who leads a raid from across
     * the field.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_captains_are_visibly_and_mechanically_distinct")
    public void captainsAreVisiblyAndMechanicallyDistinct(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity grunt = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        UUID captainId = UUID.randomUUID();
        grunt.assign(captainId, s.id, RaidObjective.KORN, 1.0F, false);
        captain.assign(captainId, s.id, RaidObjective.KORN, 1.0F, true);

        helper.assertTrue(!grunt.isCaptain(), "the follower is not the captain");
        helper.assertTrue(captain.isCaptain(), "the captain is");
        helper.assertTrue(captain.getMaxHealth() > grunt.getMaxHealth(),
            "a captain must be harder to kill: " + captain.getMaxHealth()
                + " vs " + grunt.getMaxHealth());
        double captainDamage = captain.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double gruntDamage = grunt.getAttributeValue(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(captainDamage > gruntDamage,
            "and must hit harder: " + captainDamage + " vs " + gruntDamage);
        CompoundTag channeling = new CompoundTag();
        captain.addAdditionalSaveData(channeling);
        channeling.putByte("RallyPhase",
            (byte) RaiderEntity.RallyPhase.CHANNELING.ordinal());
        channeling.putLong("RallyDeadline",
            helper.getLevel().getGameTime() + RaiderEntity.RALLY_CHANNEL_TICKS);
        captain.readAdditionalSaveData(channeling);
        captain.hurt(helper.getLevel().damageSources().fall(), 1.0F);
        helper.assertTrue(captain.rallyPhase()
                == RaiderEntity.RallyPhase.CHANNELING,
            "environmental damage must not interrupt captain authority");
        captain.invulnerableTime = 0;
        SettlerEntity defender = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 3));
        defender.bindTo(s.id, s.center);
        s.putRecord(defender.getUUID(), "Rallybreaker", Profession.NONE);
        defender.assignProfession(Profession.GUARD);
        captain.hurt(helper.getLevel().damageSources().mobAttack(defender),
            1.0F);
        helper.assertTrue(captain.rallyPhase() == RaiderEntity.RallyPhase.EXPOSED,
            "an authoritative settlement defender hit must interrupt the channel");
        CompoundTag interrupted = new CompoundTag();
        captain.addAdditionalSaveData(interrupted);
        RaiderEntity reloaded = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(4, 1, 2));
        reloaded.readAdditionalSaveData(interrupted);
        helper.assertTrue(reloaded.rallyPhase()
                == RaiderEntity.RallyPhase.EXPOSED,
            "interrupted rally phase and deadline must survive reload");
        helper.assertTrue(reloaded.rallyDeadline()
                == interrupted.getLong("RallyDeadline"),
            "rally deadline must survive reload exactly");
        interrupted.putBoolean("RallyFinalStandUsed", true);
        RaiderEntity finalReload = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(5, 1, 2));
        finalReload.readAdditionalSaveData(interrupted);
        helper.assertTrue(finalReload.rallyFinalStandUsed()
                && !CaptainRallyRules.mayStartFinalStand(
                    finalReload.rallyFinalStandUsed(), true),
            "consumed final stand must survive reload and remain one-shot");
        helper.succeed();
    }

    /**
     * The first-raid counter lesson only works when the targets themselves
     * read differently: the Archer gets a fast, fragile Skirmisher and the
     * Guard gets a slow, heavy Brute. Damage remains equal so the silhouette
     * never hides an unexplained damage spike.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "raider_variants_have_distinct_honest_combat_profiles")
    public void variantsHaveDistinctHonestCombatProfiles(
            GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        UUID captainId = UUID.randomUUID();
        RaiderEntity skirmisher = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(2, 1, 2));
        skirmisher.setVariant(RaiderEntity.Variant.SKIRMISHER);
        skirmisher.assign(captainId, s.id, RaidObjective.BLOD, 1.0F, false);
        RaiderEntity brute = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(3, 1, 2));
        brute.setVariant(RaiderEntity.Variant.BRUTE);
        brute.assign(captainId, s.id, RaidObjective.BLOD, 1.0F, false);

        helper.assertTrue(skirmisher.getMaxHealth()
                == RaiderEntity.SKIRMISHER_MAX_HEALTH
                && brute.getMaxHealth() == RaiderEntity.BRUTE_MAX_HEALTH
                && brute.getMaxHealth() > skirmisher.getMaxHealth(),
            "Brute must be the visibly durable target, got "
                + brute.getMaxHealth() + " vs " + skirmisher.getMaxHealth());
        double skirmisherSpeed = skirmisher.getAttributeValue(
            Attributes.MOVEMENT_SPEED);
        double bruteSpeed = brute.getAttributeValue(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(skirmisherSpeed > bruteSpeed,
            "Skirmisher must be the fast target, got " + skirmisherSpeed
                + " vs " + bruteSpeed);
        helper.assertTrue(brute.getAttributeValue(
                Attributes.KNOCKBACK_RESISTANCE)
                > skirmisher.getAttributeValue(
                    Attributes.KNOCKBACK_RESISTANCE),
            "Brute must resist knockback more than the pack");
        helper.assertTrue(brute.getAttributeValue(Attributes.ATTACK_DAMAGE)
                == skirmisher.getAttributeValue(Attributes.ATTACK_DAMAGE),
            "variant identity must not hide an unexplained damage spike");
        helper.succeed();
    }

    /**
     * Strength comes from the captain's own record, not from the player's
     * stat sheet -- and it is capped, so a long feud stays winnable rather
     * than becoming a wall.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_menace_scales_strength_and_is_capped")
    public void menaceScalesStrengthAndIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity mild = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity feared = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        RaiderEntity absurd = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 2));
        UUID captainId = UUID.randomUUID();
        mild.assign(captainId, s.id, RaidObjective.BLOD, 1.0F, false);
        feared.assign(captainId, s.id, RaidObjective.BLOD, 2.0F, false);
        absurd.assign(captainId, s.id, RaidObjective.BLOD, 99.0F, false);

        helper.assertTrue(feared.getMaxHealth() > mild.getMaxHealth(),
            "menace must scale health, got " + feared.getMaxHealth()
                + " vs " + mild.getMaxHealth());
        helper.assertTrue(absurd.menace() <= RaiderEntity.MAX_MENACE,
            "menace must be capped, got " + absurd.menace());
        helper.assertTrue(absurd.getMaxHealth()
                <= mild.getMaxHealth() * RaiderEntity.MAX_MENACE + 0.01F,
            "and so must the health it buys, got " + absurd.getMaxHealth());
        helper.succeed();
    }

    /**
     * The charge flag the RENDERER reads must actually track the target.
     *
     * <p>Guards a bug that made SPRINT unreachable by construction:
     * {@code RaiderModel} decided its gait from {@code entity.getTarget()},
     * but vanilla's {@code Mob.target} is server-only AI state and is never
     * networked, so on the client render copy it was always null. Every
     * skirmisher played STALK forever -- confirmed on film, creeping at
     * walking pace through a kill. {@code RaiderEntity} now publishes
     * {@link RaiderEntity#isCharging()} as synced data instead.
     *
     * <p>This is a server-side test of a client-side symptom, and that is
     * the honest limit of what a GameTest can prove here: it cannot see the
     * rendered gait. What it CAN pin is the fact the renderer depends on --
     * that the published boolean follows the target both ways. If this flag
     * stops tracking, SPRINT silently dies again exactly as it did before,
     * and no visual test would have to be running to catch it.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_the_charge_flag_tracks_a_live_target_both_ways")
    public void theChargeFlagTracksALiveTargetBothWays(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        SettlerEntity quarry = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 2));
        quarry.setSettlerName("Quarry");
        quarry.bindTo(s.id, s.center);

        // This test owns target state directly. Without the isolation,
        // vanilla's target selector can legitimately reacquire the still-live
        // quarry in the same tick after setTarget(null), making a sync test
        // depend on unrelated combat-goal timing.
        raider.setNoAi(true);

        helper.assertTrue(!raider.isCharging(),
            "a raider with no target must not report charging");

        raider.setTarget(quarry);
        // One tick: the flag is published from tick(), not from setTarget().
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(raider.isCharging(),
                "a raider closing on a live settler must report charging -- this is "
                    + "the only thing the client can see, and SPRINT plays off it");
            raider.setTarget(null);
            helper.runAfterDelay(1, () -> {
                helper.assertTrue(!raider.isCharging(),
                    "a raider that has lost its target must stop reporting charging, "
                        + "or it sprints on forever at nothing");
                helper.succeed();
            });
        });
    }

    /** Raiders never turn on each other, however the melee goes. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_raiders_do_not_fight_each_other")
    public void raidersDoNotFightEachOther(GameTestHelper helper) {
        buildArena(helper, 10);
        RaiderEntity a = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity b = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        helper.assertTrue(!a.canAttack(b), "a raider must not target another raider");
        helper.succeed();
    }

    /**
     * A raid that despawns is a raid that never happened. Raiders are
     * persistent, and their orders survive a save.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_raiders_persist_and_remember_their_orders")
    public void raidersPersistAndRememberTheirOrders(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaidCaptain remembered = RaidDirector.pickCaptain(s,
            helper.getLevel().getRandom());
        UUID captainId = remembered.id();
        raider.assign(captainId, s.id, RaidObjective.BRANN, 1.5F, true);
        raider.setCustomName(Component.literal(remembered.name()));
        raider.setCustomNameVisible(true);
        raider.setObjectivePos(helper.absolutePos(new BlockPos(5, 1, 5)));

        helper.assertTrue(!raider.removeWhenFarAway(4096.0),
            "raiders must never despawn for distance");

        double maxHealthBefore = raider.getAttribute(Attributes.MAX_HEALTH)
            .getBaseValue();
        double damageBefore = raider.getAttribute(Attributes.ATTACK_DAMAGE)
            .getBaseValue();
        var tag = raider.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        RaiderEntity reloaded = ModEntities.RAIDER.get().create(helper.getLevel());
        helper.assertTrue(reloaded != null, "the persisted raider must be creatable");
        reloaded.load(tag);

        helper.assertTrue(reloaded.isCaptain(), "captaincy must survive a save");
        helper.assertTrue(reloaded.getCustomName() != null
                && remembered.name().equals(reloaded.getCustomName().getString())
                && reloaded.isCustomNameVisible(),
            "the canonical field identity and visible nameplate must survive a full "
                + "Entity restart, got " + reloaded.getCustomName());
        helper.assertTrue(Math.abs(reloaded.getAttribute(Attributes.MAX_HEALTH)
                    .getBaseValue() - maxHealthBefore) < 0.000001D
                && Math.abs(reloaded.getAttribute(Attributes.ATTACK_DAMAGE)
                    .getBaseValue() - damageBefore) < 0.000001D,
            "reload must restore, not apply captain stats a second time");
        helper.assertTrue(reloaded.objective() == RaidObjective.BRANN,
            "as must the objective, got " + reloaded.objective());
        helper.assertTrue(captainId.equals(reloaded.captainId()),
            "and who they answer to");
        helper.assertTrue(s.id.equals(reloaded.settlementId()),
            "and which settlement they came for");
        helper.assertTrue(reloaded.objectivePos() != null,
            "and where they were headed");
        helper.succeed();
    }

    /**
     * The band forms up on the planned bearing, spread across a front
     * rather than stacked on one point -- MineColonies players report
     * raiders "usually come from the same spawn point" and ganging up on
     * one tower guard (#193), and TekTopia uses four fixed corners.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "raider_the_band_forms_up_on_the_planned_bearing")
    public void theBandFormsUpOnThePlannedBearing(GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        // North is -Z; the geometry must put a 180-degree approach south of
        // the settlement and a 0-degree approach north of it.
        BlockPos north = RaidDirector.formUpAt(center, 0.0F, 30);
        BlockPos south = RaidDirector.formUpAt(center, 180.0F, 30);
        BlockPos east = RaidDirector.formUpAt(center, -90.0F, 30);
        helper.assertTrue(north.getZ() > center.getZ(),
            "0 degrees should form up on +Z, got " + north);
        helper.assertTrue(south.getZ() < center.getZ(),
            "180 degrees should form up on -Z, got " + south);
        helper.assertTrue(east.getX() > center.getX(),
            "-90 degrees should form up on +X, got " + east);
        // And the distance must be honoured, so a band never lands on top of
        // the settlement it came to raid.
        double d = Math.sqrt(north.distSqr(center));
        helper.assertTrue(Math.abs(d - 30.0) < 1.5,
            "form-up distance should be honoured, got " + d);
        helper.succeed();
    }

    /**
     * Fluids have no collision shape, and FloatGoal can keep a raider at a
     * fluid surface instead of letting ground navigation approach the settlement.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "raider_footing_rejects_fluid_and_keeps_dry_ground")
    public void footingRejectsFluidAndKeepsDryGround(GameTestHelper helper) {
        buildArena(helper, 16);
        BlockPos dry = new BlockPos(2, 1, 2);
        BlockPos water = new BlockPos(6, 1, 3);
        BlockPos lava = new BlockPos(10, 1, 3);
        BlockPos headWaterFoot = new BlockPos(14, 1, 3);
        var level = helper.getLevel();
        // The production search extends below this template's one-block floor.
        // Isolate each wet column from any lower dry footing in the flat world;
        // the null expectation is about this complete column, not only its top.
        for (BlockPos feet : java.util.List.of(water, lava, headWaterFoot)) {
            BlockPos absoluteFeet = helper.absolutePos(feet);
            // Clear the entire upward search, including its head cell: the
            // small template does not own preexisting support above its ceiling.
            for (int height = 0; height <= RaidDirector.SPAWN_VERTICAL_SEARCH + 1; height++) {
                level.setBlockAndUpdate(absoluteFeet.above(height), Blocks.AIR.defaultBlockState());
            }
            for (int depth = 1; depth <= RaidDirector.SPAWN_VERTICAL_SEARCH + 1; depth++) {
                BlockPos support = absoluteFeet.below(depth);
                if (support.getY() >= level.getMinBuildHeight()) {
                    level.setBlockAndUpdate(support, Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
        helper.setBlock(water, Blocks.WATER);
        helper.setBlock(lava, Blocks.LAVA);
        helper.setBlock(headWaterFoot.above(), Blocks.WATER);

        BlockPos absoluteDry = helper.absolutePos(dry);
        helper.assertTrue(absoluteDry.equals(RaidDirector.standableNear(level,
                absoluteDry)),
            "ordinary dry ground must remain valid raid footing");
        BlockPos waterResult = RaidDirector.standableNear(level, helper.absolutePos(water));
        helper.assertTrue(waterResult == null,
            "water over a solid column must not count as raid footing; returned=" + waterResult);
        helper.assertTrue(RaidDirector.standableNear(level,
                helper.absolutePos(lava)) == null,
            "lava over a solid floor must not count as raid footing");
        helper.assertTrue(RaidDirector.standableNear(level,
                helper.absolutePos(headWaterFoot)) == null,
            "a dry feet cell with water at head height must not count as raid footing");
        helper.succeed();
    }

    /** A band is a band, never a horde, however rich the settlement gets. */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "raider_band_size_grows_with_worth_but_is_capped")
    public void bandSizeGrowsWithWorthButIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement small = makeSettlement(helper, new BlockPos(5, 1, 5));
        for (int i = 0; i < 4; i++) {
            small.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        Settlement huge = new Settlement(UUID.randomUUID(), "Huge", BlockPos.ZERO);
        for (int i = 0; i < 60; i++) {
            huge.putRecord(UUID.randomUUID(), "H" + i, Profession.NONE);
        }
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        int smallBand = RaidDirector.bandSizeFor(small, captain);
        int hugeBand = RaidDirector.bandSizeFor(huge, captain);
        helper.assertTrue(hugeBand > smallBand,
            "a richer settlement draws a bigger band: " + hugeBand
                + " vs " + smallBand);
        helper.assertTrue(hugeBand <= RaidDirector.MAX_BAND,
            "and it must stay a band, not a horde: " + hugeBand);
        helper.assertTrue(smallBand >= RaidDirector.MIN_BAND,
            "a raid is never one lone figure: " + smallBand);
        helper.succeed();
    }

    /**
     * A raid must actually arrive, and must actually end. A scheduled raid
     * that never concludes is the raid-shaped version of MineColonies'
     * deliveries that silently never happen.
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "raider_a_raid_arrives_and_then_resolves")
    public void aRaidArrivesAndThenResolves(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        var random = level.getRandom();
        RaidCaptain captain = RaidDirector.pickCaptain(s, random);
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 3L);

        var band = RaidDirector.spawnBand(level, s, plan);
        helper.assertTrue(!band.isEmpty(),
            "the band must actually arrive, spawned " + band.size());
        RaidAuthorityFixtures.armActive(s, plan,
            band.stream().map(RaiderEntity::getUUID).toList());
        helper.assertTrue(!RaidDirector.livingRaidersOf(level, s).isEmpty(),
            "and must be findable as this settlement's raiders");
        // Asserted on the band spawnBand actually produced, not on what a
        // bounded box query can see: a raider that forms up 30+ blocks out
        // lands beyond the small region a GameTest force-loads, so the query
        // legitimately cannot see all of them here.
        RaiderEntity fieldCaptain = band.stream()
            .filter(RaiderEntity::isCaptain).findFirst().orElse(null);
        helper.assertTrue(fieldCaptain != null,
            "a band is led, so one of them is the captain [band=" + band.size() + "]");
        helper.assertTrue(fieldCaptain.getCustomName() != null
                && captain.name().equals(fieldCaptain.getCustomName().getString()),
            "the fresh wild/non-Saga field captain must carry the exact canonical "
                + "RaidCaptain name, got " + (fieldCaptain.getCustomName() == null
                    ? "<anonymous>" : fieldCaptain.getCustomName().getString())
                + " vs " + captain.name());
        helper.assertTrue(fieldCaptain.isCustomNameVisible(),
            "the fresh field captain's identity must render, not remain hidden");

        // Not over while anyone still stands.
        helper.assertTrue(!RaidDirector.resolveIfOver(level, s),
            "a raid with raiders left standing is not over");
        helper.assertTrue(s.pendingRaid != null, "so the plan must still be set");

        int pressureBefore = s.raidPressure.pressure();
        int defeatsBefore = captain.defeats();
        for (RaiderEntity r : band) {
            r.discard();
        }
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with none left standing the raid must resolve");
        helper.assertTrue(s.pendingRaid == null,
            "and the plan must be cleared so the next night can be rolled");
        helper.assertTrue(s.raidPressure.pressure() == pressureBefore,
            "holding a raid must leave pressure unchanged. Got "
                + s.raidPressure.pressure() + " from " + pressureBefore);
        helper.assertTrue(captain.defeats() == defeatsBefore + 1,
            "and the captain must remember being driven off");
        RaidLogEntry aftermath = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(captain.name().equals(aftermath.captainName()),
            "the wild/non-Saga field captain and persisted Aftermath must retain "
                + "the same canonical identity, got " + aftermath.captainName());
        helper.succeed();
    }

    /** A recurring serial with an unauditable leader must never spawn. */
    @GameTest(template = "empty16", timeoutTicks = 300,
        batch = "raider_malformed_recurring_captain_identity_fails_closed")
    public void malformedRecurringCaptainIdentityFailsClosed(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));

        // Recurring authority begins only after one definitive first raid.
        var lifecycle = new com.hearthstead.settlement.state.RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID firstParticipant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(firstParticipant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(firstParticipant)
                && lifecycle.completeFirstRaid(false),
            "fixture must complete the authored first raid before recurring authority");
        s.raidLifecycle = lifecycle;

        UUID captainId = UUID.randomUUID();
        var malformedTag = new net.minecraft.nbt.CompoundTag();
        malformedTag.putUUID("Id", captainId);
        malformedTag.putString("Name", "\n");
        malformedTag.putInt("Victories", 0);
        malformedTag.putInt("Defeats", 0);
        s.raidCaptains.add(RaidCaptain.readNbt(malformedTag));
        RaidPlan recurring = new RaidPlan(captainId, RaidObjective.BLOD,
            0.0F, 12L);
        helper.assertTrue(s.recurringRaidRun.queue(recurring),
            "fixture must allocate one exact recurring serial");

        var spawned = RaidDirector.startQueuedRecurringRaid(helper.getLevel(), s);
        helper.assertTrue(spawned.isEmpty()
                && s.recurringRaidRun.isBlocked()
                && s.pendingRaid == null
                && RaidDirector.livingRaidersOf(helper.getLevel(), s).isEmpty(),
            "blank/control leader data must consume and quarantine the serial before "
                + "any entity, arrival broadcast or reward authority exists");
        helper.succeed();
    }

    /**
     * A captain finding the settlement while every follower bearing is void
     * must not turn a queued raid into a sealed one-entity encounter. The
     * partial spawn is rolled back, then the same plan and serial may retry
     * once the terrain can hold a complete band.
     */
    @GameTest(template = "empty16", timeoutTicks = 400,
        batch = "raider_partial_band_rolls_back_and_retries_same_plan")
    public void partialBandRollsBackAndRetriesSamePlan(GameTestHelper helper) {
        var level = helper.getLevel();
        Settlement s = makeSettlement(helper, new BlockPos(8, 200, 8));

        // Recurring authority lets this focused terrain test exercise the
        // same spawn-and-seal transaction without forging the first raid's
        // extensive live Journey-readiness evidence.
        var lifecycle = new com.hearthstead.settlement.state.RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID firstParticipant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(firstParticipant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(firstParticipant)
                && lifecycle.completeFirstRaid(false),
            "fixture must complete the authored first raid before recurring authority");
        s.raidLifecycle = lifecycle;

        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 12L);
        helper.assertTrue(s.recurringRaidRun.queue(plan),
            "fixture must queue one exact recurring plan");
        long serial = s.recurringRaidRun.activeSerial();
        int plannedSize = RaidDirector.bandSizeFor(s, captain);
        helper.assertTrue(plannedSize >= RaidDirector.MIN_BAND,
            "fixture must plan a real band, got " + plannedSize);

        // Only the settlement centre has footing. That is INSIDE the claim,
        // so the captain may no longer form up there (the old last resort);
        // every possible direct follower column (all bearings and all random
        // distances) is verified empty before the transaction starts.
        level.setBlock(s.center.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        helper.assertTrue(RaidDirector.standableNear(level, s.center) != null,
            "fixture must offer footing inside the claim only");
        for (int i = 1; i < plannedSize; i++) {
            float spread = (i / (float) (plannedSize - 1) - 0.5F)
                * 2.0F * RaidDirector.SPAWN_ARC;
            for (int distance = RaidDirector.spawnMinDistance(s.radius);
                 distance <= RaidDirector.spawnMaxDistance(s.radius); distance++) {
                BlockPos follower = RaidDirector.formUpAt(s.center,
                    plan.approachDegrees() + spread, distance);
                helper.assertTrue(RaidDirector.standableNear(level, follower) == null,
                    "fixture follower column unexpectedly has footing at " + follower);
            }
        }

        var rejected = RaidDirector.startQueuedRecurringRaid(level, s);
        helper.assertTrue(rejected.isEmpty()
                && s.recurringRaidRun.isQueued()
                && s.recurringRaidRun.activeSerial() == serial
                && s.recurringRaidRun.plan().orElseThrow().equals(plan)
                && s.pendingRaid == null
                && RaidDirector.livingRaidersOf(level, s).isEmpty(),
            "footing only inside the claim must spawn nobody and retain the "
                + "same queued plan/serial without sealing or announcing");

        // Give every possible random distance on every planned bearing one
        // direct floor, then retry without changing the plan or serial.
        java.util.Set<BlockPos> retryFloors = new java.util.LinkedHashSet<>();
        for (int i = 0; i < plannedSize; i++) {
            float spread = (i / (float) (plannedSize - 1) - 0.5F)
                * 2.0F * RaidDirector.SPAWN_ARC;
            for (int distance = RaidDirector.spawnMinDistance(s.radius);
                 distance <= RaidDirector.spawnMaxDistance(s.radius); distance++) {
                retryFloors.add(RaidDirector.formUpAt(s.center,
                    plan.approachDegrees() + spread, distance).below());
            }
        }
        for (BlockPos floor : retryFloors) {
            level.setBlock(floor, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }

        java.util.List<RaiderEntity> retry;
        try {
            retry = RaidDirector.startQueuedRecurringRaid(level, s);
        } finally {
            for (BlockPos floor : retryFloors) {
                level.setBlock(floor, Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(s.center.below(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(retry.size() >= RaidDirector.MIN_BAND
                && retry.size() <= RaidDirector.MAX_BAND
                && s.recurringRaidRun.isActive()
                && s.recurringRaidRun.activeSerial() == serial
                && s.recurringRaidRun.plan().orElseThrow().equals(plan)
                && s.recurringRaidRun.participants().size() == retry.size()
                && plan.equals(s.pendingRaid),
            "restored footing must activate and seal 2-9 entities under the exact "
                + "same queued plan/serial, got " + retry.size());
        for (RaiderEntity raider : retry) {
            raider.discard();
        }
        SettlementSavedData.get(level).settlements.remove(s.id);
        SettlementSavedData.get(level).setDirty();
        helper.succeed();
    }

    /**
     * Theft is physical. A raider takes goods OUT of a real chest and INTO
     * its own real inventory, the chest is genuinely emptier, and killing
     * the raider gives the goods back. MineColonies leaves a chat line and a
     * day of mourning; its own feature requests (#113, #129) are asking for
     * exactly this -- a consequence you can chase down.
     */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "raider_raiders_steal_real_goods_and_drop_them_when_killed")
    public void raidersStealRealGoodsAndDropThemWhenKilled(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        BlockPos chestRel = new BlockPos(9, 1, 9);
        helper.setBlock(chestRel, Blocks.CHEST);
        var bounds = net.minecraft.world.level.levelgen.structure.BoundingBox
            .fromCorners(helper.absolutePos(new BlockPos(8, 1, 8)),
                helper.absolutePos(new BlockPos(10, 3, 10)));
        // A plaque block MUST exist at the anchor. BuildingManager's sweep
        // dissolves any building whose plaquePos holds no plaque -- correctly,
        // since "no plaque, no building" is the permanent invariant (D-005).
        // A fixture that skips this registers a building the game then deletes
        // out from under the test, on a round-robin sweep shared with every
        // other concurrently running test: the root cause of KF-014.
        helper.setBlock(new BlockPos(8, 1, 8), ModBlocks.PLAQUE.get());
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(8, 1, 8)),
            helper.absolutePos(new BlockPos(8, 1, 8)), bounds);
        warehouse.valid = true;
        s.buildings.add(warehouse);
        var chest = (net.minecraft.world.Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(chestRel));
        helper.assertTrue(chest != null, "the store must exist");
        chest.setItem(0, new ItemStack(Items.WHEAT, 12));
        final int total = 12;

        RaiderEntity thief = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        thief.assign(UUID.randomUUID(), s.id, RaidObjective.KORN, 1.0F, false);

        helper.succeedWhen(() -> {
            int inChest = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack st = chest.getItem(i);
                if (st.is(Items.WHEAT)) {
                    inChest += st.getCount();
                }
            }
            int carried = thief.lootCount();
            helper.assertTrue(inChest + carried == total,
                "wheat must be conserved at every instant: chest=" + inChest
                    + " carried=" + carried);
            helper.assertTrue(carried > 0,
                "the raider should be taking the stores, chest still has "
                    + inChest);
            helper.assertTrue(inChest < total,
                "and the chest must be genuinely emptier, not just counted down");
        });
    }

    /**
     * Getting away with the goods and being wiped out empty-handed must
     * resolve DIFFERENTLY. Whether the settlement held is about whether the
     * raiders got what they came for, not about who died.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_escaping_with_the_stores_resolves_as_a_loss")
    public void escapingWithTheStoresResolvesAsALoss(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        RaidAuthorityFixtures.armTerminal(s,
            new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 2L));
        s.raidPressure.setPressureForTesting(50);

        // The band is gone AND the goods went with them.
        s.raidLootEscaped = true;
        int before = s.raidPressure.pressure();
        int victoriesBefore = captain.victories();
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(s.raidPressure.pressure() < before,
            "losing the stores must EASE pressure -- at a price already paid. "
                + "Got " + s.raidPressure.pressure() + " from " + before);
        helper.assertTrue(captain.victories() == victoriesBefore + 1,
            "and the captain must remember winning");
        helper.assertTrue(!s.raidLootEscaped,
            "the flag must be cleared so the next raid starts honest");
        helper.succeed();
    }

    /** Guards already hunt hostiles, so a raider is a target without a special case. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_guards_treat_raiders_as_hostile")
    public void guardsTreatRaidersAsHostile(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(5, 1, 5));
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 5));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        helper.assertTrue(raider instanceof net.minecraft.world.entity.monster.Monster,
            "raiders must be Monsters so existing guard targeting sees them");
        helper.succeedWhen(() -> helper.assertTrue(
            guard.getTarget() == raider || raider.getTarget() == guard,
            "a guard and a raider inside the settlement should engage; guard="
                + guard.getTarget() + " raider=" + raider.getTarget()));
    }

    // ---------------------------------------------------------- SLICE A3-RAIDS ---

    /**
     * D-A3-3: escalation must be legible in the stage, not only felt through
     * wealth. The same settlement, unchanged in every other way, must pull a
     * visibly bigger band once it reads as besieged.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_band_size_escalates_with_pressure_stage")
    public void bandSizeEscalatesWithPressureStage(GameTestHelper helper) {
        Settlement calm = new Settlement(UUID.randomUUID(), "Calm", BlockPos.ZERO);
        Settlement besieged = new Settlement(UUID.randomUUID(), "Besieged", BlockPos.ZERO);
        for (int i = 0; i < 6; i++) {
            calm.putRecord(UUID.randomUUID(), "C" + i, Profession.NONE);
            besieged.putRecord(UUID.randomUUID(), "B" + i, Profession.NONE);
        }
        besieged.raidPressure.setPressureForTesting(RaidPressure.BELEIRING_THRESHOLD);
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        int calmBand = RaidDirector.bandSizeFor(calm, captain);
        int siegeBand = RaidDirector.bandSizeFor(besieged, captain);
        helper.assertTrue(siegeBand > calmBand,
            "the same settlement under siege must pull a bigger band than a "
                + "calm one: siege=" + siegeBand + " calm=" + calmBand);
        helper.assertTrue(siegeBand <= RaidDirector.MAX_BAND,
            "and escalation must still respect the performance cap, got " + siegeBand);
        helper.succeed();
    }

    /**
     * The aftermath report (D-A3-8 / "Aftermath"): what a lost raid actually
     * cost must be readable afterward, not only felt in the moment.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_a_lost_raid_leaves_a_report_of_what_was_stolen_and_who_was_hurt")
    public void aLostRaidLeavesAReportOfWhatWasStolenAndWhoWasHurt(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        RaidAuthorityFixtures.armTerminal(s,
            new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 5L));
        s.raidLootEscaped = true;
        s.raidItemsStolenTonight = 7;
        s.raidSettlersHurtTonight = 2;

        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(!s.raidLog.isEmpty(), "the morning must leave a record");
        RaidLogEntry entry = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(!entry.held(), "the settlement lost this one");
        helper.assertTrue(entry.itemsStolen() == 7,
            "the report must say what was taken, got " + entry.itemsStolen());
        helper.assertTrue(entry.settlersHurt() == 2,
            "and who was hurt, got " + entry.settlersHurt());
        helper.assertTrue(entry.captainName().equals(captain.name()),
            "and who led it, got " + entry.captainName());
        helper.assertTrue(s.raidItemsStolenTonight == 0 && s.raidSettlersHurtTonight == 0,
            "tallies must reset so tomorrow's raid starts honest");
        helper.succeed();
    }

    /**
     * The other outcome must read differently: held means the raid failed
     * at what it actually came for.
     *
     * <p><b>Fixed, 2026-08-26 raid-night audit.</b> This used to run the
     * scenario under {@code RaidObjective.BLOD} and still assert {@code
     * held()} with a settler hurt -- which was exactly the HIGH defect the
     * audit found (a raid that hurts settlers reported as held) rather than
     * a proof of anything intentional. The scenario this test actually
     * means -- "someone can get hurt defending a raid that is nonetheless
     * repelled" -- is true of {@code KORN}, whose own success signal is
     * untouched loot ({@link Settlement#raidLootEscaped}), not of BLOD,
     * whose own signal ({@link Settlement#raidSettlersHurtTonight}) is
     * exactly the thing this test sets to 1. See
     * {@code RaidPressureGameTests#aBlodRaidOnlyHoldsIfNobodyWasActuallyHurt}
     * for BLOD's own now-correct behaviour.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_a_held_raid_is_logged_as_held_without_stolen_goods")
    public void aHeldRaidIsLoggedAsHeldWithoutStolenGoods(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        RaidAuthorityFixtures.armTerminal(s,
            new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 6L));
        s.raidSettlersHurtTonight = 1;
        // raidLootEscaped is left false: nothing got away with the goods --
        // KORN's own signal, so the raid holds even though a settler was
        // hurt defending it.

        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        RaidLogEntry entry = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(entry.held(), "nothing escaped, so the settlement held");
        helper.assertTrue(entry.itemsStolen() == 0,
            "held must mean nothing was stolen, got " + entry.itemsStolen());
        helper.assertTrue(entry.settlersHurt() == 1,
            "but people can still be hurt in a raid that is repelled, got "
                + entry.settlersHurt());
        helper.succeed();
    }

    /** The report is a history, not an unbounded diary -- capped like the enemy gallery. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_the_raid_log_stays_bounded")
    public void theRaidLogStaysBounded(GameTestHelper helper) {
        Settlement s = new Settlement(UUID.randomUUID(), "Logtown", BlockPos.ZERO);
        var level = helper.getLevel();
        for (int i = 0; i < RaidDirector.MAX_RAID_LOG + 5; i++) {
            RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
            RaidAuthorityFixtures.armTerminal(s,
                new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, i));
            helper.assertTrue(RaidDirector.resolveIfOver(level, s),
                "an empty band resolves immediately");
        }
        helper.assertTrue(s.raidLog.size() <= RaidDirector.MAX_RAID_LOG,
            "the raid log must stay bounded, got " + s.raidLog.size());
        helper.succeed();
    }

    /**
     * A hit landed as part of a live raid must count toward the morning
     * report; the same raider swinging outside a raid (a scout defending
     * itself, say) must not inflate one that never happened.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_hurting_a_settler_is_only_tallied_during_a_live_raid")
    public void hurtingASettlerIsOnlyTalliedDuringALiveRaid(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        SettlerEntity outsideRaid = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 5));
        outsideRaid.setSettlerName("Kari");
        outsideRaid.bindTo(s.id, s.center);
        s.putRecord(outsideRaid.getUUID(), outsideRaid.getSettlerName(), Profession.NONE);
        SettlerEntity duringRaid = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 5));
        duringRaid.setSettlerName("Ola");
        duringRaid.bindTo(s.id, s.center);
        s.putRecord(duringRaid.getUUID(), duringRaid.getSettlerName(), Profession.NONE);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 5));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        raider.doHurtTarget(outsideRaid);
        helper.assertTrue(s.raidSettlersHurtTonight == 0,
            "a hit outside a live raid must not be tallied, got "
                + s.raidSettlersHurtTonight);

        s.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 0.0F, 1L);
        raider.doHurtTarget(duringRaid);
        helper.assertTrue(s.raidSettlersHurtTonight == 1,
            "a hit during a live raid must be tallied for the defense report, got "
                + s.raidSettlersHurtTonight);
        helper.succeed();
    }

    /**
     * Telegraphing (D-A3's "1-2 days ahead"): a scout is a real, findable
     * RaiderEntity, but it must never itself start the fight it is warning
     * the settlement about.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_scouts_are_omens_and_do_not_hunt_even_next_to_settlers")
    public void scoutsAreOmensAndDoNotHuntEvenNextToSettlers(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(9, 1, 7));
        guard.setSettlerName("Vakt");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);

        RaiderEntity scout = RaidTelegraph.spawnScout(helper.getLevel(), s);
        helper.assertTrue(scout != null, "the omen must actually appear");
        helper.assertTrue(scout.isScout(), "and be flagged as a scout, not a raider");

        // Control: an ordinary raider in the same arena must still hunt, so
        // a null target on the scout proves the guard, not just an empty test.
        RaiderEntity control = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(9, 1, 9));
        control.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        helper.succeedWhen(() -> {
            helper.assertTrue(control.getTarget() == guard || guard.getTarget() == control,
                "control check: an ordinary raider must still hunt, or this "
                    + "test proves nothing");
            helper.assertTrue(scout.getTarget() == null,
                "a scout is an omen, not a fight -- it must never initiate "
                    + "one, got target=" + scout.getTarget());
        });
    }
}
