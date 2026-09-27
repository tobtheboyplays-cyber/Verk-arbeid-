package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.event.worldevent.PackWolfEntity;
import com.hearthstead.event.worldevent.WildBoarEntity;
import com.hearthstead.event.worldevent.WorldEventActors;
import com.hearthstead.event.worldevent.WorldEventDirector;
import com.hearthstead.event.worldevent.WorldEventEntities;
import com.hearthstead.event.worldevent.WorldEventSavedData;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Regression tests for bugs found by the background bug hunter
 * (plan/BUGHUNT-LOG.md). Every batch is prefixed {@code bughunt_}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class BughuntGameTests {
    private static final String ARENA = "empty64";

    /**
     * BH-08: the world-event wolves and boar are Monsters (so Guards fight
     * them), but vanilla refuses a bed when any Monster within 8x5x8 of it
     * answers isPreventingPlayerRest. A roaming pack or a boar rooting the
     * fields near the houses must never keep co-op players from sleeping.
     * The query below is exactly vanilla's Player#startSleepInBed check; a
     * plain Zombie proves the check itself still bites.
     */
    @GameTest(template = ARENA, batch = "bughunt_event_sleep", timeoutTicks = 60)
    public void eventCreaturesNeverBlockPlayerSleep(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 3; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos bed = h.absolutePos(new BlockPos(8, 1, 8));
        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(bed.getX() + .5, bed.getY(), bed.getZ() + 1.5);

        PackWolfEntity wolf = h.spawn(WorldEventEntities.PACK_WOLF.get(), new BlockPos(5, 1, 8));
        WildBoarEntity boar = h.spawn(WorldEventEntities.WILD_BOAR.get(), new BlockPos(11, 1, 8));
        h.assertTrue(wolf instanceof Monster && boar instanceof Monster,
            "event creatures stay Monsters so Guards engage them");

        Vec3 at = Vec3.atBottomCenterOf(bed);
        AABB vanillaBox = new AABB(at.x - 8.0D, at.y - 5.0D, at.z - 8.0D,
            at.x + 8.0D, at.y + 5.0D, at.z + 8.0D);
        List<Monster> blockers = h.getLevel().getEntitiesOfClass(Monster.class, vanillaBox,
            monster -> monster.isPreventingPlayerRest(player));
        h.assertTrue(!blockers.contains(wolf), "a Night Wolf beside the bed must not block sleep");
        h.assertTrue(!blockers.contains(boar), "a Wild Boar beside the bed must not block sleep");

        Zombie zombie = h.spawn(EntityType.ZOMBIE, new BlockPos(8, 1, 5));
        List<Monster> withZombie = h.getLevel().getEntitiesOfClass(Monster.class, vanillaBox,
            monster -> monster.isPreventingPlayerRest(player));
        h.assertTrue(withZombie.contains(zombie), "control: an ordinary zombie still blocks sleep");

        wolf.discard();
        boar.discard();
        zombie.discard();
        h.succeed();
    }

    // ------------------------------------- BH-16 conserved without doTileDrops --

    private static int itemsNear(GameTestHelper h, BlockPos rel, net.minecraft.world.item.Item item) {
        BlockPos at = h.absolutePos(rel);
        int n = 0;
        for (net.minecraft.world.entity.item.ItemEntity entity : h.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, new AABB(at).inflate(3.0D))) {
            if (entity.getItem().is(item)) n += entity.getItem().getCount();
        }
        return n;
    }

    /**
     * BH-16 (Codex P2): with {@code doTileDrops=false}, Block.popResource does
     * nothing. A guard stripped of armour with no armoury, warehouse or
     * Banner to take it used to lose the piece outright. The last-resort
     * spill must place the REAL item whatever the gamerule says.
     */
    @GameTest(template = ARENA, batch = "bughunt_item_conservation", timeoutTicks = 60)
    public void strippedArmourIsConservedWithTileDropsOff(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 3; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos rel = new BlockPos(8, 1, 8);
        com.hearthstead.entity.SettlerEntity settler = h.spawn(
            com.hearthstead.registry.ModEntities.SETTLER.get(), rel);
        settler.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        settler.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
        var rule = h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS);
        boolean before = rule.get();
        try {
            rule.set(false, h.getLevel().getServer());
            // Unbound settler: no armoury, no warehouse, no Banner -> the last resort.
            com.hearthstead.entity.GuardRank.clearEquipment(settler);
            com.hearthstead.util.ItemSpill.conserve(h.getLevel(), h.absolutePos(rel),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 3));
        } finally {
            rule.set(before, h.getLevel().getServer());
        }
        h.assertTrue(settler.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty(),
            "the chestplate left the slot");
        h.succeedWhen(() -> {
            h.assertTrue(itemsNear(h, rel, net.minecraft.world.item.Items.IRON_CHESTPLATE) == 1,
                "the chestplate still exists in the world");
            h.assertTrue(itemsNear(h, rel, net.minecraft.world.item.Items.IRON_HELMET) == 1,
                "the helmet still exists in the world");
            h.assertTrue(itemsNear(h, rel, net.minecraft.world.item.Items.BREAD) == 3,
                "ItemSpill placed all 3 bread");
            settler.discard();
        });
    }

    /**
     * BH-18 (Codex P2): the deferred-drop retry used to restart at the first
     * pending row every tick, so eight rows in unloaded chunks spent the whole
     * budget forever and a loadable row behind them never appeared.
     */
    @GameTest(template = ARENA, batch = "bughunt_item_conservation", timeoutTicks = 200)
    public void deferredDropBehindUnloadedRowsStillMaterializes(GameTestHelper h) {
        var level = h.getLevel();
        var ledger = com.hearthstead.settlement.DeferredItemMaterializationSavedData.get(level);
        java.util.List<java.util.UUID> far = new java.util.ArrayList<>();
        for (int i = 0; i < com.hearthstead.settlement.DeferredItemMaterializationSavedData.RETRIES_PER_TICK + 2; i++) {
            java.util.UUID id = ledger.queue(level, 900_000.5D + i * 64, 70.0D, 900_000.5D,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PRISMARINE_SHARD));
            h.assertTrue(id != null, "queued an unloaded row");
            far.add(id);
        }
        BlockPos rel = new BlockPos(8, 2, 8);
        BlockPos at = h.absolutePos(rel);
        h.assertTrue(ledger.queue(level, at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NAUTILUS_SHELL)) != null,
            "queued a loaded row behind them");
        h.succeedWhen(() -> {
            h.assertTrue(itemsNear(h, rel, net.minecraft.world.item.Items.NAUTILUS_SHELL) == 1,
                "the loadable row materialized despite 10 unloaded rows ahead of it");
            for (java.util.UUID id : far) ledger.cancel(id); // leave no test rows behind
            for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, new AABB(at).inflate(3.0D))) {
                if (e.getItem().is(net.minecraft.world.item.Items.NAUTILUS_SHELL)) e.discard();
            }
        });
    }

    /**
     * BH-16 follow-up: when the ledger AND the spawn both refuse, ItemSpill
     * still owns the stack (a retried backlog), so a caller that already
     * cleared its source never loses the item.
     */
    @GameTest(template = ARENA, batch = "bughunt_item_conservation", timeoutTicks = 100)
    public void refusedSpillIsHeldAndPlacedLater(GameTestHelper h) {
        BlockPos rel = new BlockPos(4, 2, 4);
        int before = com.hearthstead.util.ItemSpill.backlog();
        boolean placed;
        try {
            com.hearthstead.util.ItemSpill.refuseForTests(true);
            placed = com.hearthstead.util.ItemSpill.conserve(h.getLevel(), h.absolutePos(rel),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HEART_OF_THE_SEA));
        } finally {
            com.hearthstead.util.ItemSpill.refuseForTests(false);
        }
        h.assertTrue(!placed, "the refusal branch was taken");
        h.assertTrue(com.hearthstead.util.ItemSpill.backlog() == before + 1, "the stack is held, not dropped");
        h.succeedWhen(() -> {
            h.assertTrue(itemsNear(h, rel, net.minecraft.world.item.Items.HEART_OF_THE_SEA) == 1,
                "the held stack was placed on a later tick");
        });
    }

    // ------------------------------------ J-02 / J-04 / J-08 battle roles --

    private static Settlement roleArena(GameTestHelper h, String name) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        com.hearthstead.entity.combat.role.RoleCombat.overrideEnabledForTests(null);
        Settlement s = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(16, 1, 16)));
        s.radius = 16;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        return s;
    }

    private static com.hearthstead.entity.SettlerEntity roleSettler(GameTestHelper h, Settlement s,
                                                                  Profession profession, BlockPos at) {
        com.hearthstead.entity.SettlerEntity settler = h.spawn(
            com.hearthstead.registry.ModEntities.SETTLER.get(), at);
        settler.setSettlerName(profession.key());
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), profession.key(), profession);
        settler.assignProfession(profession);
        settler.setHunger(80.0F);
        settler.setNoAi(true);
        return settler;
    }

    /** Puts {@code soldier} on the watch that covers the current phase (index 0 = day, 1 = night). */
    private static void onCurrentWatch(com.hearthstead.settlement.Building post,
                                       com.hearthstead.entity.SettlerEntity soldier) {
        com.hearthstead.settlement.DayPhase phase = soldier.dayPhase();
        boolean dayWatchCovers = phase.work() || phase.meal()
            || phase == com.hearthstead.settlement.DayPhase.EVENING;
        if (!dayWatchCovers) {
            post.workers.add(UUID.randomUUID());
        }
        post.workers.add(soldier.getUUID());
    }

    /**
     * J-02: Spearman, Longswordsman and Rune Mage stand the Guards' watch
     * between fights (patrol rounds on the shared rota) instead of strolling.
     */
    @GameTest(template = ARENA, batch = "bughunt_roles_duty", timeoutTicks = 40)
    public void battleRolesWalkTheGuardRoundsOnTheirWatch(GameTestHelper h) {
        Settlement s = roleArena(h, "Dutyholm");
        Profession[] roles = {Profession.SPEARMAN, Profession.LONGSWORDSMAN, Profession.RUNE_MAGE};
        for (int i = 0; i < roles.length; i++) {
            com.hearthstead.entity.SettlerEntity soldier = roleSettler(h, s, roles[i], new BlockPos(4 + i * 8, 1, 26));
            com.hearthstead.building.BuildingType hall = new com.hearthstead.building.BuildingType[] {
                com.hearthstead.building.BuildingType.PIKE_YARD, com.hearthstead.building.BuildingType.SWORD_HALL,
                com.hearthstead.building.BuildingType.RUNE_HALL}[i];
            com.hearthstead.settlement.Building post = GameTestFixtures.register(h, s, hall, 2 + i * 8, 2);
            com.hearthstead.settlement.DayPhase phase = soldier.dayPhase();
            boolean dayWatchCovers = phase.work() || phase.meal()
                || phase == com.hearthstead.settlement.DayPhase.EVENING;
            if (!dayWatchCovers) {
                post.workers.add(UUID.randomUUID()); // index 1 = the night watch
            }
            post.workers.add(soldier.getUUID());
            h.assertTrue(com.hearthstead.settlement.Schedule.onWatch(s, soldier, phase),
                "fixture: " + roles[i] + " is on the current watch (" + phase + ")");
            h.assertTrue(new com.hearthstead.entity.ai.GuardPatrolGoal(soldier).canUse(),
                roles[i] + " walks the Guards' rounds on its watch");
        }
        SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        h.succeed();
    }

    /**
     * J-04: the Healer sleeps on the civilian clock, but an alarm (or an
     * active raid) keeps it on its feet, wakes it, and sends it to the fight;
     * the battle roles answer the alarm too (they hold no Guard Orders).
     */
    @GameTest(template = ARENA, batch = "bughunt_roles_healer_alarm", timeoutTicks = 40)
    public void alarmWakesTheHealerAndCallsTheBattleRoles(GameTestHelper h) {
        Settlement s = roleArena(h, "Alarmholm");
        com.hearthstead.entity.SettlerEntity healer = roleSettler(h, s, Profession.HEALER, new BlockPos(8, 1, 8));
        com.hearthstead.entity.SettlerEntity spear = roleSettler(h, s, Profession.SPEARMAN, new BlockPos(10, 1, 8));
        spear.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(com.hearthstead.registry.RoleItems.IRON_SPEAR.get()));
        // Each battle trade is employed by its OWN hall (the one validation path).
        GameTestFixtures.register(h, s, com.hearthstead.building.BuildingType.INFIRMARY, 2, 2)
            .workers.add(healer.getUUID());
        GameTestFixtures.register(h, s, com.hearthstead.building.BuildingType.PIKE_YARD, 10, 2)
            .workers.add(spear.getUUID());
        com.hearthstead.entity.SettlerEntity farmer = roleSettler(h, s, Profession.FARMER, new BlockPos(12, 1, 8));
        farmer.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HOE));
        GameTestFixtures.register(h, s, com.hearthstead.building.BuildingType.FARMHOUSE, 18, 2)
            .workers.add(farmer.getUUID());
        healer.setEnergy(5.0F); // exhausted: RestAtNight wants it in bed whatever the hour
        com.hearthstead.entity.ai.RestAtNightGoal rest = new com.hearthstead.entity.ai.RestAtNightGoal(healer);
        h.assertTrue(rest.canUse(), "control: an exhausted healer rests while all is quiet");
        // Near and in the open: the continue check requires a live path, and a
        // long route can be deferred by the shared per-tick search budget
        // when parallel tests plan at the same tick (W7a 08:00).
        s.alertPos = h.absolutePos(new BlockPos(16, 1, 12));
        s.alertUntilGameTime = h.getLevel().getGameTime() + 400L;
        h.assertTrue(!rest.canUse(), "an alarm keeps the healer out of bed");
        h.assertTrue(!rest.canContinueToUse(), "an alarm wakes a resting healer");
        h.assertTrue(new com.hearthstead.entity.ai.GuardRespondToAlertGoal(healer).canUse(),
            "the healer answers the alarm");
        h.assertTrue(new com.hearthstead.entity.ai.GuardRespondToAlertGoal(spear).canUse(),
            "an armed spearman from its Pike Yard answers the alarm");
        // Civilians never pass the battle-trade validation, alarm or orders.
        h.assertTrue(com.hearthstead.settlement.guard.GuardAssignmentService.validate(h.getLevel(), s, farmer, true)
                .reason() == com.hearthstead.settlement.guard.GuardAssignmentService.InvalidReason.WRONG_PROFESSION,
            "a Farmer is rejected as WRONG_PROFESSION");
        h.assertTrue(!new com.hearthstead.entity.ai.GuardRespondToAlertGoal(farmer).canUse(),
            "a Farmer does not answer the alarm");
        // One current-role rule, rechecked every tick: a role switch or the
        // battle-roles switch mid-run stops the response at once.
        com.hearthstead.entity.ai.GuardRespondToAlertGoal run = new com.hearthstead.entity.ai.GuardRespondToAlertGoal(spear);
        h.assertTrue(run.canUse(), "the spearman starts for the alarm");
        com.hearthstead.entity.path.RoadNavigation.resetExpandedBudgetForTests(h.getLevel());
        // A fresh NoAI mob never ticks physics, so it is never onGround and
        // ground navigation refuses to plan (path null, W8a). It stands on
        // the arena floor: say so, as a live settler would be.
        spear.setOnGround(true);
        run.start();
        h.assertTrue(run.canContinueToUse(), "and keeps going while nothing changes (path "
            + spear.getNavigation().getPath() + ")");
        try {
            com.hearthstead.entity.combat.role.RoleCombat.overrideEnabledForTests(false);
            h.assertTrue(!run.canContinueToUse(), "battle roles switched off mid-run: it stands down");
        } finally {
            com.hearthstead.entity.combat.role.RoleCombat.overrideEnabledForTests(null);
        }
        h.assertTrue(run.canContinueToUse(), "switched back on: it may continue");
        spear.assignProfession(Profession.FARMER);
        h.assertTrue(!run.canContinueToUse(), "re-trained as a Farmer mid-run: it stops at once");
        s.alertUntilGameTime = 0L;
        SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        h.succeed();
    }

    /**
     * J-08: the Rune Mage fetches rune stones from its Rune Hall chest when
     * it runs low (nothing used to supply them), chest-true: the stones move,
     * none are created.
     */
    @GameTest(template = ARENA, batch = "bughunt_roles_rune_supply", timeoutTicks = 200)
    public void runeMageRestocksStonesFromItsHall(GameTestHelper h) {
        Settlement s = roleArena(h, "Runeholm");
        com.hearthstead.entity.combat.role.RoleWorld.resetForTests(h.getLevel().getServer());
        com.hearthstead.settlement.Building hall = GameTestFixtures.register(h, s,
            com.hearthstead.building.BuildingType.RUNE_HALL, 20, 20);
        BlockPos chestRel = new BlockPos(21, 1, 21);
        h.setBlock(chestRel, Blocks.CHEST);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(chestRel))
            .setItem(0, new net.minecraft.world.item.ItemStack(com.hearthstead.registry.RoleItems.RUNE_STONE.get(), 6));
        com.hearthstead.entity.SettlerEntity mage = roleSettler(h, s, Profession.RUNE_MAGE, new BlockPos(20, 1, 22));
        onCurrentWatch(hall, mage);
        mage.setEnergy(100.0F);
        com.hearthstead.entity.combat.role.RuneMageGoal goal = new com.hearthstead.entity.combat.role.RuneMageGoal(mage);
        h.assertTrue(goal.stonesCarried() == 0, "starts with no stones");
        boolean[] running = {false};
        h.onEachTick(() -> {
            if (!running[0]) {
                // The container index may need a tick to see the chest.
                if (com.hearthstead.settlement.warehouse.WarehouseIndex.containers(h.getLevel(), hall).isEmpty()) {
                    return;
                }
                running[0] = goal.canUse();
                if (running[0]) goal.start();
                return;
            }
            if (goal.canContinueToUse()) goal.tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(mage.getOffhandItem().is(com.hearthstead.registry.RoleItems.RUNE_STONE.get())
                && mage.getOffhandItem().getCount() == 6, "the mage carries the hall's 6 stones, has "
                + mage.getOffhandItem());
            h.assertTrue(((net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(chestRel))
                .getItem(0).isEmpty(), "the stones left the chest (moved, not copied)");
            SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        });
    }

    /**
     * Codex P2 on J-08: the peacetime restock must never pre-empt sleep or
     * critical rest (the goal outranks RestAtNightGoal), while a real threat
     * still makes an exhausted mage fight.
     */
    @GameTest(template = ARENA, batch = "bughunt_roles_rune_supply", timeoutTicks = 100)
    public void exhaustedMageDoesNotRestockButStillFights(GameTestHelper h) {
        Settlement s = roleArena(h, "Weariholm");
        com.hearthstead.entity.combat.role.RoleWorld.resetForTests(h.getLevel().getServer());
        com.hearthstead.settlement.Building hall = GameTestFixtures.register(h, s,
            com.hearthstead.building.BuildingType.RUNE_HALL, 20, 20);
        BlockPos chestRel = new BlockPos(21, 1, 21);
        h.setBlock(chestRel, Blocks.CHEST);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(chestRel))
            .setItem(0, new net.minecraft.world.item.ItemStack(com.hearthstead.registry.RoleItems.RUNE_STONE.get(), 6));
        com.hearthstead.entity.SettlerEntity mage = roleSettler(h, s, Profession.RUNE_MAGE, new BlockPos(20, 1, 22));
        onCurrentWatch(hall, mage);
        mage.setEnergy(5.0F);
        com.hearthstead.entity.combat.role.RuneMageGoal goal = new com.hearthstead.entity.combat.role.RuneMageGoal(mage);
        GameTestTicks.at(h, 10L, () -> {
            h.assertTrue(!com.hearthstead.settlement.warehouse.WarehouseIndex.containers(h.getLevel(), hall).isEmpty(),
                "fixture: the hall's chest is indexed");
            h.assertTrue(!goal.canUse(), "an exhausted mage does not walk off for rune stones");
            com.hearthstead.entity.RaiderEntity raider = h.spawn(com.hearthstead.registry.ModEntities.RAIDER.get(),
                new BlockPos(24, 1, 22));
            raider.assign(UUID.randomUUID(), s.id, com.hearthstead.settlement.raid.RaidObjective.BLOD, 1.0F, false);
            raider.setNoAi(true);
            h.runAfterDelay(30L, () -> { // relative: the first test tick may already be past 10
                h.assertTrue(goal.canUse(), "with a raider in range the exhausted mage still fights");
                raider.discard();
                SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
                h.succeed();
            });
        });
    }

    // ------------------------------------------------ BH-12 orphaned events --

    /** A cleared arena with one small settlement whose Banner sits at (32,1,32). */
    private static Settlement eventVillage(GameTestHelper h, String name) {
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 24;
        for (int i = 0; i < 3; i++) {
            s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        }
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        WorldEventDirector.resetTransientForTests();
        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = h.absolutePos(new BlockPos(32, 1, 38));
        player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return s;
    }

    /**
     * Owner rule (26 Sep): an ending event never puffs its visitors away. They
     * walk out through conversation.Departure and despawn once unseen (or after
     * the give-up cap). Either way they are no longer event actors.
     */
    private static boolean leftOrLeaving(Entity visitor) {
        return visitor.isRemoved() || com.hearthstead.conversation.Departure.isDeparting(visitor)
            && !visitor.getPersistentData().contains(WorldEventDirector.TAG);
    }

    private static WorldEventSavedData.Active activeOf(GameTestHelper h, Settlement s) {
        WorldEventSavedData.Row row = WorldEventSavedData.get(h.getLevel()).row(s.id);
        return row == null ? null : row.active;
    }

    /**
     * BH-12 (Codex P2): breaking the Banner while a visitor event runs must end
     * that event and remove its visitors at once, not leave a peddler and
     * llama standing forever with a timeout that never advances.
     */
    @GameTest(template = ARENA, batch = "bughunt_event_orphan", timeoutTicks = 100)
    public void breakingTheBannerEndsItsRunningEvent(GameTestHelper h) {
        Settlement s = eventVillage(h, "Orphanholm");
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true),
            "the peddler event starts");
        WorldEventSavedData.Active active = activeOf(h, s);
        h.assertTrue(active != null, "an active event row exists");
        List<Entity> visitors = WorldEventActors.actors(h.getLevel(), active);
        h.assertTrue(!visitors.isEmpty(), "the peddler and llama are loaded");

        SettlementManager.disbandAt(h.getLevel(), s.center);

        h.assertTrue(!SettlementSavedData.get(h.getLevel()).settlements.containsKey(s.id),
            "the settlement is gone");
        h.assertTrue(activeOf(h, s) == null, "its event ended with it");
        for (Entity visitor : visitors) {
            h.assertTrue(leftOrLeaving(visitor), "visitor left or walking out with the settlement: " + visitor.getType());
        }
        h.succeed();
    }

    /**
     * BH-12: a settlement row that vanishes WITHOUT the Banner hook (save
     * edit, another mod, a quarantine) is still swept by the periodic orphan
     * pass, which must run even when no settlement is left to observe.
     */
    @GameTest(template = ARENA, batch = "bughunt_event_orphan", timeoutTicks = 1400)
    public void orphanSweepEndsAnEventWhoseSettlementVanished(GameTestHelper h) {
        Settlement s = eventVillage(h, "Sweepholm");
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true),
            "the peddler event starts");
        WorldEventSavedData.Active active = activeOf(h, s);
        List<Entity> visitors = WorldEventActors.actors(h.getLevel(), active);
        h.assertTrue(!visitors.isEmpty(), "visitors are loaded");
        SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        h.succeedWhen(() -> {
            h.assertTrue(activeOf(h, s) == null, "the orphan sweep ended the event");
            for (Entity visitor : visitors) {
                h.assertTrue(leftOrLeaving(visitor), "orphaned visitor left or walking out: " + visitor.getType());
            }
        });
    }

    // ------------------------------------------------ miner_drops diagnosis --

    /**
     * BH-25 diagnosis for miner_drops (NO_VALID_TARGET with a pickaxe and a
     * rock in the dig area): the exact fixture, then the MineShaft model the
     * goal reads, reported column by column, now and after the plaque has
     * had time to re-link the building (which may move the anchor).
     */
    @GameTest(template = "empty16", batch = "bughunt_miner_diag", timeoutTicks = 200)
    public void minerShaftModelSeesTheFixtureRock(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.OAK_PLANKS);
                for (int y = 1; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos rockRel = new BlockPos(6, 0, 6);
        h.setBlock(rockRel, Blocks.STONE);
        SettlementSavedData data = SettlementSavedData.get(h.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Diagdal", h.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        com.hearthstead.settlement.Building mine = GameTestFixtures.register(h, s,
            com.hearthstead.building.BuildingType.MINE, 4, 4);
        h.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockPos rock = h.absolutePos(rockRel);
        String early = shaftReport(h, mine, rock);
        // The real goal, on a real hired Miner with a pickaxe, asked directly.
        com.hearthstead.entity.SettlerEntity miner = h.spawn(com.hearthstead.registry.ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        miner.setSettlerName("Diag");
        miner.bindTo(s.id, s.center);
        s.putRecord(miner.getUUID(), "Diag", Profession.NONE);
        boolean hired = com.hearthstead.settlement.Employment.hire(h.getLevel(), s, mine, miner).ok();
        miner.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        miner.setNoAi(true); // only the direct probe below decides
        h.getLevel().setDayTime(3000);
        boolean earlyUse = new com.hearthstead.entity.ai.MinerWorkGoal(miner).canUse();
        String earlyGoal = "hired=" + hired + " prof=" + miner.getProfession() + " canUse=" + earlyUse
            + " stop=" + miner.logisticsStopReason();
        GameTestTicks.at(h, 120, () -> {
            boolean lateUse = new com.hearthstead.entity.ai.MinerWorkGoal(miner).canUse();
            String late = shaftReport(h, mine, rock) + " | goal early[" + earlyGoal + "] late[canUse=" + lateUse
                + " stop=" + miner.logisticsStopReason() + " anchor=" + mine.anchor + " valid=" + mine.valid + "]";
            miner.discard();
            data.settlements.remove(s.id);
            h.assertTrue(early.startsWith("OK") && late.startsWith("OK") && earlyUse && lateUse,
                "early[" + early + "] late[" + late + "]");
            h.succeed();
        });
    }

    private static String shaftReport(GameTestHelper h, com.hearthstead.settlement.Building mine, BlockPos rock) {
        var level = h.getLevel();
        BlockPos anchor = mine.anchor;
        com.hearthstead.entity.ai.MineShaft.Model m = com.hearthstead.entity.ai.MineShaft.survey(level, anchor, 12);
        boolean[] before = com.hearthstead.entity.ai.MineShaft.reachable(m);
        int col = com.hearthstead.entity.ai.MineShaft.columnOf(anchor, rock);
        int seeds = 0;
        int reach = 0;
        for (int i = 0; i < before.length; i++) {
            if (m.depth[i] == 0 && m.open[i]) seeds++;
            if (before[i]) reach++;
        }
        if (col < 0) {
            return "NO_COLUMN anchor=" + h.relativePos(anchor) + " valid=" + mine.valid;
        }
        int depth = m.depth[col];
        BlockPos cut = new BlockPos(rock.getX(), m.surfaceY - depth, rock.getZ());
        boolean dig = com.hearthstead.entity.ai.MineShaft.diggable(level, cut);
        int after = com.hearthstead.entity.ai.MineShaft.depthAfterCut(level, m, cut, 12);
        boolean safe = com.hearthstead.entity.ai.MineShaft.safeToDeepen(m, col, after, before);
        boolean ok = before[col] && m.open[col] && com.hearthstead.entity.ai.MineShaft.inDigArea(col)
            && cut.equals(rock) && dig && safe;
        return (ok ? "OK" : "BAD") + " anchor=" + h.relativePos(anchor) + " surfaceY=" + (m.surfaceY - h.absolutePos(BlockPos.ZERO).getY())
            + " valid=" + mine.valid + " open=" + m.open[col] + " depth=" + depth + " reach=" + before[col]
            + " cutRel=" + h.relativePos(cut) + " dig=" + dig + " after=" + after + " safe=" + safe
            + " seeds=" + seeds + " reachable=" + reach + " below=" + level.getBlockState(rock.below())
            + " up1=" + level.getBlockState(rock.above()) + " up2=" + level.getBlockState(rock.above(2))
            + " loaded=" + level.isLoaded(rock.above()) + " pass1=" + com.hearthstead.entity.ai.MineShaft.passable(level, rock.above())
            + " pass2=" + com.hearthstead.entity.ai.MineShaft.passable(level, rock.above(2))
            + " anchorAbs=" + anchor + " rockAbs=" + rock;
    }

    // ------------------------------------------- T14 world-event switch off --

    /**
     * Codex T14: an event already running (here: restored from a save) stops
     * at the next observation once the master switch is off; its visitors
     * leave and the row records the "disabled" outcome.
     */
    @GameTest(template = ARENA, batch = "bughunt_event_switch_off", timeoutTicks = 100)
    public void masterSwitchOffEndsARestoredRunningEvent(GameTestHelper h) {
        Settlement s = eventVillage(h, "Offholm");
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true),
            "the peddler event starts");
        List<Entity> visitors = WorldEventActors.actors(h.getLevel(), activeOf(h, s));
        h.assertTrue(!visitors.isEmpty(), "visitors are loaded");
        // Restart: the saved data is written out and read back in.
        var level = h.getLevel();
        net.minecraft.nbt.CompoundTag saved = WorldEventSavedData.get(level).save(
            new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        level.getDataStorage().set("hearthstead_world_events",
            WorldEventSavedData.load(saved, level.registryAccess()));
        h.assertTrue(activeOf(h, s) != null, "the running event survived the reload");
        com.hearthstead.event.worldevent.WorldEventConfig.overrideMasterForTests(false);
        try {
            WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
            h.assertTrue(activeOf(h, s) == null, "switched off: the restored event ended");
            h.assertTrue(WorldEventSavedData.get(level).row(s.id).lastOutcome
                    .endsWith(":" + WorldEventDirector.DISABLED_OUTCOME),
                "outcome recorded as disabled: " + WorldEventSavedData.get(level).row(s.id).lastOutcome);
            for (Entity visitor : visitors) {
                h.assertTrue(leftOrLeaving(visitor), "visitor left or walking out: " + visitor.getType());
            }
        } finally {
            com.hearthstead.event.worldevent.WorldEventConfig.overrideMasterForTests(null);
            SettlementSavedData.get(level).settlements.remove(s.id);
        }
        h.succeed();
    }

    /** Codex T14: turning off only that event's own switch stops it too. */
    @GameTest(template = ARENA, batch = "bughunt_event_switch_off", timeoutTicks = 100)
    public void perEventSwitchOffEndsThatRunningEvent(GameTestHelper h) {
        Settlement s = eventVillage(h, "Typeholm");
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true),
            "the peddler event starts");
        var level = h.getLevel();
        WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
        h.assertTrue(activeOf(h, s) != null, "control: with its switch on the event keeps running");
        com.hearthstead.event.worldevent.WorldEventConfig.overrideTypeForTests(WorldEventType.PEDDLER, false);
        try {
            WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
            h.assertTrue(activeOf(h, s) == null, "its own switch off: the event ended");
        } finally {
            com.hearthstead.event.worldevent.WorldEventConfig.overrideTypeForTests(WorldEventType.PEDDLER, null);
            SettlementSavedData.get(level).settlements.remove(s.id);
        }
        h.succeed();
    }

    // ----------------------------------------- BH-29 crafter carry-back --

    /**
     * BH-29: a crafter whose fetched load cannot be stowed (bench full of
     * inputs) must still craft -- crafting is what frees room. Before the
     * fix the carry-back pre-empted crafting forever (a livelock).
     */
    @GameTest(template = "empty16", batch = "bughunt_crafter_full_bench", timeoutTicks = 1200)
    public void fullBenchNeverLivelocksTheCarryBack(GameTestHelper h) {
        com.hearthstead.settlement.economy.EconomyConfig.testOverride = Boolean.TRUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Fullbench", h.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        com.hearthstead.settlement.Building sawmill = GameTestFixtures.register(h, s,
            com.hearthstead.building.BuildingType.SAWMILL, 2, 2);
        BlockPos benchRel = new BlockPos(3, 1, 2);
        h.setBlock(benchRel, Blocks.CHEST);
        net.minecraft.world.Container bench = (net.minecraft.world.Container) h.getBlockEntity(benchRel);
        for (int slot = 0; slot < bench.getContainerSize() - 1; slot++) {
            bench.setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64));
        }
        bench.setItem(bench.getContainerSize() - 1,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 8));
        com.hearthstead.entity.SettlerEntity saw = h.spawn(com.hearthstead.registry.ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        saw.setSettlerName("Stuckny");
        saw.bindTo(s.id, s.center);
        s.putRecord(saw.getUUID(), "Stuckny", Profession.NONE);
        h.assertTrue(com.hearthstead.settlement.Employment.hire(h.getLevel(), s, sawmill, saw).ok(),
            "a sawmill must take a sawyer");
        // A load fetched earlier that the full bench cannot take.
        saw.bag.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 4));
        h.onEachTick(() -> h.getLevel().setDayTime(2000));
        h.succeedWhen(() -> {
            int planks = 0;
            for (int slot = 0; slot < bench.getContainerSize(); slot++) {
                if (bench.getItem(slot).is(net.minecraft.world.item.Items.OAK_PLANKS)) planks += bench.getItem(slot).getCount();
            }
            h.assertTrue(planks > 8, "the sawyer must keep sawing while its carried logs cannot be stowed (planks="
                + planks + " act=" + saw.getActivity() + ")");
            com.hearthstead.settlement.economy.EconomyConfig.testOverride = null;
            SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        });
    }

    // ---------------------------------------------- BH-30 dog stays home --

    /** BH-30: the village dog (and any event actor) never changes dimension. */
    @GameTest(template = "empty16", batch = "bughunt_event_no_portal", timeoutTicks = 20)
    public void villageDogAndEventActorsNeverTravelThroughPortals(GameTestHelper h) {
        net.minecraft.world.entity.animal.Wolf dog = h.spawn(EntityType.WOLF, new BlockPos(4, 1, 4));
        dog.getPersistentData().put(com.hearthstead.event.worldevent.VillageDog.TAG,
            new net.minecraft.nbt.CompoundTag());
        var dogTravel = new net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent(dog,
            net.minecraft.world.level.Level.NETHER);
        WorldEventDirector.travel(dogTravel);
        h.assertTrue(dogTravel.isCanceled(), "the village dog stays in its village's dimension");
        net.minecraft.world.entity.animal.Wolf wild = h.spawn(EntityType.WOLF, new BlockPos(8, 1, 8));
        var wildTravel = new net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent(wild,
            net.minecraft.world.level.Level.NETHER);
        WorldEventDirector.travel(wildTravel);
        h.assertTrue(!wildTravel.isCanceled(), "control: an ordinary wolf may still use a portal");
        dog.discard();
        wild.discard();
        h.succeed();
    }

    // -------------------------------------------- BH-31 panic path budget --

    /** A plain HOUSE whose floor is y=0 of these bounds; walled/roofed (no door) when sealed. */
    private static com.hearthstead.settlement.Building panicHouse(GameTestHelper h, Settlement s,
                                                                   int x0, int z0, boolean sealed) {
        if (sealed) {
            for (int x = x0; x <= x0 + 6; x++) for (int z = z0; z <= z0 + 6; z++) {
                boolean rim = x == x0 || z == z0 || x == x0 + 6 || z == z0 + 6;
                for (int y = 1; y <= 3; y++) if (rim) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
            }
        }
        BlockPos plaqueRel = new BlockPos(x0, 2, z0);
        GameTestFixtures.placePlaque(h, plaqueRel);
        com.hearthstead.settlement.Building house = new com.hearthstead.settlement.Building(UUID.randomUUID(),
            com.hearthstead.building.BuildingType.HOUSE, h.absolutePos(plaqueRel),
            h.absolutePos(new BlockPos(x0 + 3, 1, z0 + 3)),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                h.absolutePos(new BlockPos(x0, 0, z0)), h.absolutePos(new BlockPos(x0 + 6, 3, z0 + 6))));
        house.valid = true;
        s.buildings.add(house);
        return house;
    }

    /**
     * BH-31 (captain JFR: raid melee P99 764 ms, 88 % in SettlerPanicGoal
     * pathSafe): 30 civilians panic on one alarm with sealed (unreachable)
     * houses nearest and open ones further out. The panic goal's path queries
     * must stay within a per-tick budget (was: a full A* for every floor cell
     * of every house, per civilian, in one tick), and the village must still
     * reach cover.
     */
    @GameTest(template = ARENA, batch = "bughunt_panic_perf", timeoutTicks = 1000)
    public void aWholeVillagePanicsWithinAPathBudgetAndReachesCover(GameTestHelper h) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Panicholm", h.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 30;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        panicHouse(h, s, 20, 20, true);
        panicHouse(h, s, 38, 20, true);
        java.util.List<com.hearthstead.settlement.Building> open = List.of(
            panicHouse(h, s, 20, 42, false), panicHouse(h, s, 38, 42, false),
            panicHouse(h, s, 6, 29, false), panicHouse(h, s, 52, 29, false));
        java.util.List<com.hearthstead.entity.SettlerEntity> village = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            com.hearthstead.entity.SettlerEntity c = h.spawn(com.hearthstead.registry.ModEntities.SETTLER.get(),
                new BlockPos(24 + (i % 10) * 2, 1, 30 + (i / 10) * 2));
            c.setSettlerName("Villager" + i);
            c.bindTo(s.id, s.center);
            s.putRecord(c.getUUID(), "Villager" + i, Profession.NONE);
            village.add(c);
        }
        h.getLevel().setDayTime(3000);
        s.alertUntilGameTime = h.getLevel().getGameTime() + 5000L;
        long[] last = {com.hearthstead.entity.ai.SettlerPanicGoal.pathQueries()};
        long[] worst = {0L};
        long[] total = {0L};
        h.onEachTick(() -> {
            long now = com.hearthstead.entity.ai.SettlerPanicGoal.pathQueries();
            worst[0] = Math.max(worst[0], now - last[0]);
            total[0] += now - last[0];
            last[0] = now;
        });
        GameTestTicks.at(h, 700, () -> {
            int covered = 0;
            for (com.hearthstead.entity.SettlerEntity c : village) {
                for (com.hearthstead.settlement.Building b : open) {
                    if (b.bounds.isInside(c.blockPosition())) { covered++; break; }
                }
            }
            h.assertTrue(worst[0] <= 60, "panic path queries in one tick must stay within budget: worst="
                + worst[0] + " total=" + total[0]);
            h.assertTrue(covered >= 24, "the village must reach cover: " + covered + "/30 inside an open house"
                + " (worst tick " + worst[0] + ", total " + total[0] + ")");
            for (com.hearthstead.entity.SettlerEntity c : village) c.discard();
            s.alertUntilGameTime = 0L;
            SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
            h.succeed();
        });
    }

    // -------------------------------------------- toenail is not a meal --

    /** Item audit 26 Sep: the Troll Toenail trophy is edible but never settler food. */
    @GameTest(template = "empty16", batch = "bughunt_food_rules", timeoutTicks = 20)
    public void trollToenailIsNeverASettlerMeal(GameTestHelper h) {
        h.assertTrue(!com.hearthstead.settlement.ReadyFood.isReadyMeal(
                new net.minecraft.world.item.ItemStack(com.hearthstead.registry.ModItems.TROLL_TOENAIL.get())),
            "the Troll Toenail trophy must not count as (or be fed away as) a settler meal");
        h.assertTrue(com.hearthstead.settlement.ReadyFood.isReadyMeal(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD)),
            "control: bread is a meal");
        h.succeed();
    }

    // --------------------------------------------- QA #7 goblin grace --

    /**
     * Survival QA #7: a goblin thief showed up on day 1 of a brand-new
     * village. The natural thief now waits for the same early grace as the
     * hostile world events (WorldEventDirector.hostileReady): no thief in a
     * freshly founded village with no guard, even with Coins in a store.
     */
    @GameTest(template = ARENA, batch = "bughunt_goblin_grace", timeoutTicks = 40)
    public void aFreshVillageGetsNoGoblinThief(GameTestHelper h) {
        net.minecraft.server.level.ServerLevel level = h.getLevel();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearth = h.absolutePos(new BlockPos(16, 1, 16));
        level.setBlockAndUpdate(hearth, com.hearthstead.registry.ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance;
        Settlement fresh;
        try {
            com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance = true;
            fresh = com.hearthstead.settlement.SettlementManager.tryFound(level, hearth);
        } finally {
            com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(fresh != null, "fixture: founded");
        ((com.hearthstead.block.HearthBlockEntity) level.getBlockEntity(hearth)).bindSettlement(fresh.id);
        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(hearth.getX() + .5, hearth.getY(), hearth.getZ() + 3.5);
        // makeMockServerPlayerInLevel starts in creative, and creative players are never pickpocket victims.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().add(new net.minecraft.world.item.ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 3));
        h.assertTrue(!com.hearthstead.event.worldevent.WorldEventDirector.hostileReady(level, fresh),
            "a day-0 village with no guard is still in its hostile grace");
        h.assertTrue(com.hearthstead.event.GoblinThiefDemo.eligiblePickpocketVictim(player),
            "control: the player carries Coins and could be a victim");
        long before = level.getEntitiesOfClass(com.hearthstead.entity.RaiderEntity.class,
            new net.minecraft.world.phys.AABB(hearth).inflate(64)).size();
        for (int i = 0; i < 3; i++) com.hearthstead.event.GoblinTheftDirector.observe(level, fresh);
        long after = level.getEntitiesOfClass(com.hearthstead.entity.RaiderEntity.class,
            new net.minecraft.world.phys.AABB(hearth).inflate(64)).size();
        h.assertTrue(after == before, "no goblin thief may be published during the grace (" + before + " -> " + after + ")");
        for (var actor : com.hearthstead.settlement.SettlementManager.loadedMembers(level, fresh)) actor.discard();
        SettlementSavedData.get(level).settlements.remove(fresh.id);
        level.setBlockAndUpdate(hearth, Blocks.AIR.defaultBlockState());
        h.succeed();
    }
}
