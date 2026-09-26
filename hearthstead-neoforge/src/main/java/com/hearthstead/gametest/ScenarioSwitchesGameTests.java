package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.worldevent.WorldEventConfig;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.summon.PlayerSummons;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;

/**
 * SCENARIO lane (26 Sep): a FRESH world with every emergency switch off at
 * once, through the real config values (batch {@code scenario_switches_all_off}).
 * The core survival path must still work: a Banner founds a village, the
 * founders live, and Timber Rights can be bought. Each switched-off system
 * refuses cleanly: orders, summons, parleys, extended emblems, gear gating.
 * Every value is restored to its default in {@code @AfterBatch}, pass or fail.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioSwitchesGameTests {
    private static final String BATCH = "scenario_switches_all_off";

    private static void all(boolean on) {
        HearthsteadServerConfig.BUILDER_ENABLED.set(on);
        HearthsteadServerConfig.WORLD_EVENTS_ENABLED.set(on);
        HearthsteadServerConfig.BATTLE_ROLES_ENABLED.set(on);
        HearthsteadServerConfig.GUARD_COMMANDS_ENABLED.set(on);
        HearthsteadServerConfig.WAREHOUSE_LEVELS_ENABLED.set(on);
        HearthsteadServerConfig.GEAR_TIERS_ENABLED.set(on);
        HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(on);
        HearthsteadServerConfig.CONVERSATIONS_ENABLED.set(on);
        HearthsteadServerConfig.EXTENDED_TRADES_ENABLED.set(on ? HearthsteadServerConfig.DEFAULT_EXTENDED_TRADES : false);
        HearthsteadServerConfig.LIVING_VILLAGE_ENABLED.set(on);
        HearthsteadServerConfig.PATROL_ROUTES_ENABLED.set(on);
        HearthsteadServerConfig.REVIVE_ENABLED.set(on ? HearthsteadServerConfig.DEFAULT_REVIVE_ENABLED : false);
        HearthsteadServerConfig.FINISHER_ENABLED.set(on ? HearthsteadServerConfig.DEFAULT_FINISHER_ENABLED : false);
    }

    @AfterBatch(batch = BATCH)
    public static void restore(ServerLevel level) {
        all(true);
    }

    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 600, batch = BATCH)
    public void everySwitchOffAFreshVillageStillFoundsLivesAndLearns(GameTestHelper h) {
        all(false);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearthPos = h.absolutePos(new BlockPos(32, 1, 32));
        h.getLevel().setBlockAndUpdate(hearthPos, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement founded;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            founded = SettlementManager.tryFound(h.getLevel(), hearthPos);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        final Settlement s = founded;
        h.assertTrue(s != null && s.mayorId != null, "with every switch off a Banner still founds a village");
        HearthBlockEntity hearth = (HearthBlockEntity) h.getLevel().getBlockEntity(hearthPos);
        hearth.bindSettlement(s.id);
        Development.revisionOf(h.getLevel(), s);
        List<SettlerEntity> founders = SettlementManager.loadedMembers(h.getLevel(), s);
        h.assertTrue(founders.size() == 4, "four founders, got " + founders.size());

        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(hearthPos.getX() + 2.5, hearthPos.getY(), hearthPos.getZ() + 0.5);

        // Each switched-off system refuses cleanly.
        h.assertTrue(!WorldEventConfig.enabled() && !ConversationConfig.enabled() && !GearGate.enabled(),
            "the real config values read as off");
        h.assertTrue(FieldOrders.issue(player, new FieldOrderRequestPayload(FieldOrderRules.Group.ALL.wireId(),
                FieldOrderRules.Kind.RETURN.wireId(), FieldOrderRequestPayload.NO_POS, 4, 3, -1)).refusal()
            == FieldOrderRules.Refusal.DISABLED, "field orders: DISABLED");
        SettlerEntity anyone = founders.get(0);
        h.assertTrue(PlayerSummons.request(player, anyone.getId(), anyone.getUUID()).refusal()
            == PlayerSummons.Refusal.DISABLED, "summon: DISABLED");
        h.assertTrue(JobEmblemCatalog.forProfession(Profession.MILLER) == null
                && JobEmblemCatalog.forProfession(Profession.LUMBERER) != null,
            "extended emblems are not sold, the base shop is");

        // The core path still works: Timber Rights from the player's pack.
        for (DevelopmentNode.Cost cost : DevelopmentNode.TIMBER_RIGHTS.costs()) {
            player.getInventory().add(new ItemStack(cost.item(), cost.count()));
        }
        Development.Result learned = Development.purchaseNode(h.getLevel(), s, hearth, DevelopmentNode.TIMBER_RIGHTS,
            Development.revisionOf(h.getLevel(), s), player);
        h.assertTrue(learned == Development.Result.APPLIED, "Timber Rights can be learned with every switch off: " + learned);

        GameTestTicks.at(h, 400, () -> {
            List<SettlerEntity> alive = SettlementManager.loadedMembers(h.getLevel(), s);
            h.assertTrue(alive.size() == 4 && alive.stream().allMatch(e -> e.isAlive() && s.id.equals(e.getSettlementId())),
                "the founders live on through 400 ticks with everything switched off");
            for (var member : alive) member.discard();
            SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
            h.getLevel().setBlockAndUpdate(hearthPos, Blocks.AIR.defaultBlockState());
            all(true);
            h.succeed();
        });
    }
}
