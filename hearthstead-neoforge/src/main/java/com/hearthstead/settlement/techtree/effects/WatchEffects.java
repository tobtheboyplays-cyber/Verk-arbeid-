package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleCombatRules;
import com.hearthstead.entity.combat.role.SpearmanCombatGoal;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechBonus;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watch &amp; Defense branch effects (23 nodes). OWNED BY THE WATCH BRANCH
 * LANE ("techtree-watch"): only that lane edits this file and
 * data/hearthstead/techtree/watch.json.
 *
 * <p>Every hook site calls one small, settlement-aware helper below, so the
 * numbers live here once and each gameplay class changes by a line. Each
 * helper returns the unchanged base value when the node is not learned (or
 * the settlement is unknown), so behaviour without the node is exactly the
 * old behaviour. All bonuses are per settlement (one tree per Banner), never
 * per player. Tests: {@code TechTreeWatchGameTests}, batch
 * {@code techtree_watch}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WatchEffects {

    // ------------------------------------------------------------ numbers ---

    /** commanders_horn: extra order earshot in blocks (48 to 80). */
    public static final TechBonus ORDER_EARSHOT = TechBonus.of("watch.order_earshot",
        "Orders reach %s blocks further (48 to 80 blocks)");
    /** commanders_horn: raid orders hold this much longer after the alarm ends. */
    public static final long HORN_RAID_GRACE_BONUS_TICKS = 30L * 20L;
    /** commanders_horn: the rally, +10% move speed for 10 s. */
    public static final double HORN_RALLY_SPEED = 0.10D;
    public static final int HORN_RALLY_TICKS = 10 * 20;
    /** One horn blast per commander per 3 s, however fast the orders come. */
    public static final int HORN_SOUND_COOLDOWN_TICKS = 60;
    static final ResourceLocation HORN_RALLY_ID = Hearthstead.id("horn_rally");

    /** barricades: extra barricade columns (5 to 10). */
    public static final TechBonus BARRICADE_LENGTH = TechBonus.of("watch.barricade_length",
        "Barricade lines may be %s blocks longer (5 to 10)");

    /** shield_doctrine: less damage from the front while holding a shield line. */
    public static final TechBonus LINE_GUARD = TechBonus.percent("watch.line_guard",
        "Guards holding a line with a shield take %s%% less damage from the front");

    /** crossbows: slower draw, heavier hit. */
    public static final double CROSSBOW_DRAW_SCALE = 1.5D;
    public static final TechBonus CROSSBOW_DAMAGE = TechBonus.percent("watch.crossbow_damage",
        "Archer shots hit %s%% harder");

    /** watchfires: shelter run speed during the alarm. */
    public static final TechBonus SHELTER_SPEED = TechBonus.percent("watch.shelter_speed",
        "Villagers run for shelter %s%% faster when the alarm sounds");

    /** palisade / stone_walls: more blows to break a wall block of that segment. */
    public static final TechBonus PALISADE_HITS = TechBonus.percent("watch.palisade_hits",
        "Palisade segments take %s%% more blows to break (6 to 12)");
    public static final TechBonus STONE_HITS = TechBonus.percent("watch.stone_hits",
        "Stone wall segments take %s%% more blows to break (6 to 18)");

    /** earthworks: slow raiders near any built defense segment. */
    public static final int EARTHWORKS_RADIUS = 3;
    /** Slowness II: -30% move speed. */
    public static final int EARTHWORKS_AMPLIFIER = 1;
    public static final int EARTHWORKS_PULSE_TICKS = 20;
    /** A little longer than a pulse so the slow never flickers off in between. */
    public static final int EARTHWORKS_EFFECT_TICKS = 30;

    /** veteran_techniques. */
    public static final double VETERAN_BASH_COOLDOWN_SCALE = 0.7D;
    public static final int VETERAN_COMBO_WINDOW_BONUS = 4;
    public static final TechBonus VETERAN_HEAVY = TechBonus.percent("watch.veteran_heavy",
        "Guard heavy strikes hit %s%% harder");

    /** knights: Sergeant and Captain guards. */
    public static final TechBonus KNIGHT_DAMAGE = TechBonus.percent("watch.knight_damage",
        "Sergeant and Captain guards deal %s%% more melee damage");

    /** pike_square: braced spearmen. */
    public static final TechBonus BRACE_STRIKE = TechBonus.percent("watch.brace_strike",
        "Braced Spearmen's brace strike hits %s%% harder");

    private WatchEffects() {
    }

    static void register(EffectRegistry r) {
        r.node("commanders_horn")
            .bonus(ORDER_EARSHOT, 32)
            .flag("FieldOrders.issue (order lifetime)",
                "Orders given during a raid hold 60 s after it ends instead of 30 s")
            .flag("FieldOrders.issue (horn rally)",
                "Every order sounds a horn: soldiers who hear it move 10% faster for 10 s");
        r.node("barricades")
            .bonus(BARRICADE_LENGTH, BuildPlanner.BARRICADE_MAX)
            .flag("BuilderWorkGoal.canContinueToUse (barricade call)",
                "When a raid is spotted, every Builder drops other work for a waiting barricade");
        // Its Pike Yard / Sword Hall emblems moved to spearmen / longswords:
        // the doctrine's own effects are the XP bonus and the shield line.
        r.node("shield_doctrine")
            .bonus(LINE_GUARD, 30)
            .flag("DevelopmentBonuses.guardCombatXp", "Guards earn +25% combat XP");
        r.node("spearmen")
            .building(BuildingType.PIKE_YARD)
            .profession(Profession.SPEARMAN)
            .grandfatheredBy("shield_doctrine");
        r.node("longswords")
            .building(BuildingType.SWORD_HALL)
            .profession(Profession.LONGSWORDSMAN)
            .grandfatheredBy("shield_doctrine");
        r.node("rune_mage")
            .building(BuildingType.RUNE_HALL)
            .profession(Profession.RUNE_MAGE)
            .grandfatheredBy("hearth_doctrine")
            .flag("RoleHiring.mageCap", "One Rune Mage per settlement (two with High Runes)");
        r.node("high_runes")
            .flag("RoleHiring.mageCap", "A second Rune Mage may be hired")
            .flag("RuneMageGoal.ward", "Wards last 6 s instead of 5 s");
        r.node("crossbows")
            .flag("ArcherAttackGoal.tick (draw)", "Archers draw 50% slower (1 s to 1.5 s a shot)")
            .bonus(CROSSBOW_DAMAGE, 40);
        r.node("watchfires")
            .bonus(SHELTER_SPEED, 25);
        r.node("palisade")
            .bonus(PALISADE_HITS, 100);
        r.node("earthworks")
            .flag("WatchEffects.onEntityTick (raiders)",
                "Raiders within 3 blocks of a built defense line are slowed (Slowness II, -30% speed)");
        r.node("stone_walls")
            .bonus(STONE_HITS, 200);
        r.node("veteran_techniques")
            .flag("GuardMeleeGoal (shield bash)", "Guard shield bash is ready 30% sooner (4 s to 2.8 s)")
            .flag("GuardMeleeGoal (combo)", "Guard combo window is 4 ticks longer (6 to 10)")
            .bonus(VETERAN_HEAVY, 30);
        // Hero Captain (battle-roles lane): CaptainStatus.commissioned reads
        // Development.has(..., "captains_commission"); CaptainWorld promotes.
        r.node("captains_commission")
            .flag("CaptainStatus.isHero / CaptainWorld field promotion",
                "Field-promotes your top guard to Sergeant and commissions him Captain")
            .flag("CaptainKit", "The Captain: +16 health, +4 armour, +2 damage, a weapon loadout with special attacks");
        r.node("master_armoury")
            .flag("GearTier DIAMOND (node:castle_charter + node:master_armoury) via GearGate.owns",
                "With the Castle Charter: guards and archers may wear diamond (Gear Tier 3)")
            .flag("GearTier NETHERITE (node:kingdom_crown + node:master_armoury) via GearGate.owns",
                "With the Kingdom Crown: netherite (Gear Tier 4)");
        r.node("knights")
            .bonus(KNIGHT_DAMAGE, 25)
            .flag("WatchEffects.onKnockBack", "Sergeant and Captain guards cannot be knocked back");
        r.node("pike_square")
            .bonus(BRACE_STRIKE, 50)
            .flag("WatchEffects.onKnockBack", "Braced Spearmen cannot be knocked back");
    }

    // ------------------------------------------------------------ helpers ---

    private static boolean has(@Nullable ServerLevel level, @Nullable Settlement settlement, String id) {
        return level != null && settlement != null && Development.has(level, settlement, id);
    }

    private static double bonus(@Nullable ServerLevel level, @Nullable Settlement settlement, TechBonus key) {
        return level == null || settlement == null ? 0.0D : TechTree.bonus(level, settlement, key);
    }

    @Nullable
    private static ServerLevel levelOf(@Nullable Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel server ? server : null;
    }

    // ---------------------------------------------------- commanders_horn ---

    /** Order earshot in blocks for this settlement's commanders. */
    public static double orderEarshot(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                      double base) {
        return base + bonus(level, settlement, ORDER_EARSHOT);
    }

    /** How long a raid order holds after the raid (or alarm) ends. */
    public static long orderRaidGraceTicks(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                           long base) {
        return has(level, settlement, "commanders_horn") ? base + HORN_RAID_GRACE_BONUS_TICKS : base;
    }

    private static final Map<UUID, Long> LAST_HORN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RALLY_UNTIL = new ConcurrentHashMap<>();

    /**
     * The horn rally: one horn blast from the commander (at most every 3 s)
     * and +10% move speed for 10 s on every soldier who heard the order.
     * Does nothing without Commander's Horn. Returns how many were rallied.
     */
    public static int hornRally(ServerLevel level, @Nullable Settlement settlement,
                                @Nullable ServerPlayer commander, Collection<SettlerEntity> listeners) {
        if (!has(level, settlement, "commanders_horn") || listeners.isEmpty()) {
            return 0;
        }
        long now = level.getGameTime();
        if (commander != null) {
            Long last = LAST_HORN.get(commander.getUUID());
            if (last == null || now - last >= HORN_SOUND_COOLDOWN_TICKS || now < last) {
                LAST_HORN.put(commander.getUUID(), now);
                // Volume 4 carries about 64 blocks: the listeners hear it.
                level.playSound(null, commander.getX(), commander.getEyeY(), commander.getZ(),
                    SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0), SoundSource.PLAYERS, 4.0F, 1.0F);
            }
        }
        int rallied = 0;
        for (SettlerEntity soldier : listeners) {
            if (soldier == null || !soldier.isAlive()) {
                continue;
            }
            AttributeInstance speed = soldier.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed == null) {
                continue;
            }
            if (!speed.hasModifier(HORN_RALLY_ID)) {
                speed.addTransientModifier(new AttributeModifier(HORN_RALLY_ID, HORN_RALLY_SPEED,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
            RALLY_UNTIL.put(soldier.getUUID(), now + HORN_RALLY_TICKS);
            level.sendParticles(ParticleTypes.NOTE, soldier.getX(), soldier.getY() + 2.2D, soldier.getZ(),
                1, 0.0D, 0.0D, 0.0D, 0.0D);
            rallied++;
        }
        return rallied;
    }

    /** True while the horn rally speed is on this soldier. */
    public static boolean rallied(SettlerEntity soldier) {
        AttributeInstance speed = soldier.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.hasModifier(HORN_RALLY_ID);
    }

    /** Removes an expired rally. Called from the entity tick (every 10 ticks). */
    public static void expireRally(SettlerEntity soldier, long now) {
        AttributeInstance speed = soldier.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || !speed.hasModifier(HORN_RALLY_ID)) {
            return;
        }
        Long until = RALLY_UNTIL.get(soldier.getUUID());
        if (until == null || now >= until || until - now > HORN_RALLY_TICKS) {
            speed.removeModifier(HORN_RALLY_ID);
            RALLY_UNTIL.remove(soldier.getUUID());
        }
    }

    // --------------------------------------------------------- barricades ---

    /** Maximum barricade line length in columns. */
    public static int barricadeMax(@Nullable ServerLevel level, @Nullable Settlement settlement, int base) {
        return base + (int) Math.round(bonus(level, settlement, BARRICADE_LENGTH));
    }

    /**
     * True when this settlement owns Barricades, a raid is warned, and a
     * rushed barricade site is waiting with nobody working it: a Builder on
     * other work should drop it and take the barricade.
     */
    public static boolean barricadeCallsBuilders(ServerLevel level, @Nullable Settlement settlement) {
        if (settlement == null || !has(level, settlement, "barricades")
            || !BuildJobs.raidWarned(level, settlement)) {
            return false;
        }
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        if (data == null) {
            return false;
        }
        long now = level.getGameTime();
        for (BuildJob job : data.activeJobs(settlement.id)) {
            // The same filter BuildJobs.claimNext applies, so a Builder that
            // drops his work is sure to be handed this barricade next.
            if (job.kind == BuildJob.Kind.BARRICADE && job.rush && job.workable()
                && (job.claimant == null || job.leaseUntil <= now)
                && !(job.exhausted() && job.blockedCount() > 0 && !job.allowOverwrite)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------- shield_doctrine ---

    /**
     * Damage a Guard takes while holding a shield line: reduced by the
     * Shield Doctrine share unless the blow comes from the flank or back.
     */
    public static float shieldLineDamage(@Nullable ServerLevel level, SettlerEntity guard,
                                         @Nullable Entity from, float amount, boolean bracing) {
        if (!bracing || from == null || guard.getProfession() != Profession.GUARD
            || !guard.hasPhysicalOffhandShield()) {
            return amount;
        }
        double percent = bonus(level, guard.settlement(), LINE_GUARD);
        if (percent <= 0.0D) {
            return amount;
        }
        float bearing = RoleCombatRules.yawToward(guard.getX(), guard.getZ(), from.getX(), from.getZ());
        if (RoleCombatRules.isFlankHit(guard.yBodyRot, bearing)) {
            return amount;
        }
        return (float) (amount * Math.max(0.0D, 1.0D - percent / 100.0D));
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof SettlerEntity guard)
            || guard.getProfession() != Profession.GUARD) {
            return;
        }
        ServerLevel level = levelOf(guard);
        if (level == null) {
            return;
        }
        DamageSource source = event.getSource();
        Entity from = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
        float amount = event.getAmount();
        float reduced = shieldLineDamage(level, guard, from, amount, FieldOrders.bracing(guard));
        if (reduced < amount) {
            event.setAmount(reduced);
        }
    }

    // ---------------------------------------------------- rune_mage/runes ---

    /** Rune Mage cap: one, two with High Runes. */
    public static int mageCap(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return has(level, settlement, "high_runes") ? 2 : 1;
    }

    /** Ward length in ticks for this mage's settlement. */
    public static int wardTicks(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                int base, int highRunes) {
        return has(level, settlement, "high_runes") ? highRunes : base;
    }

    // ---------------------------------------------------------- crossbows ---

    /** Ordinary draw ticks with heavy bolts (x1.5). */
    public static int archerDrawTicks(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                      int base) {
        return has(level, settlement, "crossbows")
            ? (int) Math.round(base * CROSSBOW_DRAW_SCALE) : base;
    }

    /** Arrow damage multiplier (1.4 with Crossbows). */
    public static double archerDamageScale(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return 1.0D + bonus(level, settlement, CROSSBOW_DAMAGE) / 100.0D;
    }

    public static boolean crossbows(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return has(level, settlement, "crossbows");
    }

    // --------------------------------------------------------- watchfires ---

    /** Shelter run speed modifier for a settler fleeing the alarm. */
    public static double shelterSpeed(SettlerEntity settler, double base) {
        return base * (1.0D + bonus(levelOf(settler), settler.settlement(), SHELTER_SPEED) / 100.0D);
    }

    // ------------------------------------------------ palisade/stone walls ---

    /** Blows a raider needs to break this wall block. */
    public static int wallHits(@Nullable ServerLevel level, @Nullable Settlement settlement,
                               BlockPos pos, int base) {
        if (level == null || settlement == null) {
            return base;
        }
        double palisade = bonus(level, settlement, PALISADE_HITS);
        double stone = bonus(level, settlement, STONE_HITS);
        if (palisade <= 0.0D && stone <= 0.0D) {
            return base;
        }
        String segment = BuildSiteSavedData.segmentAt(level, settlement.id, pos);
        double percent = BuildPlanner.PALISADE.equals(segment) ? palisade
            : BuildPlanner.STONE.equals(segment) ? stone : 0.0D;
        return (int) Math.round(base * (1.0D + percent / 100.0D));
    }

    // --------------------------------------------------------- earthworks ---

    private record DefenseIndex(BuildSiteSavedData data, int revision, LongOpenHashSet blocks) {
    }

    private static final Map<UUID, DefenseIndex> DEFENSE_INDEX = new ConcurrentHashMap<>();

    /** True when a built defense segment block lies within the earthworks radius. */
    public static boolean nearDefenseLine(ServerLevel level, Settlement settlement, BlockPos at) {
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        if (data == null) {
            return false;
        }
        DefenseIndex index = DEFENSE_INDEX.get(settlement.id);
        if (index == null || index.data() != data || index.revision() != data.revision()) {
            LongOpenHashSet blocks = new LongOpenHashSet();
            for (BuildSiteSavedData.DefenseWork work : data.defenseWorks(settlement.id)) {
                for (long b : work.blocks()) {
                    blocks.add(b);
                }
            }
            index = new DefenseIndex(data, data.revision(), blocks);
            DEFENSE_INDEX.put(settlement.id, index);
        }
        if (index.blocks().isEmpty()) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int r = EARTHWORKS_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    cursor.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    // The index is the Builder's record; the wall must still
                    // stand (mined, burnt or breached cells no longer count).
                    if (index.blocks().contains(cursor.asLong()) && level.isLoaded(cursor)
                        && !level.getBlockState(cursor).isAir()
                        && level.getBlockState(cursor).getFluidState().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** One earthworks pulse on a raider. Returns true when it was slowed. */
    public static boolean earthworksPulse(RaiderEntity raider) {
        ServerLevel level = levelOf(raider);
        if (level == null || !raider.isAlive()) {
            return false;
        }
        Settlement settlement = raider.settlement();
        if (!has(level, settlement, "earthworks")
            || !nearDefenseLine(level, settlement, raider.blockPosition())) {
            return false;
        }
        raider.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, EARTHWORKS_EFFECT_TICKS,
            EARTHWORKS_AMPLIFIER, false, true));
        return true;
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity instanceof RaiderEntity raider) {
            if (raider.tickCount % EARTHWORKS_PULSE_TICKS == 3 && levelOf(raider) != null) {
                earthworksPulse(raider);
            }
        } else if (entity instanceof SettlerEntity settler && settler.tickCount % 10 == 4) {
            ServerLevel level = levelOf(settler);
            if (level != null) {
                expireRally(settler, level.getGameTime());
            }
        }
    }

    // ------------------------------------------------- veteran_techniques ---

    public static int shieldBashCooldown(SettlerEntity guard, int base) {
        return has(levelOf(guard), guard.settlement(), "veteran_techniques")
            ? (int) Math.round(base * VETERAN_BASH_COOLDOWN_SCALE) : base;
    }

    public static int comboWindow(SettlerEntity guard, int base) {
        return has(levelOf(guard), guard.settlement(), "veteran_techniques")
            ? base + VETERAN_COMBO_WINDOW_BONUS : base;
    }

    /**
     * Extra power on one guard move: Veteran Techniques' heavy (x1.3) and the
     * Order of Knights (x1.25 for Sergeant and Captain guards).
     */
    public static double guardMovePower(SettlerEntity guard, @Nullable GuardMove move) {
        ServerLevel level = levelOf(guard);
        Settlement settlement = guard.settlement();
        if (level == null || settlement == null || guard.getProfession() != Profession.GUARD) {
            return 1.0D;
        }
        double power = 1.0D;
        if (move == GuardMove.HEAVY) {
            power *= 1.0D + bonus(level, settlement, VETERAN_HEAVY) / 100.0D;
        }
        if (GuardRank.of(guard).atLeast(GuardRank.SERGEANT)) {
            power *= 1.0D + bonus(level, settlement, KNIGHT_DAMAGE) / 100.0D;
        }
        return power;
    }

    // ---------------------------------------------------- knights / pikes ---

    /** Brace strike damage share for a spearman (x1.5 with Pike Square). */
    public static double braceStrikeShare(SettlerEntity spearman) {
        return 1.0D + bonus(levelOf(spearman), spearman.settlement(), BRACE_STRIKE) / 100.0D;
    }

    /**
     * True when this settler shrugs off knockback: a Sergeant+ guard with
     * the Order of Knights, or a braced Spearman with Pike Square.
     */
    public static boolean resistsKnockback(SettlerEntity settler, boolean braced) {
        ServerLevel level = levelOf(settler);
        Settlement settlement = settler.settlement();
        if (level == null || settlement == null) {
            return false;
        }
        Profession p = settler.getProfession();
        if (p == Profession.GUARD) {
            return GuardRank.of(settler).atLeast(GuardRank.SERGEANT) && has(level, settlement, "knights");
        }
        return p == Profession.SPEARMAN && braced && RoleCombat.enabled()
            && has(level, settlement, "pike_square");
    }

    /** Live brace state of a spearman (its combat goal decides). */
    public static boolean braced(SettlerEntity spearman) {
        ServerLevel level = levelOf(spearman);
        if (level == null) {
            return false;
        }
        long now = level.getGameTime();
        for (WrappedGoal wrapped : spearman.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SpearmanCombatGoal goal && goal.braced(level, now)) {
                return true;
            }
        }
        return FieldOrders.bracing(spearman);
    }

    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof SettlerEntity settler) || levelOf(settler) == null) {
            return;
        }
        Profession p = settler.getProfession();
        if (p != Profession.GUARD && p != Profession.SPEARMAN) {
            return;
        }
        boolean braced = p == Profession.SPEARMAN && braced(settler);
        if (resistsKnockback(settler, braced)) {
            event.setCanceled(true);
        }
    }

    /** Test seam: forget horn/rally bookkeeping between tests. */
    public static void resetForTests() {
        LAST_HORN.clear();
        RALLY_UNTIL.clear();
        DEFENSE_INDEX.clear();
    }

}
