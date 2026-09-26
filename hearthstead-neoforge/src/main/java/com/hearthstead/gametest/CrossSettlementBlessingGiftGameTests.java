package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * A physical Blessing Seal is a gift, not a settlement-bound permission token.
 *
 * <p>Each test first earns and reserves the real reward in settlement A's
 * authoritative {@link BlessingState}, then carries the canonical physical
 * item to a target owned by settlement B. The issued counter must stay on A;
 * the permanent target rank must stay on B. This distinction catches both a
 * future hidden owner gate and an accidental settlement-wide effect write.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CrossSettlementBlessingGiftGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_gift_a_reward_b_settler_full_interaction")
    public void rewardFromACanBlessBSettlerWithoutOwnerGate(
            GameTestHelper helper) {
        SettlementPair settlements = settlements(helper,
            new BlockPos(2, 1, 2), 4,
            new BlockPos(12, 1, 12), 4);
        SettlerEntity aSettler = boundSettler(helper, settlements.a(),
            "A-giver", new BlockPos(2, 1, 2));
        SettlerEntity bTarget = boundSettler(helper, settlements.b(),
            "B-target", new BlockPos(12, 1, 12));
        SettlerEntity bInvalid = boundSettler(helper, settlements.b(),
            "B-invalid", new BlockPos(11, 1, 12));
        quarantineTargetLedger(bInvalid);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;
        player.setShiftKeyDown(true);
        player.setPos(settlements.a().center.getX() + 0.5D,
            settlements.a().center.getY(), settlements.a().center.getZ() + 0.5D);

        ItemStack wardenRewards = issueFrom(helper, settlements.a(),
            settlements.b(), BlessingId.WARDEN_OATH, 4);
        ItemStack consumingMain = new ItemStack(Items.NAME_TAG, 2);
        consumingMain.set(DataComponents.CUSTOM_NAME,
            Component.literal("Must not rename B-target"));
        player.setItemInHand(InteractionHand.MAIN_HAND, consumingMain);
        player.setItemInHand(InteractionHand.OFF_HAND, wardenRewards);
        player.setPos(bTarget.getX(), bTarget.getY(), bTarget.getZ());

        int beforeFirst = player.getOffhandItem().getCount();
        InteractionResult first = player.interactOn(bTarget,
            InteractionHand.MAIN_HAND);
        helper.assertTrue(first.consumesAction()
                && beforeFirst - player.getOffhandItem().getCount() == 1
                && bTarget.blessingRank(BlessingId.WARDEN_OATH) == 1,
            "A reward used through Player#interactOn must consume exactly one "
                + "offhand seal and give B's settler exact rank I (result="
                + first + ", before=" + beforeFirst + ", after="
                + player.getOffhandItem().getCount() + ")");
        assertSettlerOwnershipSplit(helper, settlements, aSettler, bTarget,
            BlessingId.WARDEN_OATH, 1, 4);
        helper.assertTrue(player.getMainHandItem().is(Items.NAME_TAG)
                && player.getMainHandItem().getCount() == 2,
            "the consuming main-hand item must stay untouched while the actual "
                + "offhand gift is applied to B");

        applySettlerGift(helper, player, bTarget, 2);
        applySettlerGift(helper, player, bTarget, 3);
        int beforeMaxed = player.getOffhandItem().getCount();
        InteractionResult maxed = player.interactOn(bTarget,
            InteractionHand.MAIN_HAND);
        helper.assertTrue(maxed.consumesAction()
                && player.getOffhandItem().getCount() == beforeMaxed
                && bTarget.blessingRank(BlessingId.WARDEN_OATH) == 3,
            "MAXED on B must retain A's fourth physical seal and leave rank III exact");

        ItemStack invalidReward = issueFrom(helper, settlements.a(),
            settlements.b(), BlessingId.THORNED_ROADS, 1);
        player.setItemInHand(InteractionHand.OFF_HAND, invalidReward);
        int beforeInvalid = player.getOffhandItem().getCount();
        InteractionResult invalid = player.interactOn(bInvalid,
            InteractionHand.MAIN_HAND);
        helper.assertTrue(invalid.consumesAction()
                && player.getOffhandItem().getCount() == beforeInvalid
                && bInvalid.getSettlementId().equals(settlements.b().id)
                && bInvalid.blessingRank(BlessingId.THORNED_ROADS) == 0,
            "INVALID B settler must retain the A-issued item and remain inert");
        helper.assertTrue(aSettler.blessingRank(BlessingId.WARDEN_OATH) == 0
                && aSettler.blessingRank(BlessingId.THORNED_ROADS) == 0,
            "neither B interaction may write a hidden target effect into A");

        cleanup(helper, settlements);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 400,
        batch = "blessing_gift_a_reward_b_plaque_full_interaction")
    public void rewardFromACanBlessBBuildingThroughPhysicalPlaque(
            GameTestHelper helper) {
        SettlementPair settlements = settlements(helper,
            new BlockPos(2, 1, 2), 4,
            new BlockPos(10, 1, 10), 7);
        Building aBuilding = GameTestFixtures.register(helper, settlements.a(),
            BuildingType.HOUSE, 1, 1);
        BlockPos room = new BlockPos(7, 0, 7);
        buildFarmRoom(helper, room);
        BlockPos bPlaqueRel = fitFarmPlaque(helper, room);

        helper.runAfterDelay(20, () -> {
            PlaqueBlockEntity bPlaque = plaqueAt(helper, bPlaqueRel);
            bPlaque.survey(helper.getLevel());
            Building bBuilding = bPlaque.building(helper.getLevel());
            helper.assertTrue(bPlaque.state() == PlaqueState.LINKED_VALID
                    && bBuilding != null && bBuilding.valid
                    && settlements.b().buildings.contains(bBuilding)
                    && !settlements.a().buildings.contains(bBuilding),
                "setup: the exact physical plaque must resolve to B's registered building");
            helper.assertTrue(bPlaque.settlementFor(helper.getLevel()) == settlements.b(),
                "setup: B, not nearby reward-origin A, must own the plaque target");

            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.getAbilities().instabuild = false;
            player.setShiftKeyDown(true);
            player.setPos(settlements.a().center.getX() + 0.5D,
                settlements.a().center.getY(),
                settlements.a().center.getZ() + 0.5D);
            ItemStack hearthwardRewards = issueFrom(helper, settlements.a(),
                settlements.b(), BlessingId.HEARTHWARD, 4);
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.STONE, 2));
            player.setItemInHand(InteractionHand.OFF_HAND, hearthwardRewards);
            player.setPos(bPlaque.getBlockPos().getX() + 0.5D,
                bPlaque.getBlockPos().getY(), bPlaque.getBlockPos().getZ() + 0.5D);

            int beforeFirst = player.getOffhandItem().getCount();
            dispatchPhysicalPlaqueClick(helper, bPlaqueRel, player);
            helper.assertTrue(beforeFirst - player.getOffhandItem().getCount() == 1
                    && bBuilding.blessingRank(BlessingId.HEARTHWARD) == 1
                    && bPlaque.blessingRank(BlessingId.HEARTHWARD) == 1,
                "Shift-right-clicking B's real plaque must consume exactly one "
                    + "A-issued offhand seal and give the sole B ledger exact rank I");
            assertBuildingOwnershipSplit(helper, settlements, aBuilding,
                bBuilding, BlessingId.HEARTHWARD, 1, 4);
            helper.assertTrue(player.getMainHandItem().is(Items.STONE)
                    && player.getMainHandItem().getCount() == 2,
                "plaque hand priority must preserve the consuming main-hand BlockItem");

            applyPlaqueGift(helper, player, bPlaqueRel, bBuilding, 2);
            applyPlaqueGift(helper, player, bPlaqueRel, bBuilding, 3);
            int beforeMaxed = player.getOffhandItem().getCount();
            dispatchPhysicalPlaqueClick(helper, bPlaqueRel, player);
            helper.assertTrue(player.getOffhandItem().getCount() == beforeMaxed
                    && bBuilding.blessingRank(BlessingId.HEARTHWARD) == 3,
                "MAXED on B's plaque must retain A's fourth seal and keep rank III exact");

            ItemStack invalidReward = issueFrom(helper, settlements.a(),
                settlements.b(), BlessingId.THORNED_ROADS, 1);
            player.setItemInHand(InteractionHand.OFF_HAND, invalidReward);
            bBuilding.valid = false;
            int beforeInvalid = player.getOffhandItem().getCount();
            dispatchPhysicalPlaqueClick(helper, bPlaqueRel, player);
            helper.assertTrue(player.getOffhandItem().getCount() == beforeInvalid
                    && bBuilding.blessingRank(BlessingId.THORNED_ROADS) == 0
                    && aBuilding.blessingRank(BlessingId.THORNED_ROADS) == 0,
                "INVALID B building identity must retain the distinct A-issued seal "
                    + "without falling back to or mutating A's building");

            cleanup(helper, settlements);
            helper.succeed();
        });
    }

    private static SettlementPair settlements(GameTestHelper helper,
                                               BlockPos aCenter, int aRadius,
                                               BlockPos bCenter, int bRadius) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement a = new Settlement(UUID.randomUUID(), "Gift-origin A",
            helper.absolutePos(aCenter));
        a.radius = aRadius;
        Settlement b = new Settlement(UUID.randomUUID(), "Gift-target B",
            helper.absolutePos(bCenter));
        b.radius = bRadius;
        data.settlements.put(a.id, a);
        data.settlements.put(b.id, b);
        data.setDirty();
        return new SettlementPair(a, b);
    }

    private static SettlerEntity boundSettler(GameTestHelper helper,
                                              Settlement owner, String name,
                                              BlockPos relativePos) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relativePos);
        settler.setNoAi(true);
        settler.setSettlerName(name);
        settler.bindTo(owner.id, owner.center);
        owner.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /**
     * Issues real physical rewards from A's authority without involving the
     * screen/network layer: grant, compare-and-commit, then canonical item map.
     */
    private static ItemStack issueFrom(GameTestHelper helper, Settlement origin,
                                       Settlement nonOrigin, BlessingId blessing,
                                       int count) {
        int originEarnedBefore = origin.blessingState.earned();
        int originSpentBefore = origin.blessingState.spent();
        int originIssuedBefore = origin.blessingState.issuedCount(blessing);
        int nonOriginIssuedBefore = nonOrigin.blessingState.issuedCount(blessing);
        for (int i = 0; i < count; i++) {
            helper.assertTrue(origin.blessingState.grantOffer(),
                "setup: settlement A must be able to earn gift " + (i + 1));
            int revision = origin.blessingState.revision();
            int offerSerial = origin.blessingState.offerSerial();
            helper.assertTrue(origin.blessingState.compareAndCommit(revision,
                    offerSerial, blessing) == BlessingState.CommitResult.ACCEPTED,
                "setup: A's earned offer must reserve exactly one physical seal");
        }
        helper.assertTrue(origin.blessingState.earned() == originEarnedBefore + count
                && origin.blessingState.spent() == originSpentBefore + count
                && origin.blessingState.issuedCount(blessing)
                    == originIssuedBefore + count,
            "the reward audit ledger must prove every carried seal originated in A");
        helper.assertTrue(nonOrigin.blessingState.issuedCount(blessing)
                == nonOriginIssuedBefore,
            "B must not counterfeit an issued reward merely because B owns the target");
        ItemStack rewards = BlessingSealItem.stackFor(blessing);
        rewards.setCount(count);
        return rewards;
    }

    private static void applySettlerGift(GameTestHelper helper, ServerPlayer player,
                                         SettlerEntity target, int expectedRank) {
        int before = player.getOffhandItem().getCount();
        InteractionResult result = player.interactOn(target,
            InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction()
                && before - player.getOffhandItem().getCount() == 1
                && target.blessingRank(BlessingId.WARDEN_OATH) == expectedRank,
            "each APPLIED Player#interactOn must consume exactly one gift and "
                + "advance B by exactly one rank to " + expectedRank);
    }

    private static void assertSettlerOwnershipSplit(GameTestHelper helper,
                                                    SettlementPair settlements,
                                                    SettlerEntity aSettler,
                                                    SettlerEntity bSettler,
                                                    BlessingId blessing,
                                                    int expectedRank,
                                                    int issuedByA) {
        helper.assertTrue(aSettler.getSettlementId().equals(settlements.a().id)
                && bSettler.getSettlementId().equals(settlements.b().id),
            "setup: source and recipient settlers must belong to different settlements");
        helper.assertTrue(aSettler.blessingRank(blessing) == 0
                && bSettler.blessingRank(blessing) == expectedRank,
            "the permanent effect must live only on B's target, never A's settler");
        helper.assertTrue(settlements.a().blessingState.issuedCount(blessing)
                == issuedByA
                && settlements.b().blessingState.issuedCount(blessing) == 0,
            "issuance must remain attributed to A while B receives the target rank");
    }

    private static void applyPlaqueGift(GameTestHelper helper, ServerPlayer player,
                                       BlockPos plaqueRel, Building target,
                                       int expectedRank) {
        int before = player.getOffhandItem().getCount();
        dispatchPhysicalPlaqueClick(helper, plaqueRel, player);
        helper.assertTrue(before - player.getOffhandItem().getCount() == 1
                && target.blessingRank(BlessingId.HEARTHWARD) == expectedRank,
            "each APPLIED physical plaque click must consume exactly one gift "
                + "and advance B exactly to rank " + expectedRank);
    }

    private static void assertBuildingOwnershipSplit(GameTestHelper helper,
                                                     SettlementPair settlements,
                                                     Building aBuilding,
                                                     Building bBuilding,
                                                     BlessingId blessing,
                                                     int expectedRank,
                                                     int issuedByA) {
        helper.assertTrue(settlements.a().buildings.contains(aBuilding)
                && settlements.b().buildings.contains(bBuilding),
            "setup: source and target buildings must belong to different settlements");
        helper.assertTrue(aBuilding.blessingRank(blessing) == 0
                && bBuilding.blessingRank(blessing) == expectedRank,
            "the permanent building effect must live only in B's sole target ledger");
        helper.assertTrue(settlements.a().blessingState.issuedCount(blessing)
                == issuedByA
                && settlements.b().blessingState.issuedCount(blessing) == 0,
            "building gift issuance must remain attributed to A, not B");
    }

    private static void quarantineTargetLedger(SettlerEntity settler) {
        CompoundTag save = new CompoundTag();
        settler.addAdditionalSaveData(save);
        save.putString("TargetBlessings", "malformed-present-ledger");
        settler.readAdditionalSaveData(save);
    }

    private static void dispatchPhysicalPlaqueClick(GameTestHelper helper,
                                                    BlockPos plaqueRel,
                                                    ServerPlayer player) {
        try {
            helper.useBlock(plaqueRel, player);
        } catch (UnsupportedOperationException exception) {
            String message = exception.getMessage();
            if (message == null || !message.contains("may not be sent")) {
                throw exception;
            }
        }
    }

    /** Proven 7x7 farmhouse geometry; its surveyed plaque becomes B's record. */
    private static void buildFarmRoom(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(3, 1, 0),
            Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(3, 2, 0),
            Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(1, 1, 1), Blocks.COMPOSTER);
        helper.setBlock(origin.offset(5, 1, 1), Blocks.CHEST);
        helper.setBlock(origin.offset(1, 2, 5), Blocks.TORCH);
    }

    private static BlockPos fitFarmPlaque(GameTestHelper helper,
                                          BlockPos origin) {
        BlockPos plaqueRel = origin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), PlaqueItemData.stamped(
                new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.FARMHOUSE)),
            "setup: the physical B farmhouse plan must fit into its plaque");
        return plaqueRel;
    }

    private static PlaqueBlockEntity plaqueAt(GameTestHelper helper,
                                              BlockPos relativePos) {
        var blockEntity = helper.getLevel().getBlockEntity(
            helper.absolutePos(relativePos));
        helper.assertTrue(blockEntity instanceof PlaqueBlockEntity,
            "setup: expected a physical plaque at " + relativePos);
        return (PlaqueBlockEntity) blockEntity;
    }

    private static void cleanup(GameTestHelper helper,
                                SettlementPair settlements) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlements.a().id);
        data.settlements.remove(settlements.b().id);
        data.setDirty();
    }

    private record SettlementPair(Settlement a, Settlement b) {
    }
}
