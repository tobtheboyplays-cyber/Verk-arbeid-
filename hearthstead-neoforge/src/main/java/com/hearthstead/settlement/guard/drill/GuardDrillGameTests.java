package com.hearthstead.settlement.guard.drill;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guard Drill's morning drill (batch "guard_drill"): a fresh village's guards pair up after
 * breakfast and spar with no damage; an alarm ends the drill at once and they engage a raider;
 * guards on watch are never pulled into the yard unless a partner is needed and another guard
 * still keeps watch. The batch sets the clock to 07:25 and clear weather.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GuardDrillGameTests {
    private static final long MORNING = 1500L;

    public GuardDrillGameTests() {
    }

    private record Yard(Settlement settlement, Building barracks, List<SettlerEntity> guards) {
    }

    private static Yard village(GameTestHelper helper, int guards, boolean drillOwned) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        var level = helper.getLevel();
        level.setDayTime(MORNING);
        level.setWeatherParameters(6000, 0, false, false);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement s = new Settlement(UUID.randomUUID(), "Drillholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building barracks = GameTestFixtures.register(helper, s, BuildingType.BARRACKS, 2, 2);
        GuardDrillYard.forget(barracks.id);
        if (drillOwned) {
            com.hearthstead.settlement.development.TechTreeTestGrants.grant(Development.of(level, s), "guard_drill");
            helper.assertTrue(Development.hasUpgrade(level, s, PostRaidUpgrade.GUARD_DRILL), "fixture: Guard Drill owned");
        }
        List<SettlerEntity> out = new ArrayList<>();
        for (int i = 0; i < guards; i++) {
            SettlerEntity g = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3 + i * 2, 1, 9));
            g.setSettlerName("Drill " + i);
            g.bindTo(s.id, s.center);
            s.putRecord(g.getUUID(), g.getSettlerName(), Profession.NONE);
            helper.assertTrue(Employment.hire(level, s, barracks, g).ok(), "fixture: guard " + i + " hired");
            g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            g.setHunger(90.0F);
            g.setEnergy(80.0F);
            g.setHealth(g.getMaxHealth());
            out.add(g);
        }
        return new Yard(s, barracks, out);
    }

    private static int mode(SettlerEntity g) {
        return g.guardDrillCue().getInt("Mode");
    }

    private static boolean onWatch(Yard y, SettlerEntity g) {
        return Schedule.onWatch(y.settlement(), g, g.dayPhase());
    }

    private static String state(Yard y) {
        StringBuilder sb = new StringBuilder("members=").append(GuardDrillYard.members(y.settlement(), y.guards().get(0)))
            .append(' ').append(GuardDrillYard.debug(y.settlement() == null ? null
                : (net.minecraft.server.level.ServerLevel) y.guards().get(0).level(), y.settlement(), y.guards().get(0)));
        for (SettlerEntity g : y.guards()) {
            sb.append(" [").append(g.getSettlerName()).append(" watch=").append(onWatch(y, g))
                .append(" mode=").append(mode(g)).append(" act=").append(g.getActivity())
                .append(" hp=").append(g.getHealth()).append(" pos=").append(g.blockPosition())
                .append(" why=").append(GuardDrillYard.why(g.level() instanceof net.minecraft.server.level.ServerLevel l ? l
                    : null, y.settlement(), g, false))
                .append(" time=").append(g.level().getDayTime()).append(']');
        }
        return sb.toString();
    }

    /**
     * Captain's Sunday case: a fresh village with three guards (two on the day watch, one on the
     * night watch). The relieved night guard gets a drafted day guard as partner, the pair spars
     * with practice blows only, and the third guard keeps its watch.
     */
    @GameTest(template = "empty16", batch = "guard_drill", timeoutTicks = 600)
    public void freshVillageThreeGuardsPairUpAndSparWithoutDamage(GameTestHelper helper) {
        Yard y = village(helper, 3, true);
        // Health may still climb (rank health bar, well-fed regen); a practice blow would show
        // as a DROP between two ticks.
        float[] lastHealth = new float[y.guards().size()];
        String[] dropped = {null};
        List<Integer> damage = new ArrayList<>();
        for (int i = 0; i < y.guards().size(); i++) {
            lastHealth[i] = y.guards().get(i).getHealth();
            damage.add(y.guards().get(i).getMainHandItem().getDamageValue());
        }
        long[] pairedAt = {-1L};
        helper.onEachTick(() -> {
            for (int i = 0; i < y.guards().size(); i++) {
                float h = y.guards().get(i).getHealth();
                if (h < lastHealth[i] - 1.0E-3F && dropped[0] == null) {
                    dropped[0] = y.guards().get(i).getSettlerName() + " " + lastHealth[i] + " -> " + h;
                }
                lastHealth[i] = h;
            }
            List<SettlerEntity> pair = y.guards().stream().filter(g -> mode(g) == GuardDrillYard.MODE_PAIR).toList();
            if (pair.size() == 2 && pairedAt[0] < 0) pairedAt[0] = helper.getLevel().getGameTime();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(pairedAt[0] >= 0 && helper.getLevel().getGameTime() - pairedAt[0] >= 120,
                "a pair must spar for 6 s; " + state(y));
            int watching = 0;
            for (int i = 0; i < y.guards().size(); i++) {
                SettlerEntity g = y.guards().get(i);
                helper.assertTrue(dropped[0] == null, "no practice blow hurts: " + dropped[0] + " " + state(y));
                helper.assertTrue(g.getMainHandItem().getDamageValue() == damage.get(i), "no durability lost");
                helper.assertTrue(g.getTarget() == null, "no aggro from the drill");
                if (mode(g) == GuardDrillYard.MODE_NONE && onWatch(y, g)) watching++;
            }
            helper.assertTrue(watching >= 1, "at least one guard stays on watch: " + state(y));
            List<UUID> members = GuardDrillYard.members(y.settlement(), y.guards().get(0));
            helper.assertTrue(members.size() == 2, "exactly one pair: " + state(y));
            SettlerEntity night = y.guards().stream().filter(g -> !onWatch(y, g)).findFirst().orElseThrow();
            helper.assertTrue(members.contains(night.getUUID()), "the relieved night guard drills");
        });
    }

    /** Raid safety: the first alarm ends the drill the very next tick and the guards fight. */
    @GameTest(template = "empty16", batch = "guard_drill", timeoutTicks = 600)
    public void alarmCancelsTheDrillAtOnceAndGuardsEngageARaider(GameTestHelper helper) {
        Yard y = village(helper, 3, true);
        long[] alarmAt = {-1L};
        RaiderEntity[] raider = {null};
        helper.onEachTick(() -> {
            var level = helper.getLevel();
            long now = level.getGameTime();
            boolean sparring = y.guards().stream().filter(g -> mode(g) == GuardDrillYard.MODE_PAIR).count() == 2;
            if (alarmAt[0] < 0 && sparring) {
                alarmAt[0] = now;
                y.settlement().alertUntilGameTime = now + 400L;
                y.settlement().alertPos = helper.absolutePos(new BlockPos(12, 1, 13));
                RaiderEntity r = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(12, 1, 13));
                r.setVariant(RaiderEntity.Variant.SKIRMISHER);
                r.assign(UUID.randomUUID(), y.settlement().id, RaidObjective.BLOD, 1.0F, false);
                r.setAbsorptionAmount(100.0F);
                r.setNoAi(true);
                raider[0] = r;
            } else if (alarmAt[0] >= 0 && now == alarmAt[0] + 3) {
                for (SettlerEntity g : y.guards()) {
                    helper.assertTrue(mode(g) == GuardDrillYard.MODE_NONE,
                        "the drill cue is gone within a couple of ticks of the alarm: " + state(y));
                }
                helper.assertTrue(!GuardDrillYard.cancelReason(y.settlement(), y.guards().get(0)).isEmpty(),
                    "the yard is closed for the day");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(raider[0] != null, "waiting for the pair to spar; " + state(y));
            helper.assertTrue(helper.getLevel().getGameTime() > alarmAt[0] + 3, "past the cancel check");
            boolean engaged = y.guards().stream().anyMatch(g -> g.getTarget() == raider[0]);
            helper.assertTrue(engaged, "a guard engages the raider: " + state(y));
            for (SettlerEntity g : y.guards()) {
                helper.assertTrue(mode(g) == GuardDrillYard.MODE_NONE, "nobody returns to the yard during the alarm");
            }
        });
    }

    /**
     * Two guards, one per watch: the day guard is the last one on watch and is never drafted;
     * the relieved night guard shadow-drills alone.
     */
    @GameTest(template = "empty16", batch = "guard_drill", timeoutTicks = 500)
    public void onDutyGuardsNeverJoin(GameTestHelper helper) {
        Yard y = village(helper, 2, true);
        SettlerEntity day = y.guards().stream().filter(g -> onWatch(y, g)).findFirst().orElseThrow();
        SettlerEntity night = y.guards().stream().filter(g -> !onWatch(y, g)).findFirst().orElseThrow();
        boolean[] dayJoined = {false};
        int[] soloTicks = {0};
        helper.onEachTick(() -> {
            if (mode(day) != GuardDrillYard.MODE_NONE
                || GuardDrillYard.members(y.settlement(), day).contains(day.getUUID())) {
                dayJoined[0] = true;
            }
            if (mode(night) == GuardDrillYard.MODE_SOLO) soloTicks[0]++;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!dayJoined[0], "the last guard on watch never joins: " + state(y));
            helper.assertTrue(soloTicks[0] >= 100, "the night guard shadow-drills alone: " + state(y));
        });
    }

    /** Without the node nobody drills. */
    @GameTest(template = "empty16", batch = "guard_drill", timeoutTicks = 200)
    public void withoutTheNodeNobodyDrills(GameTestHelper helper) {
        Yard y = village(helper, 3, false);
        helper.runAfterDelay(180, () -> {
            for (SettlerEntity g : y.guards()) {
                helper.assertTrue(mode(g) == GuardDrillYard.MODE_NONE, "no drill without Guard Drill: " + state(y));
            }
            helper.assertTrue(GuardDrillYard.members(y.settlement(), y.guards().get(0)).isEmpty(), "no members");
            helper.succeed();
        });
    }
}
