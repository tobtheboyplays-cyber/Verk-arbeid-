package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The owner's Elmfield house (captain1 soak 2026-09-26, lumberer Dunstan):
 * a settler stands at 116.7,77.0,-123.9 on the top-slab balcony corner,
 * beside the fence post at 116,77,-124 and the log beam at 117,76,-124,
 * directly south of the only way down, a ladder at 116,73..76,-125 on the
 * house wall. Everything below is a four-block drop. He recorded
 * "nav:stuck" towards the Hearth for the whole soak at 0 energy.
 * Geometry copied relative to (116, 72, -126) = (0, 1, 0).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ElmfieldBalconyGameTests {

    @GameTest(batch = "elmfield_balcony", template = "empty16", timeoutTicks = 1600)
    public void aSettlerOnTheBalconyCornerClimbsDownTheLadder(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.COBBLESTONE);
                for (int y = 2; y < 8; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        int ox = 4, oz = 3; // (116, 72, -126) -> (ox, 1, oz)
        // house wall the ladder hangs on (116, 73..79, -126)
        for (int y = 2; y <= 7; y++) helper.setBlock(new BlockPos(ox, y, oz), Blocks.OAK_PLANKS);
        for (int y = 2; y <= 7; y++) helper.setBlock(new BlockPos(ox - 1, y, oz), Blocks.OAK_PLANKS);
        for (int y = 2; y <= 7; y++) helper.setBlock(new BlockPos(ox + 1, y, oz), Blocks.OAK_PLANKS);
        // ladder (116, 73..76, -125), facing south onto the wall
        for (int y = 2; y <= 5; y++) {
            helper.setBlock(new BlockPos(ox, y, oz + 1),
                Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        // corner post log (117, 73..79, -125)
        for (int y = 2; y <= 7; y++) helper.setBlock(new BlockPos(ox + 1, y, oz + 1), Blocks.OAK_LOG);
        // balcony: top slab (116,76,-124) with fence (116,77,-124); log beam (117,76,-124..-123)
        helper.setBlock(new BlockPos(ox, 5, oz + 2),
            Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        helper.setBlock(new BlockPos(ox, 6, oz + 2), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(ox - 1, 5, oz + 2),
            Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        helper.setBlock(new BlockPos(ox - 1, 6, oz + 2), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(ox + 1, 5, oz + 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(ox + 1, 5, oz + 3), Blocks.OAK_LOG);

        BlockPos hearthRel = new BlockPos(10, 2, 11);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Elmhjem", helper.absolutePos(hearthRel));
        s.radius = 16;
        data.settlements.put(s.id, s);
        data.setDirty();
        ((HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))).bindSettlement(s.id);

        SettlerEntity dunstan = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(dunstan != null, "settler constructs");
        BlockPos corner = helper.absolutePos(new BlockPos(ox, 6, oz + 2));
        // Dunstan's exact saved position inside the corner cell: x+.70, z+.075, feet on the slab top.
        dunstan.setPos(new Vec3(corner.getX() + 0.70D, corner.getY(), corner.getZ() + 0.075D));
        helper.getLevel().addFreshEntity(dunstan);
        dunstan.setSettlerName("Dunstan");
        dunstan.bindTo(s.id, s.center);
        s.putRecord(dunstan.getUUID(), "Dunstan", com.hearthstead.entity.Profession.NONE);
        dunstan.setEnergy(0.0F); // exhausted: RestAtNightGoal walks him to the Hearth
        dunstan.setHunger(100.0F);
        helper.getLevel().setDayTime(18000);

        int ground = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
        helper.succeedWhen(() -> helper.assertTrue(dunstan.blockPosition().getY() <= ground,
            "the settler must get off the balcony, at " + helper.relativePos(dunstan.blockPosition())
                + " route=" + dunstan.routeFailureNote()));
    }
}
