package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/**
 * The host may serve from any clear side of a table. A chair whose across-table
 * cell is another bench (or the other half of a two-block table) must still
 * yield one stable SeatSite whose host stand is a real clear side or corner.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernTableSideGameTests {
    private static final BlockPos CHAIR = new BlockPos(6, 1, 7);
    private static final BlockPos TABLE = new BlockPos(6, 1, 6);
    private static final BlockPos STORE = new BlockPos(3, 1, 3);

    private record Fixture(Settlement settlement, Building tavern) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        // Evening of the current day; never rewind the level clock across days.
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        h.getLevel().setDayTime(day + 11500L);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Table side fixture",
            h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(settlement.id, settlement);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, settlement, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)),
                h.absolutePos(new BlockPos(12, 4, 12))));
        table(h, TABLE);
        // The diner looks NORTH across the table (stair back faces the diner).
        chair(h, CHAIR, Direction.SOUTH);
        return new Fixture(settlement, tavern);
    }

    private static void table(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, Blocks.OAK_FENCE);
        h.setBlock(rel.above(), Blocks.OAK_PRESSURE_PLATE);
    }

    private static void chair(GameTestHelper h, BlockPos rel, Direction stairFacing) {
        h.setBlock(rel, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, stairFacing));
    }

    private static SettlerEntity resident(GameTestHelper h, Settlement settlement, BlockPos pos) {
        SettlerEntity actor = h.spawn(ModEntities.SETTLER.get(), pos);
        actor.bindTo(settlement.id, settlement.center);
        settlement.putRecord(actor.getUUID(), "Table side resident", Profession.NONE);
        actor.setNoAi(true);
        actor.setHunger(100);
        actor.setEnergy(100);
        actor.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, -.05, 0));
        return actor;
    }

    private static TavernSeating.SeatSite siteFor(GameTestHelper h, Fixture f, BlockPos aisleRel) {
        return TavernSeating.site(h.getLevel(), f.tavern(), h.absolutePos(CHAIR), h.absolutePos(aisleRel));
    }

    /** Across cell is another bench: the host moves to the clockwise flank, deterministically. */
    @GameTest(batch = "tavern_table_side_flank", template = "empty16", timeoutTicks = 40)
    public void blockedAcrossCellUsesClearSideStand(GameTestHelper h) {
        Fixture f = fixture(h);
        // Opposite diner's bench occupies the legacy across cell.
        chair(h, TABLE.north(), Direction.NORTH);
        SettlerEntity probe = resident(h, f.settlement(), new BlockPos(10, 1, 10));
        BlockPos aisle = CHAIR.west();
        var site = siteFor(h, f, aisle);
        h.assertTrue(site != null, "a chair whose across cell is a bench still yields a SeatSite");
        h.assertTrue(!site.hostApproach().equals(h.absolutePos(TABLE.north())),
            "the blocked across bench is never the host stand");
        h.assertTrue(site.hostApproach().equals(h.absolutePos(TABLE.east())),
            "clockwise flank is the first clear side after the across cell; got " + site.hostApproach());
        h.assertTrue(TavernSeating.clearStand(h.getLevel(), probe, site.hostApproach()),
            "the chosen host stand is physically standable");
        h.assertTrue(site.equals(siteFor(h, f, aisle)) && TavernSeating.sameSite(h.getLevel(), f.tavern(), site),
            "recomputation is stable for equality checks");
        h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(site.hostYaw() - Direction.WEST.toYRot())) < .01F,
            "an east-flank host faces west toward the table cell");

        // Block the clockwise flank too: the counter-clockwise flank wins.
        h.setBlock(TABLE.east(), Blocks.STONE_BRICKS);
        var west = siteFor(h, f, aisle);
        h.assertTrue(west != null && west.hostApproach().equals(h.absolutePos(TABLE.west())),
            "counter-clockwise flank follows a blocked clockwise flank");
        // A saved host stand that is still admissible is retained, never reshuffled.
        h.setBlock(TABLE.east(), Blocks.AIR);
        h.assertTrue(TavernSeating.sameSite(h.getLevel(), f.tavern(), west),
            "a saved seat keeps its still-valid side stand even when an earlier side reopens");
        h.assertTrue(site.equals(siteFor(h, f, aisle)), "fresh recomputation returns the first clear side again");
        h.succeed();
    }

    /** Owner-world geometry: a two-block table fully ringed by six benches. */
    @GameTest(batch = "tavern_table_side_ring", template = "empty16", timeoutTicks = 40)
    public void surroundedTwoBlockTableUsesCornerStand(GameTestHelper h) {
        Fixture f = fixture(h);
        ringTwoBlockTable(h);
        SettlerEntity probe = resident(h, f.settlement(), new BlockPos(10, 1, 10));
        BlockPos aisle = CHAIR.west();
        var site = siteFor(h, f, aisle);
        h.assertTrue(site != null, "surrounded two-block table still yields a SeatSite");
        h.assertTrue(site.hostApproach().equals(h.absolutePos(TABLE.north().west())),
            "the first clear corner is the host stand; got " + site.hostApproach());
        h.assertTrue(TavernSeating.clearStand(h.getLevel(), probe, site.hostApproach())
                && TavernSeating.clearStand(h.getLevel(), probe, site.aisle()),
            "host stand and diner aisle are both physically standable");
        h.assertTrue(!site.hostApproach().equals(site.aisle()) && !site.hostApproach().equals(site.chair()),
            "the host stand never reuses the diner's chair or aisle");
        h.assertTrue(site.equals(siteFor(h, f, aisle)) && TavernSeating.sameSite(h.getLevel(), f.tavern(), site),
            "corner stand is stable across recomputation");
        h.succeed();
    }

    /** Full chain on the ringed table: real reservation, real order, host reaches table contact from a corner. */
    @GameTest(batch = "tavern_table_side_contact", template = "empty16", timeoutTicks = 160)
    public void hostReachesTableContactFromSideStand(GameTestHelper h) {
        Fixture f = fixture(h);
        ringTwoBlockTable(h);
        h.setBlock(STORE, Blocks.BARREL);
        Container store = (Container) h.getLevel().getBlockEntity(h.absolutePos(STORE));
        store.setItem(0, new ItemStack(Items.BREAD, 2));
        store.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 2));
        SettlerEntity host = resident(h, f.settlement(), new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), f.settlement(), f.tavern(), host).ok(), "real Innkeeper hire");
        SettlerEntity guest = resident(h, f.settlement(), new BlockPos(10, 1, 10));
        guest.setHunger(20);
        var reservation = TavernSeating.reserveReachable(guest);
        h.assertTrue(reservation.seat() != null, "a surrounded table still offers a real seat reservation");
        TavernSeatEntity seat = reservation.seat();
        var site = seat.site();
        BlockPos across = site.table().relative(site.dinerFacing());
        h.assertTrue(!site.hostApproach().equals(across) || TavernSeating.clearStand(h.getLevel(), host, across),
            "reserved seat never uses a blocked across cell");
        h.assertTrue(TavernSeating.clearStand(h.getLevel(), host, site.hostApproach()),
            "reserved seat's host stand is clear for the real host");
        guest.setPos(Vec3.atBottomCenterOf(site.aisle()));
        h.assertTrue(seat.mount(guest), "real chair entry");
        h.assertTrue(TavernSeating.valid(h.getLevel(), guest, site), "seated site stays valid on recomputation");
        TavernServingEntity order = TavernHostService.reserveQuote(host, guest, seat, false);
        h.assertTrue(order != null && site.equals(order.site()), "real order claims the same side-stand site");
        host.getNavigation().stop();
        host.setPos(Vec3.atBottomCenterOf(site.hostApproach()));
        h.succeedWhen(() -> {
            h.assertTrue(TavernHostService.tableContact(host, order), "host has table contact from its side stand");
            h.assertTrue(TavernHostService.settleTableFacing(host, order), "host settles facing the table cell");
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(host.getYRot() - site.hostYaw())) <= 1.0F,
                "host yaw points from its stand to the table, not across from the diner");
        });
    }

    /**
     * Table cells TABLE and TABLE.east(); six benches: two per long side
     * (south diners look north, north diners look south) and one per end.
     */
    private static void ringTwoBlockTable(GameTestHelper h) {
        table(h, TABLE.east());
        chair(h, CHAIR.east(), Direction.SOUTH);
        chair(h, TABLE.north(), Direction.NORTH);
        chair(h, TABLE.east().north(), Direction.NORTH);
        chair(h, TABLE.west(), Direction.WEST);
        chair(h, TABLE.east().east(), Direction.EAST);
    }
}
