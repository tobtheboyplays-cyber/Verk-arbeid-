package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Protect-civilians-first (DESIGN.md system 5 / R19), routed here whole
 * after the 2026-08-26 raid-night audit found it was not true:
 * {@code SettlerDefenseTargetGoal} used to be a plain nearest-hostile
 * search, so a guard already fighting a harmless raider at the wall would
 * never so much as glance at a second one mauling a settler three blocks
 * away. See that class's own doc for the mechanism; these pin the
 * observable result.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardDefenseGameTests {

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
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Wardholm", helper.absolutePos(centerRel));
        s.radius = 8;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity spawnGuard(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), rel);
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);
        return guard;
    }

    private static SettlerEntity spawnCivilian(GameTestHelper helper, Settlement s, BlockPos rel,
                                               String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static RaiderEntity spawnIdleRaider(GameTestHelper helper, Settlement s, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        return raider;
    }

    /**
     * The core claim: a raider actively targeting a settler outranks a
     * raider that is simply closer and doing nothing. Determinism over
     * relying on real AI acquisition: {@link RaiderEntity#setTarget} is
     * called directly, the same "seed the world, read the public API"
     * discipline {@code RaidDamageGameTests}' class doc names.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "guard_defense_prefers_a_raider_attacking_a_settler_over_a_nearer_idle_one")
    public void prefersARaiderAttackingASettlerOverANearerIdleOne(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        SettlerEntity victim = spawnCivilian(helper, s, new BlockPos(13, 1, 8), "Civilian");

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity attackingAndFar = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));
        attackingAndFar.setTarget(victim);

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == attackingAndFar,
            "a guard must prefer the raider actively attacking a settler over one merely "
                + "closer and idle; got " + guard.getTarget()));
    }

    /**
     * Second-tier preference: absent any raider attacking a settler, one
     * attacking the player still outranks a nearer, idle raider.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "guard_defense_prefers_a_raider_attacking_the_player_over_a_nearer_idle_one")
    public void prefersARaiderAttackingThePlayerOverANearerIdleOne(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos playerAbs = helper.absolutePos(new BlockPos(13, 1, 8));
        player.teleportTo(playerAbs.getX() + 0.5, playerAbs.getY(), playerAbs.getZ() + 0.5);

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity attackingAndFar = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));
        attackingAndFar.setTarget(player);

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == attackingAndFar,
            "absent a settler under attack, a guard must still prefer the raider actively "
                + "attacking the player over one merely closer and idle; got "
                + guard.getTarget()));
    }

    /**
     * The requirement the audit named explicitly: the preference must stay
     * visible mid-fight, not only at the moment a guard first picks a
     * target. A guard already engaged with the nearer, idle raider must
     * abandon it the instant a farther one starts mauling a settler.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "guard_defense_abandons_a_distant_fight_to_intercept_one_standing_over_a_civilian")
    public void abandonsADistantFightToInterceptOneStandingOverACivilian(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        SettlerEntity victim = spawnCivilian(helper, s, new BlockPos(13, 1, 8), "Civilian");

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity laterAttacker = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));

        // Confirm the guard actually engages the near, idle raider FIRST --
        // otherwise a later switch to the far one would prove nothing about
        // abandoning an existing fight. Only once that is confirmed does the
        // second raider become a threat to a settler, one runnable so the
        // ordering (check, then arm the threat) is not left to chance.
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(guard.getTarget() == idleAndNear,
                "setup: the guard must start on the nearer idle raider, got " + guard.getTarget());
            laterAttacker.setTarget(victim);
        });

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == laterAttacker,
            "an already-engaged guard must switch to intercept a raider that starts "
                + "attacking a settler, even though it is farther away; got "
                + guard.getTarget()));
    }
}
