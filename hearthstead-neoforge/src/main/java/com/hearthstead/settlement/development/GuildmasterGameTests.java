package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.GuildmasterEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentNetwork;
import com.hearthstead.registry.GuildmasterEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guildmaster.GuildmasterRegistry;
import com.hearthstead.settlement.guildmaster.GuildmasterService;
import com.hearthstead.settlement.guildmaster.GuildmasterTrade;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.List;
import java.util.UUID;

/**
 * The Guildmaster (26 Sep): one seated NPC per Banner that survives save and
 * reload without duplicating, and a Professions & Emblems trade that never
 * duplicates emblems or eats Coins (double click, stale screen, two players,
 * full inventory, walking away).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuildmasterGameTests {
    private static final String BATCH = "guildmaster";

    @GameTest(template = "empty16", timeoutTicks = 200, batch = BATCH)
    public void oneGuildmasterPerBannerPersistsWithoutDuplicates(GameTestHelper helper) {
        Fixture f = fixture(helper);
        ServerLevel level = helper.getLevel();
        try {
            GuildmasterEntity g = GuildmasterService.ensure(level, f.settlement);
            helper.assertTrue(g != null, "the Guildmaster takes his seat at a loaded Banner");
            helper.assertTrue(GuildmasterService.ensure(level, f.settlement) == g,
                "a second pass returns the same Guildmaster, never a new one");
            helper.assertTrue(count(level, f) == 1, "exactly one Guildmaster per Banner");

            // Not a settler: no roster row, no population, no founder place.
            helper.assertTrue(f.settlement.population() == SettlementManager.FOUNDER_COUNT,
                "founders only; the Guildmaster is not counted: " + f.settlement.population());
            helper.assertTrue(f.settlement.record(g.getUUID()) == null, "no roster record");
            helper.assertTrue(SettlementManager.loadedMembers(level, f.settlement).stream()
                .noneMatch(member -> member.getUUID().equals(g.getUUID())), "not a settlement member");
            helper.assertTrue(f.settlement.mayorId == null, "no Mayor is seated at founding");
            for (SettlerEntity founder : SettlementManager.loadedMembers(level, f.settlement)) {
                helper.assertTrue(founder.getProfession() == Profession.NONE,
                    "every founder is an ordinary unassigned settler");
            }

            // Seated beside, not on, the Banner.
            BlockPos seat = g.seat();
            helper.assertTrue(seat != null && !seat.equals(f.center)
                && seat.distManhattan(f.center) <= 4, "seated beside the Banner: " + seat);

            // Invulnerable: ordinary damage never lands.
            helper.assertTrue(!g.hurt(level.damageSources().generic(), 100.0F) && g.isAlive(),
                "ordinary damage is refused");
            helper.assertTrue(!g.hurt(level.damageSources().mobAttack(g), 100.0F) && g.isAlive(),
                "raid-style melee damage is refused");

            // Save -> unload -> reload: the same identity comes back, no second one spawns.
            CompoundTag saved = g.saveWithoutId(new CompoundTag());
            UUID id = g.getUUID();
            g.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            GuildmasterEntity reloaded = GuildmasterEntities.GUILDMASTER.get().create(level);
            helper.assertTrue(reloaded != null, "entity type exists");
            reloaded.load(saved);
            helper.assertTrue(level.addFreshEntity(reloaded), "reloaded Guildmaster rejoins the level");
            helper.assertTrue(reloaded.getUUID().equals(id)
                && f.settlement.id.equals(reloaded.settlementId())
                && seat.equals(reloaded.seat()), "identity, settlement link and seat persist");
            helper.assertTrue(GuildmasterService.ensure(level, f.settlement) == reloaded
                && count(level, f) == 1, "reload adopts the saved Guildmaster, no duplicate");

            // A stray duplicate (e.g. a stale chunk copy) removes itself.
            GuildmasterEntity stray = GuildmasterEntities.GUILDMASTER.get().create(level);
            stray.bind(f.settlement.id, seat, 0.0F);
            level.addFreshEntity(stray);
            GuildmasterService.check(level, stray);
            helper.assertTrue(stray.isRemoved() && reloaded.isAlive() && count(level, f) == 1,
                "a duplicate leaves; the linked Guildmaster stays");

            // A lost link is re-adopted instead of spawning a second one.
            GuildmasterRegistry.get(level).unlink(f.settlement.id);
            helper.assertTrue(GuildmasterService.ensure(level, f.settlement) == reloaded
                && id.equals(GuildmasterRegistry.get(level).idFor(f.settlement.id))
                && count(level, f) == 1, "a lost link adopts the seated Guildmaster");

            // Displaced (pushed/teleported): he returns to his stool.
            reloaded.teleportTo(seat.getX() + 3.5D, seat.getY(), seat.getZ() + 0.5D);
            GuildmasterService.check(level, reloaded);
            helper.assertTrue(reloaded.blockPosition().equals(seat), "back on the stool");

            // Banner removed: the settlement disbands and he leaves with it.
            SettlementManager.disbandAt(level, f.center);
            GuildmasterService.check(level, reloaded);
            helper.assertTrue(reloaded.isRemoved(), "no Banner, no Guildmaster");
            GuildmasterService.ensureAll(level);
            helper.assertTrue(GuildmasterRegistry.get(level).idFor(f.settlement.id) == null,
                "the link is dropped with the settlement");
        } finally {
            cleanup(helper, f);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = BATCH)
    public void emblemTradeIsAtomicUnderDoubleClicksStaleScreensAndFullInventories(GameTestHelper helper) {
        Fixture f = fixture(helper);
        ServerLevel level = helper.getLevel();
        try {
            GuildmasterEntity g = GuildmasterService.ensure(level, f.settlement);
            helper.assertTrue(g != null, "Guildmaster seated");
            DevelopmentState state = Development.of(level, f.settlement);
            JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(Profession.LUMBERER);
            helper.assertTrue(entry != null, "Lumberer emblem is in the catalogue");
            state.unlock(entry.unlock());
            TechTreeTestGrants.grantClaimants(state, Profession.LUMBERER);
            Item coin = ModItems.GOLD_COIN.get();
            int unitCoins = entry.coinPrice();
            put(f.hearth, coin, unitCoins * 3);
            for (DevelopmentNode.Cost cost : entry.goods()) {
                put(f.hearth, cost.item(), cost.count() * 3);
            }
            Item emblem = ModItems.LUMBERER_EMBLEM.get();

            ServerPlayer buyer = helper.makeMockServerPlayerInLevel();
            ServerPlayer rival = helper.makeMockServerPlayerInLevel();
            buyer.getInventory().clearContent();
            rival.getInventory().clearContent();
            near(buyer, g);
            near(rival, g);

            GuildmasterService.openTrade(buyer, g);
            int revision = Development.revisionOf(level, f.settlement);

            // Buy two in one press.
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 2), revision);
            helper.assertTrue(count(buyer, emblem) == 2, "two emblems delivered: " + count(buyer, emblem));
            helper.assertTrue(stock(f.hearth, coin) == unitCoins, "exactly two emblems paid for");

            // Double click / stale screen: the same packet again changes nothing.
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 2), revision);
            helper.assertTrue(count(buyer, emblem) == 2 && stock(f.hearth, coin) == unitCoins,
                "a replayed purchase neither pays nor delivers twice");

            // Two players on the same snapshot: the second is refused, uncharged.
            int shared = Development.revisionOf(level, f.settlement);
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 1), shared);
            buy(rival, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 1), shared);
            helper.assertTrue(count(buyer, emblem) == 3 && count(rival, emblem) == 0
                && stock(f.hearth, coin) == 0, "one sale per snapshot, never two");

            // Not enough for the whole quantity: all-or-nothing, nothing charged.
            put(f.hearth, coin, unitCoins);
            for (DevelopmentNode.Cost cost : entry.goods()) {
                put(f.hearth, cost.item(), cost.count() * 3);
            }
            int before = stock(f.hearth, coin);
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 3),
                Development.revisionOf(level, f.settlement));
            helper.assertTrue(count(buyer, emblem) == 3 && stock(f.hearth, coin) == before,
                "a quantity the settlement cannot fully pay for is refused whole");

            // Full inventory: refused before payment, nothing lost.
            for (int i = 0; i < rival.getInventory().items.size(); i++) {
                if (rival.getInventory().items.get(i).isEmpty()) {
                    rival.getInventory().items.set(i, new ItemStack(Items.DIRT));
                }
            }
            buy(rival, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 1),
                Development.revisionOf(level, f.settlement));
            helper.assertTrue(count(rival, emblem) == 0 && stock(f.hearth, coin) == before,
                "a full inventory refuses the purchase and charges nothing");

            // Walked away: out of reach refuses without charge.
            buyer.teleportTo(g.getX() + 20.0D, g.getY(), g.getZ());
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 1),
                Development.revisionOf(level, f.settlement));
            helper.assertTrue(count(buyer, emblem) == 3 && stock(f.hearth, coin) == before,
                "out of reach of the Guildmaster: no sale");

            // Back in reach, one more sale succeeds with the fresh screen.
            near(buyer, g);
            buy(buyer, f, g, GuildmasterTrade.encode(Profession.LUMBERER.id(), 1),
                Development.revisionOf(level, f.settlement));
            helper.assertTrue(count(buyer, emblem) == 4 && stock(f.hearth, coin) == before - unitCoins,
                "a fresh, in-reach, affordable purchase succeeds");

            // Meeting him records the Journey's "Meet the Guildmaster" evidence
            // (FJ030 then completes as soon as its FJ020 prerequisite is done).
            helper.assertTrue(f.settlement.journeyState.evidence().stream().anyMatch(e ->
                    e.stepId().equals(com.hearthstead.settlement.journey.JourneyIds.FJ_030_APPOINT_MAYOR)),
                "opening his trade records meeting the Guildmaster (FJ030 evidence)");
        } finally {
            cleanup(helper, f);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------ fixture --

    private record Fixture(Settlement settlement, HearthBlockEntity hearth, BlockPos center) {
    }

    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(center, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(helper.getLevel(), center);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        helper.assertTrue(settlement != null, "founding succeeds");
        settlement.radius = 8;
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(center);
        helper.assertTrue(hearth != null, "Banner block entity exists");
        hearth.bindSettlement(settlement.id);
        return new Fixture(settlement, hearth, center);
    }

    private static void cleanup(GameTestHelper helper, Fixture f) {
        ServerLevel level = helper.getLevel();
        for (GuildmasterEntity g : level.getEntitiesOfClass(GuildmasterEntity.class,
                new AABB(f.center).inflate(12.0D))) {
            g.discard();
        }
        for (SettlerEntity member : SettlementManager.loadedMembers(level, f.settlement)) {
            member.discard();
        }
        GuildmasterRegistry.get(level).unlink(f.settlement.id);
        SettlementSavedData.get(level).settlements.remove(f.settlement.id);
        SettlementSavedData.get(level).setDirty();
    }

    private static int count(ServerLevel level, Fixture f) {
        List<GuildmasterEntity> all = level.getEntitiesOfClass(GuildmasterEntity.class,
            new AABB(f.center).inflate(12.0D),
            g -> g.isAlive() && f.settlement.id.equals(g.settlementId()));
        return all.size();
    }

    private static void near(ServerPlayer player, GuildmasterEntity g) {
        player.teleportTo(g.getX() + 1.5D, g.getY(), g.getZ() + 1.5D);
    }

    private static void buy(ServerPlayer player, Fixture f, GuildmasterEntity g, int target, int revision) {
        DevelopmentNetwork.handle(player, new DevelopmentActionPayload(f.center, f.settlement.id,
            g.getUUID(), DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.BUY_EMBLEM, target, revision));
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) n += stack.getCount();
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        IItemHandlerModifiable inv = hearth.getInventory();
        int left = amount;
        for (int slot = 0; slot < inv.getSlots() && left > 0; slot++) {
            ItemStack in = inv.getStackInSlot(slot);
            int max = Math.min(inv.getSlotLimit(slot), item.getDefaultMaxStackSize());
            if (in.isEmpty()) {
                int n = Math.min(max, left);
                inv.setStackInSlot(slot, new ItemStack(item, n));
                left -= n;
            } else if (in.is(item) && in.getCount() < max) {
                int n = Math.min(max - in.getCount(), left);
                in.grow(n);
                left -= n;
            }
        }
    }

    private static int stock(HearthBlockEntity hearth, Item item) {
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack in = hearth.getInventory().getStackInSlot(slot);
            if (in.is(item)) n += in.getCount();
        }
        return n;
    }
}
