package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Player-path contract for direct-to-settler physical job emblems.
 *
 * <p>These tests deliberately enter through
 * {@link JobEmblemItem#interactLivingEntity}: no plaque candidate button and no
 * administrative fixture seam is allowed to impersonate the player's flow.
 * Every refusal checks both ledgers -- roster and exact held stack -- because
 * preserving only one still permits either duplication or item loss.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EmploymentEmblemGameTests {

    private static final BlockPos HUT_ORIGIN = new BlockPos(5, 0, 5);

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_offhand_is_not_silently_spent")
    public void offhandEmblemIsNotSilentlySpent(GameTestHelper helper) {
        Fixture f = fixture(helper);
        ItemStack offhand = new ItemStack(ModItems.FARMER_EMBLEM.get());
        f.player.setItemInHand(InteractionHand.OFF_HAND, offhand);

        InteractionResult result = ((JobEmblemItem) offhand.getItem())
            .interactLivingEntity(offhand, f.player, f.candidate,
                InteractionHand.OFF_HAND);

        helper.assertTrue(result == InteractionResult.PASS,
            "only the visible selected main hand may authorize a job");
        assertUnemployed(helper, f);
        helper.assertTrue(offhand.getCount() == 1,
            "an off-hand emblem must remain untouched");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_no_compatible_post_preserves_item")
    public void noCompatiblePostPreservesEmblem(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.LUMBERER_EMBLEM.get(), 2));

        giveHeldEmblem(f);

        assertUnemployed(helper, f);
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "no Lumber Camp means no Lumberer emblem may be consumed");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_full_post_preserves_everything")
    public void fullPostPreservesEmblemAndCandidateRoster(GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity first = settler(helper, f.settlement, "First", 9, 8);
        SettlerEntity second = settler(helper, f.settlement, "Second", 9, 9);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
            f.workplace, first).ok(), "fixture: first farm post must fill");
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
            f.workplace, second).ok(), "fixture: second farm post must fill");
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 2));

        giveHeldEmblem(f);

        assertUnemployed(helper, f);
        helper.assertTrue(f.workplace.workers.size() == 2,
            "a full workplace refusal must leave its roster unchanged");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "a full workplace must consume no Farmer emblem");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_invalid_post_preserves_everything")
    public void invalidPostPreservesEmblemAndRoster(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.workplace.valid = false;
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 2));

        giveHeldEmblem(f);

        assertUnemployed(helper, f);
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "an inactive workplace must consume no Farmer emblem");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_success_consumes_once_and_replay_is_inert")
    public void successConsumesExactlyOneAndReplayConsumesNothing(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 3));

        giveHeldEmblem(f);

        helper.assertTrue(count(f.workplace, f.candidate.getUUID()) == 1,
            "direct emblem use must add exactly one roster entry");
        helper.assertTrue(f.candidate.getProfession() == Profession.FARMER,
            "the compatible farmhouse must project FARMER");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "one successful direct assignment must consume exactly one emblem");

        giveHeldEmblem(f);

        helper.assertTrue(count(f.workplace, f.candidate.getUUID()) == 1,
            "replaying the same emblem use must not duplicate employment");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "already working this trade must not consume a second emblem");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_reassignment_moves_once_and_consumes_once")
    public void reassignmentMovesOnceAndConsumesExactlyOne(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Building oldPost = GameTestFixtures.register(helper, f.settlement,
            BuildingType.LUMBER_CAMP, 12, 12);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
            oldPost, f.candidate).ok(),
            "fixture: candidate must begin at the Lumber Camp");
        UUID firstSale = UUID.randomUUID();
        ItemStack firstEmblems = new ItemStack(ModItems.FARMER_EMBLEM.get(), 2);
        helper.assertTrue(JourneyEmblemProvenance.stamp(firstEmblems,
                f.settlement.id, firstSale, Profession.FARMER),
            "fixture: survival-issued emblem must carry its sale transaction");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, firstEmblems);

        giveHeldEmblem(f);

        helper.assertFalse(oldPost.workers.contains(f.candidate.getUUID()),
            "reassignment must remove the old post in the same operation");
        helper.assertTrue(count(f.workplace, f.candidate.getUUID()) == 1,
            "reassignment must add exactly one new workplace entry");
        helper.assertTrue(f.candidate.getProfession() == Profession.FARMER,
            "reassignment must update the profession projection");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 1,
            "one successful reassignment must consume exactly one emblem");

        SettlerEntity copiedStampTarget = settler(helper, f.settlement,
            "Copied stamp target", 4, 4);
        helper.assertTrue(!Employment.hireWithHeldEmblem(helper.getLevel(),
                f.settlement, f.workplace, copiedStampTarget, f.player).ok()
                && f.player.getMainHandItem().getCount() == 1
                && !f.workplace.workers.contains(
                    copiedStampTarget.getUUID())
                && f.settlement.employmentAuthorizations.receipt(
                    copiedStampTarget.getUUID()) == null,
            "a second physical stack carrying the copied sale stamp must be "
                + "refused without consumption or a second authority receipt");

        helper.assertTrue(f.settlement.employmentAuthorizations.matches(
                f.settlement.id, f.candidate.getUUID(), f.workplace.id,
                Profession.FARMER)
                && f.settlement.employmentAuthorizations.receipt(
                    f.candidate.getUUID()).saleTransactionId().equals(firstSale),
            "charged reassignment must author the exact current receipt");
        Settlement restarted = Settlement.readNbt(f.settlement.writeNbt());
        helper.assertTrue(restarted.employmentAuthorizations.matches(
                f.settlement.id, f.candidate.getUUID(), f.workplace.id,
                Profession.FARMER),
            "current employment receipt must survive restart");

        Building rebuilt = GameTestFixtures.register(helper, f.settlement,
            BuildingType.FARMHOUSE, 1, 11);
        UUID rebuiltSale = UUID.randomUUID();
        ItemStack rebuiltEmblem = new ItemStack(ModItems.FARMER_EMBLEM.get());
        helper.assertTrue(JourneyEmblemProvenance.stamp(rebuiltEmblem,
                f.settlement.id, rebuiltSale, Profession.FARMER),
            "fixture: rebuilt post needs a newly purchased emblem");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, rebuiltEmblem);
        helper.assertTrue(Employment.hireWithHeldEmblem(helper.getLevel(),
                f.settlement, rebuilt, f.candidate, f.player).ok(),
            "a new building UUID must accept a new charged authorization");
        helper.assertFalse(f.settlement.employmentAuthorizations.matches(
                f.settlement.id, f.candidate.getUUID(), f.workplace.id,
                Profession.FARMER),
            "immutable tutorial history must not keep the old post authorized");
        helper.assertTrue(f.settlement.employmentAuthorizations.matches(
                f.settlement.id, f.candidate.getUUID(), rebuilt.id,
                Profession.FARMER),
            "rebuild/reassignment must overwrite authority with the new post UUID");

        helper.assertTrue(Employment.dismiss(helper.getLevel(), f.settlement,
                f.candidate) == rebuilt
                && f.settlement.employmentAuthorizations.receipt(
                    f.candidate.getUUID()) == null,
            "dismissal must clear current employment authority");
        SettlerEntity replacement = settler(helper, f.settlement,
            "Replacement", 3, 3);
        ItemStack serialReplay = new ItemStack(ModItems.FARMER_EMBLEM.get());
        helper.assertTrue(JourneyEmblemProvenance.stamp(serialReplay,
                f.settlement.id, firstSale, Profession.FARMER),
            "fixture: copied stack must carry the already-spent first sale");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, serialReplay);
        helper.assertTrue(!Employment.hireWithHeldEmblem(helper.getLevel(),
                f.settlement, rebuilt, replacement, f.player).ok()
                && f.player.getMainHandItem().getCount() == 1
                && !rebuilt.workers.contains(replacement.getUUID())
                && f.settlement.employmentAuthorizations.receipt(
                    replacement.getUUID()) == null,
            "dismissal/rebuild must not unspend a copied sale transaction");
        UUID replacementSale = UUID.randomUUID();
        ItemStack replacementEmblem = new ItemStack(
            ModItems.FARMER_EMBLEM.get());
        helper.assertTrue(JourneyEmblemProvenance.stamp(replacementEmblem,
                f.settlement.id, replacementSale, Profession.FARMER),
            "fixture: replacement needs its own physical emblem sale");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, replacementEmblem);
        helper.assertTrue(Employment.hireWithHeldEmblem(helper.getLevel(),
                f.settlement, rebuilt, replacement, f.player).ok()
                && f.settlement.employmentAuthorizations.matches(
                    f.settlement.id, replacement.getUUID(), rebuilt.id,
                    Profession.FARMER),
            "paid replacement must gain exact current authority after tutorial completion");

        helper.assertTrue(Employment.dismiss(helper.getLevel(), f.settlement,
                replacement) == rebuilt
                && Employment.hire(helper.getLevel(), f.settlement, rebuilt,
                    f.candidate).ok()
                && !f.settlement.employmentAuthorizations.matches(
                    f.settlement.id, f.candidate.getUUID(), rebuilt.id,
                    Profession.FARMER),
            "free/admin hire must never impersonate a consumed emblem receipt");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_nearest_compatible_post_wins")
    public void nearestCompatiblePostWinsDeterministically(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Building nearest = GameTestFixtures.register(helper, f.settlement,
            BuildingType.FARMHOUSE, 11, 10);
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 2));

        giveHeldEmblem(f);

        helper.assertTrue(nearest.workers.contains(f.candidate.getUUID()),
            "the nearest valid compatible workplace must be selected");
        helper.assertFalse(f.workplace.workers.contains(f.candidate.getUUID()),
            "a farther compatible workplace must remain untouched");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 1,
            "nearest-post selection must still consume exactly one emblem");
        helper.succeed();
    }

    /** Playtest 27 Sep #5: Shift + right-click with an ordinary tool opens the inventory. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "settler_interaction_shift_tool_opens_inventory")
    public void shiftRightClickWithPickaxeOpensInventory(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        f.player.setItemInHand(InteractionHand.OFF_HAND,
            new ItemStack(net.minecraft.world.item.Items.BREAD));
        f.player.setShiftKeyDown(true);
        boolean opened;
        try {
            InteractionResult result = f.candidate.interact(f.player, InteractionHand.MAIN_HAND);
            opened = result.consumesAction()
                && f.player.containerMenu instanceof com.hearthstead.menu.SettlerInventoryMenu;
        } catch (RuntimeException sent) {
            // The mock player has no client channel for NeoForge's extended
            // open-screen packet; reaching that send proves openMenu ran.
            opened = String.valueOf(sent.getMessage()).contains("open_screen");
        }
        f.player.setShiftKeyDown(false);
        helper.assertTrue(opened,
            "Shift + right-click with a pickaxe must open the settler inventory");
        f.player.closeContainer();
        // A held emblem keeps its own action: sneaking with it opens nothing.
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get()));
        f.player.setShiftKeyDown(true);
        f.candidate.interact(f.player, InteractionHand.MAIN_HAND);
        f.player.setShiftKeyDown(false);
        helper.assertFalse(f.player.containerMenu instanceof com.hearthstead.menu.SettlerInventoryMenu,
            "a held Job Emblem must keep its own interaction");
        helper.succeed();
    }

    /** Playtest 27 Sep #4: the player's explicit workplace choice wins over the nearest post. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_selected_workplace_is_authoritative")
    public void selectedWorkplaceIsAuthoritative(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Building nearest = GameTestFixtures.register(helper, f.settlement,
            BuildingType.FARMHOUSE, 11, 10);
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 2));
        Employment.selectWorkplace(f.player, f.workplace);

        giveHeldEmblem(f);

        helper.assertTrue(f.workplace.workers.contains(f.candidate.getUUID())
                && Employment.employerOf(f.settlement, f.candidate.getUUID()) == f.workplace,
            "the explicitly selected compatible workplace must be used");
        helper.assertFalse(nearest.workers.contains(f.candidate.getUUID()),
            "the nearer, unselected workplace must stay untouched");
        helper.assertTrue(f.candidate.getProfession() == Profession.FARMER,
            "the settler must take up the selected trade");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 1,
            "the selected hire consumes exactly one emblem");

        // Same trade, other post: re-select the nearer farmhouse and give the
        // emblem again. The worker moves; no second emblem is spent.
        Employment.selectWorkplace(f.player, nearest);
        giveHeldEmblem(f);
        helper.assertTrue(Employment.employerOf(f.settlement, f.candidate.getUUID()) == nearest
                && count(nearest, f.candidate.getUUID()) == 1
                && !f.workplace.workers.contains(f.candidate.getUUID()),
            "a same-trade worker must move to the newly selected workplace");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 1,
            "moving a worker inside the same trade must not spend an emblem");
        Employment.clearSelectedWorkplace(f.player);
        // The job goal reads exactly this roster entry, so he works there.
        helper.succeedWhen(() -> helper.assertTrue(
            Employment.employerOf(f.settlement, f.candidate.getUUID()) == nearest,
            "the worker must still belong to the selected workplace"));
    }

    /** A selected but unusable workplace refuses with its reason and keeps the emblem. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "employment_emblem_selected_workplace_refuses_clearly")
    public void selectedFullWorkplaceRefusesInsteadOfSwapping(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Building nearest = GameTestFixtures.register(helper, f.settlement,
            BuildingType.FARMHOUSE, 11, 10);
        SettlerEntity other = settler(helper, f.settlement, "Bran", 12, 12);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                f.workplace, other).ok() || !Employment.hasVacancy(f.settlement, f.workplace),
            "fixture: occupy the selected farmhouse");
        while (Employment.hasVacancy(f.settlement, f.workplace)) {
            SettlerEntity filler = settler(helper, f.settlement, "Filler", 13, 13);
            helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                f.workplace, filler).ok(), "fixture: fill the selected farmhouse");
        }
        f.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get(), 2));
        Employment.selectWorkplace(f.player, f.workplace);

        giveHeldEmblem(f);

        helper.assertFalse(nearest.workers.contains(f.candidate.getUUID()),
            "a full selected workplace must not be silently swapped for another");
        helper.assertTrue(f.player.getMainHandItem().getCount() == 2,
            "a refused selection must keep the emblem");
        helper.assertTrue(f.candidate.recentWorkRefusal() != null,
            "the refusal must be kept for the settler sheet's Right now line");
        Employment.clearSelectedWorkplace(f.player);
        helper.succeed();
    }

    private static void giveHeldEmblem(Fixture fixture) {
        ItemStack held = fixture.player.getMainHandItem();
        ((JobEmblemItem) held.getItem()).interactLivingEntity(held,
            fixture.player, fixture.candidate, InteractionHand.MAIN_HAND);
    }

    private static Fixture fixture(GameTestHelper helper) {
        floor(helper);
        buildFarmhouse(helper, HUT_ORIGIN);

        Settlement settlement = new Settlement(UUID.randomUUID(), "Emblemholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        BlockPos plaqueRel = HUT_ORIGIN.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        helper.assertTrue(helper.getLevel().getBlockEntity(plaqueAbs)
                instanceof PlaqueBlockEntity,
            "fixture: farmhouse plaque block entity must exist");
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel()
            .getBlockEntity(plaqueAbs);
        ItemStack plan = PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),
            BuildingType.FARMHOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan),
            "fixture: farmhouse plan must fit into the blank plaque");
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
            "fixture: furnished farmhouse must survey as valid, got " + plaque.state());
        Building workplace = plaque.building(helper.getLevel());
        helper.assertTrue(workplace != null && workplace.valid,
            "fixture: linked farmhouse must resolve its live Building");

        SettlerEntity candidate = settler(helper, settlement, "Eira", 10, 10);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(candidate.getX(), candidate.getY(), candidate.getZ() + 1.0D);
        return new Fixture(settlement, workplace, candidate, player);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** The production RoomScanner fixture, not a synthetic linked plaque. */
    private static void buildFarmhouse(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(1, 1, 2), Blocks.COMPOSTER);
        helper.setBlock(origin.offset(3, 1, 2), Blocks.CHEST);
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement,
                                          String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static void assertUnemployed(GameTestHelper helper, Fixture fixture) {
        helper.assertFalse(fixture.workplace.workers.contains(fixture.candidate.getUUID()),
            "a refused assignment must not change the workplace roster");
        helper.assertTrue(fixture.candidate.getProfession() == Profession.NONE,
            "a refused assignment must not change the profession projection");
    }

    private static int count(Building building, UUID settlerId) {
        int count = 0;
        for (UUID worker : building.workers) {
            if (worker.equals(settlerId)) {
                count++;
            }
        }
        return count;
    }

    private record Fixture(Settlement settlement, Building workplace,
                           SettlerEntity candidate, ServerPlayer player) {
    }
}
