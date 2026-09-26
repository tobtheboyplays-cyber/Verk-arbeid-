package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.function.Consumer;

/** Physical fixture contacts and exact inventories; not an ordinary pacing or render test. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernTapServingGameTests {
    private static final BlockPos STORE = new BlockPos(3, 1, 3);
    private record Fixture(Building tavern, SettlerEntity host, SettlerEntity guest,
                           TavernSeatEntity seat, Container store, BlockPos source) {}
    private static ItemStack named(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Tavern service proof")); return stack;
    }
    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(11500);
        Settlement s = new Settlement(UUID.randomUUID(), "Service fixture", h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building b = GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(12, 4, 12))));
        h.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_PRESSURE_PLATE);
        h.setBlock(STORE, Blocks.BARREL);
        Container store = (Container) h.getLevel().getBlockEntity(h.absolutePos(STORE));
        store.setItem(0, named(Items.BREAD, 2)); store.setItem(1, named(Items.GLASS_BOTTLE, 1));
        SettlerEntity host = resident(h, s, new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), s, b, host).ok(), "real fixture host hire");
        SettlerEntity guest = resident(h, s, new BlockPos(10, 1, 10));
        guest.setHunger(20);
        var reservation = TavernSeating.reserveReachable(guest);
        h.assertTrue(reservation.seat() != null, "real reachable furniture reservation required");
        TavernSeatEntity seat = reservation.seat();
        guest.setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        h.assertTrue(seat.mount(guest), "actual adjacent seat entry required");
        return new Fixture(b, host, guest, seat, store, h.absolutePos(STORE));
    }
    private static SettlerEntity resident(GameTestHelper h, Settlement s, BlockPos pos) {
        SettlerEntity actor = h.spawn(ModEntities.SETTLER.get(), pos);
        actor.bindTo(s.id, s.center); s.putRecord(actor.getUUID(), "Service resident", Profession.NONE);
        actor.setNoAi(true); actor.setHunger(100); actor.setEnergy(100);
        Vec3 before = actor.position();
        actor.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, -.05, 0));
        h.assertTrue(actor.onGround() && before.distanceToSqr(actor.position()) < .000001,
            "fixture must settle against its actual solid floor without moving the spawn");
        return actor;
    }
    private static TavernServingEntity pickup(GameTestHelper h, Fixture f) {
        TavernServingEntity serving = TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source());
        h.assertTrue(serving != null && f.store().getItem(0).getCount() == 1 && f.store().getItem(1).isEmpty(),
            "contact transfers one food and one physical bottle from the real store");
        return serving;
    }
    private static void atTable(Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(f.seat().site().hostApproach()));
        f.host().setYRot(f.seat().site().dinerFacing().getOpposite().toYRot());
        f.host().yBodyRot = f.host().getYRot();
    }
    private static void atStore(Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(f.source().south()));
    }
    private static final BlockPos TAP = new BlockPos(9, 1, 3), TAP_BARREL = new BlockPos(8, 1, 3);
    private static Container installTap(GameTestHelper h, Fixture f) {
        // Core tap authority requires the actual persisted plaque identity.
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag tag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        tag.putUUID("Building", f.tavern().id); tag.putString("Type", BuildingType.TAVERN.id());
        tag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(tag, h.getLevel().registryAccess());
        h.setBlock(TAP_BARREL, Blocks.BARREL);
        h.setBlock(TAP, com.hearthstead.registry.ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING, Direction.EAST));
        Container barrel = (Container) h.getLevel().getBlockEntity(h.absolutePos(TAP_BARREL));
        barrel.setItem(0, new ItemStack(ModItems.ALE.get()));
        h.assertTrue(h.absolutePos(TAP).equals(com.hearthstead.settlement.work.AleTapService.resolveTapForBarrel(h.getLevel(), f.tavern(),
            h.absolutePos(TAP_BARREL))), "exact real tap/backing-barrel ownership");
        return barrel;
    }
    private static int count(Container container, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) result += container.getItem(i).getCount();
        return result;
    }
    private static void atTap(GameTestHelper h, Fixture f) {
        f.host().setPos(Vec3.atBottomCenterOf(h.absolutePos(TAP.east())));
        f.host().getNavigation().stop();
        h.assertTrue(com.hearthstead.settlement.work.AleTapService.hostContact(f.host(), f.tavern(), h.absolutePos(TAP)),
            "fixture host reaches actual visible tap collider");
    }
    @GameTest(batch = "tavern_tap_serving_paid", template = "empty16", timeoutTicks = 360)
    public void realTapAleBecomesSipPaymentThenPhysicalBarrelIncome(GameTestHelper h) {
        runTapService(h, false);
    }
    @GameTest(batch = "tavern_tap_serving_no_funds", template = "empty16", timeoutTicks = 360)
    public void missingFundsAfterPourReturnUntastedAleWithoutFreeDrink(GameTestHelper h) {
        runTapService(h, true);
    }
    private static void runTapService(GameTestHelper h, boolean removeFundsAfterPour) {
        Fixture f = fixture(h); Container barrel = installTap(h, f);
        f.guest().bag.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 2));
        TavernServingEntity serving = pickup(h, f);
        boolean[] poured = {false}, paid = {false}, guestLeft = {false};
        h.onEachTick(() -> {
            if (serving.isRemoved()) return;
            if (serving.phase() == TavernServingEntity.Phase.CARRYING && serving.displayAle().isEmpty()) atTap(h, f);
            else if (serving.phase() == TavernServingEntity.Phase.CARRYING) atTable(f);
            else if (serving.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), serving);
                h.assertTrue(stand != null, "a real pickup stand must exist");
                h.assertTrue(h.getLevel().noCollision(f.host(),
                    f.host().getDimensions(f.host().getPose()).makeBoundingBox(stand)),
                    "the real pickup stand must keep the full host body clear of table and chair");
                f.host().setPos(stand);
            } else if (serving.phase() == TavernServingEntity.Phase.RETURNING) {
                if (!serving.displayAle().isEmpty() || !serving.displayPayment().isEmpty()) atTap(h, f);
                else atStore(f);
            }
            serving.serviceTick(f.host());
            if (!poured[0] && !serving.displayAle().isEmpty()) {
                poured[0] = true;
                h.assertTrue(count(barrel, ModItems.ALE.get()) == 0
                    && count(barrel, ModItems.GOLD_COIN.get()) == 0
                    && count(f.guest().bag, ModItems.GOLD_COIN.get()) == 2,
                    "tap pours owned stock, not an early sale or remote charge");
                if (removeFundsAfterPour) {
                    f.store().setItem(5, f.guest().bag.removeItem(0, 2)); f.store().setChanged();
                }
            }
            if (f.guest().hasMeal()) {
                // This no-AI fixture drives the same meal activity that TavernVisitGoal
                // sets for a seated guest before advancing the durable meal slice.
                f.guest().setActivity(SettlerActivity.EATING);
                f.guest().tickMeal();
            } else {
                f.guest().setActivity(SettlerActivity.IDLE);
            }
            if (!serving.displayPayment().isEmpty()) {
                paid[0] = true;
                h.assertTrue(!removeFundsAfterPour && serving.displayAle().isEmpty()
                    && count(f.guest().bag, ModItems.GOLD_COIN.get()) == 1
                    && count(barrel, ModItems.GOLD_COIN.get()) == 0,
                    "sip transfers exactly one coin into serving custody before barrel return");
                CompoundTag saved = new CompoundTag(); serving.saveWithoutId(saved);
                TavernServingEntity restored = ModEntities.TAVERN_SERVING.get().create(h.getLevel());
                h.assertTrue(restored != null, "saved serving copy exists"); restored.load(saved);
                h.assertTrue(restored.displayPayment().getCount() == 1
                    && h.absolutePos(TAP).equals(restored.tapSource())
                    && h.absolutePos(TAP_BARREL).equals(restored.tapBarrel()),
                    "payment and exact return address survive NBT roundtrip");
                if (!guestLeft[0]) {
                    guestLeft[0] = true; f.guest().stopRiding(); serving.beginReturn();
                }
            }
            int coins = count(f.guest().bag, ModItems.GOLD_COIN.get()) + count(f.store(), ModItems.GOLD_COIN.get())
                + count(barrel, ModItems.GOLD_COIN.get()) + serving.displayPayment().getCount();
            h.assertTrue(coins == 2, "every physical coin remains in exactly one owner");
            if (!serving.hasCargo()) {
                h.assertTrue(poured[0] && (removeFundsAfterPour ? !paid[0] : paid[0]), "test must observe the intended sale outcome");
                h.assertTrue(count(barrel, ModItems.ALE.get()) == (removeFundsAfterPour ? 1 : 0)
                    && count(barrel, ModItems.GOLD_COIN.get()) == (removeFundsAfterPour ? 0 : 1),
                    "original barrel receives either untasted Ale or the actual sale coin");
                h.assertTrue(count(f.store(), Items.GLASS_BOTTLE) == 1, "same reusable glass returns to its original store");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_tap_no_free_chest", template = "empty16", timeoutTicks = 20)
    public void arbitraryFoodStoreCannotBrewOrIncludeFreeAle(GameTestHelper h) {
        Fixture f = fixture(h);
        f.store().setItem(2, new ItemStack(ModItems.ALE.get(), 2));
        f.store().setItem(3, new ItemStack(Items.WHEAT, 3));
        TavernServingEntity serving = pickup(h, f);
        h.assertTrue(serving.displayAle().isEmpty() && count(f.store(), ModItems.ALE.get()) == 2
            && count(f.store(), Items.WHEAT) == 3, "food pickup neither brews nor steals arbitrary-container Ale");
        h.succeed();
    }
    @GameTest(batch = "tavern_tap_wheat_routing", template = "empty16", timeoutTicks = 20)
    public void wheatRestockTargetsOnlyConnectedBarrelWithCapacity(GameTestHelper h) {
        Fixture f = fixture(h); Container barrel = installTap(h, f);
        ItemStack wheat = new ItemStack(Items.WHEAT, 24);
        var need = TavernHostService.restockNeed(h.getLevel(), f.host().settlement(), f.tavern(), wheat);
        h.assertTrue(need != null && need.container().equals(h.absolutePos(TAP_BARREL)) && need.deficit() == 24,
            "wheat has its own real connected barrel destination, without co-located food/glass");
        h.setBlock(TAP, Blocks.AIR);
        h.assertTrue(TavernHostService.restockNeed(h.getLevel(), f.host().settlement(), f.tavern(), wheat) == null,
            "an arbitrary food barrel cannot request tap wheat without a connected tap");
        h.setBlock(TAP, com.hearthstead.registry.ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING, Direction.EAST));
        for (int i = 0; i < barrel.getContainerSize(); i++) barrel.setItem(i, new ItemStack(Items.DIRT, 64));
        barrel.setChanged();
        h.assertTrue(TavernHostService.restockNeed(h.getLevel(), f.host().settlement(), f.tavern(), wheat) == null,
            "full backing barrel cannot publish phantom wheat capacity");
        h.succeed();
    }
}
