package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.event.HunterShotEvents;
import com.hearthstead.item.CarcassData;
import com.hearthstead.item.CarcassItem;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Adversarial physical-authority checks for the Hunter vertical slice. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HunterPhysicalGameTests {
    private record Fixture(ServerLevel level, Settlement settlement,
                           Building lodge, Container chest,
                           SettlerEntity hunter, List<Cow> herd) {
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 400)
    public void rejectedArrowSpawnRestoresTheExactCarriedShaft(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 5, 4);
        int initial = arrows(f);
        boolean[] rejected = {false};
        Consumer<EntityJoinLevelEvent> rejectHunterArrow = event -> {
            if (event.getEntity() instanceof AbstractArrow arrow
                && arrow.getOwner() == f.hunter()) {
                rejected[0] = true;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectHunterArrow);
        unregisterAtEnd(helper, rejectHunterArrow);
        f.level().setDayTime(3000);
        helper.succeedWhen(() -> {
            helper.assertTrue(rejected[0],
                "the fixture must reject one real Hunter arrow insertion");
            helper.assertTrue(arrows(f) == initial,
                "a rejected spawn must restore the exact shaft; before="
                    + initial + "; after=" + arrows(f));
            helper.assertTrue(f.level().getEntitiesOfClass(Arrow.class,
                    f.hunter().getBoundingBox().inflate(24.0D)).isEmpty(),
                "a rejected insertion cannot leave a projectile authority");
            NeoForge.EVENT_BUS.unregister(rejectHunterArrow);
        });
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void rejectedLootRematerializationRetainsRecoverableOffhandAuthority(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        // Empty templates at this depth can retain generated stone. Own the
        // small positive contact corridor, as the shared collection tests do;
        // do not weaken the production ray or remove the Lodge/chest/plaque.
        for (int x = 6; x <= 7; x++) for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(x, y, 6), Blocks.AIR);
        }
        GroundCollectionSession pickup = new GroundCollectionSession(
            f.hunter(), stack -> stack.is(Items.BEEF), 1);
        BlockPos dropPos = helper.absolutePos(new BlockPos(7, 1, 6));
        ItemEntity beef = new ItemEntity(f.level(), dropPos.getX() + 0.5D,
            dropPos.getY() + 0.25D, dropPos.getZ() + 0.5D,
            new ItemStack(Items.BEEF));
        beef.setDeltaMovement(0.0D, 0.0D, 0.0D);
        helper.assertTrue(f.level().addFreshEntity(beef)
                && GroundCollectionSession.leaseExisting(f.hunter(), beef),
            "fixture must establish one real leased wildlife drop");
        pickup.recoverOwned(f.level(), f.hunter().getBoundingBox().inflate(2.0D));
        pickup.select(beef);
        GroundCollectionSession.PickupResult pickupResult =
            pickup.takeOneToOffhand(f.level(), 4.0D);
        helper.assertTrue(pickupResult == GroundCollectionSession.PickupResult.PICKED,
            "adjacent physical pickup contact must move the sole beef into the "
                + "real offhand; got " + pickupResult);

        boolean[] rejected = {false};
        Consumer<EntityJoinLevelEvent> rejectReturnedBeef = event -> {
            if (event.getEntity() instanceof ItemEntity item
                && item.getItem().is(Items.BEEF)) {
                rejected[0] = true;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectReturnedBeef);
        unregisterAtEnd(helper, rejectReturnedBeef);
        pickup.abandon(f.level());
        helper.assertTrue(rejected[0] && f.hunter().getOffhandItem().is(Items.BEEF),
            "rejected cancellation rematerialization must retain the sole stack");

        GroundCollectionSession recovered = new GroundCollectionSession(
            f.hunter(), stack -> stack.is(Items.BEEF), 1);
        helper.assertTrue(recovered.adoptEligibleOffhand(),
            "the retained marker must let a reconstructed goal recover the stack");
        NeoForge.EVENT_BUS.unregister(rejectReturnedBeef);
        helper.assertTrue(recovered.returnCarriedToWorld(f.level(),
                f.hunter().blockPosition(), false)
                && f.hunter().getOffhandItem().isEmpty(),
            "after insertion recovers, exactly one physical stack must leave the hand");
        long physical = f.level().getEntitiesOfClass(ItemEntity.class,
            f.hunter().getBoundingBox().inflate(2.0D),
            item -> item.isAlive() && item.getItem().is(Items.BEEF)).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(physical == 1,
            "recovery must materialize exactly one beef; got " + physical);
        helper.succeed();
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void carriedLodgeArrowsSurviveReloadAndReturnToTheirExactSource(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        helper.assertTrue(f.hunter().storeCarriedArrows(f.lodge().id, 7) == 7,
            "fixture must establish seven lodge-owned physical shafts");
        CompoundTag saved = f.hunter().saveWithoutId(new CompoundTag());
        f.hunter().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SettlerEntity restored = ModEntities.SETTLER.get().create(f.level());
        helper.assertTrue(restored != null, "replacement Hunter must construct");
        restored.load(saved);
        restored.moveTo(f.lodge().anchor.getX() + 0.5D,
            f.lodge().anchor.getY() + 1.0D, f.lodge().anchor.getZ() + 0.5D);
        helper.assertTrue(f.level().addFreshEntity(restored),
            "replacement Hunter must enter the same level");
        helper.assertTrue(restored.carriedArrowCount() == 7
                && f.lodge().id.equals(restored.carriedArrowSourceBuildingId()),
            "count and exact lodge source must survive entity NBT reload");
        helper.assertTrue(restored.releaseCarriedArrows(f.level(), f.settlement())
                && restored.carriedArrowCount() == 0
                && count(f.chest(), Items.ARROW) == 7,
            "neutral cleanup must return all seven shafts to that exact Lodge");
        helper.succeed();
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void hunterShotProvenanceSurvivesVanillaArrowReload(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 5, 0);
        Cow target = f.herd().get(0);
        Arrow arrow = new Arrow(f.level(), f.hunter(),
            new ItemStack(Items.ARROW), f.hunter().getMainHandItem());
        helper.assertTrue(HunterShotEvents.issue(arrow, f.hunter(), target,
                f.lodge()), "eligible shot must receive strict provenance");
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(arrow.save(saved),
            "issued vanilla Arrow must pass the normal entity save path");
        Entity entity = EntityType.loadEntityRecursive(saved, f.level(), it -> it);
        helper.assertTrue(entity instanceof Arrow,
            "normal entity factory must restore a plain registered Arrow");
        HunterShotEvents.Inspection restored = HunterShotEvents.inspect(
            (Arrow) entity);
        helper.assertTrue(restored != null
                && restored.hunterId().equals(f.hunter().getUUID())
                && restored.targetId().equals(target.getUUID())
                && restored.lodgeId().equals(f.lodge().id)
                && !restored.deathCommitted(),
            "reload must retain exact Hunter, target, Lodge and uncommitted death");
        helper.succeed();
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 60)
    public void reloadedHunterArrowReallyHitsAndLeasesItsPreyWithoutAnotherDebit(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 5, 2);
        // Own a short, clear flight corridor without altering Lodge authority.
        // The target starts wounded so this tests one real impact, not hunting
        // balance. Neither actor's AI nor the projectile's trajectory is mocked.
        for (int x = 6; x <= 11; x++) for (int z = 5; z <= 7; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        f.level().setDayTime(3000);
        helper.assertTrue(arrows(f) == 2 && f.hunter().carriedArrowCount() == 0,
            "the only two physical shafts must start in the exact Lodge chest");
        HunterWorkGoal restock = new HunterWorkGoal(f.hunter());
        helper.assertTrue(restock.canUse(), "the empty quiver must request Lodge contact");
        restock.start();
        restock.tick();
        restock.stop();
        helper.assertTrue(f.hunter().carriedArrowCount() == 2
                && f.lodge().id.equals(f.hunter().carriedArrowSourceBuildingId())
                && count(f.chest(), Items.ARROW) == 0,
            "real Lodge contact must transfer both shafts without creating any; carried="
                + f.hunter().carriedArrowCount() + " chest=" + count(f.chest(), Items.ARROW)
                + " contact=" + com.hearthstead.settlement.work.ContainerApproach.inspect(f.level(), f.hunter(),
                    helper.absolutePos(new BlockPos(5, 1, 4))).state()
                + " pos=" + f.hunter().position() + " chestAt=" + helper.absolutePos(new BlockPos(5, 1, 4))
                + " route=" + f.hunter().routeFailureNote() + " act=" + f.hunter().getActivity());

        Cow target = f.herd().get(0);
        BlockPos stand = helper.absolutePos(new BlockPos(8, 1, 6));
        BlockPos prey = helper.absolutePos(new BlockPos(10, 1, 6));
        f.hunter().moveTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
        target.moveTo(prey.getX() + 0.5D, prey.getY(), prey.getZ() + 0.5D);
        target.setHealth(1.0F);
        helper.assertTrue(HunterWorkGoal.mayHarvest(f.hunter(), target),
            "the real employed Hunter must have an eligible fifth wild cow");
        int bowDamageBefore = f.hunter().getMainHandItem().getDamageValue();
        HunterWorkGoal hunt = new HunterWorkGoal(f.hunter());
        helper.assertTrue(hunt.canUse(), "the loaded Hunter must select nearby prey");
        hunt.start();
        hunt.tick();
        helper.assertTrue(f.hunter().isUsingItem(),
            "the actual Hunter goal must begin drawing its equipped bow");
        // Drive only the existing goal's release counter synchronously. After
        // reload, only the server entity ticker may move or collide the Arrow.
        for (int tick = 0; tick < HunterWorkGoal.HUNT_RELEASE_TICK; tick++) {
            hunt.tick();
        }
        hunt.stop();
        List<Arrow> fired = f.level().getEntitiesOfClass(Arrow.class,
            f.hunter().getBoundingBox().inflate(8.0D),
            arrow -> arrow.getOwner() == f.hunter());
        helper.assertTrue(fired.size() == 1 && arrows(f) == 1,
            "the real release must insert exactly one Arrow and debit exactly one shaft");
        Arrow original = fired.get(0);
        HunterShotEvents.Inspection issued = HunterShotEvents.inspect(original);
        helper.assertTrue(issued != null
                && issued.targetId().equals(target.getUUID())
                && issued.hunterId().equals(f.hunter().getUUID())
                && issued.settlementId().equals(f.settlement().id)
                && issued.lodgeId().equals(f.lodge().id)
                && !issued.deathCommitted() && target.isAlive()
                && original.getDeltaMovement().lengthSqr() > 0.0D,
            "the saved projectile must be the live, uncommitted shot at this exact prey");
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(original.save(saved), "the real in-flight Arrow must serialize");
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.assertTrue(f.level().getEntity(original.getUUID()) == null,
            "the original Arrow must leave the entity registry before replacement");
        Entity loaded = EntityType.loadEntityRecursive(saved, f.level(), entity -> entity);
        helper.assertTrue(loaded instanceof Arrow,
            "the normal saved-entity factory must reconstruct a vanilla Arrow");
        Arrow restored = (Arrow) loaded;
        helper.assertTrue(restored != original
                && restored.getUUID().equals(original.getUUID())
                && restored.position().equals(original.position())
                && restored.getDeltaMovement().equals(original.getDeltaMovement())
                && restored.getOwner() == f.hunter()
                && restored.pickup == AbstractArrow.Pickup.DISALLOWED
                && issued.equals(HunterShotEvents.inspect(restored)),
            "reload must retain identity, flight, owner, pickup policy and exact provenance");
        helper.assertTrue(f.level().addFreshEntity(restored)
                && f.level().getEntity(original.getUUID()) == restored
                && arrows(f) == 1,
            "the reloaded Arrow must register in the same level without another shaft debit");
        long insertedAt = f.level().getGameTime();
        GroundCollectionSession recoveredLoot = new GroundCollectionSession(
            f.hunter(), CarcassItem::isCarcass, 32);
        helper.succeedWhen(() -> {
            helper.assertTrue(f.level().getGameTime() > insertedAt && !target.isAlive(),
                "ordinary server ticks must let the reloaded Arrow kill its actual prey");
            helper.assertTrue(target.getLastDamageSource() != null
                    && target.getLastDamageSource().getDirectEntity() == restored
                    && target.getLastDamageSource().getEntity() == f.hunter()
                    && HunterShotEvents.inspect(restored) != null
                    && HunterShotEvents.inspect(restored).deathCommitted(),
                "the actual fatal damage and committed drops must name this restored Arrow and Hunter");
            helper.assertTrue(f.hunter().settlement() == f.settlement()
                    && Employment.employerOf(f.settlement(), f.hunter().getUUID()) == f.lodge()
                    && f.herd().stream().filter(Cow::isAlive).count() == 4,
                "impact must retain exact employment and leave the other four cows alive");
            List<ItemEntity> carcass = f.level().getEntitiesOfClass(ItemEntity.class,
                target.getBoundingBox().inflate(2.0D),
                item -> item.isAlive() && CarcassItem.isCarcass(item.getItem())
                    && f.hunter().getUUID().equals(item.getTarget()));
            helper.assertTrue(carcass.size() == 1,
                "the real death must spawn exactly one carcass reserved to the shooting Hunter");
            helper.assertTrue(f.level().getEntitiesOfClass(ItemEntity.class,
                    target.getBoundingBox().inflate(3.0D),
                    item -> item.isAlive() && (item.getItem().is(Items.BEEF)
                        || item.getItem().is(Items.LEATHER))).isEmpty(),
                "the carcass replaces the vanilla loot: no loose beef or leather may also land");
            CarcassData data = CarcassItem.data(carcass.get(0).getItem());
            helper.assertTrue(data != null && data.yield().stream().anyMatch(s -> s.is(Items.BEEF)),
                "the carcass must record the kill's real vanilla beef as its yield");
            recoveredLoot.recoverOwned(f.level(), target.getBoundingBox().inflate(2.0D));
            recoveredLoot.select(carcass.get(0));
            helper.assertTrue(recoveredLoot.selected(f.level()) == carcass.get(0),
                "a reconstructed collection session must recognize the actual drop's live ownership lease");
            helper.assertTrue(arrows(f) == 1
                    && f.hunter().carriedArrowCount() == 1
                    && count(f.chest(), Items.ARROW) == 0
                    && f.hunter().getMainHandItem().getDamageValue() == bowDamageBefore + 1
                    && restored.isRemoved()
                    && f.level().getEntitiesOfClass(Arrow.class,
                        f.hunter().getBoundingBox().inflate(8.0D),
                        arrow -> arrow.getOwner() == f.hunter()).isEmpty(),
                "reload and impact cannot debit another shaft, wear the bow again or leave a duplicate projectile");
        });
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void populationFloorIsRevalidatedBeforeArrowDamage(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        // The floor is counted per species across the Lodge's 28-block hunt
        // box, which reaches the neighbouring GameTest cells of this batch,
        // whose fixtures spawn Cows. A species no other test spawns keeps the
        // local population exact whatever the grid layout.
        List<Chicken> herd = new ArrayList<>();
        int[][] positions = {{10,10},{11,9},{9,11},{12,11},{10,13}};
        for (int[] at : positions) {
            herd.add(helper.spawn(EntityType.CHICKEN,
                new BlockPos(at[0], 1, at[1])));
        }
        Chicken target = herd.get(0);
        Arrow arrow = new Arrow(f.level(), f.hunter(),
            new ItemStack(Items.ARROW), f.hunter().getMainHandItem());
        helper.assertTrue(HunterShotEvents.issue(arrow, f.hunter(), target,
                f.lodge()) && f.level().addFreshEntity(arrow),
            "eligible five-animal population must issue one physical shot");
        herd.get(4).discard();
        float health = target.getHealth();
        boolean accepted = target.hurt(f.level().damageSources().arrow(
            arrow, f.hunter()), 6.0F);
        helper.assertTrue(!accepted && target.getHealth() == health
                && !arrow.isAlive(),
            "when another transfer leaves only the floor, pre-damage authority "
                + "must cancel and discard the stale shot without harming game");
        helper.succeed();
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void leasedWildlifeDropCanStillTransferIntoAHopper(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        BlockPos hopperPos = new BlockPos(9, 1, 9);
        helper.setBlock(hopperPos, Blocks.HOPPER);
        BlockEntity blockEntity = f.level().getBlockEntity(
            helper.absolutePos(hopperPos));
        helper.assertTrue(blockEntity instanceof Container,
            "fixture Hopper must expose its real inventory");
        ItemEntity beef = new ItemEntity(f.level(),
            helper.absolutePos(hopperPos).getX() + 0.5D,
            helper.absolutePos(hopperPos).getY() + 1.25D,
            helper.absolutePos(hopperPos).getZ() + 0.5D,
            new ItemStack(Items.BEEF));
        helper.assertTrue(f.level().addFreshEntity(beef)
                && GroundCollectionSession.leaseExisting(f.hunter(), beef),
            "real drop must enter the level before receiving a finite lease");
        Container hopper = (Container) blockEntity;
        helper.succeedWhen(() -> helper.assertTrue(!beef.isAlive()
                && count(hopper, Items.BEEF) == 1,
            "Hopper transfer is legitimate external ownership and must not be "
                + "blocked or recreated by the Hunter lease"));
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void brokenBowCannotStrandExistingBagCargo(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        ItemStack broken = new ItemStack(Items.BOW);
        broken.setDamageValue(broken.getMaxDamage() - 1);
        f.hunter().setItemSlot(EquipmentSlot.MAINHAND, broken);
        f.hunter().bag.addItem(new ItemStack(Items.BEEF));
        f.level().setDayTime(3000);
        // Only this goal instance may act: the unload is the shared physical
        // GroundedBagUnload chest cycle, advanced once per real game tick.
        f.hunter().setNoAi(true);
        HunterWorkGoal goal = new HunterWorkGoal(f.hunter());
        helper.assertTrue(goal.canUse(),
            "existing physical cargo must outrank the new-hunt bow gate");
        goal.start();
        helper.onEachTick(() -> {
            if (goal.canContinueToUse()) goal.tick();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(count(f.chest(), Items.BEEF) == 1
                    && count(f.hunter().bag, Items.BEEF) == 0,
                "the broken-bow Hunter must finish the existing Lodge deposit");
            helper.assertTrue(count(f.chest(), Items.BEEF) + count(f.hunter().bag, Items.BEEF) <= 1,
                "the unload must never duplicate the one beef");
        });
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void bowBreakingOnARealShotEndsTheSurvivingPreyLoopAndRequestsReplacement(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 5, 0);
        helper.assertTrue(f.hunter().storeCarriedArrows(f.lodge().id, 2) == 2,
            "fixture must establish two real Lodge-owned shafts");
        f.level().setDayTime(3000);

        HunterWorkGoal goal = new HunterWorkGoal(f.hunter());
        helper.assertTrue(goal.canUse(),
            "serviceable bow, arrows and a herd over the floor must begin a hunt");
        goal.start();
        goal.tick();
        helper.assertTrue(f.hunter().isUsingItem(),
            "the first contact-range tick must enter the real vanilla bow draw");

        // The goal admitted a serviceable bow. Reduce it to its final use only
        // after drawing starts, modelling durability changed during an active
        // shot rather than weakening the eight-use equipment admission gate.
        ItemStack activeBow = f.hunter().getMainHandItem();
        activeBow.setDamageValue(activeBow.getMaxDamage() - 1);
        for (int tick = 0; tick < HunterWorkGoal.HUNT_RELEASE_TICK; tick++) {
            goal.tick();
        }

        helper.assertTrue(f.hunter().getMainHandItem().isEmpty(),
            "the physical release must consume the bow's last durability");
        helper.assertTrue(f.herd().get(0).isAlive(),
            "the selected prey must still be alive before the real arrow advances");
        helper.assertTrue(f.hunter().carriedArrowCount() == 1,
            "exactly one of the two Lodge-owned shafts must be in the projectile");
        helper.assertTrue(!f.level().getEntitiesOfClass(Arrow.class,
                f.hunter().getBoundingBox().inflate(24.0D)).isEmpty(),
            "the breaking shot must still have inserted one real Arrow");

        for (int tick = HunterWorkGoal.HUNT_RELEASE_TICK;
                tick < HunterWorkGoal.HUNT_ANIMATION_TICKS; tick++) {
            goal.tick();
        }
        helper.assertTrue(!goal.canContinueToUse(),
            "after recovery, a live target and spare shaft cannot restart hunting "
                + "without a bow");
        goal.stop();

        HunterWorkGoal replacementGate = new HunterWorkGoal(f.hunter());
        helper.assertTrue(!replacementGate.canUse(),
            "a missing bow cannot admit a new hunt while replacement is pending");
        helper.assertTrue(EquipmentRequests.requestFor(f.lodge(),
                f.hunter().getUUID()) != null,
            "ending the hunt must leave the ordinary equipment path able to "
                + "publish the replacement-bow request");
        helper.succeed();
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void cachedOldLodgeCannotReceiveCargoAfterExactEmployerChanges(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        Building nextLodge = GameTestFixtures.register(helper, f.settlement(),
            BuildingType.HUNTERS_LODGE, 10, 4);
        BlockPos nextChestPos = new BlockPos(11, 1, 4);
        helper.setBlock(nextChestPos, Blocks.CHEST);
        BlockEntity nextBlockEntity = f.level().getBlockEntity(
            helper.absolutePos(nextChestPos));
        helper.assertTrue(nextBlockEntity instanceof Container,
            "replacement Hunter Lodge must expose its physical chest");
        Container nextChest = (Container) nextBlockEntity;

        f.hunter().bag.addItem(new ItemStack(Items.BEEF));
        f.level().setDayTime(3000);
        HunterWorkGoal staleReturn = new HunterWorkGoal(f.hunter());
        helper.assertTrue(staleReturn.canUse(),
            "existing physical cargo must start a return to the current Lodge");
        staleReturn.start();

        helper.assertTrue(Employment.hire(f.level(), f.settlement(), nextLodge,
                f.hunter()).ok(),
            "fixture must perform a real same-profession employer change");
        helper.assertTrue(Employment.employerOf(f.settlement(),
                f.hunter().getUUID()) == nextLodge,
            "the replacement Lodge must be the one current roster authority");
        f.level().setDayTime(13000);
        helper.assertTrue(!staleReturn.canContinueToUse(),
            "after-hours return allowance cannot authorize a superseded Lodge");

        // Tick once adversarially even though the scheduler would stop it: the
        // contact path itself must independently fail closed before mutation.
        staleReturn.tick();
        staleReturn.stop();
        helper.assertTrue(count(f.chest(), Items.BEEF) == 0
                && count(f.hunter().bag, Items.BEEF) == 1,
            "the cached old Lodge cannot receive cargo and the bag must retain it");

        f.level().setDayTime(3000);
        f.hunter().setNoAi(true);
        // Same chest-relative standing cell as the original Lodge fixture
        // (chest +1/+2 away), so the real contact rays see the new chest.
        BlockPos nextStand = helper.absolutePos(new BlockPos(12, 1, 6));
        f.hunter().moveTo(nextStand.getX() + 0.5D, nextStand.getY(), nextStand.getZ() + 0.5D);
        HunterWorkGoal recoveredReturn = new HunterWorkGoal(f.hunter());
        helper.assertTrue(recoveredReturn.canUse(),
            "the new employer must be able to recover retained physical cargo");
        recoveredReturn.start();
        helper.onEachTick(() -> {
            if (recoveredReturn.canContinueToUse()) recoveredReturn.tick();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(count(f.chest(), Items.BEEF) == 0,
                "the superseded Lodge chest must never receive the retained cargo");
            helper.assertTrue(count(nextChest, Items.BEEF) == 1
                    && count(f.hunter().bag, Items.BEEF) == 0,
                "exactly one retained item must reach only the new Lodge; next="
                    + count(nextChest, Items.BEEF) + " bag=" + count(f.hunter().bag, Items.BEEF)
                    + " contact=" + com.hearthstead.settlement.work.ContainerApproach.inspect(f.level(),
                        f.hunter(), helper.absolutePos(nextChestPos)).state()
                    + " cont=" + recoveredReturn.canContinueToUse()
                    + " clock=" + f.hunter().bagTransferPresentation().clock()
                    + " pos=" + f.hunter().position() + " route=" + f.hunter().routeFailureNote());
        });
    }

    @GameTest(batch = "hunter_physical", template = "empty16", timeoutTicks = 100)
    public void emptyLodgeForagesOrPublishesBoundedAmmoWait(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, 0);
        f.level().setDayTime(3000);
        helper.setBlock(new BlockPos(7, 1, 6), Blocks.BROWN_MUSHROOM);
        f.hunter().setNoAi(true);
        HunterWorkGoal forage = new HunterWorkGoal(f.hunter());
        helper.assertTrue(forage.canUse(),
            "zero arrows must not make the mushroom fallback unreachable");
        forage.start();
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            if (forage.canContinueToUse()) {
                forage.tick();
            } else {
                forage.stop();
                finished[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(finished[0] && count(f.chest(), Items.BROWN_MUSHROOM) == 1,
                "the no-ammo Hunter must still forage and deliver physical output");
            HunterWorkGoal waiting = new HunterWorkGoal(f.hunter());
            helper.assertTrue(!waiting.canUse()
                    && f.hunter().logisticsStopReason() == StopReason.WAITING_INPUT
                    && f.hunter().logisticsRetrySeconds() <= 3,
                "an empty Lodge must publish the existing bounded waiting-input projection");
        });
    }

    private static Fixture fixture(GameTestHelper helper, int cows, int arrows) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            // Deposits now need real chest contact (ContainerApproach face
            // rays), so retained template stone may not occlude the Lodge.
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Huntertest",
            helper.absolutePos(new BlockPos(4, 1, 4)));
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building lodge = GameTestFixtures.register(helper, settlement,
            BuildingType.HUNTERS_LODGE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be = level.getBlockEntity(
            helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container,
            "Hunter Lodge fixture must have one physical chest");
        Container chest = (Container) be;
        if (arrows > 0) chest.setItem(0, new ItemStack(Items.ARROW, arrows));
        SettlerEntity hunter = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 6));
        hunter.bindTo(settlement.id, settlement.center);
        settlement.putRecord(hunter.getUUID(), "Hunter", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, lodge, hunter).ok(),
            "fixture must create exact Lodge employment");
        hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        EquipmentRequests.refreshFor(level, settlement, lodge, hunter);
        helper.assertTrue(EquipmentRequests.requestFor(lodge,
                hunter.getUUID()) == null,
            "the fixture's real bow must clear its hire-time equipment request");
        List<Cow> herd = new ArrayList<>();
        int[][] positions = {{10,10},{11,9},{9,11},{12,11},{10,13}};
        for (int i = 0; i < cows; i++) {
            herd.add(helper.spawn(EntityType.COW,
                new BlockPos(positions[i][0], 1, positions[i][1])));
        }
        return new Fixture(level, settlement, lodge, chest, hunter, herd);
    }

    private static int arrows(Fixture fixture) {
        return fixture.hunter().carriedArrowCount()
            + count(fixture.chest(), Items.ARROW);
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static void unregisterAtEnd(GameTestHelper helper,
                                        Consumer<EntityJoinLevelEvent> listener) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) { }
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                NeoForge.EVENT_BUS.unregister(listener);
            }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                NeoForge.EVENT_BUS.unregister(listener);
            }
            @Override public void testAddedForRerun(GameTestInfo original,
                    GameTestInfo rerun, GameTestRunner runner) {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        });
    }

    public HunterPhysicalGameTests() {
    }
}
