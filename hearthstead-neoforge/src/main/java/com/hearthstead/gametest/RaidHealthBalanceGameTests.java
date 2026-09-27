package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enemy health balance (owner request 26 Sep: longer fights). Two kinds of
 * proof, all batches prefixed {@code raid_health_}:
 * <ul>
 *   <li>Exact hit counts: a no-AI Guard's forced light slashes against a
 *       raider armed with the configured health, ticked directly.</li>
 *   <li>Measured fights: real AI Guards against real AI raiders, logged as
 *       {@code HSQA_TTK} lines with the fight length in seconds.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidHealthBalanceGameTests {

    private static Settlement arena(GameTestHelper helper, String name, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(size / 2, 1, size / 2)));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity guard(GameTestHelper helper, Settlement settlement,
                                       BlockPos rel, String name) {
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), rel);
        guard.setSettlerName(name);
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        // A new Recruit: no rolled Strength or Wits edge.
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        guard.attributes().pinForTest(Attribute.WITS, 0);
        return guard;
    }

    private static RaiderEntity raider(GameTestHelper helper, Settlement settlement,
                                       BlockPos rel, RaiderEntity.Variant variant,
                                       boolean captain) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, captain);
        return raider;
    }

    // ------------------------------------------------ exact hit counts ---

    private static void countLightHits(GameTestHelper helper, RaiderEntity.Variant variant,
                                       int minHits, int maxHits) {
        Settlement settlement = arena(helper, "Tellvik", 16);
        SettlerEntity guard = guard(helper, settlement, new BlockPos(6, 1, 8), "Teller");
        guard.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500.0D);
        guard.setHealth(500.0F);
        guard.setNoAi(true);
        RaiderEntity target = raider(helper, settlement, new BlockPos(7, 1, 8), variant, false);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        target.setNoAi(true);
        float max = target.getMaxHealth();
        helper.assertTrue(Math.abs(max - RaiderEntity.armedMaxHealth(variant, 1.0F, false)) < 0.01F,
            "the armed raider must carry the configured health, got " + max);
        guard.setTarget(target);
        GuardMeleeGoal goal = new GuardMeleeGoal(guard);
        int[] hits = {0};
        float[] firstHit = {Float.NaN};
        // Forced lights back to back at the Guard's own cadence (10-tick
        // swing + 10-tick rest), so only the damage per blow is measured.
        long window = 20L * (maxHits + 3);
        for (long t = 1L; t <= window; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (!target.isAlive()) {
                    return;
                }
                if (goal.pendingMove() == null) {
                    goal.forceNextMove(GuardMove.LIGHT_A, false);
                }
                float before = target.getHealth();
                goal.tick();
                if (target.getHealth() < before || !target.isAlive()) {
                    hits[0]++;
                    if (Float.isNaN(firstHit[0])) {
                        firstHit[0] = before - Math.max(0.0F, target.getHealth());
                    }
                }
            });
        }
        GameTestTicks.at(helper, window + 1L, () -> {
            Hearthstead.LOGGER.info("HSQA_TTK light_hits variant={} health={} damage_per_light={} hits={} alive={}",
                variant, max, firstHit[0], hits[0], target.isAlive());
            helper.assertTrue(!target.isAlive(),
                variant + " must fall to " + maxHits + " light hits, took more (health " + max
                    + ", one light " + firstHit[0] + ")");
            helper.assertTrue(hits[0] >= minHits && hits[0] <= maxHits,
                variant + " should take " + minHits + "-" + maxHits + " light hits, took "
                    + hits[0] + " (health " + max + ", one light " + firstHit[0] + ")");
            SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 300, batch = "raid_health_skirmisher_light_hits")
    public void skirmisherTakesThreeGuardLights(GameTestHelper helper) {
        countLightHits(helper, RaiderEntity.Variant.SKIRMISHER, 3, 3);
    }

    @GameTest(template = "empty16", timeoutTicks = 600, batch = "raid_health_brute_light_hits")
    public void bruteTakesFourToSixGuardLights(GameTestHelper helper) {
        // Brute base HP 70 (was 110) so four new Guards win the first wave; 70 / ~16 = 5 lights.
        countLightHits(helper, RaiderEntity.Variant.BRUTE, 4, 6);
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raid_health_menace_cap")
    public void menaceAndCaptainDoNotMakeASponge(GameTestHelper helper) {
        double base = RaiderEntity.baseMaxHealth(RaiderEntity.Variant.BRUTE);
        double worst = RaiderEntity.armedMaxHealth(RaiderEntity.Variant.BRUTE,
            RaiderEntity.MAX_MENACE, true);
        helper.assertTrue(worst <= base * 2.0 + RaiderEntity.CAPTAIN_HEALTH_BONUS + 0.01,
            "menace 3 plus captain must stay within twice the base: " + worst + " vs " + base);
        helper.assertTrue(RaiderEntity.menaceHealthFactor(99.0F) == 2.0
                && RaiderEntity.menaceHealthFactor(1.0F) == 1.0,
            "menace buys at most double health");
        helper.succeed();
    }

    // ---------------------------------------------------- measured fights ---

    private static void fight(GameTestHelper helper, String label, List<SettlerEntity> guards,
                              List<RaiderEntity> raiders, Settlement settlement,
                              boolean guardsMustWin) {
        long[] start = {-1L};
        boolean[] reported = {false};
        helper.onEachTick(() -> {
            if (reported[0]) {
                return;
            }
            long now = helper.getTick();
            if (start[0] < 0L) {
                start[0] = now;
            }
            List<RaiderEntity> alive = new ArrayList<>();
            for (RaiderEntity raider : raiders) {
                if (raider.isAlive()) {
                    alive.add(raider);
                }
            }
            List<SettlerEntity> standing = new ArrayList<>();
            for (SettlerEntity guard : guards) {
                if (guard.isAlive()) {
                    standing.add(guard);
                }
            }
            // Raiders outside a live raid have no hunt goal, so point them at
            // a Guard. Guards pick their own targets through the settlement's
            // RaidThreatBoard, exactly as in play: forcing a Guard target
            // here fought the board every ten ticks (26 Sep finding).
            for (int i = 0; i < alive.size() && !standing.isEmpty(); i++) {
                RaiderEntity raider = alive.get(i);
                if (raider.getTarget() == null || !raider.getTarget().isAlive()) {
                    raider.setTarget(standing.get(i % standing.size()));
                }
            }
            if (!alive.isEmpty() && !standing.isEmpty()) {
                return;
            }
            reported[0] = true;
            double seconds = (now - start[0]) / 20.0;
            float guardHealth = 0.0F;
            for (SettlerEntity guard : standing) {
                guardHealth += guard.getHealth();
            }
            float dealt = 0.0F;
            float raiderPool = 0.0F;
            for (RaiderEntity raider : raiders) {
                raiderPool += raider.getMaxHealth();
                dealt += raider.getMaxHealth() - Math.max(0.0F, raider.isAlive() ? raider.getHealth() : 0.0F);
            }
            Hearthstead.LOGGER.info(
                "HSQA_TTK fight={} seconds={} winner={} guards_left={}/{} guard_hp_left={} raiders_left={}/{} raider_damage_taken={}/{}",
                label, seconds, alive.isEmpty() ? "guards" : "raiders", standing.size(),
                guards.size(), guardHealth, alive.size(), raiders.size(), dealt, raiderPool);
            if (guardsMustWin) {
                helper.assertTrue(alive.isEmpty(), label + ": the defenders must win, raiders left "
                    + alive.size() + " after " + seconds + " s");
            }
            SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
            helper.succeed();
        });
    }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_skirmisher")
    public void oneGuardDuelsOneSkirmisher(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Duellvik", 32);
        SettlerEntity guard = guard(helper, settlement, new BlockPos(12, 1, 16), "Duellant");
        RaiderEntity skirmisher = raider(helper, settlement, new BlockPos(18, 1, 16),
            RaiderEntity.Variant.SKIRMISHER, false);
        fight(helper, "1_guard_vs_skirmisher", List.of(guard), List.of(skirmisher),
            settlement, true);
    }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_brute")
    public void oneGuardDuelsOneBrute(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Bautavik", 32);
        SettlerEntity guard = guard(helper, settlement, new BlockPos(12, 1, 16), "Bauta");
        RaiderEntity brute = raider(helper, settlement, new BlockPos(18, 1, 16),
            RaiderEntity.Variant.BRUTE, false);
        // Focus-fire target: a lone Recruit may lose; the length is measured.
        fight(helper, "1_guard_vs_brute", List.of(guard), List.of(brute), settlement, false);
    }

    /** Health profiles measured side by side: base Skirmisher / Brute health. */
    private enum Profile {
        OLD(14.0, 30.0), COORD(26.0, 70.0), MID(40.0, 120.0), NEW(-1.0, -1.0);

        final double skirmisher;
        final double brute;

        Profile(double skirmisher, double brute) {
            this.skirmisher = skirmisher;
            this.brute = brute;
        }
    }

    private static void applyProfile(RaiderEntity raider, Profile profile, boolean captain) {
        if (profile == Profile.NEW) {
            return; // exactly what assign() armed from the config defaults
        }
        double base = raider.variant() == RaiderEntity.Variant.BRUTE ? profile.brute
            : profile.skirmisher;
        double health = base + (captain ? RaiderEntity.CAPTAIN_HEALTH_BONUS : 0.0);
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        raider.setHealth((float) health);
    }

    private static void wave(GameTestHelper helper, int guardCount, Profile profile) {
        Settlement settlement = arena(helper, "Bolge " + guardCount + profile, 32);
        List<SettlerEntity> guards = new ArrayList<>();
        for (int i = 0; i < guardCount; i++) {
            guards.add(guard(helper, settlement, new BlockPos(10, 1, 12 + i * 2), "Vakt " + i));
        }
        // The authored first-raid band: a captain, one Brute follower and three Skirmishers.
        List<RaiderEntity> band = new ArrayList<>();
        band.add(raider(helper, settlement, new BlockPos(21, 1, 15),
            RaiderEntity.Variant.SKIRMISHER, true));
        band.add(raider(helper, settlement, new BlockPos(22, 1, 17),
            RaiderEntity.Variant.BRUTE, false));
        for (int i = 0; i < 3; i++) {
            band.add(raider(helper, settlement, new BlockPos(20, 1, 12 + i * 4),
                RaiderEntity.Variant.SKIRMISHER, false));
        }
        for (int i = 0; i < band.size(); i++) {
            applyProfile(band.get(i), profile, i == 0);
        }
        // Measurement: the real first raid adds an Archer, the player and bells.
        fight(helper, guardCount + "_guards_vs_first_wave_" + profile, guards, band,
            settlement, false);
    }

    private static void duel(GameTestHelper helper, RaiderEntity.Variant variant, Profile profile) {
        Settlement settlement = arena(helper, "Duell " + variant + profile, 32);
        SettlerEntity guard = guard(helper, settlement, new BlockPos(12, 1, 16), "Duellant");
        RaiderEntity enemy = raider(helper, settlement, new BlockPos(18, 1, 16), variant, false);
        applyProfile(enemy, profile, false);
        fight(helper, "1_guard_vs_" + variant + "_" + profile, List.of(guard), List.of(enemy),
            settlement, false);
    }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave4_old")
    public void wave4Old(GameTestHelper helper) { wave(helper, 4, Profile.OLD); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave4_coord")
    public void wave4Coord(GameTestHelper helper) { wave(helper, 4, Profile.COORD); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave4_mid")
    public void wave4Mid(GameTestHelper helper) { wave(helper, 4, Profile.MID); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave4_new")
    public void wave4New(GameTestHelper helper) { wave(helper, 4, Profile.NEW); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave2_old")
    public void wave2Old(GameTestHelper helper) { wave(helper, 2, Profile.OLD); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave2_coord")
    public void wave2Coord(GameTestHelper helper) { wave(helper, 2, Profile.COORD); }

    @GameTest(template = "empty32", timeoutTicks = 3600, batch = "raid_health_wave2_mid")
    public void wave2Mid(GameTestHelper helper) { wave(helper, 2, Profile.MID); }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_s_old")
    public void duelSkirmisherOld(GameTestHelper helper) {
        duel(helper, RaiderEntity.Variant.SKIRMISHER, Profile.OLD);
    }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_s_coord")
    public void duelSkirmisherCoord(GameTestHelper helper) {
        duel(helper, RaiderEntity.Variant.SKIRMISHER, Profile.COORD);
    }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_b_old")
    public void duelBruteOld(GameTestHelper helper) {
        duel(helper, RaiderEntity.Variant.BRUTE, Profile.OLD);
    }

    @GameTest(template = "empty32", timeoutTicks = 2400, batch = "raid_health_duel_b_coord")
    public void duelBruteCoord(GameTestHelper helper) {
        duel(helper, RaiderEntity.Variant.BRUTE, Profile.COORD);
    }
}
