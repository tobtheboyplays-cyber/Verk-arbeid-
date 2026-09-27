package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidEscalation;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Raid escalation curve (owner request 26 Sep: "have bandits early in the
 * game, then it increases"). Batches start with {@code raid_escalation_}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidEscalationGameTests {
    private static final BlockPos SKY_CENTER = new BlockPos(8, 120, 8);

    private record Arena(Settlement settlement, List<ChunkPos> forced, List<BlockPos> laid) {
    }

    private static Arena arena(GameTestHelper helper, String name, int claim) {
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(SKY_CENTER));
        settlement.radius = claim;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        int reach = RaidDirector.spawnMaxDistance(claim) + RaidDirector.CAPTAIN_EXTRA_REACH + 2;
        List<ChunkPos> forced = new ArrayList<>();
        Set<Long> already = new LinkedHashSet<>();
        for (long packed : level.getForcedChunks()) already.add(packed);
        BlockPos c = settlement.center;
        for (int x = SectionPos.blockToSectionCoord(c.getX() - reach);
             x <= SectionPos.blockToSectionCoord(c.getX() + reach); x++) {
            for (int z = SectionPos.blockToSectionCoord(c.getZ() - reach);
                 z <= SectionPos.blockToSectionCoord(c.getZ() + reach); z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!already.contains(pos.toLong())) {
                    level.setChunkForced(x, z, true);
                    forced.add(pos);
                }
            }
        }
        List<BlockPos> laid = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > reach * reach) continue;
                BlockPos pos = c.offset(dx, -1, dz);
                level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                laid.add(pos);
            }
        }
        return new Arena(settlement, forced, laid);
    }

    private static void close(GameTestHelper helper, Arena arena, List<? extends LivingEntity> actors) {
        ServerLevel level = helper.getLevel();
        for (LivingEntity actor : actors) if (!actor.isRemoved()) actor.discard();
        for (BlockPos pos : arena.laid()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (ChunkPos pos : arena.forced()) level.setChunkForced(pos.x, pos.z, false);
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.remove(arena.settlement().id);
        data.setDirty();
    }

    private static SettlerEntity settler(ServerLevel level, Settlement settlement, BlockPos at,
                                         String name, Profession profession, ItemStack weapon) {
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        settler.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(settler);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        if (profession != Profession.NONE) settler.assignProfession(profession);
        if (!weapon.isEmpty()) settler.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        settler.attributes().pinForTest(Attribute.STRENGTH, 0);
        return settler;
    }

    private static RaidPlan activeFirstRaid(GameTestHelper helper, Settlement settlement) {
        RaidCaptain captain = RaidDirector.pickCaptain(settlement, helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2)
                && settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "fixture: an authored first raid is active");
        return plan;
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raid_escalation_first_raid_is_bandits")
    public void firstRaidIsABanditBand(GameTestHelper helper) {
        Arena arena = arena(helper, "Fredvik", 12);
        List<RaiderEntity> band = List.of();
        try {
            RaidPlan plan = activeFirstRaid(helper, arena.settlement());
            band = RaidDirector.spawnFirstBandForQa(helper.getLevel(), arena.settlement(), plan);
            helper.assertTrue(band.size() == RaidDirector.FIRST_RAID_BAND_SIZE
                    && band.size() >= 3 && band.size() <= 4,
                "raid 1 must be 3-4 raiders, got " + band.size());
            for (RaiderEntity raider : band) {
                helper.assertTrue(raider.isBandit(), "raid 1 is bandits only, got " + raider.variant());
                helper.assertTrue(raider.getMaxHealth() >= 16.0F && raider.getMaxHealth() <= 18.0F
                        || raider.isCaptain(),
                    "a bandit has 16-18 HP, got " + raider.getMaxHealth());
                helper.assertTrue(raider.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE) == 1.0D,
                    "bandits are ordinary humans");
            }
            helper.assertTrue(band.get(0).isCaptain(), "a bandit captain leads");
        } finally {
            close(helper, arena, band);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raid_escalation_scales_with_strength")
    public void compositionFollowsTheSettlementsStrength(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Arena arena = arena(helper, "Styrkevik", 12);
        List<SettlerEntity> fighters = new ArrayList<>();
        try {
            RaidEscalation.Strength none = RaidEscalation.strength(level, arena.settlement());
            for (int i = 0; i < 4; i++) {
                fighters.add(settler(level, arena.settlement(), arena.settlement().center.offset(i, 0, 2),
                    "Vakt " + i, Profession.GUARD, new ItemStack(Items.IRON_SWORD)));
            }
            RaidEscalation.Strength four = RaidEscalation.strength(level, arena.settlement());
            helper.assertTrue(none.fighters() == 0 && four.fighters() == 4 && four.score() > none.score(),
                "four armed Guards must read stronger: " + none + " vs " + four);
            RaidEscalation.Band weak = RaidEscalation.compose(7, none, 0.9D);
            RaidEscalation.Band strong = RaidEscalation.compose(7, four, 0.9D);
            helper.assertTrue(strong.size() > weak.size() && strong.brutes() > weak.brutes(),
                "raid 7 must be bigger against four Guards than against none: " + weak + " vs " + strong);
            for (int raid = 1; raid <= 3; raid++) {
                helper.assertTrue(RaidEscalation.compose(raid, four, 0.0D).brutes() == 0,
                    "no Brute in raid " + raid);
            }
        } finally {
            close(helper, arena, fighters);
        }
        helper.succeed();
    }

    /**
     * A fresh town -- two Guards with the early wooden/stone kit plus one
     * player-like fighter (an iron-sword defender) and two civilians -- must
     * beat raid 1 without deaths.
     */
    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "raid_escalation_fresh_town_wins_raid_one")
    public void aFreshTownWinsRaidOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Arena arena = arena(helper, "Nyby", 12);
        Settlement s = arena.settlement();
        BlockPos c = s.center;
        List<LivingEntity> actors = new ArrayList<>();
        List<SettlerEntity> defenders = List.of(
            settler(level, s, c.offset(-2, 0, 3), "Vakt A", Profession.GUARD, new ItemStack(Items.STONE_SWORD)),
            settler(level, s, c.offset(2, 0, 3), "Vakt B", Profession.GUARD, new ItemStack(Items.WOODEN_SWORD)),
            settler(level, s, c.offset(0, 0, 5), "Spiller", Profession.GUARD, new ItemStack(Items.IRON_SWORD)));
        List<SettlerEntity> civilians = List.of(
            settler(level, s, c.offset(-3, 0, -4), "Borger A", Profession.NONE, ItemStack.EMPTY),
            settler(level, s, c.offset(3, 0, -4), "Borger B", Profession.NONE, ItemStack.EMPTY));
        actors.addAll(defenders);
        actors.addAll(civilians);
        RaidPlan plan = activeFirstRaid(helper, s);
        List<RaiderEntity> band = RaidDirector.spawnFirstBandForQa(level, s, plan);
        actors.addAll(band);
        helper.assertTrue(band.size() == RaidDirector.FIRST_RAID_BAND_SIZE, "raid 1 band forms, got " + band.size());
        for (RaiderEntity raider : band) {
            helper.assertTrue(s.raidLifecycle.recordParticipant(raider.getUUID()), "ledger");
        }
        helper.assertTrue(s.raidLifecycle.sealParticipants(), "the band seals");
        s.pendingRaid = plan;
        long[] start = {-1L};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            long now = helper.getTick();
            if (start[0] < 0L) start[0] = now;
            long elapsed = now - start[0];
            boolean bandDown = band.stream().noneMatch(LivingEntity::isAlive);
            if (!bandDown && elapsed < 20L * 120L) return;
            done[0] = true;
            long defendersDead = defenders.stream().filter(e -> !e.isAlive()).count();
            long civiliansDead = civilians.stream().filter(e -> !e.isAlive()).count();
            Hearthstead.LOGGER.info(
                "HSQA_TTK fight=raid_one_fresh_town seconds={} bandits_left={}/{} defenders_dead={}/{} civilians_dead={}/{}",
                elapsed / 20.0, band.stream().filter(LivingEntity::isAlive).count(), band.size(),
                defendersDead, defenders.size(), civiliansDead, civilians.size());
            try {
                helper.assertTrue(bandDown, "the fresh town must beat raid 1 within two minutes");
                helper.assertTrue(defendersDead == 0 && civiliansDead == 0,
                    "raid 1 must be winnable without deaths: defenders " + defendersDead
                        + ", civilians " + civiliansDead);
            } finally {
                close(helper, arena, actors);
            }
            helper.succeed();
        });
    }
}
