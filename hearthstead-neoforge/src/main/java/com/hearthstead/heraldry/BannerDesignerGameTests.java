package com.hearthstead.heraldry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementBannerInteraction;
import com.hearthstead.block.SettlementHeraldry;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.gear.GearGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Banner designer end to end on a real Banner: placement offers the placer
 * the designer, a confirm saves the village design and re-dresses current
 * and new guards and archers, cancel keeps the founding colours, members
 * redesign later, nothing ever mints or loses a banner item, two editors
 * resolve last-valid-wins, and the design survives save/load and sync.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BannerDesignerGameTests {
    private static final BlockPos STAND = new BlockPos(2, 1, 2);
    private static final String BATCH = "banner_designer";

    private static VillageDesign design(DyeColor base, BannerShape shape, String pattern, DyeColor color) {
        return new VillageDesign(base, List.of(new VillageDesign.Layer(VillageDesign.vanilla(pattern), color)), shape);
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = BATCH)
    public void placementOffersDesignerAndConfirmDressesGuards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        ServerPlayer player = player(helper, pos);
        InteractionResult placed = place(level, player, pos);
        helper.assertTrue(placed.consumesAction(), "the Banner must be placed, got " + placed);
        HearthBlockEntity banner = (HearthBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(banner != null, "a Banner block entity");

        BannerDesignNetwork.Session session = BannerDesignNetwork.sessionOf(player.getUUID());
        helper.assertTrue(session != null && session.placer() && session.pos().equals(pos),
            "placing offers the placer the designer, got " + session);
        helper.assertTrue(!banner.hasSavedDesign() && banner.effectiveDesign().equals(VillageDesign.FOUNDING),
            "until confirmed the Banner flies the founding colours");

        Settlement settlement = freshSettlement(helper, pos, "Designford");
        banner.bindSettlement(settlement.id);
        SettlerEntity guard = settler(helper, settlement, pos, Profession.GUARD, new BlockPos(1, 1, 1));
        SettlerEntity archer = settler(helper, settlement, pos, Profession.ARCHER, new BlockPos(3, 1, 1));
        GearGate.tickSecond(guard);
        helper.assertTrue(guard.heraldryPacked() == GearGate.packHeraldry(DyeColor.RED, DyeColor.YELLOW),
            "a guard starts in the founding red and gold");

        int bannersBefore = bannerItems(player);
        VillageDesign blue = design(DyeColor.BLUE, BannerShape.SWALLOWTAIL, "cross", DyeColor.WHITE);
        BannerDesignNetwork.Result result = BannerDesignNetwork.confirm(player, pos, blue);
        helper.assertTrue(result == BannerDesignNetwork.Result.SAVED, "the placer's design is saved, got " + result);
        helper.assertTrue(banner.effectiveDesign().equals(blue) && banner.hasSavedDesign(), "the Banner flies it");
        int expected = GearGate.packHeraldry(DyeColor.BLUE, DyeColor.WHITE);
        helper.assertTrue(guard.heraldryPacked() == expected, "the current guard wears blue and white at once");
        helper.assertTrue(archer.heraldryPacked() == expected, "the current archer wears blue and white at once");
        SettlerEntity recruit = settler(helper, settlement, pos, Profession.GUARD, new BlockPos(1, 1, 3));
        GearGate.tickSecond(recruit);
        helper.assertTrue(recruit.heraldryPacked() == expected, "a new guard dresses in the village colours");
        helper.assertTrue(banner.getHeraldry().isEmpty(), "designing never puts an item in the Banner");
        helper.assertTrue(bannerItems(player) == bannersBefore && droppedBanners(level, pos) == 0,
            "designing never hands out or drops a banner item");
        helper.assertTrue(settlement.members.contains(player.getUUID()), "the placer is a member after designing");
        helper.assertTrue(BannerDesignNetwork.sessionOf(player.getUUID()) == null, "the session ends on confirm");
        helper.succeed();
    }

    /** Cancel (closing the placement offer) keeps the founding colours; the placer may still design. */
    @GameTest(template = "empty5", timeoutTicks = 40, batch = BATCH)
    public void cancelKeepsFoundingColoursAndPlacerMayDesignLater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        ServerPlayer player = player(helper, pos);
        place(level, player, pos);
        HearthBlockEntity banner = (HearthBlockEntity) level.getBlockEntity(pos);
        // Bound at once so the Banner's own tick never founds (and spawns founders) inside the test.
        banner.bindSettlement(freshSettlement(helper, pos, "Cancelby").id);
        BannerDesignNetwork.close(player, pos);
        helper.assertTrue(banner.effectiveDesign().equals(VillageDesign.FOUNDING) && !banner.hasSavedDesign(),
            "cancel keeps the founding colours, never an empty banner");
        helper.assertTrue(!banner.effectiveDesign().layers().isEmpty(), "the default has its bordure and flower");
        VillageDesign green = design(DyeColor.GREEN, BannerShape.POINTED, "triangle_bottom", DyeColor.YELLOW);
        helper.assertTrue(BannerDesignNetwork.confirm(player, pos, green) == BannerDesignNetwork.Result.SAVED,
            "after cancelling, the placer can still design the fresh Banner");
        helper.assertTrue(banner.effectiveDesign().equals(green), "and it flies");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = BATCH)
    public void membersRedesignLaterAndTheServerRefusesBadRequests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        HearthBlockEntity banner = standingBanner(helper, pos);
        Settlement settlement = freshSettlement(helper, pos, "Laterby");
        banner.bindSettlement(settlement.id);
        ServerPlayer member = player(helper, pos);
        settlement.addMember(member.getUUID());
        ServerPlayer stranger = player(helper, pos);

        helper.assertTrue(BannerDesignNetwork.requestOpen(member, pos), "a member at the Banner may open it");
        helper.assertTrue(!BannerDesignNetwork.requestOpen(stranger, pos), "a non-member may not");
        VillageDesign first = design(DyeColor.BLACK, BannerShape.TONGUED, "skull", DyeColor.WHITE);
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, first) == BannerDesignNetwork.Result.SAVED, "saved");
        VillageDesign second = design(DyeColor.PURPLE, BannerShape.PENNANT, "rhombus", DyeColor.YELLOW);
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, second) == BannerDesignNetwork.Result.RATE_LIMITED,
            "a second confirm inside the cooldown is refused");
        helper.assertTrue(banner.effectiveDesign().equals(first), "a refused confirm changes nothing");
        BannerDesignNetwork.resetForTest(member.getUUID());
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, second) == BannerDesignNetwork.Result.SAVED,
            "redesigning later works");
        helper.assertTrue(banner.effectiveDesign().equals(second), "the redesign flies");

        BannerDesignNetwork.resetForTest(stranger.getUUID());
        helper.assertTrue(BannerDesignNetwork.confirm(stranger, pos, first) == BannerDesignNetwork.Result.DENIED,
            "a non-member cannot change the heraldry");
        List<VillageDesign.Layer> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) seven.add(new VillageDesign.Layer(VillageDesign.vanilla("border"), DyeColor.RED));
        BannerDesignNetwork.resetForTest(member.getUUID());
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, new VillageDesign(DyeColor.RED, seven,
            BannerShape.STRAIGHT)) == BannerDesignNetwork.Result.INVALID, "more than six layers is refused");
        BannerDesignNetwork.resetForTest(member.getUUID());
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, VillageDesign.INVALID)
            == BannerDesignNetwork.Result.INVALID, "an out-of-range colour or shape is refused");
        BannerDesignNetwork.resetForTest(member.getUUID());
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, new VillageDesign(DyeColor.RED,
            List.of(new VillageDesign.Layer(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("evil", "x"),
                DyeColor.RED)), BannerShape.STRAIGHT)) == BannerDesignNetwork.Result.INVALID,
            "an unknown pattern is refused");
        BannerDesignNetwork.resetForTest(member.getUUID());
        member.setPos(pos.getX() + 20.5, pos.getY(), pos.getZ() + 0.5);
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, first) == BannerDesignNetwork.Result.TOO_FAR,
            "a confirm from far away is refused");
        member.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() - 1.5);
        BannerDesignNetwork.resetForTest(member.getUUID());
        member.getAbilities().mayBuild = false;
        helper.assertTrue(BannerDesignNetwork.confirm(member, pos, first) == BannerDesignNetwork.Result.DENIED,
            "a member who may not build here is refused");
        member.getAbilities().mayBuild = true;
        helper.assertTrue(banner.effectiveDesign().equals(second), "no refusal changed the design");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = BATCH)
    public void noBannerItemIsMintedOnRepeatReplaceOrBreak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        HearthBlockEntity banner = standingBanner(helper, pos);
        Settlement settlement = freshSettlement(helper, pos, "Countwell");
        banner.bindSettlement(settlement.id);
        ServerPlayer player = player(helper, pos);
        settlement.addMember(player.getUUID());
        player.getInventory().clearContent();

        // Hang a physical white banner: exact exchange, the design follows it.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHITE_BANNER, 2));
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.ADOPTED, "the white banner is hung");
        ItemStack hung = banner.getHeraldry().copy();
        helper.assertTrue(banner.effectiveDesign().base() == DyeColor.WHITE, "the design follows a hung banner");
        int total = bannerItems(player) + banner.getHeraldry().getCount();
        helper.assertTrue(total == 2, "two banners in the world, got " + total);

        // Repeat designing: the escrowed item never changes, no item appears.
        VillageDesign[] designs = {
            design(DyeColor.RED, BannerShape.SWALLOWTAIL, "border", DyeColor.YELLOW),
            design(DyeColor.BLUE, BannerShape.PENNANT, "circle", DyeColor.WHITE),
            design(DyeColor.GREEN, BannerShape.POINTED, "cross", DyeColor.BLACK),
        };
        for (VillageDesign d : designs) {
            BannerDesignNetwork.resetForTest(player.getUUID());
            helper.assertTrue(BannerDesignNetwork.confirm(player, pos, d) == BannerDesignNetwork.Result.SAVED,
                "design saved");
            helper.assertTrue(ItemStack.isSameItemSameComponents(banner.getHeraldry(), hung)
                && banner.getHeraldry().getCount() == 1, "the hung banner stays exactly as it was");
            helper.assertTrue(bannerItems(player) + banner.getHeraldry().getCount() == total
                && droppedBanners(level, pos) == 0, "designing mints nothing");
        }

        // Re-hanging the same physical banner flies it again with no net item change.
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.ADOPTED, "the same banner re-adopts its colours over a design");
        helper.assertTrue(banner.effectiveDesign().base() == DyeColor.WHITE
            && banner.effectiveDesign().shape() == BannerShape.POINTED, "its colours fly, the cloth shape is kept");
        helper.assertTrue(bannerItems(player) + banner.getHeraldry().getCount() == total, "still two banners");

        // Replace with a blue banner, then break: exactly the hung banner drops once.
        ItemStack blue = new ItemStack(Items.BLUE_BANNER);
        player.getInventory().setItem(5, player.getMainHandItem().copy());
        player.setItemInHand(InteractionHand.MAIN_HAND, blue.copy());
        int withBlue = bannerItems(player) + banner.getHeraldry().getCount();
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.ADOPTED, "the blue banner replaces the white");
        helper.assertTrue(bannerItems(player) + banner.getHeraldry().getCount() == withBlue,
            "replace is an exact exchange");
        level.removeBlock(pos, false);
        long blueDrops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
            .filter(e -> e.getItem().is(Items.BLUE_BANNER)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(blueDrops == 1 && droppedBanners(level, pos) == 1,
            "breaking drops exactly the hung banner once, got " + droppedBanners(level, pos));
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 40, batch = BATCH)
    public void twoEditorsLastValidConfirmWinsAndTheOtherRefreshes(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(STAND);
        HearthBlockEntity banner = standingBanner(helper, pos);
        Settlement settlement = freshSettlement(helper, pos, "Twofold");
        banner.bindSettlement(settlement.id);
        ServerPlayer a = player(helper, pos);
        ServerPlayer b = player(helper, pos);
        settlement.addMember(a.getUUID());
        settlement.addMember(b.getUUID());
        helper.assertTrue(BannerDesignNetwork.requestOpen(a, pos) && BannerDesignNetwork.requestOpen(b, pos),
            "both members open the designer");
        VillageDesign fromA = design(DyeColor.ORANGE, BannerShape.STRAIGHT, "stripe_top", DyeColor.BLACK);
        VillageDesign fromB = design(DyeColor.CYAN, BannerShape.SWALLOWTAIL, "stripe_middle", DyeColor.WHITE);
        helper.assertTrue(BannerDesignNetwork.confirm(a, pos, fromA) == BannerDesignNetwork.Result.SAVED, "A saves");
        helper.assertTrue(BannerDesignNetwork.lastRefreshed().contains(b.getUUID()),
            "B's open designer is refreshed with A's colours");
        helper.assertTrue(banner.effectiveDesign().equals(fromA), "A's design flies");
        helper.assertTrue(BannerDesignNetwork.confirm(b, pos, fromB) == BannerDesignNetwork.Result.SAVED,
            "B confirms later");
        helper.assertTrue(banner.effectiveDesign().equals(fromB), "the last valid confirm wins");
        helper.assertTrue(!BannerDesignNetwork.lastRefreshed().contains(a.getUUID()),
            "A closed on confirm, so nothing is pushed to A");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 40, batch = BATCH)
    public void designPersistsSyncsAndOldSavesGetADefault(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        HearthBlockEntity banner = standingBanner(helper, pos);
        VillageDesign royal = HeraldryCatalog.PRESETS.get(10).design();
        banner.setDesign(royal);

        CompoundTag saved = banner.saveWithFullMetadata(level.registryAccess());
        BlockEntity reloaded = BlockEntity.loadStatic(pos, banner.getBlockState(), saved, level.registryAccess());
        helper.assertTrue(reloaded instanceof HearthBlockEntity copy && copy.effectiveDesign().equals(royal),
            "the design survives save and load (restart, chunk reload)");
        CompoundTag update = banner.getUpdateTag(level.registryAccess());
        helper.assertTrue(update.contains("Design") && !update.contains("Inventory"),
            "clients receive the design (reconnect, chunk reload) but never the stores");
        HearthBlockEntity client = new HearthBlockEntity(pos, banner.getBlockState());
        client.handleUpdateTag(update, level.registryAccess());
        helper.assertTrue(client.effectiveDesign().equals(royal), "a client applies the synced design");

        // Old saves: no Design key.
        CompoundTag old = saved.copy();
        old.remove("Design");
        old.remove("Heraldry");
        BlockEntity legacy = BlockEntity.loadStatic(pos, banner.getBlockState(), old, level.registryAccess());
        helper.assertTrue(legacy instanceof HearthBlockEntity l && !l.hasSavedDesign()
            && l.effectiveDesign().equals(VillageDesign.FOUNDING), "an old save flies the founding colours");
        CompoundTag oldHung = old.copy();
        oldHung.put("Heraldry", new ItemStack(Items.LIME_BANNER).save(level.registryAccess()));
        BlockEntity legacyHung = BlockEntity.loadStatic(pos, banner.getBlockState(), oldHung, level.registryAccess());
        helper.assertTrue(legacyHung instanceof HearthBlockEntity lh && lh.effectiveDesign().base() == DyeColor.LIME
            && lh.effectiveDesign().shape() == BannerShape.STRAIGHT, "an old save with a hung banner flies it");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 40, batch = BATCH)
    public void kingdomNameIsValidatedSavedAndUsedByTownChat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        HearthBlockEntity banner = standingBanner(helper, pos);
        Settlement settlement = freshSettlement(helper, pos, "Oldname");
        banner.bindSettlement(settlement.id);
        ServerPlayer player = player(helper, pos);
        settlement.addMember(player.getUUID());
        String suffix = letters(settlement.id, 6);
        String taken = "Taken" + suffix;
        String chosen = "Hold " + suffix;
        Settlement other = new Settlement(UUID.randomUUID(), taken, pos.offset(5000, 0, 5000));
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(other.id, other);
        List<String> heard = new ArrayList<>();
        java.util.function.BiConsumer<ServerPlayer, net.minecraft.network.chat.Component> tap =
            (to, line) -> { if (to == player) heard.add(line.getString()); };
        try {
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, "  " + taken.toUpperCase() + " ");
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == KingdomName.Problem.TAKEN
                && settlement.name.equals("Oldname"), "a name another kingdom uses is refused, any case");
            BannerDesignNetwork.resetConfirmCooldownForTest(player.getUUID());
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, "Xy");
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == KingdomName.Problem.TOO_SHORT,
                "a two-letter name is refused");
            BannerDesignNetwork.resetConfirmCooldownForTest(player.getUUID());
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, "Hold#9");
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == KingdomName.Problem.BAD_CHARACTERS
                && settlement.name.equals("Oldname"), "symbols and digits are refused");
            BannerDesignNetwork.resetConfirmCooldownForTest(player.getUUID());
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, "  Hold   " + suffix + " ");
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == KingdomName.Problem.NONE
                && settlement.name.equals(chosen), "a good name is saved trimmed, got " + settlement.name);
            BannerDesignNetwork.resetConfirmCooldownForTest(player.getUUID());
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, "Another " + suffix);
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == KingdomName.Problem.WAIT
                && settlement.name.equals(chosen), "a second rename inside 10 s waits");
            BannerDesignNetwork.resetConfirmCooldownForTest(player.getUUID());
            BannerDesignNetwork.confirm(player, pos, VillageDesign.FOUNDING, chosen);
            helper.assertTrue(BannerDesignNetwork.lastNameProblem() == null, "keeping your own name is no rename");

            com.hearthstead.settlement.TownChat.addTestTap(tap);
            com.hearthstead.settlement.TownChat.send(level, settlement, com.hearthstead.settlement.TownChat.Kind.RESEARCH,
                net.minecraft.network.chat.Component.literal("Crop Rotation learned"));
            com.hearthstead.settlement.TownChat.flushNow();
            helper.assertTrue(heard.stream().anyMatch(l -> l.contains(chosen) && !l.contains("Oldname")),
                "town chat lines carry the new name at once, heard " + heard);
            Settlement reloaded = Settlement.readNbt(settlement.writeNbt());
            helper.assertTrue(reloaded.name.equals(chosen), "the new name survives a save and reload");
        } finally {
            com.hearthstead.settlement.TownChat.removeTestTap(tap);
            data.settlements.remove(other.id);
        }
        helper.succeed();
    }

    private static String letters(UUID id, int count) {
        String hex = id.toString().replace("-", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append((char) ('a' + Character.digit(hex.charAt(i), 16)));
        }
        return out.toString();
    }

    // ---------------------------------------------------------- helpers ---

    private static ServerPlayer player(GameTestHelper helper, BlockPos pos) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() - 1.5);
        BannerDesignNetwork.resetForTest(player.getUUID());
        return player;
    }

    private static HearthBlockEntity standingBanner(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        for (int dy = 1; dy <= 2; dy++) level.setBlock(pos.above(dy), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.HEARTH.get().defaultBlockState(), 3);
        return (HearthBlockEntity) level.getBlockEntity(pos);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement, BlockPos banner,
                                         Profession profession, BlockPos at) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), at);
        settler.bindTo(settlement.id, banner);
        settler.setProfessionProjection(profession);
        settlement.putRecord(settler.getUUID(), profession.name(), profession);
        settler.setNoAi(true);
        return settler;
    }

    private static InteractionResult place(ServerLevel level, ServerPlayer player, BlockPos pos) {
        for (int dy = 0; dy <= 2; dy++) level.setBlock(pos.above(dy), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        ItemStack stack = new ItemStack(ModItems.HEARTH.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0),
            Direction.UP, pos.below(), false);
        return ModItems.HEARTH.get().place(new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static int bannerItems(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (SettlementHeraldry.isBanner(s)) total += s.getCount();
        }
        return total;
    }

    private static int droppedBanners(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).stream()
            .filter(e -> SettlementHeraldry.isBanner(e.getItem())).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static Settlement freshSettlement(GameTestHelper helper, BlockPos center, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D, old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), name, center);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }
}
