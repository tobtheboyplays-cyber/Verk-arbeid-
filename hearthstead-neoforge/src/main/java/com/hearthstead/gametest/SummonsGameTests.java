package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.PlaqueAction;
import com.hearthstead.network.PlaqueNetwork;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * The plaque's "come here" — a player summoning one of their own workers to
 * stand in front of the plaque, glowing the whole way there so they read at
 * a glance and through walls.
 *
 * <p>Fixture split follows {@code RecruitGameTests}' own reasoning: {@link
 * Summons} and {@link com.hearthstead.entity.ai.RespondToSummonsGoal} are the
 * deterministic layer under test in (a) and (b) — called directly, the same
 * way {@code EmploymentGameTests} calls {@link Employment#hire} directly —
 * because the goal only needs to be trusted to walk a settler to a point and
 * stop glowing, not to also decide whether the call was allowed. (c) is the
 * one test that puts the actual plaque protocol ({@link PlaqueNetwork}) in
 * front of a real, surveyed building, because that is specifically what
 * decides whether a call is allowed in the first place.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SummonsGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** Mirrors {@code EmploymentGameTests}' fixture: small on purpose, see there. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** Keep the login Starter Handbook intact while making the delivery branch explicit. */
    private static void selectEmptyMainHand(GameTestHelper helper,
                                            ServerPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().selected = slot;
                helper.assertTrue(player.getMainHandItem().isEmpty(),
                    "fixture: selected empty hotbar slot must expose an empty main hand");
                return;
            }
        }
        helper.fail("fixture: Fire return requires one empty hotbar slot");
    }

    /**
     * Seals the block column above {@code standingRel} (assumed to already
     * be solid ground) on all four sides and overhead, two blocks tall — no
     * door, no gap. A settler placed here has no path out at all, which is
     * exactly the point: it isolates the summons's OWN 90 s clock (and its
     * level-tick leak guard) as the only possible way the glow ever clears,
     * with {@code RespondToSummonsGoal} itself permanently unable to help.
     */
    private static void seal(GameTestHelper helper, BlockPos standingRel) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            helper.setBlock(standingRel.relative(dir), Blocks.STONE_BRICKS);
            helper.setBlock(standingRel.relative(dir).above(), Blocks.STONE_BRICKS);
        }
        helper.setBlock(standingRel.above(2), Blocks.STONE_BRICKS);
    }

    /**
     * A 5x5 room shaped exactly like {@code HearthsteadGameTests}' house
     * fixture, refitted for a building that EMPLOYS rather than houses: a
     * composter and a chest stand where the bed would, and the footprint is
     * unchanged because it already measures well past any of this catalogue's
     * {@code floor_space} demands (proven by the house fixture clearing
     * {@code floor_space(9)} on the same shape).
     */
    private static void buildWorkHut(GameTestHelper helper, BlockPos o) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Door in the south wall (z=0), lower + upper half -- same spot
        // buildHut uses, so the room-scanner's seed candidates behave the
        // same way here as they do there.
        helper.setBlock(o.offset(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(o.offset(1, 1, 2), Blocks.COMPOSTER);
        helper.setBlock(o.offset(3, 1, 2), Blocks.CHEST);
        helper.setBlock(o.offset(1, 2, 1), Blocks.TORCH);
    }

    private static ItemStack buildPlan(BuildingType type) {
        return PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type);
    }

    /** Hangs a blank plaque outside {@code hutOrigin}'s south wall and fits it. */
    private static BlockPos hangWorkPlaque(GameTestHelper helper, BlockPos hutOrigin,
                                           BuildingType type) {
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(plaqueRel);
        if (helper.getLevel().getBlockEntity(abs) instanceof PlaqueBlockEntity plaque) {
            plaque.insertPlan(helper.getLevel(), buildPlan(type));
        }
        return plaqueRel;
    }

    // --------------------------------------------------------------- (a) ---

    /**
     * The whole feature, end to end: called, the settler glows, walks to the
     * call point, and stops glowing the moment they arrive.
     */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 400)
    public void summonedSettlerWalksToTheCallAndStopsGlowingOnArrival(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, farm, astrid).ok(),
            "setup: hiring astrid into the farmhouse must succeed");

        BlockPos callPoint = helper.absolutePos(new BlockPos(12, 1, 12));
        Summons.call(astrid, callPoint, helper.getLevel());
        helper.assertTrue(astrid.isCurrentlyGlowing(),
            "setup: a settler must start glowing the instant they are called");

        boolean[] sawGlowingEnRoute = {false};
        helper.succeedWhen(() -> {
            if (astrid.isCurrentlyGlowing()) {
                sawGlowingEnRoute[0] = true;
            }
            helper.assertTrue(astrid.blockPosition().closerThan(callPoint, 2.5),
                "astrid should have reached the call point by now, at "
                    + astrid.blockPosition());
            helper.assertTrue(sawGlowingEnRoute[0],
                "astrid should have visibly glowed at some point on the way");
            helper.assertFalse(astrid.isCurrentlyGlowing(),
                "the glow must clear the moment the settler arrives");
        });
    }

    // --------------------------------------------------------------- (b) ---

    /**
     * Regression: a settler who can never physically reach the call point
     * must still lose the glow once the call's own clock runs out — the
     * arrival goal is not the only thing allowed to end a summons.
     */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = Summons.DURATION_TICKS + 200)
    public void expiredSummonsClearsGlowEvenWhenWalledOff(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity sealedAway = settler(helper, s, "SealedAway", 4, 4);
        seal(helper, new BlockPos(4, 1, 4));

        BlockPos callPoint = helper.absolutePos(new BlockPos(12, 1, 12));
        Summons.call(sealedAway, callPoint, helper.getLevel());
        helper.assertTrue(sealedAway.isCurrentlyGlowing(),
            "setup: a settler must start glowing the instant they are called");

        helper.succeedWhen(() -> helper.assertFalse(sealedAway.isCurrentlyGlowing(),
            "a walled-off settler's glow must still clear once the summons expires"));
    }

    /** A failed physical route ends this call once; it never traps unrelated bag cargo. */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 100)
    public void unreachableSummonsEndsImmediatelyWithoutTouchingBag(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity sealedAway = settler(helper, s, "Bag Carrier", 4, 4);
        sealedAway.bag.setItem(0, new ItemStack(Blocks.OAK_LOG, 6));
        seal(helper, new BlockPos(4, 1, 4));

        BlockPos callPoint = helper.absolutePos(new BlockPos(12, 1, 12));
        Summons.call(sealedAway, callPoint, helper.getLevel());
        helper.succeedWhen(() -> {
            helper.assertFalse(Summons.active(sealedAway),
                "a physically unreachable summons must end before its 90-second expiry");
            helper.assertFalse(sealedAway.isCurrentlyGlowing(),
                "ending an unreachable summons must remove its glow");
            helper.assertTrue(sealedAway.routeFailureNote().startsWith("summons_unreachable@"),
                "the worker must report the exact terminal route reason");
            helper.assertTrue(sealedAway.bag.getItem(0).is(Blocks.OAK_LOG.asItem())
                    && sealedAway.bag.getItem(0).getCount() == 6,
                "a failed summons must preserve all six physical bag logs");
        });
    }

    // --------------------------------------------------------------- (c) ---

    /** A SUMMON for a settler this building does not employ is refused outright. */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 100)
    public void summonRefusedForASettlerTheBuildingDoesNotEmploy(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildWorkHut(helper, hutOrigin);
        BlockPos plaqueRel = hangWorkPlaque(helper, hutOrigin, BuildingType.FARMHOUSE);
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
            "setup: the farmhouse should register from this room, got " + plaque.state());

        // Never hired anywhere -- exactly the settler this building must
        // refuse to summon.
        SettlerEntity stranger = settler(helper, s, "Stranger", 10, 10);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(player.serverLevel() == helper.getLevel(),
            "setup: the mock player must land in the arena's own level for "
                + "PlaqueNetwork's reach check to mean anything");
        player.setPos(plaqueAbs.getX() + 0.5, plaqueAbs.getY(), plaqueAbs.getZ() + 0.5);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        UUID sessionId = PlaqueNetwork.openFor(player, plaque);
        PlaqueNetwork.handle(player, new PlaqueAction(plaqueAbs,
            plaque.buildingId(), sessionId, PlaqueAction.Kind.SUMMON,
            stranger.getUUID(), plaque.revision()));

        helper.assertFalse(Summons.active(stranger),
            "a SUMMON for a settler this building does not employ must be refused");
        helper.assertFalse(stranger.isCurrentlyGlowing(),
            "a refused summon must never glow the settler");
        helper.succeed();
    }
    /**
     * The Staff button reuses the plaque protocol: it creates the live
     * summons signal, but leaves the worker at their real starting block for
     * RespondToSummonsGoal to walk from on later ticks.
     */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 100)
    public void plaqueSummonStartsWalkingWithoutTeleporting(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildWorkHut(helper, hutOrigin);
        BlockPos plaqueRel = hangWorkPlaque(helper, hutOrigin, BuildingType.FARMHOUSE);
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        Building farm = plaque.building(helper.getLevel());
        SettlerEntity worker = settler(helper, s, "Worker", 12, 12);
        helper.assertTrue(farm != null && Employment.hire(helper.getLevel(), s, farm, worker).ok(),
            "setup: the surveyed farmhouse must employ the real worker");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(plaqueAbs.getX() + 0.5, plaqueAbs.getY(), plaqueAbs.getZ() + 0.5);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        UUID sessionId = PlaqueNetwork.openFor(player, plaque);
        BlockPos before = worker.blockPosition();
        PlaqueNetwork.handle(player, new PlaqueAction(plaqueAbs, plaque.buildingId(),
            sessionId, PlaqueAction.Kind.SUMMON, worker.getUUID(), plaque.revision()));

        helper.assertTrue(Summons.active(worker) && Summons.where(worker).equals(
                plaqueAbs.relative(Direction.NORTH)),
            "SUMMON must create the exact live call at the plaque front");
        helper.assertTrue(worker.blockPosition().equals(before),
            "SUMMON must not teleport a worker; their normal goal walks them there");
        helper.succeed();
    }

    /**
     * Fire is one settlement transaction: reserve one player-owned emblem,
     * complete the ordinary dismissal lifecycle, then make the same packet
     * harmless because the worker no longer belongs to this workplace.
     */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 100)
    public void plaqueFireReturnsOneEmblemAndReplayCannotDuplicateIt(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildWorkHut(helper, hutOrigin);
        BlockPos plaqueRel = hangWorkPlaque(helper, hutOrigin, BuildingType.FARMHOUSE);
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        Building farm = plaque.building(helper.getLevel());
        SettlerEntity worker = settler(helper, s, "Worker", 12, 12);
        helper.assertTrue(farm != null && Employment.hire(helper.getLevel(), s, farm, worker).ok(),
            "setup: the surveyed farmhouse must employ the real worker");
        UUID paidSale = UUID.randomUUID();
        helper.assertTrue(s.employmentAuthorizations.authorize(s.id, worker.getUUID(),
                farm.id, Profession.FARMER, paidSale),
            "setup: FIRE needs the same durable paid-employment proof as survival");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(plaqueAbs.getX() + 0.5, plaqueAbs.getY(), plaqueAbs.getZ() + 0.5);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        selectEmptyMainHand(helper, player);
        UUID sessionId = PlaqueNetwork.openFor(player, plaque);
        int revision = plaque.revision();
        PlaqueAction fire = new PlaqueAction(plaqueAbs, plaque.buildingId(), sessionId,
            PlaqueAction.Kind.FIRE, worker.getUUID(), revision,
            s.employmentAuthorizations.receipt(worker.getUUID()).revision());
        PlaqueNetwork.handle(player, fire);

        helper.assertTrue(Employment.employerOf(s, worker.getUUID()) == null
                && worker.getProfession() == Profession.NONE,
            "FIRE must use ordinary dismissal and leave the worker unemployed");
        helper.assertTrue(player.getMainHandItem().is(ModItems.FARMER_EMBLEM.get())
                && player.getMainHandItem().getCount() == 1,
            "FIRE must return exactly one physical Farmer Emblem to the actor");

        PlaqueNetwork.handle(player, fire);
        helper.assertTrue(player.getMainHandItem().is(ModItems.FARMER_EMBLEM.get())
                && player.getMainHandItem().getCount() == 1
                && Employment.employerOf(s, worker.getUUID()) == null,
            "the exact replay must not restore work or duplicate the returned emblem");
        helper.succeed();
    }

    /**
     * A Fire action names the paid employment it was shown, rather than the
     * plaque's unrelated room revision.  A late first click may therefore not
     * dismiss a worker who has since spent a different physical emblem.
     */
    @GameTest(batch = "summons", template = "empty16", timeoutTicks = 100)
    public void plaqueFirePaidRehireGetsFreshReturnAndRejectsStaleClick(
            GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildWorkHut(helper, hutOrigin);
        BlockPos plaqueRel = hangWorkPlaque(helper, hutOrigin, BuildingType.FARMHOUSE);
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        Building farm = plaque.building(helper.getLevel());
        SettlerEntity worker = settler(helper, s, "Returner", 12, 12);
        helper.assertTrue(farm != null && Employment.hire(helper.getLevel(), s, farm, worker).ok(),
            "setup: the farmhouse must employ the worker");
        UUID firstSale = UUID.randomUUID();
        helper.assertTrue(s.employmentAuthorizations.authorize(s.id, worker.getUUID(),
                farm.id, Profession.FARMER, firstSale),
            "setup: first employment must have a durable paid-emblem receipt");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(plaqueAbs.getX() + 0.5, plaqueAbs.getY(), plaqueAbs.getZ() + 0.5);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        selectEmptyMainHand(helper, player);
        UUID sessionId = PlaqueNetwork.openFor(player, plaque);
        int sheetRevision = plaque.revision();
        long firstEmployment = s.employmentAuthorizations.receipt(worker.getUUID()).revision();
        PlaqueAction firstFire = new PlaqueAction(plaqueAbs, plaque.buildingId(), sessionId,
            PlaqueAction.Kind.FIRE, worker.getUUID(), sheetRevision, firstEmployment);
        PlaqueNetwork.handle(player, firstFire);
        helper.assertTrue(player.getInventory().countItem(ModItems.FARMER_EMBLEM.get()) == 1
                && Employment.employerOf(s, worker.getUUID()) == null,
            "the first paid employment must return one physical emblem and dismiss once");

        ItemStack firstReturn = player.getMainHandItem().copy();
        int parkingSlot = -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (slot != player.getInventory().selected
                    && player.getInventory().getItem(slot).isEmpty()) {
                parkingSlot = slot;
                break;
            }
        }
        helper.assertTrue(parkingSlot >= 0,
            "fixture: the first returned emblem needs a distinct empty inventory slot");
        player.getInventory().setItem(parkingSlot, firstReturn);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND,
            player.getInventory().getItem(parkingSlot));
        player.getInventory().setItem(parkingSlot, ItemStack.EMPTY);
        UUID returnedAuthorization = JourneyEmblemProvenance.read(player.getMainHandItem())
            .filter(provenance -> provenance.employmentReturn()
                && provenance.profession() == Profession.FARMER)
            .map(JourneyEmblemProvenance.Provenance::transactionId)
            .orElse(null);
        helper.assertTrue(returnedAuthorization != null && !returnedAuthorization.equals(firstSale),
            "FIRE must return a fresh physical authorization, not recreate the Mayor sale");
        helper.assertTrue(Employment.hireWithHeldEmblem(helper.getLevel(), s, farm, worker, player).ok(),
            "the returned physical emblem must rehire the same worker");
        long secondEmployment = s.employmentAuthorizations.receipt(worker.getUUID()).revision();
        helper.assertTrue(secondEmployment != firstEmployment
                && s.employmentAuthorizations.receipt(worker.getUUID())
                    .saleTransactionId().equals(returnedAuthorization)
                && player.getInventory().countItem(ModItems.FARMER_EMBLEM.get()) == 0,
            "rehire must consume the returned emblem and create a fresh relation token");

        PlaqueNetwork.handle(player, firstFire);
        helper.assertTrue(Employment.employerOf(s, worker.getUUID()) == farm
                && player.getInventory().countItem(ModItems.FARMER_EMBLEM.get()) == 0,
            "a delayed Fire click for the first employment must not dismiss the rehire");

        PlaqueAction secondFire = new PlaqueAction(plaqueAbs, plaque.buildingId(), sessionId,
            PlaqueAction.Kind.FIRE, worker.getUUID(), sheetRevision, secondEmployment);
        PlaqueNetwork.handle(player, secondFire);
        helper.assertTrue(Employment.employerOf(s, worker.getUUID()) == null
                && player.getInventory().countItem(ModItems.FARMER_EMBLEM.get()) == 1,
            "the fresh employment must return one physical emblem again");
        PlaqueNetwork.handle(player, secondFire);
        helper.assertTrue(player.getInventory().countItem(ModItems.FARMER_EMBLEM.get()) == 1
                && Employment.employerOf(s, worker.getUUID()) == null,
            "replaying the fresh Fire request must not mint or re-employ anyone");
        helper.succeed();
    }
}
