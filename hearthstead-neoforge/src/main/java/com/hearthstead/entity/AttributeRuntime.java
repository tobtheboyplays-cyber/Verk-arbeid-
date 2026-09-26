package com.hearthstead.entity;

import com.hearthstead.entity.AttributeEffects.Effect;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Server glue between a settler and {@link AttributeEffects} (attributes
 * lane, plan/ATTRIBUTES.md). Every call site that lets an attribute change
 * play goes through one method here, so the numbers stay in the pure helper
 * and each hook is a single line. All methods are identity when the
 * {@code [attributes] effectStrength} is 0 (and on the GameTest server
 * unless a batch opts in), and they never consume the entity random then.
 */
public final class AttributeRuntime {

    public static final ResourceLocation STAMINA_HEALTH_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "stamina_health");
    public static final ResourceLocation STRENGTH_MELEE_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "strength_melee");
    public static final ResourceLocation TRAIT_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "trait_speed");

    private AttributeRuntime() {
    }

    public static double strength(SettlerEntity settler) {
        return AttributeConfig.strength(settler);
    }

    /** The effect's size for this settler right now: 0..cap. */
    public static double amount(SettlerEntity settler, Effect effect) {
        if (settler == null) {
            return 0.0D;
        }
        return AttributeEffects.amount(effect, settler.attribute(effect.attribute()),
            strength(settler));
    }

    // ------------------------------------------------------------ work ---

    /** Job fit for this settler's current job: 0..0.15. */
    public static double jobFit(SettlerEntity settler) {
        if (settler == null) {
            return 0.0D;
        }
        Profession p = settler.getProfession();
        int primary = SkillLevels.primaryOf(p).map(settler::attribute).orElse(0);
        int secondary = SkillLevels.secondaryOf(p).map(settler::attribute).orElse(0);
        if (SkillLevels.primaryOf(p).isEmpty()) {
            return 0.0D;
        }
        return AttributeEffects.jobFit(primary, secondary, strength(settler));
    }

    /** Job fit (+ Hard Hands, + trait work) on timed work with no trade-level bonus (builder, healer). */
    public static int shortenWork(SettlerEntity settler, int ticks) {
        double cut = AttributeEffects.combinedWorkCut(0.0D, jobFit(settler) + workBoon(settler),
            traitWorkCut(settler));
        return cut == 0.0D ? ticks : AttributeEffects.shortenSigned(ticks, cut);
    }

    /**
     * The traits that change how fast a settler works, as a signed cut:
     * BIG_EATER x1.15 while fed (hunger 50+); WELCOMING x0.92 (the work waits
     * while they talk) but x1.05 in a social job; EARLY_RISER x1.05 in the
     * morning; NIGHT_OWL x1.05 in the late hours. 0 when off.
     */
    public static double traitWorkCut(SettlerEntity settler) {
        // + drunkenness (tavern lane): -10% drunk, -20% very drunk; 0 sober/tipsy or when off
        return settler == null ? 0.0D : traitWorkCutAt(settler, settler.level().getDayTime())
            + Drunkenness.workCut(settler.drunkLevel());
    }

    /** {@link #traitWorkCut} at a given day time (GameTests never move the world clock). */
    public static double traitWorkCutAt(SettlerEntity settler, long dayTime) {
        double on = settler == null ? 0.0D : traitOn(settler);
        if (on <= 0.0D) {
            return 0.0D;
        }
        double work = 1.0D;
        for (Trait trait : settler.traits()) {
            switch (trait) {
                case BIG_EATER -> work *= settler.getHunger() >= AttributeEffects.BIG_EATER_FED
                    ? trait.work() : 1.0D;
                case WELCOMING -> work *= social(settler.getProfession())
                    ? AttributeEffects.WELCOMING_SOCIAL_WORK : trait.work();
                case EARLY_RISER -> work *= AttributeEffects.morning(dayTime)
                    ? AttributeEffects.SHIFT_WORK_BONUS : 1.0D;
                case NIGHT_OWL -> work *= AttributeEffects.lateHours(dayTime)
                    ? AttributeEffects.SHIFT_WORK_BONUS : 1.0D;
                default -> work *= trait.work();
            }
        }
        return AttributeEffects.traitWorkCut(work, on);
    }

    /** Jobs where talking IS the work. */
    public static boolean social(Profession profession) {
        return profession == Profession.INNKEEPER || profession == Profession.TRADER;
    }

    /** Focus (+ Steady Purpose): batch time at a bench, as a fraction to add to the work cut. */
    public static double craftCut(SettlerEntity settler) {
        return amount(settler, Effect.CRAFT_TIME) + boon(settler, AttributeEffects.Boon.STEADY_PURPOSE);
    }

    /** Hard Hands: the mayor boon's settlement-wide work-time cut (0 without that boon). */
    public static double workBoon(SettlerEntity settler) {
        return boon(settler, AttributeEffects.Boon.HARD_HANDS);
    }

    // ------------------------------------------------------ mayor boons ---

    /** The size of {@code boon} in this settler's settlement right now, 0 if not active. */
    public static double boon(SettlerEntity settler, AttributeEffects.Boon boon) {
        if (settler == null || !(settler.level() instanceof ServerLevel level)) {
            return 0.0D;
        }
        Settlement s = settler.settlement();
        return s == null ? 0.0D : boonAmount(level, s, boon);
    }

    /**
     * The boon size for a settlement: 0 unless the mayor boon in force
     * (Mayor.activeBoon: settled in, not in mourning) is exactly this one;
     * then scaled by the mayor's own value in the boon attribute.
     */
    public static double boonAmount(ServerLevel level, Settlement settlement, AttributeEffects.Boon boon) {
        double strength = AttributeConfig.strength(level.getServer());
        if (strength <= 0.0D || settlement == null) {
            return 0.0D;
        }
        Mayor.Boon active = Mayor.activeBoon(level, settlement);
        if (active == null || !active.name().equals(boon.name())) {
            return 0.0D;
        }
        SettlerEntity mayor = Mayor.find(level, settlement);
        return mayor == null ? 0.0D
            : AttributeEffects.boonAmount(boon, mayor.attribute(boon.attribute()), strength);
    }

    // ----------------------------------------------------------- traits ---

    /** 0 = trait speed/sight/fear/watch effects off (GameTest default), 1 = full. */
    private static double traitOn(SettlerEntity settler) {
        return Math.min(1.0D, strength(settler));
    }

    /**
     * Trait speed (STRONG_BACK 0.92, FEARFUL 1.15), clamped 0.85..1.15, as a
     * transient movement modifier. Change-detected; cheap per needs tick.
     */
    public static void applyTraitSpeed(SettlerEntity settler) {
        AttributeInstance speed = settler.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        // Never touch traits() (it may roll them from the entity random) when off.
        double on = traitOn(settler);
        double want = on <= 0.0D ? 0.0D
            : AttributeEffects.traitSpeed(Trait.speed(settler.traits()), on) - 1.0D;
        AttributeModifier current = speed.getModifier(TRAIT_SPEED_ID);
        double have = current == null ? 0.0D : current.amount();
        if (Math.abs(have - want) < 1.0E-6D) {
            return;
        }
        if (Math.abs(want) < 1.0E-6D) {
            speed.removeModifier(TRAIT_SPEED_ID);
        } else {
            speed.addOrUpdateTransientModifier(new AttributeModifier(TRAIT_SPEED_ID, want,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** A civilian threat-scan radius (SettlerPanicGoal): 12, FEARFUL 16, WATCHFUL ~15.6. */
    public static double panicScanRadius(SettlerEntity settler) {
        double on = settler == null ? 0.0D : traitOn(settler);
        if (on <= 0.0D) {
            return AttributeEffects.PANIC_SCAN;
        }
        return AttributeEffects.panicScanRadius(Trait.any(settler.traits(), Trait.Flag.FEARFUL),
            Trait.sight(settler.traits()), on);
    }

    // --------------------------------------------------------- day clock ---

    /**
     * The settler's own day phase: EARLY_RISER lives an hour ahead (works
     * from first light, spent by evening), NIGHT_OWL an hour behind (sleeps
     * late, works late). Server-side only; plain village clock when off.
     */
    public static com.hearthstead.settlement.DayPhase dayPhaseOf(SettlerEntity settler, long dayTime) {
        return com.hearthstead.settlement.DayPhase.of(dayTime + clockShift(settler));
    }

    public static long clockShift(SettlerEntity settler) {
        // Guards, archers, battle roles and the healer keep the watch rota,
        // not a body clock: a shifted handover would leave the wall empty.
        if (settler == null || settler.level().isClientSide()
            || settler.getProfession().battlefield()) {
            return 0L;
        }
        double on = traitOn(settler);
        if (on <= 0.0D) {
            return 0L;
        }
        long shift = 0L;
        if (Trait.any(settler.traits(), Trait.Flag.EARLY_RISER)) shift += AttributeEffects.EARLY_RISER_SHIFT;
        if (Trait.any(settler.traits(), Trait.Flag.NIGHT_OWL)) shift += AttributeEffects.NIGHT_OWL_SHIFT;
        return AttributeEffects.clockShift(shift, on);
    }

    /** NIGHT_OWL: working at night drains a quarter less energy. */
    public static float nightDrain(SettlerEntity settler, float drain) {
        return settler == null ? drain : nightDrainAt(settler, drain, settler.level().getDayTime());
    }

    public static float nightDrainAt(SettlerEntity settler, float drain, long dayTime) {
        double on = settler == null ? 0.0D : traitOn(settler);
        if (on <= 0.0D || !AttributeEffects.night(dayTime)
            || !Trait.any(settler.traits(), Trait.Flag.NIGHT_OWL)) {
            return drain;
        }
        return (float) (drain * (1.0D - (1.0D - AttributeEffects.NIGHT_OWL_NIGHT_DRAIN) * on));
    }

    // ------------------------------------------------------ green fingers ---

    /** GREEN_FINGERS on a farmer, with trait effects on. */
    public static boolean greenFingers(SettlerEntity settler) {
        return settler != null && settler.getProfession() == Profession.FARMER && traitOn(settler) > 0.0D
            && Trait.any(settler.traits(), Trait.Flag.GREEN_FINGERS);
    }

    /**
     * GREEN_FINGERS, called from the farmer's work tick: once per
     * {@value AttributeEffects#GREEN_FINGERS_INTERVAL} ticks one random
     * column of their field gets one extra vanilla random tick on its crop
     * (light and farmland rules still apply). Roughly a quarter faster
     * growth while they work the field; never more than one tick per interval.
     */
    public static void tendField(SettlerEntity settler, @javax.annotation.Nullable
                                 com.hearthstead.settlement.workzone.WorkZone zone) {
        if (zone == null || !(settler.level() instanceof ServerLevel level)
            || (level.getGameTime() + (settler.getId() & 63)) % AttributeEffects.GREEN_FINGERS_INTERVAL != 0L
            || !greenFingers(settler)) {
            return;
        }
        tendOnce(level, zone, settler.getRandom());
    }

    /** One extra random tick on one crop in the zone; true if a crop was ticked. */
    public static boolean tendOnce(ServerLevel level, com.hearthstead.settlement.workzone.WorkZone zone,
                                   net.minecraft.util.RandomSource random) {
        int x = zone.min().getX() + random.nextInt(Math.max(1, zone.max().getX() - zone.min().getX() + 1));
        int z = zone.min().getZ() + random.nextInt(Math.max(1, zone.max().getZ() - zone.min().getZ() + 1));
        for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return false;
            }
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.is(net.minecraft.tags.BlockTags.CROPS) && state.isRandomlyTicking()) {
                state.randomTick(level, pos, level.getRandom());
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------- welcoming ---

    private static final java.util.Map<java.util.UUID, long[]> WELCOME_CACHE = new java.util.HashMap<>();

    /** Whether a loaded member of this settlement is WELCOMING (cached 100 ticks). */
    public static boolean welcoming(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null || AttributeConfig.strength(level.getServer()) <= 0.0D) {
            return false;
        }
        long now = level.getGameTime();
        synchronized (WELCOME_CACHE) {
            long[] cached = WELCOME_CACHE.get(settlement.id);
            if (cached != null && now >= cached[0] && now < cached[0] + 100L) {
                return cached[1] != 0L;
            }
        }
        boolean found = false;
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (member.isAlive() && Trait.any(member.traits(), Trait.Flag.WELCOMING)) {
                found = true;
                break;
            }
        }
        synchronized (WELCOME_CACHE) {
            WELCOME_CACHE.put(settlement.id, new long[] {now, found ? 1L : 0L});
        }
        return found;
    }

    /** Test hook: forget the cached answer. */
    public static void forgetWelcome(Settlement settlement) {
        synchronized (WELCOME_CACHE) {
            WELCOME_CACHE.remove(settlement.id);
        }
    }

    /** A waiting guest's patience: x1.5 with a WELCOMING member. */
    public static long guestPatience(ServerLevel level, Settlement settlement, long patience) {
        return welcoming(level, settlement)
            ? Math.round(patience * AttributeEffects.WELCOME_PATIENCE) : patience;
    }

    /** A tavern visit's length for this patron: x1.5 in a settlement with a WELCOMING member. */
    public static int tavernVisitTicks(SettlerEntity patron, int ticks) {
        if (patron == null || !(patron.level() instanceof ServerLevel level)) {
            return ticks;
        }
        return welcoming(level, patron.settlement())
            ? (int) Math.round(ticks * AttributeEffects.WELCOME_TAVERN_STAY) : ticks;
    }

    /** Persuasion points a WELCOMING member adds with a visitor (not a raider, brute or rival lord). */
    public static int welcomingPoints(ServerLevel level, Settlement settlement, String speakerKind) {
        if (speakerKind != null && (speakerKind.equals("raider") || speakerKind.equals("brute")
            || speakerKind.equals("rival_lord") || speakerKind.equals("captain"))) {
            return 0;
        }
        return welcoming(level, settlement)
            ? (int) Math.round(100.0D * AttributeEffects.WELCOME_PERSUASION) : 0;
    }

    /** WATCHFUL: a real sighting raises the settlement alarm at once. */
    public static boolean criesAlarm(SettlerEntity settler) {
        return settler != null && traitOn(settler) > 0.0D
            && Trait.any(settler.traits(), Trait.Flag.WATCHFUL);
    }

    // ---------------------------------------------------------- combat ---

    /** Strength: melee damage multiplier, 1.0..1.2. */
    public static float meleeGain(SettlerEntity settler) {
        return (float) (1.0D + amount(settler, Effect.MELEE_DAMAGE));
    }

    /** Dexterity: cadence (opener to opener) of a melee move, shortened. */
    public static int meleeCadence(SettlerEntity settler, int ticks) {
        double cut = amount(settler, Effect.MELEE_TEMPO);
        return cut <= 0.0D ? ticks : AttributeEffects.shorten(ticks, cut);
    }

    /** Dexterity: arrow spread, reduced. */
    public static float spread(SettlerEntity settler, float inaccuracy) {
        return (float) (inaccuracy * (1.0D - amount(settler, Effect.RANGED_SPREAD)));
    }

    /**
     * A search or shot range, widened by Perception, the Clear Sight boon and
     * the WATCHFUL trait sight -- together at most +40%.
     */
    public static double range(SettlerEntity settler, double base) {
        if (settler == null) {
            return base;
        }
        double on = traitOn(settler);
        if (on <= 0.0D) {
            return base;
        }
        double gain = amount(settler, Effect.DETECTION_RANGE)
            + boon(settler, AttributeEffects.Boon.CLEAR_SIGHT)
            + AttributeEffects.traitSightGain(Trait.sight(settler.traits()), on);
        return base * (1.0D + Math.min(AttributeEffects.MAX_RANGE_GAIN, gain));
    }

    /** Focus: archer draw / rune mage cast ticks, shortened. */
    public static int drawCast(SettlerEntity settler, int ticks) {
        double cut = amount(settler, Effect.DRAW_CAST_TIME);
        return cut <= 0.0D ? ticks : AttributeEffects.shorten(ticks, cut);
    }

    /** Spirit: a healer's heal, raised. */
    public static float heal(SettlerEntity healer, float base) {
        return (float) (base * (1.0D + amount(healer, Effect.HEAL_AMOUNT)));
    }

    // ----------------------------------------------------------- needs ---

    /** Spirit: a morale LOSS (negative delta) shrunk; gains untouched. */
    public static float moraleDelta(SettlerEntity settler, float delta) {
        if (delta >= 0.0F) {
            return delta;
        }
        // Spirit and the Open Hearth boon together cut at most 35%.
        double cut = Math.min(AttributeEffects.MAX_MORALE_CUT, amount(settler, Effect.MORALE_DECAY)
            + boon(settler, AttributeEffects.Boon.OPEN_HEARTH));
        return (float) (delta * (1.0D - cut));
    }

    /** Stamina (and a NIGHT_OWL at night): energy drained by working, reduced. */
    public static float workingDrain(SettlerEntity settler, float drain) {
        return nightDrain(settler, (float) (drain * (1.0D - amount(settler, Effect.ENERGY_DRAIN))));
    }

    /**
     * Stamina: keeps the max-health modifier in step. Change-detected, so it is
     * cheap to call every needs tick; health is clamped when the max falls.
     */
    public static void applyMaxHealth(SettlerEntity settler) {
        AttributeInstance max = settler.getAttribute(Attributes.MAX_HEALTH);
        if (max == null) {
            return;
        }
        double bonus = Math.round(amount(settler, Effect.MAX_HEALTH) * 2.0D) / 2.0D;
        AttributeModifier current = max.getModifier(STAMINA_HEALTH_ID);
        double have = current == null ? 0.0D : current.amount();
        if (Math.abs(have - bonus) < 1.0E-6D) {
            return;
        }
        if (bonus <= 0.0D) {
            max.removeModifier(STAMINA_HEALTH_ID);
        } else {
            max.addOrUpdateTransientModifier(new AttributeModifier(STAMINA_HEALTH_ID, bonus,
                AttributeModifier.Operation.ADD_VALUE));
        }
        if (settler.getHealth() > settler.getMaxHealth()) {
            settler.setHealth(settler.getMaxHealth());
        }
    }

    // ---------------------------------------------------------- output ---

    /** Strength: extra items per trip for a courier or gatherer. */
    public static int haulBonus(SettlerEntity settler) {
        if (settler == null) {
            return 0;
        }
        return AttributeEffects.whole(Effect.HAUL_CAPACITY,
            settler.attribute(Attribute.STRENGTH), strength(settler));
    }

    /** Perception: one extra item on this gathered unit? Consumes random only when it can pay. */
    public static boolean extraFind(SettlerEntity settler) {
        // Perception and the Careful Work boon together at most 20%.
        // Perception, the Careful Work boon and a GREEN_FINGERS farmer: at most 20%.
        double chance = Math.min(AttributeEffects.MAX_EXTRA_FIND, amount(settler, Effect.EXTRA_FIND)
            + boon(settler, AttributeEffects.Boon.CAREFUL_WORK)
            + (greenFingers(settler) ? AttributeEffects.GREEN_FINGERS_FIND : 0.0D));
        return chance > 0.0D && settler.getRandom().nextDouble() < chance;
    }

    /** Focus: extra research progress (fraction of a session) from a scholar's concentration. */
    public static float study(SettlerEntity settler) {
        return (float) amount(settler, Effect.STUDY);
    }

    /** Presence: morale an innkeeper gives a guest, raised. */
    public static float hospitality(SettlerEntity host, float morale) {
        return (float) (morale * (1.0D + amount(host, Effect.HOSPITALITY)));
    }

    /**
     * Presence: the trader's payout, raised. The fractional part is paid as a
     * chance of one more coin so small sales still feel it.
     */
    public static int tradePayout(SettlerEntity trader, int payout) {
        double gain = amount(trader, Effect.TRADE_PRICE);
        if (payout <= 0 || gain <= 0.0D) {
            return payout;
        }
        double total = payout * (1.0D + gain);
        int whole = (int) Math.floor(total);
        return whole + (trader.getRandom().nextDouble() < total - whole ? 1 : 0);
    }

    /** Who may speak for a settlement in a conversation: the mayor or a Presence-core job. */
    public static boolean speaks(Profession profession) {
        return profession == Profession.TRADER || profession == Profession.INNKEEPER
            || profession == Profession.GUARD;
    }

    /**
     * Presence: persuasion points (0..15) from the settlement's best loaded
     * speaker -- the mayor, a trader, an innkeeper or a guard.
     */
    public static int persuasionPoints(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) {
            return 0;
        }
        double strength = AttributeConfig.strength(level.getServer());
        if (strength <= 0.0D) {
            return 0;
        }
        int best = 0;
        SettlerEntity mayor = Mayor.find(level, settlement);
        if (mayor != null) {
            best = mayor.attribute(Attribute.PRESENCE);
        }
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (speaks(member.getProfession())) {
                best = Math.max(best, member.attribute(Attribute.PRESENCE));
            }
        }
        // Common Voice adds on top of the best speaker; together at most 15 points.
        double points = AttributeEffects.amount(Effect.PERSUASION, best, strength)
            + boonAmount(level, settlement, AttributeEffects.Boon.COMMON_VOICE);
        return (int) Math.round(100.0D * Math.min(AttributeEffects.MAX_PERSUASION, points));
    }
}
