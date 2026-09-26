package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * QA-UI-03: a Banner opened in the first second after placement, before the once-a-second
 * tick founded its settlement, kept a NO_SETTLEMENT menu that the Tech Tree's exact-menu
 * authority rightly refused until the player reopened it.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BannerFreshOpenGameTests {

    private static final BlockPos BANNER = new BlockPos(8, 1, 8);

    private static HearthBlockEntity freshBanner(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        helper.setBlock(BANNER, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(BANNER));
        helper.assertTrue(hearth != null && hearth.getSettlementId() == null, "fixture: a fresh, unfounded Banner");
        return hearth;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer playerBeside(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(BANNER.west(2));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        // Sneak-use opens the Banner menu itself (it skips a pending Blessing offer).
        player.setShiftKeyDown(true);
        return player;
    }

    private static void use(GameTestHelper helper, ServerPlayer player) {
        BlockPos pos = helper.absolutePos(BANNER);
        helper.getBlockState(BANNER).useWithoutItem(helper.getLevel(), player,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false));
    }

    private static void cleanup(GameTestHelper helper, HearthBlockEntity hearth, ServerPlayer player) {
        player.closeContainer();
        UUID id = hearth.getSettlementId();
        helper.setBlock(BANNER, Blocks.AIR);
        if (id != null) {
            SettlementSavedData.get(helper.getLevel()).settlements.remove(id);
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "banner_fresh_open")
    public void aFreshBannerOpensWithItsRealSettlement(GameTestHelper helper) {
        HearthBlockEntity hearth = freshBanner(helper);
        ServerPlayer player = playerBeside(helper);
        use(helper, player);
        UUID id = hearth.getSettlementId();
        helper.assertTrue(id != null, "opening a fresh Banner founds its settlement at once");
        helper.assertTrue(player.containerMenu instanceof HearthMenu menu && id.equals(menu.getSettlementId()),
            "the menu carries the real settlement, not NO_SETTLEMENT");
        cleanup(helper, hearth, player);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 320, batch = "banner_fresh_open_refresh")
    public void aMenuOpenedBeforeFoundingIsRefreshedWhenTheBannerFounds(GameTestHelper helper) {
        HearthBlockEntity hearth = freshBanner(helper);
        // A neighbouring settlement blocks the founding at first (too close).
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement neighbour = new Settlement(UUID.randomUUID(), "Neighbour", helper.absolutePos(BANNER.east(3)));
        data.settlements.put(neighbour.id, neighbour);
        ServerPlayer player = playerBeside(helper);
        use(helper, player);
        helper.assertTrue(hearth.getSettlementId() == null, "fixture: founding refused while the neighbour stands");
        helper.assertTrue(player.containerMenu instanceof HearthMenu menu
                && HearthMenu.NO_SETTLEMENT.equals(menu.getSettlementId()),
            "an unfounded Banner still opens, as before");
        data.settlements.remove(neighbour.id);
        helper.succeedWhen(() -> {
            UUID id = hearth.getSettlementId();
            helper.assertTrue(id != null, "the Banner founds on its retry");
            helper.assertTrue(player.containerMenu instanceof HearthMenu menu && id.equals(menu.getSettlementId()),
                "the open menu is refreshed to the real settlement");
            cleanup(helper, hearth, player);
        });
    }
}
