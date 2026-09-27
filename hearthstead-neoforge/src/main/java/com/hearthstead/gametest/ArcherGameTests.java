package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.ArcherTowerPost;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The archer trade, end to end (owner's ask, 2026-08-25: an archer whose
 * abilities — Power Shot, Triple Shot — arrive over time).
 *
 * <p>The load-bearing rules pinned here, each of which would fail its named
 * test if broken in code:
 *
 * <ul>
 *   <li><b>Chest truth.</b> Every arrow an archer looses left the
 *       WATCHTOWER's own chest, exactly counted — and an empty tower means
 *       an archer who cannot shoot. This is the consumer end of FLOWS.md's
 *       fletcher → watchtower edge, and the first test asserts the full
 *       conservation identity, not just "the chest went down".
 *   <li><b>Doing the job makes you better at it</b> (job standard point 8):
 *       loosing arrows trains DEXTERITY, the number {@link ArcherRank#of}
 *       reads — without it, a career archer could never leave RECRUIT, the
 *       exact defect the guard progression audit found on STRENGTH.
 *   <li><b>The Power Shot cadence</b>: a SHARPSHOOTER's every-4th-shot
 *       ability actually fires on its cadence, observed through the goal's
 *       own counters (designed-for-testability seams, not reflection).
 * </ul>
 *
 * <p>Helpers mirror {@link GuardTrainingGameTests} exactly (a registered
 * settlement small enough that neighbouring arenas cannot answer for each
 * other, a valid building the hire API accepts).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** Independent zero-noise air integration: move, .99 drag, then .05 gravity. */
    private static double arrowHeightAtDistance(double horizontal, double verticalInput) {
        double length = Math.hypot(horizontal, verticalInput);
        double vx = 1.6D * horizontal / length, vy = 1.6D * verticalInput / length;
        double x = 0.0D, y = 0.0D;
        for (int tick = 0; tick < 160; tick++) {
            double nextX = x + vx, nextY = y + vy;
            if (nextX >= horizontal) {
                double fraction = (horizontal - x) / (nextX - x);
                return y + (nextY - y) * fraction;
            }
            x = nextX; y = nextY;
            vx *= .99D; vy = vy * .99D - .05D;
        }
        return Double.NaN;
    }

    private static void assertTowerArc(GameTestHelper helper, double horizontal, double targetDeltaY) {
        double vertical = ArcherAttackGoal.towerBallisticVerticalInput(horizontal, targetDeltaY);
        double landed = arrowHeightAtDistance(horizontal, vertical);
        helper.assertTrue(Double.isFinite(landed) && Math.abs(landed - targetDeltaY) < .015D,
            "Tower Post zero-noise arc must reach " + horizontal + " blocks at dY=" + targetDeltaY
                + "; input=" + vertical + "; landed=" + landed);
    }

    /** See {@link GuardTrainingGameTests#settlement}: registered, and small,
     *  for exactly the same reasons. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Skytterholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        // Morning, always: these tests never set the time, and in a full suite
        // the clock is wherever earlier tests left it. At night the archer is
        // asleep on its watch rota, and a sleeping archer never fetches the
        // bow from its rack (RestAtNight holds priority 5 against the equal
        // acquisition goal), so it never looses a single arrow.
        helper.getLevel().setDayTime(1000);
        return s;
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

    /** A valid WATCHTOWER whose bounds contain the chest the tests stock —
     *  bounds are what {@code WarehouseIndex.containers} walks. */
    private static Building tower(GameTestHelper helper, Settlement s,
                                  int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, x, z);
    }

    private static Container chestAt(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        var be = helper.getBlockEntity(rel);
        if (!(be instanceof Container c)) {
            throw new IllegalStateException("fixture: no container at " + rel);
        }
        return c;
    }

    private static int countOf(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** A physical bow is employment equipment; arrows remain separate stock. */
    private static void stockArcherRack(Container rack, int arrows) {
        rack.setItem(0, new ItemStack(Items.BOW));
        if (arrows > 0) {
            rack.setItem(1, new ItemStack(Items.ARROW, arrows));
        }
    }

    /** See {@link GuardTrainingGameTests#trainStrengthTo}: repeated small
     *  calls, so the result lands close to the target. */
    private static void trainDexterityTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.DEXTERITY) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.DEXTERITY, 5.0F, 1.0F);
        }
    }

    /**
     * The goal under test, from the entity's own selector.
     *
     * <p>{@code SettlerEntity.registerGoals} is the model-wiring worker's
     * file this cycle, so until the registration line lands there the
     * fixture arms the goal itself, at the same slot {@code GuardMeleeGoal}
     * holds (2). The lookup-first shape means these tests keep measuring the
     * one real instance — never a duplicate that would double-shoot — both
     * before and after that wiring lands.
     */
    private static ArcherAttackGoal arm(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) {
                return existing;
            }
        }
        ArcherAttackGoal goal = new ArcherAttackGoal(archer);
        archer.goalSelector.addGoal(2, goal);
        return goal;
    }

    /** Why an archer is not shooting: activity, bow, target and running goals. */
    private static String diag(SettlerEntity archer) {
        StringBuilder goals = new StringBuilder();
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning()) {
                goals.append(wrapped.getGoal().getClass().getSimpleName()).append('@')
                    .append(wrapped.getPriority()).append(' ');
            }
        }
        return "[activity=" + archer.getActivity() + " main=" + archer.getMainHandItem()
            + " target=" + (archer.getTarget() == null ? "none" : archer.getTarget().getType().toShortString())
            + " pos=" + archer.blockPosition() + " goals=" + goals.toString().trim()
            + " route=" + archer.routeFailureNote() + "]";
    }

    // --------------------------------------------------- chest-true ammo ---

    /**
     * The whole trade in one arena: a hired archer, a stocked tower, a
     * raider — the raider gets hurt AND the tower's arrow count goes down,
     * and the conservation identity holds at every observed instant:
     * chest + quiver + loosed == what the chest started with. (A MARKSMAN
     * fixture, so the spread is tight, every volley is exactly one arrow,
     * and the assertion is arithmetic rather than luck.)
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerLoosesChestTrueArrowsAtARaider(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Skytte", 4, 4);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, tower, archer);
        helper.assertTrue(hired.ok(), "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getProfession() == Profession.ARCHER,
            "the watchtower's trade is ARCHER now, was " + archer.getProfession());
        trainDexterityTo(archer, ArcherRank.MARKSMAN.threshold());

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            helper.assertTrue(inChest + goal.quiverCount() + goal.shotsFired() == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " != the 16 the tower started with");
            helper.assertTrue(inChest < 16,
                "the tower's own chest must be what the quiver drains " + diag(archer));
            helper.assertTrue(pell.getHealth() < pellMax,
                "an archer with arrows and a clear shot must hurt the raider"
                    + " (still " + pell.getHealth() + "/" + pellMax
                    + " after " + goal.shotsFired() + " volleys)");
        });
    }

    /** Regression for actual native feedback: 128 arrows in bag, empty quiver/rack. */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 300)
    public void archerUsesSuppliedBagArrowsWithoutMintingOrStrippingComponents(GameTestHelper helper) {
        floor(helper,16);
        Settlement settlement = settlement(helper);
        Building tower = tower(helper,settlement,2,2);
        Container rack = chestAt(helper,new BlockPos(3,1,3));
        stockArcherRack(rack,0);
        SettlerEntity archer = settler(helper,settlement,"Supplied defender",4,4);
        helper.assertTrue(Employment.hire(helper.getLevel(),settlement,tower,archer).ok(),"actual Archer employment");
        archer.setItemSlot(EquipmentSlot.MAINHAND,rack.removeItem(0,1));
        trainDexterityTo(archer,ArcherRank.MARKSMAN.threshold());
        ItemStack named = new ItemStack(Items.ARROW,3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("Keep this named ammunition"));
        archer.bag.setItem(0,named.copy());
        archer.bag.setItem(1,new ItemStack(Items.ARROW,64));
        archer.bag.setItem(2,new ItemStack(Items.ARROW,64));
        for (int slot=3;slot<archer.bag.getContainerSize();slot++)
            archer.bag.setItem(slot,new ItemStack(Items.DIRT,64));
        int unrelatedDirt=(archer.bag.getContainerSize()-3)*64;
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),new BlockPos(13,1,4));
        target.setNoAi(true); // Stationary target isolates ammunition ownership, not combat balance.
        float originalHealth=target.getHealth();
        ArcherAttackGoal goal=arm(archer);
        archer.setTarget(target);
        helper.onEachTick(() -> {
            helper.assertTrue(countOf(archer.bag,Items.ARROW)+countOf(rack,Items.ARROW)
                +goal.quiverCount()+goal.shotsFired()==131,"bag/rack/quiver/released arrow conservation");
            helper.assertTrue(countOf(archer.bag,Items.DIRT)==unrelatedDirt,"full-bag unrelated goods stay untouched");
            helper.assertTrue(ItemStack.matches(named,archer.bag.getItem(0)),"unsupported named arrows remain exact and unconsumed");
            if (goal.shotsFired()>0 && target.getHealth()<originalHealth) {
                helper.assertTrue(goal.quiverCount()>0 && archer.archerQuiverOwnedBy(tower.id),"actual supplied arrows bind to current employer");
                helper.assertTrue(countOf(archer.bag,Items.ARROW)==131-ArcherAttackGoal.QUIVER_SIZE,
                    "only the bounded quiver load leaves the full bag; all excess stays carried");
                CompoundTag saved=new CompoundTag(); archer.saveWithoutId(saved);
                SettlerEntity loaded=ModEntities.SETTLER.get().create(helper.getLevel());
                helper.assertTrue(loaded!=null,"decode-only entity available");
                loaded.load(saved);
                helper.assertTrue(loaded.archerQuiverCount()==archer.archerQuiverCount()
                    && loaded.archerQuiverOwnedBy(tower.id)
                    && countOf(loaded.bag,Items.ARROW)==countOf(archer.bag,Items.ARROW)
                    && countOf(loaded.bag,Items.DIRT)==unrelatedDirt
                    && ItemStack.matches(named,loaded.bag.getItem(0)),"save/load preserves enlisted and untouched physical ammunition");
                helper.succeed();
            }
        });
    }

    /** A real approaching zombie must not reset every draw inside six blocks. */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 160)
    public void approachingZombieCannotStarveCloseRangeDraw(GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building watchtower = tower(helper, settlement, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);
        // A clear five-by-five interior keeps both bodies inside six blocks.
        // The roof prevents sunlight damage; neither actor is invulnerable.
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS);
                if (x == 5 || x == 11 || z == 5 || z == 11) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
            }
        }
        SettlerEntity archer = settler(helper, settlement, "Close defense", 6, 8);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
            watchtower, archer).ok(), "fixture: actual Watchtower employment");
        archer.setItemSlot(EquipmentSlot.MAINHAND, rack.removeItem(0, 1));
        var zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
            new BlockPos(10, 1, 8));
        zombie.setTarget(archer);
        archer.setTarget(zombie);
        ArcherAttackGoal goal = arm(archer);
        net.minecraft.world.phys.Vec3 initialArcherPosition = archer.position();
        double initialApproachDistance = zombie.position().distanceTo(initialArcherPosition);
        float initialHealth = zombie.getHealth();
        boolean[] drewInsideRetreatRange = {false};
        boolean[] zombieApproached = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount()
                    + goal.shotsFired() == 16,
                "close defense must conserve physical rack/quiver/released arrows");
            zombieApproached[0] |= zombie.position().distanceTo(initialArcherPosition) < initialApproachDistance - 0.25;
            drewInsideRetreatRange[0] |= archer.isUsingItem()
                && archer.distanceTo(zombie) < ArcherAttackGoal.BACK_AWAY_UNDER;
            if (goal.shotsFired() > 0 && zombie.getHealth() < initialHealth
                    && drewInsideRetreatRange[0] && zombieApproached[0]) {
                helper.assertTrue(archer.isAlive(), "defender must survive its first release");
                helper.succeed();
            }
        });
        GameTestTicks.at(helper, 150, () -> com.hearthstead.Hearthstead.LOGGER.info(
            "close defense late witness: draw="+drewInsideRetreatRange[0]+", approach="+zombieApproached[0]
                +", shots="+goal.shotsFired()+", archer="+archer.position()+", zombie="+zombie.position()
                +", using="+archer.isUsingItem()+", zombieTarget="+zombie.getTarget()));
    }

    /**
     * Regression for the old transient goal field: a chunk unload or restart
     * after restock erased every shaft already removed from the tower. The
     * borrowed count now lives on the entity, survives NBT reload exactly and
     * remains bounded when malformed external data is presented.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 200)
    public void borrowedQuiverSurvivesReloadWithoutBreakingConservation(
            GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building watchtower = tower(helper, settlement, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, ArcherAttackGoal.QUIVER_SIZE);

        SettlerEntity archer = settler(helper, settlement, "Lagerfast", 4, 4);
        helper.assertTrue(
            Employment.hire(helper.getLevel(), settlement, watchtower, archer).ok(),
            "fixture: the watchtower must hire the reload archer");
        // This test is about persisted borrowed-arrow ownership. Give it the
        // already-physical rack bow synchronously; the separate equipment
        // acquisition tests own the animated chest-to-hand journey.
        ItemStack physicalBow = rack.removeItem(0, 1);
        helper.assertTrue(physicalBow.is(Items.BOW),
            "fixture: the exact Watchtower rack must supply the bow");
        archer.setItemSlot(EquipmentSlot.MAINHAND, physicalBow);
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(13, 1, 4));
        target.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(target);

        // The persistence boundary is the first real combat tick: it pulls
        // the physical rack arrows, then begins the ordinary 20-tick draw.
        // Drive that single authoritative goal tick directly so the save
        // cannot race the selector past this deliberately narrow boundary.
        helper.assertTrue(goal.canUse(),
            "fixture: the physically armed Archer must enter its real combat goal");
        goal.start();
        goal.tick();
        int borrowed = goal.quiverCount();
        helper.assertTrue(borrowed > 0 && goal.shotsFired() == 0,
            "fixture must save after rack withdrawal and before release");
        int inRack = countOf(rack, Items.ARROW);

        CompoundTag save = new CompoundTag();
        archer.addAdditionalSaveData(save);
        helper.assertTrue(save.getInt(SettlerEntity.ARCHER_QUIVER_NBT_KEY)
                == borrowed,
            "entity save must own the exact borrowed arrow count");
        helper.assertTrue(save.hasUUID(
                SettlerEntity.ARCHER_QUIVER_SOURCE_NBT_KEY)
                && save.getUUID(SettlerEntity.ARCHER_QUIVER_SOURCE_NBT_KEY)
                    .equals(watchtower.id),
            "entity save must bind borrowed arrows to the exact source tower");

            SettlerEntity loaded = ModEntities.SETTLER.get().create(
                helper.getLevel());
            helper.assertTrue(loaded != null,
                "fixture: replacement settler entity must construct");
            loaded.readAdditionalSaveData(save);
            ArcherAttackGoal loadedGoal = arm(loaded);
            helper.assertTrue(loadedGoal.quiverCount() == borrowed,
                "reload changed quiver ownership from " + borrowed + " to "
                    + loadedGoal.quiverCount());
            helper.assertTrue(loaded.archerQuiverOwnedBy(watchtower.id),
                "reload must retain exact source-tower ownership");
            helper.assertTrue(inRack + loadedGoal.quiverCount()
                    + goal.shotsFired() == ArcherAttackGoal.QUIVER_SIZE,
                "reload broke conservation: rack " + inRack + " + quiver "
                    + loadedGoal.quiverCount() + " + loosed "
                    + goal.shotsFired());

            CompoundTag overflow = save.copy();
            overflow.putInt(SettlerEntity.ARCHER_QUIVER_NBT_KEY,
                ArcherAttackGoal.QUIVER_SIZE + 99);
            loaded.readAdditionalSaveData(overflow);
            helper.assertTrue(loaded.archerQuiverCount()
                    == ArcherAttackGoal.QUIVER_SIZE,
                "malformed reload must clamp quiver ownership to its bound");

            Building towerB = tower(helper, settlement, 9, 2);
            chestAt(helper, new BlockPos(10, 1, 3));
            helper.assertFalse(loaded.archerQuiverOwnedBy(towerB.id),
                "Tower A's restarted quiver must never authorize Tower B");

            archer.setTarget(null);
            goal.stop();
            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    towerB, archer).ok(),
                "a normal reassignment must settle Tower A's quiver first");
            helper.assertTrue(archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null
                    && countOf(rack, Items.ARROW) == ArcherAttackGoal.QUIVER_SIZE,
                "A->B reassignment must return every borrowed shaft to A and clear provenance");

            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    watchtower, archer).ok(),
                "fixture: Archer must return to Tower A for full-rack dismissal");
            int arrowSlot = -1;
            for (int slot = 0; slot < rack.getContainerSize(); slot++) {
                if (rack.getItem(slot).is(Items.ARROW)) {
                    arrowSlot = slot;
                    break;
                }
            }
            ItemStack rackArrows = arrowSlot < 0 ? ItemStack.EMPTY
                : rack.getItem(arrowSlot);
            helper.assertTrue(rackArrows.is(Items.ARROW)
                    && rackArrows.getCount() >= 4,
                "fixture: Tower A rack must contain the returned arrows");
            rackArrows.shrink(4);
            helper.assertTrue(archer.storeArcherQuiverArrows(watchtower.id, 4)
                    == 4,
                "fixture: four physical rack arrows must enter the sourced quiver");
            Container holding = chestAt(helper, new BlockPos(15, 1, 15));
            for (int slot = 0; slot < rack.getContainerSize(); slot++) {
                holding.setItem(slot, rack.removeItemNoUpdate(slot));
                rack.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            AABB nearby = archer.getBoundingBox().inflate(3.0D);
            int droppedBefore = helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, nearby, item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(Employment.dismiss(helper.getLevel(), settlement,
                    archer) == watchtower,
                "full-rack dismissal must complete through visible fallback");
            int droppedAfter = helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, nearby, item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(droppedAfter - droppedBefore == 4
                    && archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null,
                "full rack must materialize exactly four arrows once, then clear count+source");

            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    watchtower, archer).ok(),
                "fixture: terminal Archer must re-enter the exact Watchtower");
            UUID terminalSale = UUID.randomUUID();
            helper.assertTrue(settlement.employmentAuthorizations.authorize(
                    settlement.id, archer.getUUID(), watchtower.id,
                    Profession.ARCHER, terminalSale),
                "fixture: terminal cleanup needs one exact current paid receipt");
            UUID orderAuthor = UUID.randomUUID();
            GuardOrder terminalOrder = settlement.guardOrders.orderForMutation(
                settlement.id, archer.getUUID(),
                helper.getLevel().dimension().location()).orElseThrow();
            helper.assertTrue(terminalOrder.issueTower(watchtower.anchor,
                    Direction.NORTH, GuardOrder.DEFAULT_FACING_ARC,
                    orderAuthor, watchtower.id, helper.getLevel().getGameTime()),
                "fixture: terminal Archer needs a persisted Tower Post");
            int holdingArrowSlot = -1;
            for (int slot = 0; slot < holding.getContainerSize(); slot++) {
                if (holding.getItem(slot).is(Items.ARROW)
                    && holding.getItem(slot).getCount() >= 3) {
                    holdingArrowSlot = slot;
                    break;
                }
            }
            helper.assertTrue(holdingArrowSlot >= 0,
                "fixture: conserved rack stock must contain three death-test arrows");
            holding.getItem(holdingArrowSlot).shrink(3);
            holding.setChanged();
            helper.assertTrue(archer.storeArcherQuiverArrows(watchtower.id, 3)
                    == 3,
                "fixture: death-test quiver must own three exact Tower A arrows");
            archer.bag.setItem(0, new ItemStack(Items.BREAD, 2));

            int arrowsBeforeCancelledDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            Consumer<LivingDeathEvent> cancelArcherDeath = event -> {
                if (event.getEntity() == archer) {
                    event.getEntity().setHealth(1.0F);
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class,
                cancelArcherDeath);
            try {
                helper.assertTrue(archer.hurt(
                        helper.getLevel().damageSources().genericKill(),
                        archer.getMaxHealth() + 100.0F),
                    "fixture: the lethal hit must land before cancellation");
            } finally {
                NeoForge.EVENT_BUS.unregister(cancelArcherDeath);
            }
            int arrowsAfterCancelledDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(archer.isAlive() && !archer.isRemoved()
                    && settlement.record(archer.getUUID()) != null
                    && watchtower.workers.contains(archer.getUUID())
                    && settlement.employmentAuthorizations.matches(
                        settlement.id, archer.getUUID(), watchtower.id,
                        Profession.ARCHER)
                    && settlement.guardOrders.order(archer.getUUID()).isPresent()
                    && archer.bag.getItem(0).is(Items.BREAD)
                    && archer.bag.getItem(0).getCount() == 2
                    && archer.archerQuiverCount() == 3
                    && archer.archerQuiverOwnedBy(watchtower.id)
                    && arrowsAfterCancelledDeath == arrowsBeforeCancelledDeath,
                "a cancelled LivingDeathEvent must preserve the living roster, "
                    + "job receipt, order, bag, quiver and physical item count");

            archer.setHealth(1.0F);
            // Both death edges are compressed into one GameTest tick. The
            // cancelled lethal hit correctly leaves vanilla's hurt cooldown
            // behind, so clear only that fixture clock before exercising the
            // distinct accepted terminal edge.
            archer.invulnerableTime = 0;
            int breadBeforeAcceptedDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            Consumer<EntityJoinLevelEvent> rejectTerminalItems = event -> {
                if (event.getLevel() == helper.getLevel()
                    && event.getEntity() instanceof ItemEntity item
                    && (item.getItem().is(Items.ARROW)
                        || item.getItem().is(Items.BREAD))) {
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                EntityJoinLevelEvent.class, rejectTerminalItems);
            try {
                helper.assertTrue(archer.hurt(
                        helper.getLevel().damageSources().genericKill(), 20.0F),
                    "fixture: the accepted terminal death must land");
            } finally {
                NeoForge.EVENT_BUS.unregister(rejectTerminalItems);
            }
            int arrowsAfterRejectedSpawn = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            int breadAfterRejectedSpawn = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            DeferredItemMaterializationSavedData escrow =
                DeferredItemMaterializationSavedData.get(helper.getLevel());
            helper.assertTrue(!archer.isAlive()
                    && settlement.record(archer.getUUID()) == null
                    && !watchtower.workers.contains(archer.getUUID())
                    && settlement.employmentAuthorizations.receipt(
                        archer.getUUID()) == null
                    && settlement.guardOrders.order(archer.getUUID()).isEmpty()
                    && watchtower.equipmentRequests.stream().noneMatch(
                        request -> request.requesterId().equals(
                            archer.getUUID()))
                    && archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null
                    && archer.bag.isEmpty()
                    && arrowsAfterRejectedSpawn == arrowsAfterCancelledDeath
                    && breadAfterRejectedSpawn == breadBeforeAcceptedDeath
                    && escrow.pendingRows() == 2
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.ARROW) == 3
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.BREAD) == 2,
                "a rejected terminal ItemEntity spawn must transfer exact arrow "
                    + "and bag ownership into durable escrow while clearing worker, "
                    + "request, order and current authorization once");

            CompoundTag escrowSave = escrow.save(new CompoundTag(),
                helper.getLevel().registryAccess());
            DeferredItemMaterializationSavedData escrowRestart =
                DeferredItemMaterializationSavedData.load(escrowSave,
                    helper.getLevel().registryAccess());
            helper.assertTrue(!escrowRestart.quarantined()
                    && escrowRestart.pendingRows() == 2
                    && escrowRestart.pendingItems(
                        helper.getLevel().registryAccess(), Items.ARROW) == 3
                    && escrowRestart.pendingItems(
                        helper.getLevel().registryAccess(), Items.BREAD) == 2,
                "rejected death drops must retain exact counts through restart");

            // Simulate the opposite save-tear edge: the physical arrow entity
            // exists with the stable row UUID/proof, but SavedData still owns
            // the pending row. Retry must consume escrow, not spawn a second
            // three-arrow stack.
            ListTag pendingRows = escrowSave.getList("Pending",
                Tag.TAG_COMPOUND);
            CompoundTag arrowRow = null;
            ItemStack arrowStack = ItemStack.EMPTY;
            for (int rowIndex = 0; rowIndex < pendingRows.size(); rowIndex++) {
                CompoundTag candidate = pendingRows.getCompound(rowIndex);
                ItemStack decoded = ItemStack.parseOptional(
                    helper.getLevel().registryAccess(),
                    candidate.getCompound("Stack"));
                if (decoded.is(Items.ARROW)) {
                    arrowRow = candidate;
                    arrowStack = decoded;
                    break;
                }
            }
            helper.assertTrue(arrowRow != null && arrowStack.getCount() == 3,
                "fixture: restart escrow must expose the exact arrow row");
            CompoundTag proof = new CompoundTag();
            proof.putUUID("Id", arrowRow.getUUID("Id"));
            CompoundTag options = new CompoundTag();
            options.putInt("PickupDelay", -1);
            options.putBoolean("ExtendedLifetime", false);
            proof.put("Options", options);

            ItemEntity wrongPosition = new ItemEntity(helper.getLevel(),
                arrowRow.getDouble("X"), arrowRow.getDouble("Y") + 65.0D,
                arrowRow.getDouble("Z"), arrowStack.copy());
            wrongPosition.setUUID(arrowRow.getUUID("Id"));
            wrongPosition.getPersistentData().put(
                "HearthsteadDeferredMaterializationProof", proof.copy());
            helper.assertTrue(helper.getLevel().addFreshEntity(wrongPosition),
                "fixture: a same-UUID wrong-position collision must join first");
            DeferredItemMaterializationSavedData.retryLoaded(
                helper.getLevel());
            helper.assertTrue(escrow.pendingRows() == 1
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.ARROW) == 3
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.BREAD) == 0,
                "an out-of-bounds UUID collision must retain the exact arrow "
                    + "escrow while an independent bread row remains free to materialize");
            wrongPosition.discard();

            ItemEntity alreadyPhysical = new ItemEntity(helper.getLevel(),
                arrowRow.getDouble("X") + 1.25D, arrowRow.getDouble("Y"),
                arrowRow.getDouble("Z"), arrowStack);
            alreadyPhysical.setUUID(arrowRow.getUUID("Id"));
            alreadyPhysical.getPersistentData().put(
                "HearthsteadDeferredMaterializationProof", proof);
            helper.assertTrue(helper.getLevel().addFreshEntity(alreadyPhysical),
                "fixture: the exact already-materialized replay entity must join");

            DeferredItemMaterializationSavedData.retryLoaded(
                helper.getLevel());
            int arrowsAfterRetry = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            int breadAfterRetry = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(escrow.pendingRows() == 0
                    && arrowsAfterRetry - arrowsAfterRejectedSpawn == 3
                    && breadAfterRetry - breadAfterRejectedSpawn == 2,
                "retry must acknowledge one exact pre-existing arrow entity, "
                    + "materialize one bread stack and never duplicate either row");
            helper.assertTrue(escrow.materializedExact(helper.getLevel(),
                    arrowRow.getUUID("Id"), arrowRow.getDouble("X"),
                    arrowRow.getDouble("Y"), arrowRow.getDouble("Z"),
                    arrowStack,
                    DeferredItemMaterializationSavedData.ItemEntityOptions.NONE),
                "source-ledger replay must prove the exact moved physical item "
                    + "even after its deferred row was consumed");

            UUID deterministicHandoff = UUID.randomUUID();
            ItemStack stagedBread = new ItemStack(Items.BREAD);
            helper.assertTrue(escrow.queue(helper.getLevel(),
                    deterministicHandoff, archer.getX(), archer.getY(),
                    archer.getZ(), stagedBread,
                    DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.INSERTED
                    && escrow.queue(helper.getLevel(), deterministicHandoff,
                        archer.getX(), archer.getY(), archer.getZ(), stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.IDEMPOTENT
                    && escrow.containsExact(helper.getLevel(),
                        deterministicHandoff, archer.getX(), archer.getY(),
                        archer.getZ(), stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    && escrow.queue(helper.getLevel(), deterministicHandoff,
                        archer.getX() + 1.0D, archer.getY(), archer.getZ(),
                        stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.COLLISION
                    && escrow.cancel(deterministicHandoff),
                "deterministic source handoff must insert once, replay exactly, "
                    + "reject changed coordinates and roll back before source clear");

            SettlerEntity replacement = settler(helper, settlement,
                "Etterfølger", 5, 4);
            UUID replacementSale = UUID.randomUUID();
            ItemStack replacementEmblem = JobEmblemItem.stackFor(
                Profession.ARCHER);
            helper.assertTrue(JourneyEmblemProvenance.stamp(replacementEmblem,
                    settlement.id, replacementSale, Profession.ARCHER),
                "fixture: replacement Archer needs a new physical sale stamp");
            ServerPlayer replacementAuthor =
                helper.makeMockServerPlayerInLevel();
            replacementAuthor.setPos(replacement.getX(), replacement.getY(),
                replacement.getZ());
            replacementAuthor.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                replacementEmblem);
            helper.assertTrue(Employment.hireWithHeldEmblem(helper.getLevel(),
                    settlement, watchtower, replacement,
                    replacementAuthor).ok()
                    && settlement.employmentAuthorizations.matches(
                        settlement.id, replacement.getUUID(), watchtower.id,
                        Profession.ARCHER),
                "the vacated Watchtower must accept one newly paid replacement");
            Settlement terminalRestart = Settlement.readNbt(
                settlement.writeNbt());
            helper.assertTrue(terminalRestart.employmentAuthorizations.matches(
                    settlement.id, replacement.getUUID(), watchtower.id,
                    Profession.ARCHER),
                "replacement authority must survive restart after terminal cleanup");
        helper.succeed();
    }

    /**
     * A volley is not allowed to spawn from an unchanged stance. The real
     * MAINHAND bow must enter vanilla's synced use-item state for at least one
     * observable pre-contact tick, then leave it on the same tick the first
     * arrow is loosed. SettlerModel consumes this exact state for draw pose and
     * bow pull; EV_ARCHER_LOOSE owns only the bounded recovery presentation.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerPublishesPhysicalDrawBeforeRelease(GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building watchtower = tower(helper, settlement, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, settlement, "Buestreng", 4, 4);
        helper.assertTrue(
            Employment.hire(helper.getLevel(), settlement, watchtower, archer).ok(),
            "fixture: the watchtower must hire the archer");
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(13, 1, 4));
        target.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(target);

        boolean[] sawPhysicalDraw = {false};
        helper.onEachTick(() -> {
            if (goal.shotsFired() == 0 && archer.isUsingItem()) {
                helper.assertTrue(archer.getUseItem().is(Items.BOW),
                    "the draw state must belong to the real MAINHAND bow");
                sawPhysicalDraw[0] = true;
            }
            if (goal.shotsFired() > 0) {
                helper.assertTrue(sawPhysicalDraw[0],
                    "an arrow spawned without an observable physical bow draw");
                helper.assertTrue(!archer.isUsingItem(),
                    "the bow-use state must stop on the exact release tick");
                helper.succeed();
            }
        });
        helper.runAfterDelay(395, () -> helper.fail("no release within 395 ticks " + diag(archer)));
    }

    /**
     * No arrows in the tower = no shooting. The mirror image of the test
     * above, and the pressure that makes the fletcher worth hiring: the
     * archer stands the post empty-handed rather than conjuring ammunition.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void anEmptyTowerMeansNoShots(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));  // present, empty
        stockArcherRack(rack, 0);

        SettlerEntity archer = settler(helper, s, "Tomhendt", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        // This regression isolates ammunition, not the independent bow
        // acquisition goal. Equip the physical bow already stocked above.
        archer.setItemSlot(EquipmentSlot.MAINHAND, rack.removeItem(0, 1));
        rack.setChanged();

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        boolean[] returning = {false};
        // Register observers before ticking starts: adding scheduled callbacks
        // inside a scheduled callback mutates GameTest's active iterator.
        helper.onEachTick(() -> {
            if (!returning[0]) return;
            helper.assertTrue(!goal.outOfAmmoAnnounced(),
                "returning to stocked storage must not announce empty storage");
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount()
                    + goal.shotsFired() == 16,
                "return/refill/fire must conserve the same sixteen arrows");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(returning[0] && goal.shotsFired() > 0
                    && pell.getHealth() < pellMax,
                "normal AI must return, physically refill and hit the hostile");
        });
        helper.runAfterDelay(250, () -> {
            helper.assertTrue(goal.shotsFired() == 0,
                "no arrows in the tower must mean no shots, yet "
                    + goal.shotsFired() + " were loosed");
            helper.assertTrue(countOf(rack, Items.ARROW) == 0,
                "a rack without arrows must not mint arrows");
            helper.assertTrue(pell.isAlive() && pell.getHealth() >= pellMax,
                "the raider must be untouched, at " + pell.getHealth()
                    + "/" + pellMax);
            helper.assertTrue(goal.outOfAmmoAnnounced(),
                "a confirmed empty rack must explain why no shots are fired");
            goal.stop();
            goal.start();
            helper.assertTrue(goal.outOfAmmoAnnounced(),
                "goal restart must not announce the same empty rack again");
            rack.setItem(0, new ItemStack(Items.ARROW, 16));
            archer.setTarget(pell);
            goal.tick();
            helper.assertTrue(!goal.outOfAmmoAnnounced() && goal.quiverCount() == 16,
                "physical restock must end the starvation episode");
            // Move the SAME physical ammunition back to the rack and put the
            // archer outside refill reach. Normal AI must return and fire;
            // no test tick, teleport or navigation command drives the recovery.
            int returned = archer.takeArcherQuiverArrows(16);
            rack.setItem(0, new ItemStack(Items.ARROW, returned));
            tower.bounds = BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(3, 4, 3)));
            BlockPos away = helper.absolutePos(new BlockPos(15, 1, 14));
            archer.moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
            helper.assertTrue(goal.quiverCount() == 0 && !goal.outOfAmmoAnnounced(),
                "fixture must begin the return with an empty quiver and no warning");
            returning[0] = true;
        });
    }

    // ------------------------------------------------- doing trains rank ---

    /**
     * Loosing arrows trains DEXTERITY — the number {@link ArcherRank#of}
     * reads. Counted at the moment of release (and again per arrow that
     * strikes, from the arrow's own hit hook), never on a timer: without
     * this, a career archer could never leave RECRUIT, the exact defect the
     * guard progression audit found on the STRENGTH ladder.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void loosingArrowsTrainsDexterity(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, s, "Laerling", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        int before = archer.attribute(Attribute.DEXTERITY);

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> helper.assertTrue(
            archer.attribute(Attribute.DEXTERITY) > before,
            "loosing arrows must train Dexterity -- the rank ladder reads it"
                + " (started " + before + ", still "
                + archer.attribute(Attribute.DEXTERITY) + ") " + diag(archer)));
    }

    /**
     * W3b, a real Sunday risk: an off-watch archer asleep at night, its bow
     * still in the tower rack, and an enemy it is aimed at. It must wake,
     * fetch the bow and loose -- never sleep through the fight unarmed.
     */
    @GameTest(batch = "archer_night", template = "empty16", timeoutTicks = 600)
    public void aSleepingArcherWakesArmsFromTheRackAndLooses(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);
        SettlerEntity archer = settler(helper, s, "Nattvakt", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        helper.getLevel().setDayTime(18000); // midnight, after settlement() pinned the morning
        helper.assertTrue(com.hearthstead.settlement.Schedule.shouldSleep(s, archer, archer.dayPhase()),
            "fixture: this archer is off watch at midnight, so it would be asleep");
        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        helper.runAfterDelay(40, () -> archer.setTarget(pell)); // it has lain down by now
        helper.succeedWhen(() -> {
            helper.assertTrue(archer.getMainHandItem().is(Items.BOW),
                "the woken archer must fetch its bow from the rack " + diag(archer));
            helper.assertTrue(goal.shotsFired() > 0, "the armed archer must loose " + diag(archer));
        });
    }

    // ---------------------------------------------------- the power shot ---

    /**
     * A SHARPSHOOTER's Power Shot fires on its cadence: every 4th volley,
     * no more and no fewer — {@code shotsFired / 4 == powerShotsFired} is an
     * invariant at any instant, because both counters move in the same
     * release. Observed through the goal's own seams; the fixture reaches
     * DEX 35 the same way {@link GuardTrainingGameTests} reaches VETERAN.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void aSharpshooterFiresThePowerShotOnItsCadence(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, s, "Skarpskytter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        trainDexterityTo(archer, ArcherRank.SHARPSHOOTER.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.SHARPSHOOTER),
            "fixture sanity: the Power Shot needs a Sharpshooter, DEX="
                + archer.attribute(Attribute.DEXTERITY));

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            helper.assertTrue(
                goal.shotsFired() / ArcherRank.POWER_SHOT_EVERY == goal.powerShotsFired(),
                "the cadence broke: " + goal.powerShotsFired() + " power shots in "
                    + goal.shotsFired() + " volleys is not every "
                    + ArcherRank.POWER_SHOT_EVERY + "th");
            helper.assertTrue(goal.powerShotsFired() >= 1,
                "a Sharpshooter's 4th volley must be a Power Shot ("
                    + goal.shotsFired() + " volleys so far) " + diag(archer));
        });
    }

    // --------------------------------------------------- the triple shot ---

    /**
     * ACCEPT-JOBS audit (2026-08-26): Triple Shot -- the owner's other named
     * ability, MASTER's every-5th-volley fan of three arrows -- had NO
     * coverage anywhere: only {@link ArcherAttackGoal#powerShotsFired()}
     * existed as a test seam, {@code tripleShotsFired()} did not exist at
     * all. Added one line for line (see that goal's own class doc for why
     * it is duplicated rather than shared) so this is observable the same
     * way Power Shot already was. The conservation identity accounts for
     * the fan explicitly: a Triple Shot spends THREE arrows for one volley,
     * not one, so {@code shotsFired} alone would silently under-count ammo
     * the moment a Master archer's cadence lands on its 5th shot -- exactly
     * the kind of chest-truth gap this audit exists to catch.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void aMasterArcherFansTheTripleShotOnItsCadence(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Mesterskytter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        trainDexterityTo(archer, ArcherRank.MASTER.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.MASTER),
            "fixture sanity: the Triple Shot needs a Master, DEX="
                + archer.attribute(Attribute.DEXTERITY));

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        // The fourth cadence slot is a Power Shot. Keep the fixture target
        // alive through the following fifth slot so Triple Shot cadence,
        // rather than a premature target death, owns this assertion.
        pell.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        pell.setHealth(pell.getMaxHealth());
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            // A Triple Shot spends 3 arrows for 1 counted volley -- the 2
            // extra per triple shot have to be added back in, or the
            // identity below would look broken even though nothing leaked.
            int accounted = inChest + goal.quiverCount() + goal.shotsFired()
                + 2 * goal.tripleShotsFired();
            helper.assertTrue(accounted == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " + 2*triples " + goal.tripleShotsFired() + " != 16");
            helper.assertTrue(goal.tripleShotsFired() >= 1,
                "a Master's 5th volley must be a Triple Shot ("
                    + goal.shotsFired() + " volleys so far, "
                    + goal.powerShotsFired() + " power) " + diag(archer));
            helper.assertTrue(pell.getHealth() < pellMax,
                "a Master archer firing its real cadence must still hurt the "
                    + "raider (still " + pell.getHealth() + "/" + pellMax + ")");
        });
    }

    // ------------------------------------------------ tower-post sniper ---

    /**
     * A 32-block post must correct the existing real Arrow's air drop rather
     * than merely authorizing a longer target scan. These values include the
     * same-target, uphill and downhill cases without spawning a synthetic hit.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 20)
    public void towerPostBallisticsReachLongTargetsWithTheRealArrowArc(GameTestHelper helper) {
        assertTowerArc(helper, 18.0D, -.93D);
        assertTowerArc(helper, 30.0D, -.93D);
        assertTowerArc(helper, 32.0D, -.93D);
        assertTowerArc(helper, 30.0D, 2.4D);
        assertTowerArc(helper, 30.0D, -2.8D);
        assertTowerArc(helper, 25.0D, 12.0D);
        assertTowerArc(helper, 30.0D, -10.0D);
        helper.succeed();
    }

    /**
     * A Tower Post is a physical firing position, not a Watchtower-wide buff:
     * this archer acquires and hurts a visible raider beyond the normal
     * 18-block shot range only after it is standing on its elevated own post.
     * It then holds through a blocked sight line instead of descending to
     * chase. Clearing that authored post releases the extension and ordinary
     * movement may resume, but cannot loose from the old long distance.
     */
    @GameTest(batch = "archer", template = "empty64", timeoutTicks = 900)
    public void towerPostedArcherCoversVisibleRangeWithoutDescending(GameTestHelper helper) {
        floor(helper, 64);
        Settlement s = settlement(helper);
        // The board's bounded settlement scan is radius + 8. Thirty blocks
        // from the post remains a real in-settlement threat, but exceeds the
        // ordinary 18-block release rule.
        s.radius = 24;
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 32);

        // The post is one real level above the floor: solid support below,
        // clear body/head cells, and still inside the surveyed Watchtower.
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(4, 3, 4), Blocks.AIR);
        helper.setBlock(new BlockPos(4, 4, 4), Blocks.AIR);
        // One-block physical descent keeps explicit release independently walkable.
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.STONE_BRICKS);
        BlockPos post = helper.absolutePos(new BlockPos(4, 3, 4));

        SettlerEntity archer = settler(helper, s, "Tower Warden", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the surveyed watchtower must employ its Archer");
        archer.setItemSlot(EquipmentSlot.MAINHAND, rack.removeItem(0, 1));
        rack.setChanged();
        // The Tower Post changes acquisition/release range, not the existing
        // rank accuracy contract. Use the established Marksman fixture rank so
        // this 30-block proof cannot be a Recruit-spread coin toss.
        trainDexterityTo(archer, ArcherRank.MARKSMAN.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.MARKSMAN),
            "fixture: long-range coverage needs the established Marksman accuracy");
        Development.of(helper.getLevel(), s);
        UUID issuer = UUID.randomUUID();
        GuardOrder order = s.guardOrders.orderForMutation(s.id, archer.getUUID(),
            helper.getLevel().dimension().location()).orElseThrow();
        helper.assertTrue(order.issueTower(post, Direction.EAST,
                GuardOrder.DEFAULT_FACING_ARC, issuer, tower.id,
                helper.getLevel().getGameTime()),
            "fixture: Archer needs its exact authored Tower Post");
        helper.assertTrue(GuardAssignmentService.validate(helper.getLevel(), s, archer,
                false).valid(), "fixture: elevated Tower Post must validate");
        archer.moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D);
        archer.getNavigation().stop();

        RaiderEntity threat = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(34, 3, 4));
        threat.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        threat.setNoAi(true);
        threat.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        threat.setHealth(threat.getMaxHealth());
        helper.assertTrue(archer.distanceTo(threat) > ArcherAttackGoal.NORMAL_SHOT_RANGE
                && archer.distanceTo(threat) < ArcherTowerPost.SHOT_RANGE,
            "fixture target must be outside normal but inside Tower Post range");
        float threatMax = threat.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        int[] shotsBeforeBlockedSight = {-1};
        float[] healthBeforeBlockedSight = {-1.0F};
        int[] shotsBeforeRelease = {-1};

        helper.onEachTick(() -> {
            if (shotsBeforeRelease[0] < 0) {
                helper.assertTrue(archer.blockPosition().distSqr(post) <= 2.25D,
                    "active Tower Post must keep the Archer on its elevated firing cell");
                helper.assertTrue(archer.getNavigation().isDone(),
                    "Tower Post sniper must not navigate down to chase or kite");
            }
        });
        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertTrue(archer.getTarget() == threat,
                    "posted Archer must acquire the real visible in-range raider itself");
                helper.assertTrue(goal.shotsFired() > 0 && threat.getHealth() < threatMax,
                    "Tower Post must loose a chest-backed arrow beyond normal range"
                        + "; shots=" + goal.shotsFired() + "; health="
                        + threat.getHealth() + "/" + threatMax);
            })
            .thenExecute(() -> {
                shotsBeforeBlockedSight[0] = goal.shotsFired();
                healthBeforeBlockedSight[0] = threat.getHealth();
                // Build a full physical wall between the already-acquired
                // actors. Coverage must stop here: no wall vision, no route
                // down from the tower, and no delayed old draw.
                for (int y = 1; y <= 6; y++) {
                    for (int z = 2; z <= 6; z++) {
                        helper.getLevel().setBlockAndUpdate(
                            helper.absolutePos(new BlockPos(19, y, z)),
                            Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
                helper.assertTrue(!archer.hasLineOfSight(threat),
                    "fixture wall must physically block the posted Archer's sight");
            })
            .thenIdle(50)
            .thenExecute(() -> {
                helper.assertTrue(goal.shotsFired() == shotsBeforeBlockedSight[0]
                        && threat.getHealth() == healthBeforeBlockedSight[0],
                    "blocked Tower Post sight must not loose or damage through the wall");
                helper.assertTrue(archer.blockPosition().distSqr(post) <= 2.25D
                        && archer.getNavigation().isDone(),
                    "blocked Tower Post sight must hold the firing cell rather than descend");
                for (int y = 1; y <= 6; y++) {
                    for (int z = 2; z <= 6; z++) {
                        helper.getLevel().setBlockAndUpdate(
                            helper.absolutePos(new BlockPos(19, y, z)),
                            Blocks.AIR.defaultBlockState());
                    }
                }
                helper.assertTrue(order.clear(issuer, tower.id,
                        helper.getLevel().getGameTime()),
                    "fixture: explicit player release must clear Tower Post authority");
                shotsBeforeRelease[0] = goal.shotsFired();
            })
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertTrue(goal.shotsFired() == shotsBeforeRelease[0],
                    "released Archer must not retain the old Tower Post shot range");
                helper.assertTrue(archer.distanceTo(threat) > ArcherAttackGoal.NORMAL_SHOT_RANGE,
                    "fixture must observe ordinary movement before normal-range firing resumes");
                helper.assertTrue(archer.blockPosition().distSqr(post) > 2.25D,
                    "explicit release must actually allow ordinary movement away from Tower Post " + diag(archer));
                threat.discard();
            })
            .thenSucceed();
    }

    // ------------------------------------------------- self-acquisition ---

    /**
     * ACCEPT-JOBS audit (2026-08-26): every test above calls {@code
     * archer.setTarget(pell)} before waiting -- real fixtures for the
     * shooting mechanics, but every one of them skips straight past {@link
     * ArcherAttackGoal#canUse()}'s own {@code acquire()} call, the archer's
     * DUPLICATED copy of {@code SettlerDefenseTargetGoal}'s targeting logic
     * (the class doc's own "What is deliberately mirrored" section explains
     * why it is duplicated rather than shared). That method had zero
     * coverage: nothing ever left an archer's target null and simply waited
     * to see whether the trade notices a raider on its own. This is the
     * full chain the owner is judging at 18:00 -- hired, posted, watching,
     * and finding its own target -- not a raider handed to it by the test.
     * The raider is spawned real (never {@code setTarget} on either side)
     * and given no AI so it cannot wander out of the settlement ring before
     * the archer's own {@code RETARGET_INTERVAL} scan finds it.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 800)
    public void archerFindsAndLoosesAtARaiderWithNoHelp(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        s.radius = 8;
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Speider", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getTarget() == null,
            "fixture sanity: nothing may hand the archer a target");
        archer.setItemSlot(EquipmentSlot.MAINHAND, rack.removeItem(0, 1));
        rack.setChanged();
        Development.of(helper.getLevel(), s);
        var alertWrapped = archer.goalSelector.getAvailableGoals().stream()
            .filter(w -> w.getGoal() instanceof GuardRespondToAlertGoal).findFirst().orElseThrow();
        GuardRespondToAlertGoal alert = (GuardRespondToAlertGoal) alertWrapped.getGoal();
        s.alertPos = helper.absolutePos(new BlockPos(15, 1, 15));
        s.alertUntilGameTime = helper.getLevel().getGameTime() + 1000;
        helper.assertTrue(alert.canUse(), "an absent order must preserve ordinary alarm response");
        BlockPos post = helper.absolutePos(new BlockPos(9, 1, 8));
        UUID issuer = UUID.randomUUID();
        int[] phase = {0};
        int[] authoredRevision = {-1};
        long[] contactPhaseStarted = {-1};
        double[] retreatStartX = {0};
        long setupTick = helper.getLevel().getGameTime();
        int[] shotsBeforeExcludedRack = {0};
        RaiderEntity[] excludedRackTarget = {null};
        float[] healthAtCover = {0};
        int[] shotsAtCover = {0};

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(15, 1, 15));
        pell.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        pell.setNoAi(true);
        // No eligible threat during the alarm phase. Its real settlement
        // identity is restored when the close-threat phase begins.
        pell.assign(UUID.randomUUID(), UUID.randomUUID(), RaidObjective.BLOD, 1.0F, false);
        float pellMax = pell.getMaxHealth();
        // Armed only so the fixture can read shotsFired()/quiverCount() --
        // the SAME lookup-first helper every other test in this file uses,
        // never a second goal instance. Never armed with a target.
        ArcherAttackGoal goal = arm(archer);

        helper.onEachTick(() -> {
            if (phase[0] < 4) {
                helper.assertTrue(helper.getLevel().getGameTime() - setupTick < 240,
                    "alarm, retreat and physical recovery each have a bounded test window");
            }
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount()
                    + goal.shotsFired() == 16,
                "ordered movement must conserve every physical arrow");
            if (phase[0] < 1) return;
            GuardOrder current = s.guardOrders.order(archer.getUUID()).orElseThrow();
            helper.assertTrue(current.revision() == authoredRevision[0],
                "AI must not rewrite the authored defensive order");
            if (helper.getLevel().getGameTime() - setupTick < 25) return;
            BlockPos currentPost = current.pos().orElseThrow();
            double radiusSqr = (double) current.leashRadius() * current.leashRadius();
            if (phase[0] != 3) {
                helper.assertTrue(archer.blockPosition().distSqr(currentPost) <= radiusSqr,
                    "ordinary AI must not voluntarily leave the Stand region");
            }
            var path = archer.getNavigation().getPath();
            if (path != null && !path.isDone() && phase[0] != 3) {
                for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                    helper.assertTrue(path.getNode(i).asBlockPos().distSqr(currentPost) <= radiusSqr,
                        "an inside endpoint must not hide an outside path detour");
                }
            }
            if (contactPhaseStarted[0] >= 0 && phase[0] == 4) {
                helper.assertTrue(helper.getLevel().getGameTime() - contactPhaseStarted[0] < 400,
                    "original acquire/cover/contact behavior retains its 400-tick allowance");
            }
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(alertWrapped.isRunning() && !archer.getNavigation().isDone(),
                "ordinary scheduler must actually begin the unordered alarm route");
            GuardOrder order = s.guardOrders.orderForMutation(s.id, archer.getUUID(),
                helper.getLevel().dimension().location()).orElseThrow();
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(order.issueStand(post, Direction.SOUTH, 8, issuer, tower.id, now),
                "fixture must author Stand during a running alarm");
            helper.assertTrue(!alert.canContinueToUse() && !alert.canUse(),
                "active Stand must reject alarm start and continuation");
            helper.assertTrue(order.issueTower(helper.absolutePos(new BlockPos(5, 1, 5)),
                    Direction.SOUTH, GuardOrder.DEFAULT_FACING_ARC, issuer, tower.id, now)
                    && GuardAssignmentService.validate(helper.getLevel(), s, archer, false).valid()
                    && !alert.canUse(), "valid Tower must also reject alarm movement");
            helper.assertTrue(order.issueStand(post, Direction.SOUTH, 8, issuer, UUID.randomUUID(), now)
                    && !alert.canUse(), "invalid persisted employer must fail closed");
            helper.assertTrue(order.issueStand(post, Direction.SOUTH, 8, issuer, tower.id, now)
                    && order.clear(issuer, tower.id, now) && alert.canUse(),
                "valid inactive order must preserve alarm response");
            helper.assertTrue(order.appendPatrolPoint(post, issuer, tower.id, now)
                    && order.appendPatrolPoint(post.east(), issuer, tower.id, now)
                    && order.issuePatrol(GuardOrder.Traversal.LOOP, issuer, tower.id, now)
                    && alert.canUse(), "valid Patrol retains existing alarm behavior");
            helper.assertTrue(order.issueStand(post, Direction.SOUTH, 8, issuer, tower.id, now),
                "fixture must restore the authored Stand");
            authoredRevision[0] = order.revision();
            phase[0] = 1;
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(!alertWrapped.isRunning(),
                "scheduler must relinquish the old alarm goal after Stand is imposed");
            // Controlled initial condition at the edge; all subsequent retreat
            // is driven by ordinary entity AI, never direct goal ticks.
            BlockPos edge = helper.absolutePos(new BlockPos(2, 1, 8));
            archer.moveTo(edge.getX() + 0.5, edge.getY(), edge.getZ() + 0.5);
            archer.getNavigation().stop();
            retreatStartX[0] = archer.getX();
            BlockPos close = helper.absolutePos(new BlockPos(4, 1, 8));
            pell.moveTo(close.getX() + 0.5, close.getY(), close.getZ() + 0.5);
            pell.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
            // Block the direct retreat cell; a reachable sideways alternative
            // is required rather than merely clamping a destination.
            helper.setBlock(new BlockPos(1, 1, 8), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(1, 2, 8), Blocks.STONE_BRICKS);
            phase[0] = 2;
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(Math.abs(archer.getX() - retreatStartX[0]) > 0.5
                    || Math.abs(archer.getZ() - (post.getZ() + 0.5)) > 0.5,
                "close-threat defense must find real movement around the blocked retreat");
            helper.setBlock(new BlockPos(1, 1, 8), Blocks.AIR);
            helper.setBlock(new BlockPos(1, 2, 8), Blocks.AIR);
            // Simulate outside displacement once. Recovery must use navigation.
            BlockPos outside = helper.absolutePos(new BlockPos(0, 1, 8));
            archer.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
            archer.getNavigation().stop();
            phase[0] = 3;
        });

        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertTrue(phase[0] == 3,
                    "the displacement phase must begin before reading its order");
                GuardOrder current = s.guardOrders.order(archer.getUUID()).orElse(null);
                helper.assertTrue(current != null,
                    "the displaced Archer must retain its authored order");
                int inset = Math.max(0, current.leashRadius() - 1);
                helper.assertTrue(archer.onGround()
                        && archer.blockPosition().distSqr(post)
                            <= (double) inset * inset,
                    "displaced Archer must return grounded and one block inside "
                        + "its authored region before the contact phase");
            })
            .thenExecute(() -> {
                helper.assertTrue(helper.getLevel().getGameTime() - setupTick < 240,
                    "added alarm/retreat/recovery phases must complete within 240 ticks");
                BlockPos firing = helper.absolutePos(new BlockPos(13, 1, 4));
                pell.moveTo(firing.getX() + 0.5, firing.getY(), firing.getZ() + 0.5);
                phase[0] = 4;
                contactPhaseStarted[0] = helper.getLevel().getGameTime();
            })
            .thenWaitUntil(() -> helper.assertTrue(archer.getTarget() == pell,
                "the archer must find the raider through its OWN goal (never "
                    + "setTarget from the test), got " + archer.getTarget()))
            .thenExecute(() -> {
                // Once self-acquisition is proven, a three-block-high wall
                // interrupts the current draw while leaving a real route
                // around either end. The Archer must physically reposition
                // to regain line of sight instead of freezing inside
                // PREFER_MAX. Building it before acquisition would test the
                // intentional initial-visibility predicate instead.
                // Recovery may finish at a different grounded point inside
                // Stand on each navigation run. Centre the finite cover on
                // the actual sight line, rather than assuming the Archer
                // returned to a particular x/z before self-acquisition.
                double dx = pell.getX() - archer.getX();
                double dz = pell.getZ() - archer.getZ();
                helper.assertTrue(Math.max(Math.abs(dx), Math.abs(dz)) >= 5.0D,
                    "fixture must leave room for cover between the live combatants");
                BlockPos midpoint = BlockPos.containing(
                    archer.position().lerp(pell.position(), 0.5D));
                boolean spanZ = Math.abs(dx) >= Math.abs(dz);
                int floorY = Math.min(archer.blockPosition().getY(),
                    pell.blockPosition().getY());
                for (int y = floorY; y < floorY + 3; y++) {
                    for (int offset = -2; offset <= 2; offset++) {
                        BlockPos cover = new BlockPos(
                            midpoint.getX() + (spanZ ? 0 : offset), y,
                            midpoint.getZ() + (spanZ ? offset : 0));
                        helper.getLevel().setBlockAndUpdate(cover,
                            Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
                helper.assertTrue(!archer.hasLineOfSight(pell),
                    "fixture wall must interrupt the acquired Archer's line of sight");
                healthAtCover[0] = pell.getHealth();
                shotsAtCover[0] = goal.shotsFired();
            })
            .thenWaitUntil(() -> {
                int inChest = countOf(rack, Items.ARROW);
                helper.assertTrue(inChest + goal.quiverCount()
                        + goal.shotsFired() == 16,
                    "ammo conservation broke: chest " + inChest + " + quiver "
                        + goal.quiverCount() + " + loosed " + goal.shotsFired()
                        + " != the 16 the tower started with");
                helper.assertTrue(pell.getHealth() < healthAtCover[0]
                        && goal.shotsFired() > shotsAtCover[0],
                    "an archer that finds its own target must path around reachable "
                        + "cover and still hurt the raider"
                        + " (still " + pell.getHealth() + "/" + pellMax
                        + " after " + goal.shotsFired() + " volleys)");
            })
            .thenExecute(() -> {
                int inChest = countOf(rack, Items.ARROW);
                helper.assertTrue(inChest + goal.quiverCount()
                        + goal.shotsFired() == 16,
                    "final ammo conservation broke after the cover shot");
                // A new post excludes even the tower's expanded refill reach.
                // Set up physical ownership once; ordinary AI must then hold
                // without crossing the order or manufacturing ammunition.
                int returned = archer.takeArcherQuiverArrows(goal.quiverCount());
                rack.setItem(1, new ItemStack(Items.ARROW, inChest + returned));
                rack.setChanged();
                tower.bounds = BoundingBox.fromCorners(tower.anchor,
                    helper.absolutePos(new BlockPos(3, 4, 3)));
                BlockPos dryPost = helper.absolutePos(new BlockPos(13, 1, 13));
                GuardOrder order = s.guardOrders.order(archer.getUUID()).orElseThrow();
                helper.assertTrue(order.issueStand(dryPost, Direction.SOUTH, 2,
                        issuer, tower.id, helper.getLevel().getGameTime()),
                    "fixture must author the incompatible refill post");
                authoredRevision[0] = order.revision();
                archer.moveTo(dryPost.getX() + 0.5, dryPost.getY(), dryPost.getZ() + 0.5);
                archer.getNavigation().stop();
                // End the positive-shot fixture before the independent no-ammo phase.
                // Already released arrows remain counted as spent; retiring only
                // this archer's projectiles prevents a delayed earlier hit from
                // invalidating the new phase's live-threat precondition.
                for (var arrow : helper.getLevel().getEntitiesOfClass(
                        net.minecraft.world.entity.projectile.AbstractArrow.class,
                        new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                            net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16, 8, 16)))),
                        arrow -> arrow.getOwner() == archer)) {
                    arrow.discard();
                }
                pell.discard();
                RaiderEntity fresh = helper.spawn(ModEntities.RAIDER.get(),
                    new BlockPos(13, 1, 14));
                fresh.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
                fresh.setNoAi(true);
                excludedRackTarget[0] = fresh;
                helper.assertTrue(fresh.isAlive() && fresh.getHealth() == fresh.getMaxHealth(),
                    "negative ammo phase must begin with a fresh real live threat");
                shotsBeforeExcludedRack[0] = goal.shotsFired();
                phase[0] = 5;
            })
            .thenIdle(40)
            .thenExecute(() -> helper.assertTrue(goal.quiverCount() == 0
                    && goal.shotsFired() == shotsBeforeExcludedRack[0]
                    && excludedRackTarget[0].isAlive() && archer.getTarget() == excludedRackTarget[0]
                    && archer.getActivity() == com.hearthstead.entity.SettlerActivity.OUT_OF_AMMO
                    && archer.goalSelector.getAvailableGoals().stream()
                        .anyMatch(w -> w.getGoal() == goal && w.isRunning()),
                "an inaccessible rack must not override the post or invent arrows"
                    + "; quiver=" + goal.quiverCount() + "; shots=" + goal.shotsFired()
                    + "; shotsBefore=" + shotsBeforeExcludedRack[0]
                    + "; targetAlive=" + excludedRackTarget[0].isAlive()
                    + "; targetHealth=" + excludedRackTarget[0].getHealth()
                    + "; health=" + archer.getHealth()
                    + "; activity=" + archer.getActivity() + "; target=" + archer.getTarget()
                    + "; goalRunning=" + archer.goalSelector.getAvailableGoals().stream()
                        .anyMatch(w -> w.getGoal() == goal && w.isRunning())))
            .thenSucceed();
    }
}
