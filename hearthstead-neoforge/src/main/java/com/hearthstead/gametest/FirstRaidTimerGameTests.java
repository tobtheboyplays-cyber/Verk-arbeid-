package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidBossBarService;
import com.hearthstead.settlement.raid.RaidPresentation;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Isolated world authority, real spawning, and bounded scripted basic-sword defence. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FirstRaidTimerGameTests {
    private record Arena(Settlement settlement, HearthBlockEntity banner, long dayTime,
                         List<ChunkPos> forced, List<BlockPos> floors, List<LivingEntity> actors) {}

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "first_raid_timer_unprepared_defence")
    public void noGuardsBarracksOrFoodStillWarnsSpawnsAndResolves(GameTestHelper h) {
        h.runAfterDelay(20L - Math.floorMod(h.getLevel().getGameTime(), 20L), () -> {
        Arena a = arena(h);
        try {
            Settlement s = a.settlement();
            RaidBossBarService.tick(h.getLevel());
            int barsBefore = RaidBossBarService.activeBarCount(h.getLevel().getServer());
            CompoundTag journeyBefore = s.journeyState.writeNbt();
            h.assertTrue(s.buildings.isEmpty() && s.settlers.isEmpty()
                && java.util.stream.IntStream.range(0, a.banner().getInventory().getSlots())
                    .allMatch(slot -> a.banner().getInventory().getStackInSlot(slot).isEmpty()), "no guard, Barracks, food, or checklist fixture");
            h.assertTrue(!FirstRaidReadinessService.assessDomain(h.getLevel(), s).ready(),
                "explicit readiness remains unavailable");
            warn(h, a);
            RaidBossBarService.tick(h.getLevel());
            h.assertTrue(RaidPresentation.warningView(s).isPresent()
                && RaidDirector.firstWarningReceiptReady(s)
                && RaidBossBarService.activeBarCount(h.getLevel().getServer()) == barsBefore + 1,
                "exact town warning and scheduled native HUD bar have authority");
            h.assertTrue(s.raidLifecycle.firstAttackNight() - s.raidLifecycle.firstWarningNight() == 1,
                "one full in-game day before arrival");
            List<RaiderEntity> band = arrive(h, a);
            h.assertTrue(band.size() == HearthsteadServerConfig.firstRaidMinimalSize(), "configured small raid");
            RaidBossBarService.tick(h.getLevel());
            h.assertTrue(RaidBossBarService.activeBarCount(h.getLevel().getServer()) == barsBefore + 1,
                "small active roster retains the native raid bar");
            Player left = h.makeMockPlayer(GameType.SURVIVAL);
            Player right = h.makeMockPlayer(GameType.SURVIVAL);
            left.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
            right.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
            int hits = 0;
            for (RaiderEntity raider : band) {
                h.assertTrue(raider.isBandit() && raider.menace() == 1.0F
                    && raider.getMaxHealth() <= 24.0F, "only basic, unboosted bandits");
                // Script ordinary 5-damage stone-sword hits through the real damage/death hooks.
                for (int hit = 0; hit < 6 && raider.isAlive(); hit++) {
                    raider.invulnerableTime = 0;
                    raider.hurt(h.getLevel().damageSources().playerAttack(hit % 2 == 0 ? left : right), 5.0F);
                    hits++;
                }
                h.assertTrue(!raider.isAlive(), "basic sword defence can defeat every timer actor");
            }
            h.assertTrue(hits <= band.size() * 5 && s.raidLifecycle.allParticipantsTerminal(),
                "bounded basic damage closes actual participant deaths");
            RaidDirector.tick(h.getLevel(), s);
            h.assertTrue(s.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && s.pendingRaid == null && s.raidLog.size() == 1
                && RaidDirector.firstResolutionReceiptReady(s), "timer resolution unlocks normal recovery");
            RaidBossBarService.tick(h.getLevel());
            h.assertTrue(RaidBossBarService.activeBarCount(h.getLevel().getServer()) == barsBefore,
                "resolved timer raid removes its HUD bar");
            int coins = coins(a.banner());
            h.assertTrue(coins == 8, "one physical first-victory reward");
            s.raidLifecycle = RaidLifecycle.readNbt(s.raidLifecycle.writeNbt());
            RaidDirector.reconcileCompletedFirstRaid(h.getLevel(), s);
            h.assertTrue(coins(a.banner()) == coins && s.raidLog.size() == 1,
                "reload/reconciliation cannot duplicate the reward or aftermath");
            h.assertTrue(journeyBefore.equals(s.journeyState.writeNbt()),
                "timer warning, combat and rewards never forge player readiness evidence");
        } finally { close(h, a); }
        h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "first_raid_timer_ignored_budget")
    public void ignoredTimerRaidClosesAfterPersistedBudgetEvenWithoutDawn(GameTestHelper h) {
        h.runAfterDelay(20L - Math.floorMod(h.getLevel().getGameTime(), 20L), () -> {
        Arena a = arena(h);
        try {
            warn(h, a);
            List<RaiderEntity> band = arrive(h, a);
            Settlement s = a.settlement();
            Set<UUID> participants = new HashSet<>(s.raidLifecycle.participants());
            long dusk = h.getLevel().getDayTime();
            CompoundTag saved = s.raidLifecycle.writeNbt();
            long deadline = saved.getCompound("FirstRaidTimer").getLong("RetreatAtGameTime");
            h.assertTrue(deadline == h.getLevel().getGameTime() + RaidLifecycle.FIRST_TIMER_RAID_BUDGET_TICKS
                && !s.raidLifecycle.firstTimerRetreatDue(h.getLevel().getGameTime())
                && !s.raidLifecycle.recurringRetreatDue(dusk),
                "real start persists the complete game-time budget before either retreat is due");
            RaidDirector.tick(h.getLevel(), s);
            h.assertTrue(s.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                && band.stream().allMatch(LivingEntity::isAlive), "unexpired budget leaves the ignored raid active");
            // Simulate loading the same raid after its server-time budget elapsed; keep dusk unchanged.
            saved.getCompound("FirstRaidTimer").putLong("RetreatAtGameTime", h.getLevel().getGameTime());
            s.raidLifecycle = RaidLifecycle.readNbt(saved);
            h.assertTrue(!s.raidLifecycle.integrityLost() && band.stream().allMatch(LivingEntity::isAlive),
                "ignored actors and timer provenance survive reload");
            RaidDirector.tick(h.getLevel(), s);
            h.assertTrue(s.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && s.pendingRaid == null && band.stream().allMatch(LivingEntity::isRemoved)
                && h.getLevel().getDayTime() == dusk,
                "persisted budget ends the ignored siege without advancing to dawn");
            // allParticipantsTerminal() is an ACTIVE-state completion gate, not a completed-raid query.
            h.assertTrue(!participants.isEmpty() && !s.raidLifecycle.integrityLost()
                && s.raidLifecycle.participants().equals(participants)
                && s.raidLifecycle.terminalParticipants().equals(participants)
                && s.raidLifecycle.firstRaidTerminal().isPresent(),
                "completed raid preserves every actual participant and its terminal receipt");
            h.assertTrue(coins(a.banner()) == 0 && !s.raidLifecycle.mayGrantReward()
                && RaidDirector.firstResolutionReceiptReady(s)
                && s.raidLifecycle.recurringNextWarningNight() >= 0,
                "retreat pays nothing and permits ordinary recurring recovery");
            var terminalReceipt = s.raidLifecycle.firstRaidTerminal().orElseThrow();
            s.raidLifecycle = RaidLifecycle.readNbt(s.raidLifecycle.writeNbt());
            RaidDirector.reconcileCompletedFirstRaid(h.getLevel(), s);
            h.assertTrue(!s.raidLifecycle.integrityLost()
                && s.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && s.raidLifecycle.participants().equals(participants)
                && s.raidLifecycle.terminalParticipants().equals(participants)
                && s.raidLifecycle.firstRaidTerminal().orElseThrow().equals(terminalReceipt)
                && RaidDirector.firstResolutionReceiptReady(s)
                && s.raidLog.size() == 1 && coins(a.banner()) == 0
                && s.raidCoinRewards.pending() == 0 && s.blessingState.earned() == 0
                && !s.raidLifecycle.mayGrantReward(),
                "completed retreat reload retains terminal evidence without issuing any reward");
        } finally { close(h, a); }
        h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "first_raid_timer_actual_defenders")
    public void armedDefendersIncreasePersistedSizeButAgeAndCiviliansDoNot(GameTestHelper h) {
        h.runAfterDelay(20L - Math.floorMod(h.getLevel().getGameTime(), 20L), () -> {
        Arena a = arena(h);
        try {
            Settlement s = a.settlement();
            int basic = RaidDirector.firstTimerBandSize(h.getLevel(), s);
            SettlerEntity guard = ModEntities.SETTLER.get().create(h.getLevel());
            h.assertTrue(guard != null, "guard entity");
            guard.moveTo(s.center.getX() + 2.5, s.center.getY(), s.center.getZ() + 0.5, 0, 0);
            guard.bindTo(s.id, s.center);
            h.getLevel().addFreshEntity(guard);
            a.actors().add(guard);
            s.putRecord(guard.getUUID(), "Defender", Profession.NONE);
            h.assertTrue(RaidDirector.firstTimerBandSize(h.getLevel(), s) == basic, "civilian adds no pressure");
            guard.assignProfession(Profession.GUARD);
            h.assertTrue(RaidDirector.firstTimerBandSize(h.getLevel(), s) == basic, "unarmed guard adds no pressure");
            guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
            h.assertTrue(RaidDirector.firstTimerBandSize(h.getLevel(), s) == basic + 1, "real armed guard scales size");
            warn(h, a);
            s.raidLifecycle = RaidLifecycle.readNbt(s.raidLifecycle.writeNbt());
            List<RaiderEntity> band = arrive(h, a);
            h.assertTrue(band.size() == basic + 1 && band.stream().allMatch(RaiderEntity::isBandit),
                "scaled timer spawn uses its persisted all-bandit roster");
        } finally { close(h, a); }
        h.succeed();
        });
    }

    private static void warn(GameTestHelper h, Arena a) {
        Settlement s = a.settlement();
        long night = s.raidLifecycle.foundedNight() + HearthsteadServerConfig.firstRaidAutoDays() - 1L;
        h.getLevel().setDayTime(night * 24_000L + 13_000L);
        RaidDirector.tick(h.getLevel(), s);
        h.assertTrue(s.raidLifecycle.isTimerScheduled() && s.raidLifecycle.queuedPlan().isPresent()
            && s.raidLifecycle.timerWarningPresented(), "timer fires through real director tick");
    }

    private static List<RaiderEntity> arrive(GameTestHelper h, Arena a) {
        Settlement s = a.settlement();
        h.getLevel().setDayTime(s.raidLifecycle.firstAttackNight() * 24_000L + 13_000L);
        RaidDirector.tick(h.getLevel(), s);
        List<RaiderEntity> band = new ArrayList<>();
        for (UUID id : s.raidLifecycle.participants()) {
            if (h.getLevel().getEntity(id) instanceof RaiderEntity raider) band.add(raider);
        }
        a.actors().addAll(band);
        h.assertTrue(s.raidLifecycle.firstState() == FirstRaidState.ACTIVE
            && s.raidLifecycle.participantRosterTracked() && !band.isEmpty()
            && s.raidLifecycle.timerSunsetPresented(), "real small band spawns and seals after sunset warning");
        return band;
    }

    private static Arena arena(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(new BlockPos(8, 120, 8));
        Settlement s = new Settlement(UUID.randomUUID(), "Timerwatch", c);
        s.radius = 4;
        s.journeyState = JourneyState.fresh(s.id);
        long dayTime = level.getDayTime();
        h.assertTrue(s.raidLifecycle.prepareAtFounding(Math.max(0L, dayTime / 24_000L), 2, 1,
            RaidProfile.BALANCED), "authentic preparing calendar");
        SettlementSavedData.get(level).settlements.put(s.id, s);
        int reach = RaidDirector.spawnMaxDistance(s.radius) + RaidDirector.CAPTAIN_EXTRA_REACH + 2;
        Set<Long> prior = new HashSet<>(level.getForcedChunks());
        List<ChunkPos> forced = new ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(c.getX() - reach); x <= SectionPos.blockToSectionCoord(c.getX() + reach); x++) {
            for (int z = SectionPos.blockToSectionCoord(c.getZ() - reach); z <= SectionPos.blockToSectionCoord(c.getZ() + reach); z++) {
                ChunkPos cp = new ChunkPos(x, z);
                if (!prior.contains(cp.toLong())) { level.setChunkForced(x, z, true); forced.add(cp); }
            }
        }
        List<BlockPos> floors = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
            if (dx * dx + dz * dz > reach * reach) continue;
            BlockPos floor = c.offset(dx, -1, dz);
            level.setBlock(floor, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            floors.add(floor);
        }
        level.setBlock(c, ModBlocks.HEARTH.get().defaultBlockState(), 2);
        HearthBlockEntity banner = (HearthBlockEntity) level.getBlockEntity(c);
        banner.bindSettlement(s.id);
        return new Arena(s, banner, dayTime, forced, floors, new ArrayList<>());
    }

    private static int coins(HearthBlockEntity banner) {
        int count = 0;
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
            ItemStack stack = banner.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) count += stack.getCount();
        }
        return count;
    }

    private static void close(GameTestHelper h, Arena a) {
        for (LivingEntity actor : a.actors()) if (!actor.isRemoved()) actor.discard();
        h.getLevel().setBlock(a.settlement().center, Blocks.AIR.defaultBlockState(), 2);
        for (BlockPos pos : a.floors()) h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (ChunkPos cp : a.forced()) h.getLevel().setChunkForced(cp.x, cp.z, false);
        SettlementSavedData.get(h.getLevel()).settlements.remove(a.settlement().id);
        RaidBossBarService.tick(h.getLevel());
        h.getLevel().setDayTime(a.dayTime());
    }
}
