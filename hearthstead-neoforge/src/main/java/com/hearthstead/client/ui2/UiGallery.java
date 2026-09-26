package com.hearthstead.client.ui2;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.builder.BuilderConfirmScreen;
import com.hearthstead.client.builder.BuilderPlanScreen;
import com.hearthstead.client.builder.DesignNameScreen;
import com.hearthstead.client.conversation.BarterScreen;
import com.hearthstead.client.conversation.ConversationScreen;
import com.hearthstead.client.patrol.PatrolRouteScreen;
import com.hearthstead.client.screen.BannerOrderScreen;
import com.hearthstead.client.screen.BlessingScreen;
import com.hearthstead.client.screen.DevelopmentScreen;
import com.hearthstead.client.screen.EmblemShopScreen;
import com.hearthstead.client.screen.HandbookScreen;
import com.hearthstead.client.screen.PlaqueScreen;
import com.hearthstead.client.screen.ResearchScreen;
import com.hearthstead.client.screen.StorageScreen;
import com.hearthstead.client.screen.TechTreeScreen;
import com.hearthstead.conversation.net.ConvBarterPayload;
import com.hearthstead.conversation.net.ConvStatePayload;
import com.hearthstead.entity.Profession;
import com.hearthstead.network.BannerOrderMenuPayload;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.network.PlaqueSnapshot;
import com.hearthstead.network.ResearchSnapshotPayload;
import com.hearthstead.network.StorageIndexPayload;
import com.hearthstead.network.TechTreeSnapshotPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.research.ResearchProject;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Dev-only UI gallery: {@code /hsui <name>} opens one payload-driven screen
 * with realistic sample data built in code, so a single screenshot session
 * can capture every screen for a before/after contact sheet. {@code /hsui list}
 * prints the names.
 *
 * <p>Purely additive: no screen, packet or behaviour changes. The command is
 * only registered in non-production runs ({@code runClient}) or when the JVM
 * property {@code -Dhearthstead.uiGallery=true} is set. The sample ids are
 * random, so any packet a screen sends from here is refused by the server.
 *
 * <p>Not covered (need a server container menu or a live parent screen):
 * HearthScreen, SettlerInventoryScreen, CoinMerchantScreen, FishRackScreen
 * (AbstractContainerScreen with a server menu), SettlerScreen (live
 * SettlerEntity), GuardOrderScreen (SettlerScreen parent) and
 * EquipmentRequestListScreen (a live courier; its rows arrive only by packet).
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class UiGallery {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks to wait after the command so the closing chat screen cannot replace ours. */
    private static final int OPEN_DELAY_TICKS = 2;
    /** Far enough that the chunk is never loaded, so block-watching screens stay open. */
    private static final int UNLOADED_OFFSET = 8192;

    private static final Map<String, Supplier<Screen>> SCREENS = new LinkedHashMap<>();

    static {
        SCREENS.put("workzone", UiGallery::workZone);
        SCREENS.put("patrol", UiGallery::patrol);
        SCREENS.put("barter", UiGallery::barter);
        SCREENS.put("conversation", UiGallery::conversation);
        SCREENS.put("builderconfirm", UiGallery::builderConfirm);
        SCREENS.put("builderplan", BuilderPlanScreen::new);
        SCREENS.put("designname", UiGallery::designName);
        SCREENS.put("bannerorder", UiGallery::bannerOrder);
        SCREENS.put("blessing", UiGallery::blessing);
        SCREENS.put("emblemshop", UiGallery::emblemShop);
        SCREENS.put("development", UiGallery::development);
        SCREENS.put("research", UiGallery::research);
        SCREENS.put("plaque", UiGallery::plaqueWorkplace);
        SCREENS.put("plaqueincomplete", UiGallery::plaqueIncomplete);
        SCREENS.put("plaquehouse", UiGallery::plaqueHouse);
        SCREENS.put("storage", UiGallery::storage);
        SCREENS.put("techtree", UiGallery::techTree);
        SCREENS.put("handbook", HandbookScreen::new);
    }

    private static String pending;
    private static int pendingDelay;

    private UiGallery() {
    }

    /** Dev runs are non-production; a packaged jar needs -Dhearthstead.uiGallery=true. */
    public static boolean enabled() {
        return !FMLEnvironment.production || Boolean.getBoolean("hearthstead.uiGallery");
    }

    public static List<String> names() {
        return List.copyOf(SCREENS.keySet());
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!enabled()) {
            return;
        }
        event.getDispatcher().register(Commands.literal("hsui")
            .then(Commands.literal("list").executes(UiGallery::list))
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SCREENS.keySet(), builder))
                .executes(UiGallery::open)));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        say(Component.literal("hsui screens: " + String.join(", ", SCREENS.keySet())));
        return SCREENS.size();
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!SCREENS.containsKey(name)) {
            say(Component.literal("hsui: unknown screen '" + name + "'. Try /hsui list"));
            return 0;
        }
        pending = name;
        pendingDelay = OPEN_DELAY_TICKS;
        return 1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pending == null || --pendingDelay > 0) {
            return;
        }
        String name = pending;
        pending = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        try {
            Screen screen = SCREENS.get(name).get();
            if (screen != null) {
                mc.setScreen(screen);
            }
        } catch (RuntimeException e) {
            LOGGER.error("hsui: could not open sample screen '{}'", name, e);
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            say(Component.literal("hsui: '" + name + "' failed: " + e));
        }
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BlockPos base() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
    }

    /** A position whose chunk is not loaded, so tick() block checks never fire. */
    private static BlockPos unloaded() {
        return base().offset(UNLOADED_OFFSET, 0, UNLOADED_OFFSET);
    }

    private static String dimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
    }

    private static UUID id(String seed) {
        return UUID.nameUUIDFromBytes(("hsui:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ samples

    private static Screen workZone() {
        BlockPos b = base();
        return new com.hearthstead.client.screen.WorkZoneConfirmScreen(new WorkZoneSnapshotPayload(
            id("workzone.session"), id("settlement"), id("workzone.building"), 1, dimension(), 3,
            WorkZoneSnapshotPayload.Stage.PREVIEW_READY,
            Optional.of(b.offset(-6, 0, -5)), Optional.of(b.offset(7, 0, 6)),
            Component.literal("Elmfield Farmhouse"), Optional.empty()));
    }

    private static Screen patrol() {
        BlockPos b = base();
        UUID hild = id("guard.hild");
        UUID wilmot = id("guard.wilmot");
        UUID osric = id("guard.osric");
        UUID edda = id("guard.edda");
        UUID bram = id("guard.bram");
        List<PatrolSnapshotPayload.Route> routes = List.of(
            new PatrolSnapshotPayload.Route(1, "Wall walk", 0, true, 1, 2,
                List.of(b.offset(-20, 0, -20), b.offset(20, 0, -20), b.offset(20, 0, 20), b.offset(-20, 0, 20)),
                List.of(hild, wilmot), List.of(hild, wilmot), 0, true),
            new PatrolSnapshotPayload.Route(2, "Mill road", 2, false, 0, 1,
                List.of(b.offset(0, 0, 12), b.offset(18, 0, 30), b.offset(34, 0, 44)),
                List.of(osric), List.of(osric), 0, true),
            new PatrolSnapshotPayload.Route(3, "North gate", 4, false, 0, 0,
                List.of(b.offset(-4, 0, -36)),
                List.of(), List.of(), 0, false));
        List<PatrolSnapshotPayload.Guard> guards = List.of(
            new PatrolSnapshotPayload.Guard(hild, "Hild", Profession.GUARD.id(), false, true, false),
            new PatrolSnapshotPayload.Guard(wilmot, "Wilmot", Profession.ARCHER.id(), false, true, false),
            new PatrolSnapshotPayload.Guard(osric, "Osric", Profession.SPEARMAN.id(), true, false, false),
            new PatrolSnapshotPayload.Guard(edda, "Edda", Profession.GUARD.id(), true, false, true),
            new PatrolSnapshotPayload.Guard(bram, "Bram", Profession.LONGSWORDSMAN.id(), false, false, false));
        return new PatrolRouteScreen(new PatrolSnapshotPayload(id("settlement"), dimension(), 7, 1, true,
            routes, guards));
    }

    private static Screen barter() {
        List<ItemStack> theirs = List.of(new ItemStack(Items.BREAD, 12), new ItemStack(Items.WHEAT, 32),
            new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.LEATHER, 8), new ItemStack(Items.APPLE, 10));
        List<Integer> theirValues = List.of(4, 1, 24, 10, 3);
        List<ItemStack> mine = new ArrayList<>();
        List<Integer> mineValues = new ArrayList<>();
        ItemStack[] held = {new ItemStack(Items.EMERALD, 9), new ItemStack(Items.IRON_INGOT, 14),
            new ItemStack(Items.WHEAT, 48), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.COAL, 20)};
        int[] heldValues = {60, 24, 1, 2, 3};
        for (int i = 0; i < ConvBarterPayload.MINE_SLOTS; i++) {
            mine.add(i < held.length ? held[i] : ItemStack.EMPTY);
            mineValues.add(i < held.length ? heldValues[i] : 0);
        }
        return new BarterScreen(new ConvBarterPayload(9001, speakerId(), Component.literal("Wilmot the Trader"),
            35, 110, theirs, theirValues, mine, mineValues, 0, 1));
    }

    private static Screen conversation() {
        List<ConvStatePayload.OptionView> options = List.of(
            new ConvStatePayload.OptionView("ask_news", Component.literal("What news from the road?"), -1,
                List.of(), true, Component.empty(), false),
            new ConvStatePayload.OptionView("persuade_price",
                Component.literal("Surely a friend of Elmfield pays less."), 62,
                List.of(new ConvStatePayload.CostView(new ItemStack(Items.EMERALD), 2, 9)),
                true, Component.empty(), false),
            new ConvStatePayload.OptionView("barter", Component.literal("Show me your wares."), -1,
                List.of(), true, Component.empty(), true));
        // Plain talk (mode 0): no cinematic card. Opened directly, not through
        // ConversationClient, so the camera and session state stay untouched.
        return new ConversationScreen(new ConvStatePayload(9001, speakerId(), Component.literal("Wilmot"),
            Component.literal("Travelling trader"), 35,
            Component.literal("Remembers you paid fairly for iron last spring."),
            Component.literal("3 trades, 1 favour"),
            List.of(Component.literal("Well met again. The Elmfield road was muddy, but the wagon held."),
                Component.literal("I carry bread, iron and a little leather this time.")),
            options, 0, 0, 0, 0, 1));
    }

    /** Nearest living non-player entity for the portrait; the player if none; never crashes when missing. */
    private static int speakerId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return -1;
        }
        Entity best = null;
        double bestDist = 32.0 * 32.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && entity != mc.player) {
                double d = entity.distanceToSqr(mc.player);
                if (d < bestDist) {
                    bestDist = d;
                    best = entity;
                }
            }
        }
        return best != null ? best.getId() : mc.player.getId();
    }

    private static Screen builderConfirm() {
        BlockPos b = base();
        return new BuilderConfirmScreen(new BuilderPayloads.Validation(BuilderPayloads.Validation.BLUEPRINT,
            "house_small", b.offset(4, 0, 4), b.offset(11, 6, 11), 0, false, 0, false, id("builder.target"),
            true, "", List.of(), 412, 38, 24, List.of(b.offset(6, 1, 5)), 3,
            List.of(new BuilderPayloads.Stock(Items.OAK_PLANKS, 128, 64, 96, 0),
                new BuilderPayloads.Stock(Items.COBBLESTONE, 96, 32, 80, 16),
                new BuilderPayloads.Stock(Items.OAK_LOG, 24, 8, 30, 0),
                new BuilderPayloads.Stock(Items.GLASS_PANE, 12, 0, 4, 0),
                new BuilderPayloads.Stock(Items.OAK_DOOR, 1, 1, 0, 0),
                new BuilderPayloads.Stock(Items.TORCH, 6, 2, 12, 0))));
    }

    private static Screen designName() {
        BlockPos b = base();
        return new DesignNameScreen(b.offset(3, 0, 3), b.offset(10, 6, 12));
    }

    /** BannerOrderScreen closes itself unless the main hand holds a banner of the menu colour. */
    private static Screen bannerOrder() {
        Minecraft mc = Minecraft.getInstance();
        ItemStack hand = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (!(hand.getItem() instanceof BannerItem banner)) {
            say(Component.literal("hsui: hold any banner in your main hand for bannerorder (it closes otherwise)."));
            return null;
        }
        return new BannerOrderScreen(new BannerOrderMenuPayload(id("banner.token"), banner.getColor().getId(),
            6, true, "Wilmot", true, base().offset(8, 0, -3), ""));
    }

    private static Screen blessing() {
        return new BlessingScreen(new BlessingSnapshotPayload(id("settlement"), id("blessing.session"),
            "Elmfield", 4, 2, 1, 0, 2, BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1, null, 1, 2, 1));
    }

    private static Screen emblemShop() {
        List<DevelopmentSnapshotPayload.EmblemView> emblems = new ArrayList<>();
        int i = 0;
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            boolean available = i % 3 != 2;
            emblems.add(new DevelopmentSnapshotPayload.EmblemView(entry.profession().id(), available,
                available ? "" : (i % 2 == 0 ? "hearthstead.development.blocked.emblem_locked"
                    : "hearthstead.development.blocked.materials")));
            i++;
        }
        return new EmblemShopScreen(new DevelopmentSnapshotPayload(base(), id("settlement"), id("mayor"), -1,
            DevelopmentActionPayload.View.EMBLEM_SHOP, 5, -1, 0L, 42, List.of(), emblems, Optional.empty()));
    }

    private static Screen development() {
        List<DevelopmentSnapshotPayload.NodeView> nodes = new ArrayList<>();
        int i = 0;
        for (DevelopmentNode node : DevelopmentNode.values()) {
            Development.NodeStatus status = i < 3 ? Development.NodeStatus.OWNED
                : i < 7 ? Development.NodeStatus.AVAILABLE : Development.NodeStatus.LOCKED;
            nodes.add(new DevelopmentSnapshotPayload.NodeView(node.wireId(), status.wireId(),
                status == Development.NodeStatus.LOCKED ? "hearthstead.development.blocked.prerequisite" : "",
                List.of()));
            i++;
        }
        List<DevelopmentSnapshotPayload.UpgradeView> upgrades = new ArrayList<>();
        int u = 0;
        for (PostRaidUpgrade upgrade : PostRaidUpgrade.values()) {
            Development.NodeStatus status = u == 0 ? Development.NodeStatus.OWNED
                : u == 1 ? Development.NodeStatus.AVAILABLE : Development.NodeStatus.LOCKED;
            upgrades.add(new DevelopmentSnapshotPayload.UpgradeView(upgrade.wireId(), status.wireId(),
                status == Development.NodeStatus.LOCKED ? "hearthstead.development.blocked.first_raid" : "",
                upgrade.coinCost()));
            u++;
        }
        return new DevelopmentScreen(new DevelopmentSnapshotPayload(base(), id("settlement"), id("mayor"), -1,
            DevelopmentActionPayload.View.TECH, 5, -1, 0L, 42, nodes, List.of(), Optional.empty(), upgrades));
    }

    private static Screen research() {
        List<List<Integer>> haves = new ArrayList<>();
        for (ResearchProject project : ResearchProject.BY_ORDINAL) {
            List<Integer> row = new ArrayList<>();
            int line = 0;
            for (ResearchProject.Cost cost : project.costs()) {
                // Mix of covered and short cost lines.
                row.add((project.ordinal() + line) % 2 == 0 ? cost.count() : cost.count() / 2);
                line++;
            }
            haves.add(List.copyOf(row));
        }
        return new ResearchScreen(new ResearchSnapshotPayload(unloaded(), 6, true, "Hild",
            ResearchProject.BLESTRING.ordinal(), 1, List.of(ResearchProject.BEDRE_GJAER.ordinal()),
            haves, Optional.empty()));
    }

    private static Screen plaqueWorkplace() {
        List<PlaqueSnapshot.RequirementLine> reqs = List.of(
            new PlaqueSnapshot.RequirementLine("oven", 2, 2),
            new PlaqueSnapshot.RequirementLine("storage", 2, 1),
            new PlaqueSnapshot.RequirementLine("doors", 1, 1),
            new PlaqueSnapshot.RequirementLine("lights", 3, 1),
            new PlaqueSnapshot.RequirementLine("floor_space", 24, 16),
            new PlaqueSnapshot.RequirementLine("level", 1, 1),
            new PlaqueSnapshot.RequirementLine("next.storage", 2, 4),
            new PlaqueSnapshot.RequirementLine("next.lights", 3, 4));
        List<PlaqueSnapshot.Occupant> occupants = List.of(
            new PlaqueSnapshot.Occupant(id("settler.hild"), "Hild", Profession.BAKER.name(), 18F, 20F, 72, true, 3L),
            new PlaqueSnapshot.Occupant(id("settler.wilmot"), "Wilmot", Profession.BAKER.name(), 14F, 20F, 55,
                true, 4L));
        return new PlaqueScreen(new PlaqueSnapshot(unloaded(), id("plaque.bakery"), id("plaque.session"),
            "bakery", "linked_valid", 9, 1, reqs, occupants, List.of(), 2, true, 1, 0, 0,
            PlaqueSnapshot.Delivery.OPEN, Optional.empty()));
    }

    private static Screen plaqueIncomplete() {
        List<PlaqueSnapshot.RequirementLine> reqs = List.of(
            new PlaqueSnapshot.RequirementLine("oven", 1, 2),
            new PlaqueSnapshot.RequirementLine("storage", 0, 1),
            new PlaqueSnapshot.RequirementLine("doors", 1, 1),
            new PlaqueSnapshot.RequirementLine("lights", 0, 1),
            new PlaqueSnapshot.RequirementLine("floor_space", 20, 16));
        return new PlaqueScreen(new PlaqueSnapshot(unloaded(), id("plaque.bakery2"), id("plaque.session2"),
            "bakery", "linked_incomplete", 2, 1, reqs, List.of(), List.of(), 0, true, 0, 0, 0,
            PlaqueSnapshot.Delivery.OPEN, Optional.empty()));
    }

    private static Screen plaqueHouse() {
        List<PlaqueSnapshot.RequirementLine> reqs = List.of(
            new PlaqueSnapshot.RequirementLine("beds", 3, 1),
            new PlaqueSnapshot.RequirementLine("doors", 1, 1),
            new PlaqueSnapshot.RequirementLine("lights", 2, 1),
            new PlaqueSnapshot.RequirementLine("floor_space", 14, 9));
        List<PlaqueSnapshot.Occupant> occupants = List.of(
            new PlaqueSnapshot.Occupant(id("settler.edda"), "Edda", Profession.FARMER.name(), 20F, 20F, 80, false, 0L),
            new PlaqueSnapshot.Occupant(id("settler.osric"), "Osric", Profession.LUMBERER.name(), 16F, 20F, 61,
                false, 0L));
        List<PlaqueSnapshot.Candidate> candidates = List.of(
            new PlaqueSnapshot.Candidate(id("settler.bram"), "Bram", Profession.MINER.name(), false, 14, "", 4,
                "hearthstead.employ.cost.none", ""),
            new PlaqueSnapshot.Candidate(id("settler.aldith"), "Aldith", Profession.COOK.name(), true, 27, "", 2,
                "hearthstead.employ.cost.none", ""),
            new PlaqueSnapshot.Candidate(id("settler.cuthbert"), "Cuthbert", Profession.GUARD.name(), true, 40,
                "hearthstead.plaque.blocked.full", 1, "hearthstead.employ.cost.none", ""));
        return new PlaqueScreen(new PlaqueSnapshot(unloaded(), id("plaque.house"), id("plaque.session3"),
            "house", "linked_valid", 5, 1, reqs, occupants, candidates, 4, true, 0, 1, 0,
            PlaqueSnapshot.Delivery.OPEN, Optional.empty()));
    }

    private static Screen storage() {
        BlockPos b = base();
        BlockPos east = b.offset(14, 0, 2);
        BlockPos west = b.offset(-12, 0, 6);
        Object[][] rows = {
            {Items.BREAD, 64, 40}, {Items.WHEAT, 212, 150}, {Items.IRON_INGOT, 37, 37},
            {Items.EMERALD, 18, 18}, {Items.OAK_LOG, 256, 128}, {Items.OAK_PLANKS, 180, 100},
            {Items.COBBLESTONE, 320, 200}, {Items.COAL, 72, 72}, {Items.LEATHER, 22, 10},
            {Items.CARROT, 45, 45}, {Items.WHITE_WOOL, 30, 12}, {Items.TORCH, 48, 48}};
        List<StorageIndexPayload.StockRow> stocks = new ArrayList<>();
        int total = 0;
        int locations = 0;
        for (Object[] row : rows) {
            net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) row[0];
            int count = (Integer) row[1];
            int inEast = (Integer) row[2];
            List<StorageIndexPayload.LocationRow> at = new ArrayList<>();
            at.add(new StorageIndexPayload.LocationRow(east, inEast));
            if (count > inEast) {
                at.add(new StorageIndexPayload.LocationRow(west, count - inEast));
            }
            stocks.add(new StorageIndexPayload.StockRow(new ItemStack(item), count, at.size(), at));
            total += count;
            locations += at.size();
        }
        List<StorageIndexPayload.WarehouseRow> warehouses = List.of(
            new StorageIndexPayload.WarehouseRow(east, 2, 2, 3, 14, 16, 0, 3, 24, -1, true,
                List.of(new StorageIndexPayload.GapRow("hearthstead.requirement.storage", 14, 20),
                    new StorageIndexPayload.GapRow("hearthstead.requirement.lights", 3, 4))),
            new StorageIndexPayload.WarehouseRow(west, 1, 1, 3, 8, 8, 2, 2, 16, -1, true,
                List.of(new StorageIndexPayload.GapRow("hearthstead.requirement.storage", 10, 14))));
        return new StorageScreen(new StorageIndexPayload("Elmfield", rows.length, total, 2, 2, 24,
            locations, locations, stocks, warehouses));
    }

    private static Screen techTree() {
        TechTreeData data = TechTreeData.get();
        Set<String> learned = new HashSet<>();
        List<TechTreeSnapshotPayload.NodeState> states = new ArrayList<>();
        List<TechNodeDef> nodes = new ArrayList<>(data.nodes());
        nodes.sort(java.util.Comparator.comparingInt(TechNodeDef::tier));
        boolean studying = false;
        for (TechNodeDef def : nodes) {
            boolean ready = learned.containsAll(def.requires());
            TechTree.Status status;
            if (ready && def.tier() <= 1) {
                status = TechTree.Status.LEARNED;
                learned.add(def.id());
            } else if (ready && !studying) {
                status = TechTree.Status.STUDYING;
                studying = true;
            } else if (ready) {
                status = TechTree.Status.AVAILABLE;
            } else {
                status = TechTree.Status.LOCKED;
            }
            int lines = TechCosts.costs(def).size();
            int[] have = new int[lines];
            for (int i = 0; i < lines; i++) {
                int need = TechCosts.costs(def).get(i).count();
                have[i] = status == TechTree.Status.LEARNED ? need : (i % 2 == 0 ? need : need / 2);
            }
            long total = status == TechTree.Status.STUDYING ? 24000L : 0L;
            long done = status == TechTree.Status.STUDYING ? 9000L : 0L;
            states.add(new TechTreeSnapshotPayload.NodeState(def.id(), status.ordinal(), "", "", have,
                List.of(), total, done));
        }
        return new TechTreeScreen(new TechTreeSnapshotPayload(base(), id("settlement"), 12, 57, states,
            "", "", true, true, "stores_and_roads"));
    }
}
