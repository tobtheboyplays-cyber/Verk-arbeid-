package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardSaluteGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/** Guards salute a passing player once per cooldown, and never while the alarm rings. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GuardSaluteGameTests {

    @GameTest(template = "empty16", timeoutTicks = 260, batch = "guard_salute_once")
    public void guardSalutesANearbyPlayerOnce(GameTestHelper helper) {
        Fixture f = fixture(helper);
        helper.runAfterDelay(100, () -> helper.assertTrue(GuardSaluteGoal.saluteCount(f.guard) == 1,
            "one salute for the player standing in front, got " + GuardSaluteGoal.saluteCount(f.guard)));
        helper.runAfterDelay(240, () -> {
            helper.assertTrue(GuardSaluteGoal.saluteCount(f.guard) == 1,
                "no repeat within the one-minute cooldown, got " + GuardSaluteGoal.saluteCount(f.guard));
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160, batch = "guard_salute_alarm")
    public void noSaluteWhileTheAlarmRings(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.settlement.alertUntilGameTime = helper.getLevel().getGameTime() + 10_000L;
        helper.runAfterDelay(140, () -> {
            helper.assertTrue(GuardSaluteGoal.saluteCount(f.guard) == 0,
                "a guard never salutes during an alarm, got " + GuardSaluteGoal.saluteCount(f.guard));
            f.settlement.alertUntilGameTime = 0L;
            helper.succeed();
        });
    }

    /**
     * Owner spec: a player walking by is greeted EARLY (inside 9 blocks, not at the last
     * moment): attention + sheathed sword + hand salute HELD while he approaches and walks past,
     * released only once he is ~4 blocks past, then the sword is drawn and the guard resumes.
     */
    @GameTest(template = "empty16", timeoutTicks = 260, batch = "guard_salute_pass")
    public void guardHoldsTheSaluteUntilThePlayerHasWalkedPast(GameTestHelper helper) {
        Fixture f = fixture(helper);
        // the player walks north past the guard, one block to his side, 0.2 blocks a tick,
        // from 9.5 blocks out to 7 blocks behind him
        double x = f.guard.getX() + 1.0D;
        double z0 = f.guard.getZ() + 9.5D;
        f.player.setPos(x, f.guard.getY(), z0 + 4.0D);          // out of range to start with
        int start = 20;
        int steps = 85;
        for (int i = 0; i <= steps; i++) {
            final double z = z0 - 0.2D * i;
            helper.runAfterDelay(start + i, () -> f.player.setPos(x, f.guard.getY(), z));
        }
        // ~7 blocks out (tick start + 12): already greeting, well before he arrives
        helper.runAfterDelay(start + 16, () -> helper.assertTrue(
            GuardSaluteGoal.of(f.guard) != null && GuardSaluteGoal.of(f.guard).phase() == GuardSaluteGoal.Phase.GREET,
            "the guard greets the player early (inside 9 blocks, still ~6 blocks out), got "
                + phaseOf(f.guard)));
        // beside him (z0 - 9.5 at step ~48): attention, sword sheathed, salute held
        helper.runAfterDelay(start + 48, () -> {
            GuardSaluteGoal goal = GuardSaluteGoal.of(f.guard);
            helper.assertTrue(goal.isSheathed(), "sword sheathed while the player passes");
            helper.assertTrue(goal.isHolding(), "hand salute held while the player passes");
            helper.assertTrue(f.guard.getMainHandItem().is(Items.IRON_SWORD),
                "sheathing is presentation only: the sword never leaves the MAINHAND");
        });
        // just past him (step ~53, not yet behind / 4 blocks past): still holding
        helper.runAfterDelay(start + 53, () -> helper.assertTrue(GuardSaluteGoal.of(f.guard).isHolding(),
            "still saluting as he walks past, got " + phaseOf(f.guard)));
        // well past (> 4 blocks beyond the closest approach, step ~75) and the draw done (+25)
        helper.runAfterDelay(start + steps + 30, () -> {
            GuardSaluteGoal goal = GuardSaluteGoal.of(f.guard);
            helper.assertTrue(goal.phase() == GuardSaluteGoal.Phase.NONE,
                "salute released, sword drawn and the guard back to his post, got " + goal.phase());
            helper.assertTrue(!goal.isSheathed(), "sword back in hand");
            helper.assertTrue(GuardSaluteGoal.saluteCount(f.guard) == 1,
                "one salute for one pass, got " + GuardSaluteGoal.saluteCount(f.guard));
            helper.succeed();
        });
    }

    /** An alarm drops the greeting on the spot: no longer at attention, sword drawn at once. */
    @GameTest(template = "empty16", timeoutTicks = 160, batch = "guard_salute_interrupt")
    public void anAlarmInterruptsTheSaluteAndTheSwordIsDrawn(GameTestHelper helper) {
        Fixture f = fixture(helper);
        helper.runAfterDelay(70, () -> {
            GuardSaluteGoal goal = GuardSaluteGoal.of(f.guard);
            helper.assertTrue(goal != null && goal.isHolding() && goal.isSheathed(),
                "saluting with the sword sheathed before the alarm, got " + phaseOf(f.guard));
            f.settlement.alertUntilGameTime = helper.getLevel().getGameTime() + 10_000L;
        });
        helper.runAfterDelay(74, () -> {
            GuardSaluteGoal goal = GuardSaluteGoal.of(f.guard);
            helper.assertTrue(goal.phase() == GuardSaluteGoal.Phase.NONE && !goal.isSheathed(),
                "the alarm cancels the greeting instantly and the sword is drawn, got " + goal.phase());
            f.settlement.alertUntilGameTime = 0L;
            helper.succeed();
        });
    }

    private static String phaseOf(SettlerEntity guard) {
        GuardSaluteGoal goal = GuardSaluteGoal.of(guard);
        return goal == null ? "no goal" : goal.phase() + (goal.isSheathed() ? " sheathed" : "")
            + (goal.isHolding() ? " holding" : "");
    }

    private record Fixture(Settlement settlement, SettlerEntity guard, ServerPlayer player) {
    }

    @SuppressWarnings("removal")
    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Salutholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        Building barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 0, 0);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 6));
        guard.bindTo(settlement.id, settlement.center);
        guard.setSettlerName("Osric");
        settlement.putRecord(guard.getUUID(), "Osric", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(), "hire guard");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        // Face south, toward where the player stands.
        guard.setYRot(0.0F);
        guard.setYHeadRot(0.0F);
        guard.setYBodyRot(0.0F);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stand = helper.absolutePos(new BlockPos(8, 1, 9));
        player.setPos(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
        return new Fixture(settlement, guard, player);
    }
}
