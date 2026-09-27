package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.event.GoblinTheftDirector;
import com.hearthstead.event.GoblinTheftSavedData;
import com.hearthstead.event.GoblinThiefDemo;
import com.hearthstead.event.worldevent.WorldEventDirector;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Bug-hunt lane, EVENTS-AUDIT goblin scenes (26 Sep). Batches
 * {@code bughunt_goblin_*}:
 * <ul>
 *   <li>{@code /hsgoblin thief <player>} (GoblinTheftDirector.forceNatural)
 *   publishes a real natural thief in a fresh village for QA, and an escape
 *   is recorded as "escaped", not "empty_departure";</li>
 *   <li>a thief reloaded in the middle of picking a door, after it had been
 *   spotted, keeps fleeing instead of walking back unspottable.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GoblinAuditGameTests {
    private static final String TAG = "HearthsteadGoblinThiefDemo";

    private static void floor(GameTestHelper h) {
        StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 60, batch = "bughunt_goblin_force")
    public void theQaCommandPublishesANaturalThiefAndAnEscapeIsRecorded(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        floor(h);
        BlockPos hearth = h.absolutePos(new BlockPos(32, 1, 32));
        level.setBlockAndUpdate(hearth, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement fresh;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            fresh = SettlementManager.tryFound(level, hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(fresh != null, "fixture: founded");
        ((HearthBlockEntity) level.getBlockEntity(hearth)).bindSettlement(fresh.id);
        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.onUpdateAbilities();
        player.teleportTo(hearth.getX() + .5, hearth.getY(), hearth.getZ() + 3.5);
        player.getInventory().setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 3));
        h.assertTrue(!WorldEventDirector.hostileReady(level, fresh), "fixture: a fresh village is in its grace");

        String failure = GoblinTheftDirector.forceNatural(level, fresh, player);
        h.assertTrue(failure == null, "the QA force must publish a natural thief even in the grace: " + failure);
        var view = GoblinTheftSavedData.get(level).view(fresh.id);
        h.assertTrue(view != null && view.active() != null, "the cadence row records the active thief");
        Entity found = level.getEntity(view.active());
        h.assertTrue(found instanceof RaiderEntity, "the published thief is in the world");
        RaiderEntity thief = (RaiderEntity) found;
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        h.assertTrue(state.hasUUID("NaturalSettlement") && player.getUUID().equals(state.getUUID("PlayerTarget")),
            "it is a natural pickpocket thief aimed at the named player");
        h.assertTrue(GoblinTheftDirector.forceNatural(level, fresh, player) != null,
            "a second force is refused while one thief is out");

        // The escape path: the goal flags Escaped, empties the sack, then discards.
        state.putBoolean("Escaped", true);
        thief.discard();
        var after = GoblinTheftSavedData.get(level).view(fresh.id);
        h.assertTrue(after.active() == null && "escaped".equals(after.outcome()),
            "an escape is recorded as escaped, got " + after.outcome());

        for (var actor : SettlementManager.loadedMembers(level, fresh)) actor.discard();
        SettlementSavedData.get(level).settlements.remove(fresh.id);
        level.setBlockAndUpdate(hearth, Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 60, batch = "bughunt_goblin_door_reload")
    public void aSpottedThiefReloadedMidDoorKeepsFleeing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        floor(h);
        BlockPos chestPos = h.absolutePos(new BlockPos(10, 1, 10));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 2));
        RaiderEntity thief = GoblinThiefDemo.spawn(level, chestPos, h.absolutePos(new BlockPos(24, 1, 10)));
        h.assertTrue(thief != null, "fixture: a demo thief sets out");
        // Mid door-pick after it was spotted and turned to flee, sack empty (a hit knocked the Coin loose).
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        state.putInt("Stage", 3);
        state.putInt("AfterDoor", 2);
        state.putBoolean("SpottedCue", true);
        state.putLong("PickDoor", h.absolutePos(new BlockPos(20, 1, 10)).asLong());
        h.assertTrue(thief.lootCount() == 0, "fixture: empty sack");
        CompoundTag saved = new CompoundTag();
        h.assertTrue(thief.save(saved), "fixture: the thief saves");
        thief.discard();
        Entity reloaded = EntityType.loadEntityRecursive(saved, level, e -> e);
        h.assertTrue(reloaded instanceof RaiderEntity, "fixture: the thief loads back");
        h.assertTrue(level.addFreshEntity(reloaded), "fixture: the reloaded thief joins");
        RaiderEntity again = (RaiderEntity) reloaded;
        h.runAfterDelay(3, () -> {
            CompoundTag s = again.getPersistentData().getCompound(TAG);
            h.assertTrue(s.getInt("Stage") == 2 && !s.contains("PickDoor"),
                "a spotted thief reloaded mid door-pick keeps fleeing (stage 2), got stage " + s.getInt("Stage"));
            again.discard();
            for (Entity e : level.getEntitiesOfClass(RaiderEntity.class, new AABB(chestPos).inflate(40),
                    r -> r.getPersistentData().contains(TAG))) e.discard();
            h.succeed();
        });
    }
}
