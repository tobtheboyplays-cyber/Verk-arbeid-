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
public class TavernHostGameTests {
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
    @GameTest(batch = "tavern_host", template = "empty16", timeoutTicks = 80)
    public void pickupNeedsContactRealGlassAndOneClaim(GameTestHelper h) {
        Fixture f = fixture(h);
        f.store().setItem(1, ItemStack.EMPTY);
        h.assertTrue(TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null
            && f.store().getItem(0).getCount() == 2, "missing glass must not consume food or fake a glass");
        f.store().setItem(1, named(Items.GLASS_BOTTLE, 1));
        atTable(f);
        h.assertTrue(TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null
            && f.store().getItem(0).getCount() == 2, "remote pickup cannot mutate stock");
        atStore(f);
        Consumer<EntityJoinLevelEvent> reject = event -> {
            if (event.getLevel() == h.getLevel() && event.getEntity() instanceof TavernServingEntity)
                event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EntityJoinLevelEvent.class, reject);
        try {
            h.assertTrue(TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null
                && f.store().getItem(0).getCount() == 2 && f.store().getItem(1).getCount() == 1
                && !f.tavern().tavernServingClaims.occupied(f.seat().site().table()),
                "rejected serving join must leave stock unchanged and release only its exact claim");
        } finally { NeoForge.EVENT_BUS.unregister(reject); }
        TavernServingEntity serving = pickup(h, f);
        h.assertTrue(TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null
            && ItemStack.isSameItemSameComponents(serving.displayFood(), named(Items.BREAD, 1)),
            "second claim must fail and exact food components remain");
        serving.displayFood().shrink(1);
        h.assertTrue(serving.displayFood().getCount() == 1, "display copy is not mutable item authority");
        h.succeed();
    }
    @GameTest(batch = "tavern_host", template = "empty16", timeoutTicks = 200)
    public void tabletopSlideTransfersFoodAtReceivingEndAndReturnsSameGlass(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity serving = pickup(h, f); atTable(f);
        Vec3[] prior = {null}; boolean[] received = {false};
        boolean[] placed = {false}, reclaimed = {false};
        h.onEachTick(() -> {
            if (serving.isRemoved()) return;
            if (serving.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), serving);
                h.assertTrue(stand != null, "direct contact fixture needs a clear physical pickup stand");
                f.host().setPos(stand);
            }
            if (serving.phase() == TavernServingEntity.Phase.RETURNING) atStore(f);
            serving.serviceTick(f.host());
            if (serving.phase() == TavernServingEntity.Phase.PLACING
                || serving.phase() == TavernServingEntity.Phase.PICKING_UP) {
                if (serving.phase() == TavernServingEntity.Phase.PLACING) placed[0] = true;
                else reclaimed[0] = true;
                Vec3 point = serving.foodPosition(0);
                if (prior[0] != null) h.assertTrue(point.distanceTo(prior[0]) <= .15,
                    "placement/reclaim must interpolate without an instantaneous prop jump");
                prior[0] = point;
            } else if (serving.phase() != TavernServingEntity.Phase.SLIDING) prior[0] = null;
            if (serving.phase() == TavernServingEntity.Phase.SLIDING) {
                Vec3 point = serving.foodPosition(0);
                h.assertTrue(!f.guest().hasMeal() && serving.displayFood().getCount() == 1,
                    "food remains table-owned throughout actual slide");
                h.assertTrue(Math.abs(point.y - (f.seat().site().tabletopCenter(h.getLevel()).y + .002)) < .00001,
                    "sliding food stays on the actual tabletop height");
                if (prior[0] != null && serving.slideTicks() > 0) h.assertTrue(point.distanceTo(prior[0]) <= .031,
                    "per-tick tabletop movement must remain bounded and continuous");
                prior[0] = point;
            }
            if (serving.phase() == TavernServingEntity.Phase.READY) {
                h.assertTrue(!f.guest().hasMeal() && serving.displayFood().getCount() == 1
                    && serving.receivingTicks() < TavernServingEntity.RECEIVING_TICKS,
                    "food stays table-owned during the full receiving reach");
            }
            if (serving.phase() == TavernServingEntity.Phase.DINING) {
                h.assertTrue(serving.receivingTicks() == TavernServingEntity.RECEIVING_TICKS,
                    "actual receiving contact must complete all eight ticks before meal transfer");
                received[0] = true;
                h.assertTrue(serving.displayFood().isEmpty() && serving.displayGlass().is(Items.GLASS_BOTTLE),
                    "after receiving, table owns only the real reusable glass");
                if (f.guest().hasMeal()) {
                    f.guest().setActivity(SettlerActivity.EATING); f.guest().tickMeal();
                }
            }
            if (received[0] && !serving.hasCargo()) {
                h.assertTrue(placed[0] && reclaimed[0] && !f.guest().hasMeal() && f.guest().getHunger() > 55
                    && f.store().getItem(0).getCount() == 1 && f.store().getItem(1).getCount() == 1
                    && ItemStack.isSameItemSameComponents(f.store().getItem(1), named(Items.GLASS_BOTTLE, 1)),
                    "one real meal is consumed and the same component-preserving bottle returns");
                h.succeed();
            }
        });
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
    @GameTest(batch = "tavern_ale", template = "empty16", timeoutTicks = 360)
    public void contactBrewedAlePersistsThenDrinksOnceAndReturnsTheSameGlass(GameTestHelper h) {
        Fixture f = fixture(h);
        Container tapBarrel = installTap(h, f);
        tapBarrel.setItem(0, ItemStack.EMPTY);
        tapBarrel.setItem(1, new ItemStack(Items.WHEAT, 3));
        f.guest().bag.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 2));
        BlockPos barrelPos = h.absolutePos(TAP_BARREL);
        f.host().setPos(Vec3.atBottomCenterOf(barrelPos.south()));
        h.assertTrue(com.hearthstead.settlement.work.TavernAleService.brewAtContact(
            f.host(), f.tavern(), barrelPos, tapBarrel)
            && count(tapBarrel, Items.WHEAT) == 0 && count(tapBarrel, ModItems.ALE.get()) == 1,
            "three real plain wheat become exactly one reserve Ale at real backing-barrel contact");
        atStore(f);
        TavernServingEntity[] owner = {pickup(h, f)};
        h.assertTrue(owner[0].displayAle().isEmpty(), "food pickup cannot withdraw Ale remotely");
        atTap(h, f);
        h.assertTrue(com.hearthstead.settlement.work.AleTapService.takeAleAtContact(
            f.host(), f.tavern(), h.absolutePos(TAP), owner[0])
            && owner[0].displayAle().is(ModItems.ALE.get()) && count(tapBarrel, ModItems.ALE.get()) == 0,
            "actual tap contact transfers the one brewed Ale into the registered serving");

        // A reload while carrying must retain the exact optional stack; no
        // source refill or second brew is permitted on rejoin.
        CompoundTag saved = new CompoundTag();
        h.assertTrue(owner[0].save(saved), "ale-owning serving must save");
        UUID id = owner[0].getUUID(); owner[0].discard();
        var loaded = EntityType.loadEntityRecursive(saved, h.getLevel(), entity -> entity);
        h.assertTrue(loaded instanceof TavernServingEntity && h.getLevel().addFreshEntity(loaded),
            "saved ale-owning serving must rejoin through its registered factory");
        owner[0] = (TavernServingEntity) loaded;
        h.assertTrue(owner[0].getUUID().equals(id) && owner[0].displayAle().is(ModItems.ALE.get())
            && owner[0].displayAle().getCount() == 1,
            "reload preserves the exact in-transit ale stack rather than recreating it");
        atTable(f);
        boolean[] drank = {false};
        int[] peakDrinkTicks = {0};
        boolean[] committed = {false};
        boolean[] drinkReloaded = {false};
        Vec3[] tabletopGlass = {null};
        h.onEachTick(() -> {
            TavernServingEntity serving = owner[0];
            if (serving.isRemoved()) return;
            // Isolate this contact from the already completed meal and ordinary
            // social/temperament changes earlier in the visit.
            float moraleBeforeService = f.guest().getMorale();
            if (serving.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), serving);
                h.assertTrue(stand != null, "ale return needs the existing physical pickup stand");
                f.host().setPos(stand);
            }
            if (serving.phase() == TavernServingEntity.Phase.RETURNING) {
                if (!serving.displayPayment().isEmpty() || !serving.displayAle().isEmpty()) atTap(h, f);
                else atStore(f);
            }
            serving.serviceTick(f.host());
            if (serving.phase() == TavernServingEntity.Phase.DINING && tabletopGlass[0] == null)
                tabletopGlass[0] = serving.glassPosition(0);
            if (serving.phase() == TavernServingEntity.Phase.DRINKING) {
                peakDrinkTicks[0] = Math.max(peakDrinkTicks[0], serving.drinkingTicks());
                if (!drinkReloaded[0] && serving.drinkingTicks() == 4) {
                    Vec3 beforeReload = serving.glassPosition(0); CompoundTag drinkSave = new CompoundTag();
                    h.assertTrue(serving.save(drinkSave), "mid-lift glass must save");
                    serving.discard();
                    var resumed = EntityType.loadEntityRecursive(drinkSave, h.getLevel(), entity -> entity);
                    h.assertTrue(resumed instanceof TavernServingEntity && h.getLevel().addFreshEntity(resumed),
                        "mid-lift serving must rejoin through its registered factory");
                    owner[0] = (TavernServingEntity) resumed; drinkReloaded[0] = true;
                    h.assertTrue(owner[0].drinkingTicks() == 4 && owner[0].displayAle().is(ModItems.ALE.get())
                        && owner[0].glassPosition(0).distanceToSqr(beforeReload) < 1e-8,
                        "reload keeps the exact ale owner and synchronized mid-lift glass point");
                    return;
                }
                if (serving.drinkingTicks() == TavernServingEntity.DRINK_LIFT_TICKS) {
                    h.assertTrue(!committed[0], "the same Ale must never commit twice");
                    drank[0] = committed[0] = true;
                    h.assertTrue(serving.displayAle().isEmpty()
                        && serving.displayPayment().getCount() == 1
                        && count(f.guest().bag, ModItems.GOLD_COIN.get()) == 1
                        && count(tapBarrel, ModItems.GOLD_COIN.get()) == 0
                        && Math.abs(f.guest().getMorale() - Math.min(100.0F,
                            moraleBeforeService + 3.0F * Trait.moraleGain(f.guest().traits()))) < .0001F
                        && tabletopGlass[0] != null
                        && serving.glassPosition(0).distanceToSqr(tabletopGlass[0]) > .01,
                        "Ale commits once at the lifted mouth contact, with the same glass and +3 morale");
                }
            }
            if (serving.phase() == TavernServingEntity.Phase.DINING && f.guest().hasMeal()) {
                f.guest().setActivity(SettlerActivity.EATING); f.guest().tickMeal();
            }
            if (drank[0] && !serving.hasCargo()) {
                h.assertTrue(committed[0] && peakDrinkTicks[0] >= TavernServingEntity.DRINK_TOTAL_TICKS - 1,
                    "the same glass must complete the full 8-lift/8-sip/8-lower cycle before return");
                h.assertTrue(f.store().getItem(0).getCount() == 1
                    && f.store().getItem(1).is(Items.GLASS_BOTTLE)
                    && f.store().getItem(1).getCount() == 1
                    && f.store().getItem(2).isEmpty()
                    && count(tapBarrel, Items.WHEAT) == 0 && count(tapBarrel, ModItems.ALE.get()) == 0
                    && count(tapBarrel, ModItems.GOLD_COIN.get()) == 1
                    && count(f.guest().bag, ModItems.GOLD_COIN.get()) == 1
                    && ItemStack.isSameItemSameComponents(f.store().getItem(1), named(Items.GLASS_BOTTLE, 1)),
                    "food and exactly one ale are consumed while the exact reusable glass returns once");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_host_restart", template = "empty16", timeoutTicks = 160)
    public void loadedSlideKeepsIdentityAndDepartedGuestReturnsBothItems(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity[] serving = {pickup(h, f)}; atTable(f);
        boolean[] restored = {false};
        h.onEachTick(() -> {
            TavernServingEntity current = serving[0];
            if (current.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), current);
                h.assertTrue(stand != null, "loaded abort fixture must have an actual clear pickup stand");
                f.host().setPos(stand);
            }
            if (current.phase() == TavernServingEntity.Phase.RETURNING) atStore(f);
            current.serviceTick(f.host());
            if (!restored[0] && current.slideTicks() == 10) {
                CompoundTag saved = new CompoundTag();
                h.assertTrue(current.save(saved), "real registered serving entity must save");
                UUID id = current.getUUID(); current.discard();
                var loaded = EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
                h.assertTrue(loaded instanceof TavernServingEntity, "registered factory must load session");
                TavernServingEntity again = (TavernServingEntity) loaded;
                h.assertTrue(h.getLevel().addFreshEntity(again), "same UUID must rejoin after old fixture removal");
                serving[0] = again; restored[0] = true;
                h.assertTrue(again.getUUID().equals(id) && TavernHostService.current(f.host()) == again
                    && again.slideTicks() == 10 && again.displayFood().getCount() == 1
                    && again.displayGlass().getCount() == 1, "reload must preserve identity, both stacks and progress");
                Vec3 beforeAbort = again.foodPosition(0);
                again.beginReturn();
                h.assertTrue(beforeAbort.equals(again.foodPosition(0)), "interruption must not jump food to another table edge");
                h.assertTrue(f.seat().release(), "fixture guest leaves through a physical clear exit");
            }
            if (restored[0] && !serving[0].hasCargo()) {
                h.assertTrue(!f.guest().hasMeal() && f.store().getItem(0).getCount() == 2
                    && f.store().getItem(1).getCount() == 1,
                    "aborted serving returns both exact items without granting a meal");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_furniture_custody", template = "empty16", timeoutTicks = 20)
    public void servingReloadPreservesFurnitureFrameAndExactCargo(GameTestHelper h) {
        Fixture f = fixture(h);
        var vanilla = f.seat().site();
        for (var furniture : TavernSeating.Furniture.values()) {
            var site = new TavernSeating.SeatSite(vanilla.tavernId(), vanilla.chair(), vanilla.table(),
                vanilla.dinerFacing(), vanilla.aisle(), vanilla.hostApproach(), furniture);
            TavernServingEntity serving = ModEntities.TAVERN_SERVING.get().create(h.getLevel());
            h.assertTrue(serving != null, "registered serving factory");
            serving.configure(f.host(), f.guest(), site, f.source());
            ItemStack food = named(Items.BREAD, 1), glass = named(Items.GLASS_BOTTLE, 1);
            serving.takeFrom(food, glass);
            CompoundTag saved = new CompoundTag();
            h.assertTrue(serving.save(saved), "serving saves its furniture frame");
            var loaded = EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
            h.assertTrue(loaded instanceof TavernServingEntity, "registered serving reload");
            var again = (TavernServingEntity) loaded;
            h.assertTrue(site.equals(again.site()) && again.getUUID().equals(serving.getUUID())
                && again.displayFood().getCount() == 1 && again.displayGlass().getCount() == 1
                && food.isEmpty() && glass.isEmpty(),
                "reload must keep furniture identity and the sole physical food/glass owner");
            if (furniture == TavernSeating.Furniture.VANILLA) {
                saved.remove("AnotherFurniture");
                var legacy = (TavernServingEntity) EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
                h.assertTrue(legacy != null && vanilla.equals(legacy.site()), "legacy vanilla frame remains readable");
            }
        }
        h.succeed();
    }
    @GameTest(batch = "tavern_host_normal", template = "empty16", timeoutTicks = 700)
    public void ordinaryHostAndSeatedDinerCompleteOnePhysicalService(GameTestHelper h) {
        Fixture f = fixture(h);
        f.host().setNoAi(false); f.guest().setNoAi(false);
        h.succeedWhen(() -> {
            h.assertTrue(f.guest().isBound() && !f.guest().isTraveler()
                && f.store().getItem(0).getCount() == 1 && f.store().getItem(1).getCount() == 1
                && f.guest().getHunger() > 55 && !f.guest().hasMeal(),
                "ordinary host movement, exact final steer, placement, slide, seated eating and glass return must complete");
        });
    }
    @GameTest(batch = "tavern_host_alternate_store", template = "empty16", timeoutTicks = 1200)
    public void inaccessibleFirstStoreDoesNotStarveAnAccessibleSecondStore(GameTestHelper h) {
        Fixture f = fixture(h);
        // Fixture placement precedes observation; neither actor is moved during service.
        f.host().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(7, 1, 4))));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            h.setBlock(STORE.relative(side), Blocks.STONE_BRICKS);
            h.setBlock(STORE.relative(side).above(), Blocks.STONE_BRICKS);
        }
        h.setBlock(STORE.above(), Blocks.STONE_BRICKS);
        BlockPos alternate = new BlockPos(9, 1, 3);
        h.setBlock(alternate, Blocks.BARREL);
        Container second = (Container) h.getLevel().getBlockEntity(h.absolutePos(alternate));
        second.setItem(0, named(Items.BREAD, 2)); second.setItem(1, named(Items.GLASS_BOTTLE, 1));
        h.assertTrue(f.source().equals(TavernHostService.source(f.host(), f.tavern())),
            "the inaccessible stocked source must really be selected first");
        f.host().setNoAi(false); f.guest().setNoAi(false);
        h.succeedWhen(() -> {
            h.assertTrue(f.store().getItem(0).getCount() == 2 && f.store().getItem(1).getCount() == 1,
                "the enclosed store must never be accessed remotely");
            h.assertTrue(second.getItem(0).getCount() == 1 && second.getItem(1).getCount() == 1
                && f.guest().getHunger() > 55 && !f.guest().hasMeal(),
                "host must try the second store, serve its real meal and return its glass");
        });
    }
    @GameTest(batch = "tavern_host_facing", template = "empty16", timeoutTicks = 700)
    public void sideApproachSettlesFacingBeforePlacementAndReclaim(GameTestHelper h) {
        Fixture f = fixture(h);
        float desired = f.seat().site().dinerFacing().getOpposite().toYRot();
        // The existing source is west of the table approach. Ordinary goals
        // own pickup and travel; no atTable teleport or serviceTick driver.
        f.host().setYRot(desired + 90); f.host().setYBodyRot(desired + 90);
        f.host().setYHeadRot(desired + 90);
        f.host().setNoAi(false); f.guest().setNoAi(false);
        boolean[] placed = {false}, disturbed = {false}, waited = {false}, reclaimed = {false};
        long[] disturbedAt = {-1}; float[] previousYaw = {desired + 90};
        h.onEachTick(() -> {
            TavernServingEntity serving = TavernHostService.current(f.host());
            if (serving == null) {
                if (reclaimed[0] && !TavernHostService.hasSession(f.host())) {
                    h.assertTrue(placed[0] && waited[0] && f.store().getItem(0).getCount() == 1
                        && f.store().getItem(1).getCount() == 1 && !f.guest().hasMeal()
                        && f.guest().getHunger() > 55,
                        "ordinary aligned service must consume one meal and return the real bottle");
                    h.succeed();
                }
                return;
            }
            float error = Math.abs(net.minecraft.util.Mth.wrapDegrees(f.host().yBodyRot - desired));
            if (serving.phase() == TavernServingEntity.Phase.PLACING) {
                placed[0] = true;
                h.assertTrue(error <= 3.01F && TavernHostService.tableContact(f.host(), serving),
                    "side arrival must settle actual body facing at real contact before placement");
            }
            if (serving.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                if (!disturbed[0] && TavernHostService.atPickupStand(f.host(), serving)) {
                    // A real orientation disturbance, without moving the host,
                    // changing ownership or forcing any serving phase.
                    f.host().setYRot(desired + 90); f.host().setYBodyRot(desired + 90);
                    f.host().setYHeadRot(desired + 90);
                    previousYaw[0] = f.host().yBodyRot;
                    disturbedAt[0] = h.getLevel().getGameTime(); disturbed[0] = true;
                } else if (disturbed[0] && h.getLevel().getGameTime() > disturbedAt[0]) {
                    h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(f.host().yBodyRot - previousYaw[0])) <= 12.01F,
                        "reclaim alignment must turn gradually, not snap the host around");
                    previousYaw[0] = f.host().yBodyRot;
                    if (error > 3.01F) {
                        waited[0] = true;
                        h.assertTrue(serving.displayFood().isEmpty() && serving.displayGlass().getCount() == 1,
                            "misaligned host waits while the same glass remains table-owned");
                    }
                }
            }
            if (serving.phase() == TavernServingEntity.Phase.PICKING_UP) {
                reclaimed[0] = true;
                h.assertTrue(disturbed[0] && waited[0] && error <= 3.01F
                    && TavernHostService.pickupContact(f.host(), serving),
                    "reclaim waits for bounded physical facing recovery before lifting the bottle");
            }
        });
    }
    @GameTest(batch = "tavern_host_missing_lookup", template = "empty16", timeoutTicks = 80)
    public void unloadedLookupRetainsSavedTableClaimAndReacquisitionIdentity(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity serving = pickup(h, f);
        UUID id = serving.getUUID();
        CompoundTag entitySave = new CompoundTag(); h.assertTrue(serving.save(entitySave), "fixture session must save");
        CompoundTag hostSave = new CompoundTag(); f.host().saveWithoutId(hostSave);
        Building restoredBuilding = Building.readNbt(f.tavern().writeNbt());
        f.tavern().tavernServingClaims = restoredBuilding.tavernServingClaims;
        serving.discard(); // Controlled missing-loaded-lookup seam, not a claimed real chunk unload.
        h.runAfterDelay(2, () -> {
            h.assertTrue(h.getLevel().getEntity(id) == null, "fixture must actually remove the loaded lookup");
            f.host().load(hostSave);
            h.assertTrue(id.equals(f.host().getPersistentData().getUUID(TavernHostService.SESSION_KEY))
                && f.host().getPersistentData().contains(TavernHostService.LOCATION_KEY, 4),
                "host reload retains exact session UUID and last known physical location");
            f.store().setItem(1, named(Items.GLASS_BOTTLE, 1)); // A second real stocked glass, not a refund.
            SettlerEntity second = resident(h, f.host().settlement(), STORE.south());
            h.assertTrue(Employment.hire(h.getLevel(), f.host().settlement(), f.tavern(), second).ok(),
                "second real Innkeeper is a supported ordinary workplace slot");
            h.assertTrue(TavernHostService.pickup(second, f.guest(), f.tavern(), f.source()) == null
                && f.store().getItem(0).getCount() == 1 && f.store().getItem(1).getCount() == 1,
                "saved exact table claim must reject another host despite no loaded serving entity");
            f.host().getNavigation().stop();
            f.host().setPos(Vec3.atBottomCenterOf(h.absolutePos(STORE.south(6))));
            h.assertTrue(TavernSeating.clearStand(h.getLevel(), f.host(), f.host().blockPosition()),
                "recovery fixture must start several cells away on actual clear supported ground");
            BlockPos lastKnown = BlockPos.of(f.host().getPersistentData().getLong(TavernHostService.LOCATION_KEY));
            double beforeDistance = f.host().blockPosition().distSqr(lastKnown);
            TavernHostService.reacquire(f.host());
            var recoveryPath = f.host().getNavigation().getPath();
            h.assertTrue(recoveryPath != null && recoveryPath.getEndNode() != null
                && recoveryPath.getEndNode().asBlockPos().distSqr(lastKnown) < beforeDistance,
                "missing lookup recovery must start a real path toward the last known serving location");
            long cooldown = f.host().getPersistentData().getLong(TavernHostService.REACQUIRE_KEY);
            TavernHostService.reacquire(f.host());
            h.assertTrue(id.equals(f.host().getPersistentData().getUUID(TavernHostService.SESSION_KEY))
                && cooldown == f.host().getPersistentData().getLong(TavernHostService.REACQUIRE_KEY),
                "missing lookup must preserve identity and back off repeated same-tick paths");
            var loaded = EntityType.loadEntityRecursive(entitySave, h.getLevel(), e -> e);
            h.assertTrue(loaded instanceof TavernServingEntity && h.getLevel().addFreshEntity(loaded),
                "exact saved serving entity rejoins without another stock withdrawal");
            h.assertTrue(TavernHostService.current(f.host()) == loaded,
                "restored UUID resolves the retained host pointer");
            h.succeed();
        });
    }
    @GameTest(batch = "tavern_host_death", template = "empty16", timeoutTicks = 80)
    public void hostDeathRejectedDropsPreserveFoodAndGlassInDeferredOwnership(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity serving = pickup(h, f);
        var escrow = DeferredItemMaterializationSavedData.get(h.getLevel());
        int foodBefore = escrow.pendingItems(h.getLevel().registryAccess(), Items.BREAD);
        int glassBefore = escrow.pendingItems(h.getLevel().registryAccess(), Items.GLASS_BOTTLE);
        Consumer<EntityJoinLevelEvent> reject = event -> {
            if (event.getLevel() == h.getLevel() && event.getEntity() instanceof ItemEntity item
                && Component.literal("Tavern service proof").equals(item.getItem().get(DataComponents.CUSTOM_NAME)))
                event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EntityJoinLevelEvent.class, reject);
        try {
            f.host().hurt(h.getLevel().damageSources().genericKill(), 1000);
            serving.tick(); serving.tick();
        } finally { NeoForge.EVENT_BUS.unregister(reject); }
        h.assertTrue(!serving.hasCargo()
            && escrow.pendingItems(h.getLevel().registryAccess(), Items.BREAD) == foodBefore + 1
            && escrow.pendingItems(h.getLevel().registryAccess(), Items.GLASS_BOTTLE) == glassBefore + 1,
            "rejected physical drops must transfer each exact owned stack once into durable escrow");
        var reloaded = DeferredItemMaterializationSavedData.load(escrow.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(reloaded.pendingItems(h.getLevel().registryAccess(), Items.BREAD) == foodBefore + 1
            && reloaded.pendingItems(h.getLevel().registryAccess(), Items.GLASS_BOTTLE) == glassBefore + 1,
            "both pending items must survive saved-data reload");
        h.succeed();
    }
    @GameTest(batch = "tavern_return_reload", template = "empty16", timeoutTicks = 260)
    public void actualDinerReturnSlideReloadKeepsClockGlassAndContact(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity[] owner = {pickup(h, f)}; atTable(f);
        boolean[] loaded = {false}, returnSeen = {false}; Vec3[] previous = {null};
        h.onEachTick(() -> {
            TavernServingEntity serving = owner[0];
            if (serving.isRemoved()) return;
            if (serving.phase() == TavernServingEntity.Phase.RETURN_TABLE) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), serving);
                h.assertTrue(stand != null, "actual supported return pickup stand");
                f.host().setPos(stand); // This test isolates contact/reload; ordinary-goal test owns walking.
            }
            if (serving.phase() == TavernServingEntity.Phase.RETURNING) atStore(f);
            serving.serviceTick(f.host());
            if (f.guest().hasMeal()) { f.guest().setActivity(SettlerActivity.EATING); f.guest().tickMeal(); }
            if (serving.phase() == TavernServingEntity.Phase.RETURN_SLIDING) {
                returnSeen[0] = true;
                h.assertTrue(serving.returnPreparationTicks() == 20 && !f.guest().hasMeal()
                    && f.guest().hasTavernSeat() && serving.displayFood().isEmpty()
                    && ItemStack.isSameItemSameComponents(serving.displayGlass(), named(Items.GLASS_BOTTLE, 1)),
                    "actual seated diner finishes meal/reach before returning the same owned glass");
                Vec3 point = serving.glassPosition(0);
                h.assertTrue(Math.abs(point.y - (f.seat().site().tabletopCenter(h.getLevel()).y + .002)) < .00001,
                    "glass must remain supported by the actual tabletop throughout return");
                if (previous[0] != null) h.assertTrue(point.distanceTo(previous[0]) <= .03001,
                    "return slide moves at most .03 blocks per real tick");
                previous[0] = point;
                if (!loaded[0] && serving.slideTicks() == 10) {
                    CompoundTag saved = new CompoundTag(); h.assertTrue(serving.save(saved), "returning glass owner must save");
                    UUID id = serving.getUUID(); serving.discard();
                    var entity = EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
                    h.assertTrue(entity instanceof TavernServingEntity && h.getLevel().addFreshEntity(entity), "actual registered return owner reloads");
                    owner[0] = (TavernServingEntity) entity; loaded[0] = true;
                    h.assertTrue(id.equals(owner[0].getUUID()) && TavernHostService.current(f.host()) == owner[0]
                        && owner[0].phase() == TavernServingEntity.Phase.RETURN_SLIDING
                        && owner[0].slideTicks() == 10 && owner[0].returnPreparationTicks() == 20
                        && owner[0].glassPosition(0).distanceToSqr(point) < .00000001,
                        "reload preserves exact UUID, physical glass position and both return clocks");
                }
            }
            if (serving.phase() == TavernServingEntity.Phase.PICKING_UP)
                h.assertTrue(TavernHostService.pickupContact(f.host(), serving), "no lift without actual current-prop hand contact");
            if (loaded[0] && returnSeen[0] && !owner[0].hasCargo()) {
                h.assertTrue(f.store().getItem(0).getCount() == 1 && f.store().getItem(1).getCount() == 1
                    && ItemStack.isSameItemSameComponents(f.store().getItem(1), named(Items.GLASS_BOTTLE, 1))
                    && !f.guest().hasMeal() && f.guest().getHunger() > 55,
                    "one meal, no second debit, one exact glass returned after real reload");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_return_departure", template = "empty16", timeoutTicks = 400)
    public void departedDinerFreezesGlassAndHostRetrievesOnlyAfterPhysicalAccess(GameTestHelper h) {
        Fixture f = fixture(h); TavernServingEntity serving = pickup(h, f); atTable(f);
        Vec3[] frozen = {null}; int[] blockedTicks = {0}; boolean[] opened = {false};
        boolean[] reclaimed = {false};
        h.onEachTick(() -> {
            // Ordinary AI can insert and remove the empty receipt before this
            // observer runs. Check the real terminal ownership before removal.
            if (opened[0] && !serving.hasCargo()) {
                h.assertTrue(reclaimed[0] && blockedTicks[0] == 5 && !f.guest().hasMeal()
                    && f.store().getItem(0).getCount() == 1 && f.store().getItem(1).getCount() == 1
                    && ItemStack.isSameItemSameComponents(f.store().getItem(1), named(Items.GLASS_BOTTLE, 1)),
                    "ordinary host recovers the stationary glass exactly once after real obstruction removal");
                if (serving.isRemoved()) {
                    h.assertTrue(!TavernHostService.hasSession(f.host())
                        && !f.tavern().tavernServingClaims.occupied(f.seat().site().table()),
                        "ordinary terminal cleanup releases the exact host session and table claim");
                    h.succeed();
                }
                return;
            }
            h.assertTrue(!serving.isRemoved(), "a still-owned glass must not disappear before the return oracle");
            serving.serviceTick(f.host());
            if (f.guest().hasMeal()) { f.guest().setActivity(SettlerActivity.EATING); f.guest().tickMeal(); }
            if (frozen[0] == null && serving.phase() == TavernServingEntity.Phase.RETURN_SLIDING && serving.slideTicks() == 15) {
                frozen[0] = serving.glassPosition(0);
                h.assertTrue(f.seat().release(), "diner really leaves via a clear local exit");
                atStore(f); // Move the isolated NoAI host away before placing physical obstacles.
                for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.WEST})
                    for (int y = 0; y <= 1; y++) h.getLevel().setBlockAndUpdate(f.seat().site().table().relative(direction).above(y),
                        Blocks.STONE_BRICKS.defaultBlockState());
                return;
            }
            if (frozen[0] != null && !opened[0]) {
                h.assertTrue(serving.phase() == TavernServingEntity.Phase.RETURN_TABLE
                    && serving.slideTicks() == 15 && serving.glassPosition(0).distanceToSqr(frozen[0]) < .00000001
                    && serving.displayGlass().getCount() == 1 && f.store().getItem(1).isEmpty()
                    && TavernHostService.pickupStand(f.host(), serving) == null,
                    "departure stops the real glass; blocked approaches cannot remotely lift or refund it");
                if (++blockedTicks[0] == 5) {
                    for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.WEST})
                        for (int y = 0; y <= 1; y++) h.getLevel().setBlockAndUpdate(f.seat().site().table().relative(direction).above(y),
                            Blocks.AIR.defaultBlockState());
                    Vec3 stand = TavernHostService.pickupStand(f.host(), serving);
                    h.assertTrue(stand != null, "opened side aisle must expose a real reachable pickup stand");
                    Vec3 table = Vec3.atBottomCenterOf(f.seat().site().table());
                    h.assertTrue(Math.abs(stand.x - table.x) >= .75
                        && Math.abs(stand.z - serving.pickupAnchor().z) < .00001,
                        "guest-edge glass requires side clearance and alignment with its current position");
                    h.assertTrue(h.getLevel().noCollision(f.host(), f.host().getDimensions(f.host().getPose()).makeBoundingBox(stand)),
                        "the actual host body must fit at the selected side approach");
                    f.host().setNoAi(false); opened[0] = true; // Ordinary host goal must navigate to the current glass.
                }
            }
            if (opened[0] && serving.phase() == TavernServingEntity.Phase.PICKING_UP) {
                reclaimed[0] = true;
                h.assertTrue(TavernHostService.pickupContact(f.host(), serving), "side retrieval requires actual hand reach and visibility");
            }
        });
    }

    @GameTest(batch = "tavern_host", template = "empty16", timeoutTicks = 240)
    public void waitingVisitorPaysOnlyAtRealMealContactAcrossReload(GameTestHelper h) {
        Fixture f = fixture(h);
        var village = f.host().settlement();
        var guest = f.guest();
        village.removeRecord(guest.getUUID()); guest.unbind();
        guest.markTraveler(village.id, village.center);
        guest.setActivity(SettlerActivity.IDLE);
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag plaqueTag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        plaqueTag.putUUID("Building", f.tavern().id);
        plaqueTag.putString("Type", BuildingType.TAVERN.id());
        plaqueTag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(plaqueTag, h.getLevel().registryAccess());
        var transaction = UUID.randomUUID();
        var quote = com.hearthstead.settlement.RecruitmentQuote.fromStartingAttributes(
            transaction, guest.getUUID(), guest.attributes(), java.util.List.of());
        var traveling = com.hearthstead.settlement.RecruitmentTransaction.fresh(village.id)
            .adminPrime(transaction, h.getLevel().getGameTime(), f.tavern().id,
                f.tavern().plaquePos, f.tavern().anchor, h.getLevel().dimension().location())
            .travelerSpawned(guest.getUUID(), "Paying visitor", h.getLevel().getGameTime(), quote);
        village.applyRecruitment(traveling.arrived(h.getLevel().getGameTime()));
        h.getLevel().setBlockAndUpdate(village.center, com.hearthstead.registry.ModBlocks.HEARTH.get().defaultBlockState());
        var hearth = (com.hearthstead.block.HearthBlockEntity) h.getLevel().getBlockEntity(village.center);
        hearth.bindSettlement(village.id);
        var treasury = hearth.getInventory();
        h.assertTrue(com.hearthstead.settlement.work.TavernGuestPayment.initializeTraveler(guest), "actual creation issues finite physical purse once");
        h.assertTrue(!com.hearthstead.settlement.work.TavernGuestPayment.initializeTraveler(guest)
            && guest.bag.getItem(0).getCount() == 3, "repeated initialization cannot mint travel money");
        CompoundTag guestTag = new CompoundTag(); guest.saveWithoutId(guestTag);
        SettlerEntity decoded = com.hearthstead.registry.ModEntities.SETTLER.get().create(h.getLevel());
        decoded.load(guestTag);
        h.assertTrue(decoded.bag.getItem(0).getCount() == 3
            && !com.hearthstead.settlement.work.TavernGuestPayment.initializeTraveler(decoded), "clean reload retains the same three physical Coins");
        for (int i = 0; i < treasury.getSlots(); i++) treasury.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(TavernHostService.pickup(f.host(), guest, f.tavern(), f.source()) == null
            && f.store().getItem(0).getCount() == 2 && guest.bag.getItem(0).getCount() == 3,
            "full physical treasury refuses ordering without taking food or payment");
        treasury.setStackInSlot(0, ItemStack.EMPTY);
        TavernServingEntity[] active = {pickup(h, f)};
        atTable(f);
        int[] stage = {0};
        h.onEachTick(() -> {
            TavernServingEntity serving = active[0];
            if (serving.phase() == TavernServingEntity.Phase.READY && stage[0] == 0) {
                CompoundTag saved = new CompoundTag(); serving.saveWithoutId(saved);
                h.assertTrue(saved.getBoolean("PaidVisitorMeal"), "visitor payment obligation is persisted before handoff");
                serving.discard();
                active[0] = com.hearthstead.registry.ModEntities.TAVERN_SERVING.get().create(h.getLevel());
                active[0].load(saved);
                h.assertTrue(h.getLevel().addFreshEntity(active[0]), "same saved serving UUID rejoins");
                serving = active[0];
                treasury.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 64)); stage[0] = 1;
            }
            serving.serviceTick(f.host());
            if (serving.phase() == TavernServingEntity.Phase.READY
                && serving.receivingTicks() == TavernServingEntity.RECEIVING_TICKS && stage[0] == 1) {
                h.assertTrue(!guest.hasMeal() && serving.displayFood().getCount() == 1
                    && guest.bag.getItem(0).getCount() == 3, "storage filled during slide leaves meal and Coins in their real owners");
                treasury.setStackInSlot(0, ItemStack.EMPTY);
                f.store().setItem(2, guest.bag.removeItem(0, 3)); stage[0] = 2;
                return;
            }
            if (stage[0] == 2) {
                h.assertTrue(!guest.hasMeal() && serving.displayFood().getCount() == 1 && treasury.getStackInSlot(0).isEmpty(),
                    "insufficient purse cannot hand over food or deposit a fabricated Coin");
                guest.bag.setItem(0, f.store().removeItem(2, 3)); stage[0] = 3;
                return;
            }
            if (stage[0] == 3 && serving.phase() == TavernServingEntity.Phase.DINING) {
                h.assertTrue(guest.hasMeal() && serving.displayFood().isEmpty()
                    && guest.bag.getItem(0).getCount() == 2 && treasury.getStackInSlot(0).getCount() == 1
                    && treasury.getStackInSlot(0).is(com.hearthstead.registry.ModItems.GOLD_COIN.get()),
                    "real eight-tick contact transfers one bread and exactly one existing Coin");
                serving.serviceTick(f.host());
                h.assertTrue(guest.bag.getItem(0).getCount() == 2 && treasury.getStackInSlot(0).getCount() == 1,
                    "same-tick replay cannot pay or serve twice");
                serving.discard(); f.seat().discard(); guest.discard(); f.host().discard();
                SettlementSavedData.get(h.getLevel()).settlements.remove(village.id);
                SettlementSavedData.get(h.getLevel()).setDirty();
                h.getLevel().setBlockAndUpdate(village.center, Blocks.AIR.defaultBlockState());
                h.succeed();
            }
        });
    }

    @GameTest(batch = "tavern_host_normal", template = "empty16", timeoutTicks = 140)
    public void unboundVisitorWithoutOrderCannotUseLegacyAiService(GameTestHelper h) {
        Fixture f = fixture(h);
        Settlement village = f.host().settlement();
        SettlerEntity visitor = f.guest();
        village.removeRecord(visitor.getUUID());
        visitor.unbind();
        visitor.markTraveler(village.id, village.center);
        visitor.setActivity(SettlerActivity.IDLE);
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag plaqueTag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        plaqueTag.putUUID("Building", f.tavern().id);
        plaqueTag.putString("Type", BuildingType.TAVERN.id());
        plaqueTag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(plaqueTag, h.getLevel().registryAccess());
        UUID transaction = UUID.randomUUID();
        var quote = com.hearthstead.settlement.RecruitmentQuote.fromStartingAttributes(
            transaction, visitor.getUUID(), visitor.attributes(), java.util.List.of());
        village.applyRecruitment(com.hearthstead.settlement.RecruitmentTransaction.fresh(village.id)
            .adminPrime(transaction, h.getLevel().getGameTime(), f.tavern().id,
                f.tavern().plaquePos, f.tavern().anchor, h.getLevel().dimension().location())
            .travelerSpawned(visitor.getUUID(), "No-order visitor", h.getLevel().getGameTime(), quote)
            .arrived(h.getLevel().getGameTime()));
        h.getLevel().setBlockAndUpdate(village.center, com.hearthstead.registry.ModBlocks.HEARTH.get().defaultBlockState());
        var hearth = (com.hearthstead.block.HearthBlockEntity) h.getLevel().getBlockEntity(village.center);
        hearth.bindSettlement(village.id);
        h.assertTrue(com.hearthstead.settlement.work.TavernGuestPayment.initializeTraveler(visitor)
            && visitor.bag.getItem(0).is(ModItems.GOLD_COIN.get()) && visitor.bag.getItem(0).getCount() == 3,
            "unbound fixture has a real finite purse and a valid physical Coin destination");
        visitor.setNoAi(true); // Keep this adversarial fixture no-order; only the host runs ordinary AI.
        f.host().setNoAi(false);
        h.runAfterDelay(100, () -> {
            h.assertTrue(!TavernHostService.hasSession(f.host())
                && !TavernHostService.hasOrderForGuest(visitor)
                && f.store().getItem(0).getCount() == 2 && f.store().getItem(1).getCount() == 1
                && visitor.bag.getItem(0).is(ModItems.GOLD_COIN.get()) && visitor.bag.getItem(0).getCount() == 3
                && h.getLevel().getEntitiesOfClass(TavernServingEntity.class,
                    visitor.getBoundingBox().inflate(16)).isEmpty(),
                "unbound no-order visitor cannot create a legacy serving or move food, glass, or Coins");
            h.succeed();
        });
    }
}
