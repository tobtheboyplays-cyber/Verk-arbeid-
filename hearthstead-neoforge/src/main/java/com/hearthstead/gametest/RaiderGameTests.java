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
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
        helper.succeed();
    }

    /**
     * Strength comes from the captain's own record, not from the player's
     * stat sheet -- and it is capped, so a long feud stays winnable rather
     * than becoming a wall.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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

    /** Raiders never turn on each other, however the melee goes. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void raidersPersistAndRememberTheirOrders(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        UUID captainId = UUID.randomUUID();
        raider.assign(captainId, s.id, RaidObjective.BRANN, 1.5F, true);
        raider.setObjectivePos(helper.absolutePos(new BlockPos(5, 1, 5)));

        helper.assertTrue(!raider.removeWhenFarAway(4096.0),
            "raiders must never despawn for distance");

        var tag = new net.minecraft.nbt.CompoundTag();
        raider.addAdditionalSaveData(tag);
        RaiderEntity reloaded = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(6, 1, 2));
        reloaded.readAdditionalSaveData(tag);

        helper.assertTrue(reloaded.isCaptain(), "captaincy must survive a save");
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

    /** Guards already hunt hostiles, so a raider is a target without a special case. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "day")
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
}
