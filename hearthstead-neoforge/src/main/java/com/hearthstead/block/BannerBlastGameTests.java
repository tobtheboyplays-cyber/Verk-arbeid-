package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Regression for SURVIVAL-BANNER-BLAST-01, including the deliberate removal control. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BannerBlastGameTests {
    private record Fixture(BlockPos pos, HearthBlockEntity banner, Settlement settlement, CompoundTag nbt) {}

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "banner_blast_real")
    public void explosionPreservesBannerAndSettlement(GameTestHelper h) {
        Fixture f = fixture(h);
        BlockPos control = f.pos().east();
        h.getLevel().setBlock(control, Blocks.WHITE_WOOL.defaultBlockState(), 3);
        h.getLevel().explode(null, control.getX() + 0.5, control.getY() + 0.5,
            control.getZ() + 0.5, 3.0F, Level.ExplosionInteraction.TNT);
        h.assertTrue(h.getLevel().getBlockState(control).isAir(), "blast must actually destroy the adjacent control");
        assertIntact(h, f);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "banner_blast_forced_callback")
    public void forcedExplosionCallbackCannotRemoveOrDuplicateBanner(GameTestHelper h) {
        Fixture f = fixture(h);
        Explosion explosion = new Explosion(h.getLevel(), null, f.pos().getX() + 0.5,
            f.pos().getY() + 0.5, f.pos().getZ() + 0.5, 3.0F, false, Explosion.BlockInteraction.DESTROY);
        int[] drops = {0};
        // A modded blast may force the Banner into its affected list despite resistance.
        h.getLevel().getBlockState(f.pos()).onExplosionHit(h.getLevel(), f.pos(), explosion,
            (stack, pos) -> drops[0] += stack.getCount());
        h.assertTrue(drops[0] == 0, "a surviving Banner must not also drop a duplicate item");
        assertIntact(h, f);
        // Also cover a direct NeoForge callback, without the normal loot wrapper.
        f.banner().getBlockState().onBlockExploded(h.getLevel(), f.pos(), explosion);
        assertIntact(h, f);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "banner_blast_player_break")
    public void deliberateSurvivalBreakStillDisbands(GameTestHelper h) {
        Fixture f = fixture(h);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(f.pos().getX() + 0.5, f.pos().getY(), f.pos().getZ() - 1.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        f.settlement().addMember(player.getUUID());
        h.assertTrue(f.banner().getBlockState().getDestroySpeed(h.getLevel(), f.pos()) == 3.5F,
            "blast protection must not change normal breaking hardness");
        h.assertTrue(player.gameMode.destroyBlock(f.pos()), "a survival player must still be able to break the Banner");
        h.assertTrue(h.getLevel().getBlockState(f.pos()).isAir(), "deliberate breaking removes the Banner");
        h.assertTrue(SettlementManager.byId(h.getLevel(), f.settlement().id) == null,
            "deliberate breaking still disbands the settlement");
        h.succeed();
    }

    private static Fixture fixture(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(7, 2, 7));
        h.getLevel().setBlock(pos, ModBlocks.HEARTH.get().defaultBlockState(), 3);
        HearthBlockEntity banner = (HearthBlockEntity) h.getLevel().getBlockEntity(pos);
        SettlementSavedData data = SettlementSavedData.get(h.getLevel());
        data.settlements.values().removeIf(s -> s.center.equals(pos));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Blastford", pos);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        banner.bindSettlement(settlement.id);
        banner.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 12));
        return new Fixture(pos, banner, settlement, banner.saveWithFullMetadata(h.getLevel().registryAccess()));
    }

    private static void assertIntact(GameTestHelper h, Fixture f) {
        h.assertTrue(h.getLevel().getBlockState(f.pos()).is(ModBlocks.HEARTH.get()), "Banner must survive");
        h.assertTrue(h.getLevel().getBlockEntity(f.pos()) == f.banner(), "original block entity must survive, not be restored");
        h.assertTrue(f.nbt().equals(f.banner().saveWithFullMetadata(h.getLevel().registryAccess())),
            "all Banner NBT, including stores and settlement link, must remain intact");
        h.assertTrue(SettlementManager.byId(h.getLevel(), f.settlement().id) == f.settlement(),
            "original settlement must remain registered");
    }
}
