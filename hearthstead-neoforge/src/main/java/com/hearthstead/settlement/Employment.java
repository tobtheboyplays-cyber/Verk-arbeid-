package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Who works where. MineColonies' hire/fire, with the six things it gets wrong
 * fixed — see {@code docs/project/PLAN_EMPLOYMENT.md}.
 *
 * <h2>D-011: employment is a relationship to a BUILDING</h2>
 *
 * <p>The old shape was TekTopia's: a job was an item you used on a person, and
 * the settler carried a {@link Profession} that nothing connected to a room.
 * The new shape is the opposite and it is the better one — <b>you hire a person
 * into a building, and the building decides the trade.</b>
 *
 * <p>So {@link Building#workers} is the <b>only</b> record of employment, and a
 * settler's profession is <b>derived</b> from whichever building lists them.
 * The settler's synced profession is a projection kept for the client (outfit
 * and animation set), the way the plaque's occupancy is: recomputed
 * on the server, never a second source of truth. Physical tools are separate
 * inventory and must be requested and delivered. Two places to write one fact
 * is what the plaque invariant exists to forbid.
 *
 * <p>The other consequences fall out of that: twenty-eight buildings need no
 * writ items or sprites, hiring is commanded at the plaque like everything
 * else, and the player's flow stops dead-ending the moment a building
 * registers.
 */
public final class Employment {

    /** Which shift a guard stands. Civilians are always {@link Watch#DAY}. */
    public enum Watch {
        DAY, NIGHT
    }

    /**
     * What hiring this settler would cost the settlement, in words.
     *
     * <p>MineColonies' worst habit is taking a worker out of another building
     * silently: you find out the farm has no farmer when the bread stops. So
     * the cost is computed <b>before</b> the press and shown in the sentence
     * that offers it.
     *
     * @param loses      the building that would lose them, or null
     * @param leavesEmpty whether that building would be left with no worker
     */
    public record Cost(@Nullable Building loses, boolean leavesEmpty) {
        public static final Cost FREE = new Cost(null, false);

        public Component sentence() {
            if (loses == null) {
                return Component.translatable("hearthstead.employ.cost.none");
            }
            return Component.translatable(leavesEmpty
                    ? "hearthstead.employ.cost.leaves_empty"
                    : "hearthstead.employ.cost.moves",
                loses.type.displayName());
        }
    }

    /** One row of the hire list. */
    public record Candidate(SettlerEntity settler, @Nullable Building current,
                            int fitness, Cost cost, boolean worksHere) {
    }

    /** What a hire did, so the caller can report it truthfully. */
    public record Hired(boolean ok, Cost cost, @Nullable Component refusal) {
        public static Hired refused(String key) {
            return new Hired(false, Cost.FREE, Component.translatable(key));
        }

        public static Hired refused(Component reason) {
            return new Hired(false, Cost.FREE, reason);
        }
    }

    /**
     * Result of giving an emblem directly to a settler.
     *
     * <p>The selected workplace is returned with the normal hire result so the
     * item can tell the player exactly where the settler now works. A refusal
     * never carries a workplace and, critically, never consumes the emblem.
     */
    public record AutoHired(boolean ok, @Nullable Building workplace, Cost cost,
                            @Nullable Component refusal) {
        private static AutoHired refused(Hired hired) {
            return new AutoHired(false, null, Cost.FREE, hired.refusal());
        }

        private static AutoHired refused(Component reason) {
            return new AutoHired(false, null, Cost.FREE, reason);
        }
    }

    // --------------------------------------------------------- the trade ---

    private static final Map<BuildingType, Profession> TRADES =
        new EnumMap<>(BuildingType.class);

    static {
        // Only the trades that are actually implemented. A building whose
        // trade does not exist yet must NOT be hireable: a worker standing in
        // a bakery doing nothing is a worse answer than an honest refusal,
        // and D-014 says a control that cannot act is disabled with a reason
        // rather than quietly doing nothing.
        TRADES.put(BuildingType.FARMHOUSE, Profession.FARMER);
        TRADES.put(BuildingType.LUMBER_CAMP, Profession.LUMBERER);
        TRADES.put(BuildingType.WAREHOUSE, Profession.COURIER);
        TRADES.put(BuildingType.BARRACKS, Profession.GUARD);
        // ARCHER slice, 2026-08-25: the tower is the RANGED post. Both
        // martial buildings hiring GUARD made them near-duplicates (guard
        // audit) -- now the barracks raises the melee line and the
        // watchtower raises archers, and FLOWS' "fletcher ->
        // barracks/watchtower" edge finally has its consumer: the archer's
        // quiver restocks, chest-true, from this building's own containers
        // (ArcherAttackGoal).
        TRADES.put(BuildingType.WATCHTOWER, Profession.ARCHER);

        // CHAINS-1: every building whose work exists in Production.
        TRADES.put(BuildingType.BAKERY, Profession.BAKER);
        TRADES.put(BuildingType.KITCHEN, Profession.COOK);
        TRADES.put(BuildingType.BUTCHER, Profession.BUTCHER);
        TRADES.put(BuildingType.SMELTER, Profession.SMELTER);
        TRADES.put(BuildingType.SMITHY, Profession.SMITH);
        TRADES.put(BuildingType.SAWMILL, Profession.SAWYER);
        TRADES.put(BuildingType.CARPENTER, Profession.CARPENTER);
        TRADES.put(BuildingType.MASON, Profession.MASON);
        TRADES.put(BuildingType.FLETCHER, Profession.FLETCHER);
        TRADES.put(BuildingType.WEAVER, Profession.WEAVER);
        TRADES.put(BuildingType.TANNERY, Profession.TANNER);
        TRADES.put(BuildingType.MINE, Profession.MINER);

        // SLICE RECRUIT-1: the tavern's own trade. Production has no recipe
        // for hospitality and never will, so the innkeeper does not run
        // through CrafterWorkGoal like the twelve above -- see
        // InnkeeperWorkGoal for the goal built for that shape instead.
        TRADES.put(BuildingType.TAVERN, Profession.INNKEEPER);
        TRADES.put(BuildingType.TRADING_POST, Profession.TRADER);

        // SLICE RESEARCH-1: the architects' study's own trade. The scholar
        // does not run through CrafterWorkGoal -- there is no Production
        // recipe table for research, a project is a multi-day undertaking
        // rather than a batch -- see ScholarWorkGoal for the goal built for
        // that shape, and com.hearthstead.settlement.research.Research for
        // the state it advances.
        TRADES.put(BuildingType.ARCHITECTS_STUDY, Profession.SCHOLAR);

        // Coordinator addendum, 2026-08-25: MILL and BREWERY both gained
        // real recipe tables in Production (SLICE CHAINS) and both run
        // through CrafterWorkGoal exactly like the twelve original crafting
        // trades -- they only needed a trade on this map to become hireable.
        TRADES.put(BuildingType.MILL, Profession.MILLER);
        TRADES.put(BuildingType.BREWERY, Profession.BREWER);

        // ARMOURY-3: the armoury's Production table (eight recipes,
        // ARMOURY-2) had no trade on this map, so hire() refused every
        // attempt with no_trade and CrafterWorkGoal never had anyone to
        // send there -- see docs/project/PLAN_CIRCULATION.md's "still open,
        // MILITARY-OUT-adjacent" entry. Same shape of follow-up as MILL and
        // BREWERY just above.
        TRADES.put(BuildingType.ARMOURY, Profession.ARMOURER);

        // TRADES-1 (SURVIVAL_AUDIT F1): PASTURE, FISHERY and HUNTERS_LODGE
        // named alongside FARMHOUSE/LUMBER_CAMP/MINE as Ring-1 sources in
        // FLOWS.md, but had "no worker code at all, wired or not" -- these
        // three buildings could be planned, built and validated, and would
        // then sit empty forever. HerderWorkGoal/FisherWorkGoal/HunterWorkGoal
        // are the goals built for their shapes (none is Production-shaped, so
        // none runs through CrafterWorkGoal).
        TRADES.put(BuildingType.PASTURE, Profession.HERDER);
        TRADES.put(BuildingType.FISHERY, Profession.FISHER);
        TRADES.put(BuildingType.HUNTERS_LODGE, Profession.HUNTER);
        // BUILDER lane: the hut is the depot couriers fill; the work is the site.
        TRADES.put(BuildingType.BUILDERS_HUT, Profession.BUILDER);
        // BATTLE-ROLES (plan/BATTLE-ROLES.md): one hall per battlefield role.
        TRADES.put(BuildingType.PIKE_YARD, Profession.SPEARMAN);
        TRADES.put(BuildingType.SWORD_HALL, Profession.LONGSWORDSMAN);
        TRADES.put(BuildingType.INFIRMARY, Profession.HEALER);
        TRADES.put(BuildingType.RUNE_HALL, Profession.RUNE_MAGE);
    }

    /**
     * The motion a trade actually performs, which is what gets animated.
     *
     * <p>D-015: clips are keyed to the action, not the job title. A butcher and
     * a tanner both cleave at a bench; a smith and a mason both swing a hammer
     * at a hard surface. Eleven trades, six real actions — and none of them is
     * a generic work loop, which is what the invariant is actually protecting.
     */
    public static SettlerActivity motionOf(BuildingType type) {
        return switch (tradeOf(type)) {
            case BAKER -> SettlerActivity.WORK_OVEN;
            case COOK -> SettlerActivity.WORK_STIR;
            case BUTCHER -> SettlerActivity.WORK_CLEAVE;
            case TANNER -> SettlerActivity.WORK_SCRAPE;
            case SMELTER -> SettlerActivity.WORK_STOKE;
            case MINER -> SettlerActivity.WORK_MINE;
            case SMITH -> SettlerActivity.WORK_HAMMER;
            case MASON -> SettlerActivity.WORK_CHISEL;
            case SAWYER -> SettlerActivity.WORK_SAW;
            case CARPENTER -> SettlerActivity.WORK_PLANE;
            case WEAVER -> SettlerActivity.WORK_WEAVE;
            case FLETCHER -> SettlerActivity.WORK_FLETCH;
            // RECRUIT-1: reuses the courier's tidying motion rather than a
            // bespoke one -- D-016's signature-motion pass never reached the
            // tavern, so a real INNKEEPER clip (working the bar, greeting a
            // guest) is future work, not this slice's.
            case INNKEEPER, TRADER -> SettlerActivity.SORTING;
            // RESEARCH-1: FINE_WORK's close, careful hand motion is the
            // closest existing clip to a scholar bent over a lectern -- its
            // activity key is WORK_WEAVE (see SettlerEntity#setupAnimationStates,
            // which animates fineWorkState on it). A dedicated WRITE clip
            // (quill moving, page turning) is future signature-motion work,
            // the same footnote as INNKEEPER's above.
            case SCHOLAR -> SettlerActivity.WORK_WEAVE;
            // Coordinator addendum: the miller works stones and sacks, which
            // reads as the same press-and-turn motion WORK_KNEAD already is;
            // the brewer tends a mash over heat, which reads as WORK_STOKE.
            // Both are reuses, not bespoke clips -- future signature-motion
            // work, exactly like SCHOLAR and INNKEEPER above.
            case MILLER -> SettlerActivity.WORK_KNEAD;
            case BREWER -> SettlerActivity.WORK_STOKE;
            // ARCHER slice: the archer's trade motion is the watch itself --
            // PATROLLING keys GUARD_STANCE when standing (the held aim pose
            // of a drawn shot) and the patrol walk when moving, exactly the
            // states the guard trade already animates. Bespoke ARCHER_AIM /
            // ARCHER_SHOOT clips are the polish worker's next cycle, the
            // same footnote as INNKEEPER and SCHOLAR above -- never a
            // generic work loop, which is what this map exists to forbid.
            case ARCHER -> SettlerActivity.PATROLLING;
            // BATTLE-ROLES: fighters stand watch like the Archer until the
            // role clips land; the Healer's bench work is fine hand work.
            case SPEARMAN, LONGSWORDSMAN, RUNE_MAGE -> SettlerActivity.PATROLLING;
            case HEALER -> SettlerActivity.WORK_WEAVE;
            // ARMOURY-3: an armourer hammering plate at an anvil is the
            // same physical act as a smith hammering a blade at one -- the
            // existing HAMMER_ANVIL clip (WORK_HAMMER's SettlerAnimations
            // clip, hammerState) fits exactly, so this reuses it rather
            // than authoring a bespoke one, the same call already made for
            // INNKEEPER/SCHOLAR/MILLER/BREWER/ARCHER above. soundOf/
            // soundPeriodOf/soundContactOf below key off this motion, not
            // the trade, so ANVIL_RING at its contact tick (9) follows for
            // free -- no separate entry needed in any of the three tables
            // below.
            case ARMOURER -> SettlerActivity.WORK_HAMMER;
            // TRADES-1: none of these three run through CrafterWorkGoal (no
            // Production recipe backs any of them -- there is real world to
            // work, not a bench), so this table entry exists for the same
            // reason MINER's own motionOf entry does even though
            // MinerWorkGoal also sets its activity directly: a documented,
            // queryable answer to "what does this trade do" for tests and
            // any future UI, and the invariant this file's own
            // everyTradeHasWorkAndAMotionOfItsOwn test enforces (no trade
            // reads as standing still). Each maps to that trade's actual
            // SIGNATURE action -- shearing, casting, loosing a shot -- the
            // other three HERDER actions (feeding, egg collection, culling)
            // reuse WORK_SOW/PICKUP_STOW/WORK_CLEAVE directly in
            // HerderWorkGoal, justified there.
            case HERDER -> SettlerActivity.WORK_SHEAR;
            case FISHER -> SettlerActivity.WORK_FISH;
            case HUNTER -> SettlerActivity.WORK_HUNT;
            // BUILDER lane: reach, set, tap -- BuilderWorkGoal also plays
            // WORK_BUILD_HAMMER on roofs/frames and CARRY_MATERIALS en route.
            case BUILDER -> SettlerActivity.WORK_BUILD;
            default -> SettlerActivity.IDLE;
        };
    }

    /**
     * The sound a trade's work makes.
     *
     * <p>Job standard, point 6: you should be able to tell what somebody is
     * doing with your eyes shut. Each of these is a different physical story —
     * metal ringing, air moving, a blade rasping — rather than one thud
     * re-tuned, because subtle variations of the same noise smear into one
     * noise at any distance.
     */
    /**
     * Whether this trade's work actually happens AT its building.
     *
     * <p>A baker bakes in the bakery and a miner cuts stone under the mine
     * entrance, so sending them to their building is sending them to work. A
     * farmer's work is in the fields and a lumberjack's is wherever the trees
     * are — for them the building is a base, not a workplace.
     *
     * <p>Found by watching: a hired lumberjack orbited his camp instead of
     * felling anything, because the schedule reclaimed him the moment each
     * felling stint ended.
     */
    public static boolean worksAtTheBuilding(BuildingType type) {
        return switch (tradeOf(type)) {
            // FISHER works the water's edge and HUNTER ranges the wild --
            // neither is ever standing at their own building while working,
            // the same shape as FARMER/LUMBERER above. HERDER is the
            // opposite: the paddock IS the building's own bounds, so a
            // herder tending it is standing at their post exactly the way a
            // miner cutting under the mine entrance is (MINE isn't listed
            // here either, for the same reason -- see MinerWorkGoal).
            case FARMER, LUMBERER, FISHER, HUNTER, TRADER, BUILDER -> false;
            default -> true;
        };
    }

    public static net.minecraft.sounds.SoundEvent soundOf(BuildingType type) {
        // RESEARCH-1: soundOf keys off the shared MOTION, not the trade, and
        // the scholar's motion IS WORK_WEAVE (motionOf's own reuse above) --
        // so without this branch a scholar would ring with the weaver's
        // LOOM_CLACK, which reads as a mechanical clack rather than a pen.
        // FEATHER_PINCH (the fletcher's own sound, at a slower period below)
        // is the existing catalogue entry that actually fits: a quill IS a
        // feather, and the fletcher's soft pinch-and-set is closer to a
        // scratching nib than any loom, forge or bench sound in the table.
        // Sound pass (sound-gen/SOUNDS.md): trades that borrow a neighbour's
        // MOTION now keep their own contact voice. Only the sound changes;
        // soundPeriodOf/soundContactOf still key off the motion, so every
        // beat lands on the same tick as before.
        switch (tradeOf(type)) {
            case SCHOLAR -> { return com.hearthstead.registry.ModSounds.WORK_QUILL_SCRATCH.get(); }
            case ARMOURER -> { return com.hearthstead.registry.ModSounds.WORK_PLATE_HAMMER.get(); }
            case MILLER -> { return com.hearthstead.registry.ModSounds.WORK_QUERN_GRIND.get(); }
            case BREWER -> { return com.hearthstead.registry.ModSounds.WORK_MASH_STIR.get(); }
            case HEALER -> { return com.hearthstead.registry.ModSounds.WORK_PESTLE_GRIND.get(); }
            case TRADER -> { return com.hearthstead.registry.ModSounds.WORK_LEDGER_TALLY.get(); }
            case INNKEEPER -> { return com.hearthstead.registry.ModSounds.WORK_BAR_WIPE.get(); }
            default -> { }
        }
        return switch (motionOf(type)) {
            case WORK_HAMMER -> com.hearthstead.registry.ModSounds.ANVIL_RING.get();
            // Coordinator addendum: MILLER (WORK_KNEAD) and BREWER
            // (WORK_STOKE) both fall straight through this motion-keyed
            // switch and land on the same sounds as BAKER and SMELTER --
            // working sacks and stones is close enough to kneading's press,
            // and tending a mash over heat is close enough to a bellows, to
            // reuse rather than invent (the SORTING/INNKEEPER precedent
            // below is the same call). Future signature-motion work, not
            // this slice's.
            case WORK_STOKE -> com.hearthstead.registry.ModSounds.BELLOWS_PUFF.get();
            case WORK_SAW -> com.hearthstead.registry.ModSounds.SAW_STROKE.get();
            case WORK_OVEN -> com.hearthstead.registry.ModSounds.OVEN_SLIDE.get();
            case WORK_KNEAD -> com.hearthstead.registry.ModSounds.KNEAD_PRESS.get();
            case WORK_CLEAVE -> com.hearthstead.registry.ModSounds.CLEAVER_CHOP.get();
            case WORK_WEAVE -> com.hearthstead.registry.ModSounds.LOOM_CLACK.get();
            case WORK_MINE -> com.hearthstead.registry.ModSounds.PICK_STRIKE.get();
            // The last five trades' own voices (JOB_STANDARD point 6,
            // catalogue §20): each is a different physical story synthesized
            // for its own motion, not a neighbour's sound re-tuned.
            case WORK_STIR -> com.hearthstead.registry.ModSounds.POT_STIR.get();
            case WORK_PLANE -> com.hearthstead.registry.ModSounds.PLANE_SHAVE.get();
            case WORK_CHISEL -> com.hearthstead.registry.ModSounds.CHISEL_TAP.get();
            case WORK_FLETCH -> com.hearthstead.registry.ModSounds.FEATHER_PINCH.get();
            case WORK_SCRAPE -> com.hearthstead.registry.ModSounds.HIDE_SCRAPE.get();
            // SORTING is shared with the courier's warehouse tidying, and
            // there is no bespoke tavern sound yet -- the same stow-and-shift
            // clink reads as a bar being kept, not a stack being counted.
            case SORTING -> com.hearthstead.registry.ModSounds.CHEST_STOW.get();
            // TRADES-1: reused, same as the block above -- no new sound
            // assets, each a documented borrow. HerderWorkGoal/FisherWorkGoal/
            // HunterWorkGoal play these directly rather than through this
            // table (none is CrafterWorkGoal-shaped), so these entries exist
            // for the same documentation/query reason motionOf's do.
            // HIDE_SCRAPE's rasp is the closest existing sound to blade-on-
            // wool; WATER_POUR is already the mod's one water sound; a bow's
            // string has no existing catalogue entry, so PICK_STRIKE's sharp
            // transient stands in for the loose.
            case WORK_SHEAR -> com.hearthstead.registry.ModSounds.HIDE_SCRAPE.get();
            case WORK_FISH -> com.hearthstead.registry.ModSounds.WATER_POUR.get();
            case WORK_HUNT -> com.hearthstead.registry.ModSounds.PICK_STRIKE.get();
            // BUILDER lane: the tap that seats a placed block (nail_tap reused).
            case WORK_BUILD -> com.hearthstead.registry.ModSounds.NAIL_TAP.get();
            default -> com.hearthstead.registry.ModSounds.KNEAD_PRESS.get();
        };
    }

    /**
     * How often that sound repeats, in ticks — the clip's own loop length, so
     * the sound lands on the motion rather than on a timer of its own.
     */
    public static int soundPeriodOf(BuildingType type) {
        // Anim lane: trades that share a MOTION but now play their OWN clip (SettlerModel
        // tradeClip) ring on their own clip's loop and contact.
        Integer own = ownClipPeriod(tradeOf(type));
        if (own != null) return own;
        if (tradeOf(type) == Profession.SCHOLAR) {
            // Slower than the fletcher's own 32 -- a quiet, thoughtful
            // scratch of a quill, not a workshop's steady rhythm.
            return 40;
        }
        return switch (motionOf(type)) {
            case WORK_HAMMER -> 20;
            case WORK_STOKE -> 28;
            case WORK_SAW -> 22;
            case WORK_OVEN -> 32;
            case WORK_KNEAD -> 24;
            case WORK_CLEAVE -> 17;
            case WORK_WEAVE -> 18;
            case WORK_MINE -> 19;
            case WORK_STIR -> 30;
            case WORK_PLANE -> 26;
            case WORK_CHISEL -> 21;
            case WORK_FLETCH -> 32;
            case WORK_SCRAPE -> 24;
            // TRADES-1: each trade's own clip length in ticks (HERDER_SHEAR
            // 1.00s, FISHER_CAST 2.00s, HUNTER_LOOSE 1.20s) -- see the same
            // entries' comment on soundOf above for why this table exists
            // even though none of the three goals reads it directly.
            case WORK_SHEAR -> 20;
            case WORK_FISH -> 40;
            case WORK_HUNT -> 24;
            case WORK_BUILD -> 32;   // BUILD_PLACE loop 1.60s
            default -> 24;
        };
    }

    /**
     * The tick WITHIN each loop where the trade's sound belongs — the clip's
     * contact beat, out of the catalogue's own documentation (§7.2, §8.1,
     * §18, §20).
     *
     * <p>Why this exists (audit F8, found independently on mason, smelter
     * and cook): firing "once per period" at {@code workedTicks % period == 0}
     * lands the sound on the LOOP SEAM — the rest pose — half a cycle from
     * the visible strike. The job standard's point 6 wants the thock on the
     * blow. MinerWorkGoal's {@code cutTicks % 19 == 9} was the one goal that
     * did it right; this table gives every crafter trade the same treatment.
     *
     * <p>Ticks marked (est.) are read from the catalogue's prose rather than
     * an exact accent line — the animation owner trues them up whenever a
     * clip is retimed, and the value must always stay in [1, period-1] so it
     * can never alias back onto the seam.
     */
    public static int soundContactOf(BuildingType type) {
        Integer own = ownClipContact(tradeOf(type));
        if (own != null) return own;
        if (tradeOf(type) == Profession.SCHOLAR) {
            // Period 40 over an 18-tick clip: the quill scratch is sparser
            // than the loop by design, so seam alignment does not exist --
            // mid-period simply keeps it off the wrap.
            return 20;
        }
        return switch (motionOf(type)) {
            case WORK_HAMMER -> 9;   // §18.4: strike 0.30-0.45s, first hold tick
            case WORK_STOKE -> 7;    // §18.3: bellows_puff peaks ~0.28s after onset -> peak on the 0.60s full compression
            case WORK_SAW -> 9;      // §18.5: 0.45s reversal hold ends, fast 0.45-0.80s stroke carries the rasp
            case WORK_OVEN -> 5;     // §18.8: oven_slide onset on the 0.25-0.50s peel push into the mouth
            case WORK_KNEAD -> 9;    // §18.1: right palm bottoms out at 0.45s (keyframe -88deg)
            case WORK_CLEAVE -> 9;   // §18.2: blade meets the board at the 0.45s LINEAR snap
            case WORK_WEAVE -> 9;    // §18.6: deeper second pass, mid-loop (est.)
            case WORK_MINE -> 9;     // §8.1: pick_strike t=0.45s -- MinerWorkGoal's own tick
            case WORK_STIR -> 24;    // §7.2: pot_stir accent documented at t=1.20s
            case WORK_PLANE -> 3;    // §20.2: plane_shave swells over the 0.15-0.45s push stroke
            case WORK_CHISEL -> 10;  // §20.3: strike lands 0.45-0.50s, hold from 0.50s
            case WORK_FLETCH -> 15;  // §20.4: middle pinch of three, t=0.75s
            case WORK_SCRAPE -> 4;   // §20.5: hide_scrape starts with the 0.20-0.50s draw stroke
            // TRADES-1: HERDER_SHEAR's snip lands at t=0.45s of its 1.00s
            // loop; FISHER_CAST's bite at t=1.45s of its 2.00s loop;
            // HUNTER_LOOSE's release at t=0.70s of its non-looping 1.20s
            // physical shot -- all
            // exact accent ticks straight from each clip's own keyframes
            // (catalogue §24), not estimates.
            case WORK_SHEAR -> 9;
            case WORK_FISH -> 29;
            case WORK_HUNT -> 14;
            case WORK_BUILD -> 18;   // BUILD_PLACE first tap at 0.90s
            default -> 12;           // never 0: the seam is the one wrong answer
        };
    }

    /** The attribute a trade's work trains, so doing the job makes you better at it. */
    public static Attribute trainedBy(BuildingType type) {
        return switch (tradeOf(type)) {
            // ARMOURY-3: hammering plate is the same STRENGTH-trained work
            // as the smithy's own hammering, right beside it below.
            case SMITH, MASON, SMELTER, LUMBERER, MINER, ARMOURER -> Attribute.STRENGTH;
            case COURIER, GUARD -> Attribute.STAMINA;
            case BAKER, COOK, BUTCHER, TANNER, SAWYER, CARPENTER,
                 FLETCHER, WEAVER, FARMER,
                 // Coordinator addendum: grinding grain and working a mash
                 // are the same "fine manual work" this whole group already
                 // covers, not a strength or judgement trade.
                 MILLER, BREWER,
                 // TRADES-1: shearing, baiting a line and reading a paddock
                 // are hands, not force -- the same fine-manual-work group
                 // FARMER already anchors.
                 HERDER, FISHER -> Attribute.DEXTERITY;
            // Keeping guests waiting happily is a social skill, not a
            // physical one -- the same reason WITS is what fitness for the
            // post is measured against below, in keyAttributeOf's default.
            case INNKEEPER -> Attribute.WITS;
            // RESEARCH-1: judgement, explicitly -- named the same way
            // INNKEEPER is above, rather than left to fall through the
            // default, because a reader should never have to wonder whether
            // a brand-new trade's attribute was a deliberate choice.
            case SCHOLAR -> Attribute.WITS;
            // ARCHER slice: named explicitly for the same reason. A shot is
            // hands, not force -- and ArcherRank reads DEXTERITY, so the
            // trade's own work must be what climbs its ladder (the exact
            // lesson GuardRank's training constants document for STRENGTH).
            case ARCHER -> Attribute.DEXTERITY;
            // TRADES-1: a hunt is stalking and a clean shot, the same
            // hands-not-force reasoning as ARCHER right above -- named
            // explicitly rather than folded into the big DEXTERITY group for
            // the same reason ARCHER is.
            case HUNTER -> Attribute.DEXTERITY;
            // BATTLE-ROLES: the blades climb GuardRank (Strength); the Healer
            // steadies (Spirit); the mage concentrates (Focus).
            case SPEARMAN, LONGSWORDSMAN -> Attribute.STRENGTH;
            case HEALER -> Attribute.SPIRIT;
            case RUNE_MAGE -> Attribute.FOCUS;
            // BUILDER lane: setting blocks true is hands, not force.
            case BUILDER -> Attribute.DEXTERITY;
            default -> Attribute.WITS;
        };
    }

    /** The trade practised in this kind of building, or NONE if none yet is. */
    /**
     * Loop length (ticks) of a trade's OWN work clip where it no longer plays the shared motion's
     * clip (client: SettlerModel#tradeClip). Null = the motion's clip. BREWER: BREW_MASH 1.60 s.
     */
    @javax.annotation.Nullable
    public static Integer ownClipPeriod(Profession trade) {
        return switch (trade) {
            case BREWER -> 32;
            case WEAVER -> 16;      // LOOM_WEAVE: one throw-and-beat pass per 16 ticks
            default -> null;
        };
    }

    /** Contact tick of that own clip (BREW_MASH: the paddle at the far side of the tun, 0.80 s). */
    @javax.annotation.Nullable
    public static Integer ownClipContact(Profession trade) {
        return switch (trade) {
            case BREWER -> 16;
            case WEAVER -> 12;      // the beater pulled home, 0.60 s into each pass
            default -> null;
        };
    }

    public static Profession tradeOf(BuildingType type) {
        return TRADES.getOrDefault(type, Profession.NONE);
    }

    public static boolean teaches(BuildingType type) {
        return tradeOf(type) != Profession.NONE;
    }

    // ------------------------------------------------------- the relation ---

    /** The building that employs this settler, or null. The one lookup. */
    @Nullable
    public static Building employerOf(Settlement settlement, UUID settler) {
        if (settlement == null || settler == null) {
            return null;
        }
        Building employer = null;
        for (Building building : settlement.buildings) {
            if (building.workers.contains(settler)) {
                if (employer != null) {
                    return null; // one worker cannot have two authorities
                }
                employer = building;
            }
        }
        if (employer == null) {
            return null;
        }
        int identityMatches = 0;
        for (Building building : settlement.buildings) {
            if (employer.id.equals(building.id) && ++identityMatches > 1) {
                return null; // duplicate persisted identity: fail closed
            }
        }
        return employer;
    }

    /** A retained legacy workplace can wait for safe physical handover. */
    public static boolean mayorHandoverPending(Settlement settlement, UUID member) {
        return settlement != null && member != null && member.equals(settlement.mayorId)
            && settlement.buildings.stream().anyMatch(building -> building.workers.contains(member));
    }

    /** The profession this settler should have, derived from their employer. */
    public static Profession professionOf(Settlement settlement, UUID settler) {
        if (settlement != null && settler != null
            && settler.equals(settlement.mayorId)) {
            return Profession.MAYOR;
        }
        Building employer = employerOf(settlement, settler);
        return employer == null ? Profession.NONE : tradeOf(employer.type);
    }

    /**
     * The authoritative workplace for logistics.  A seated Mayor may help
     * one valid Warehouse without becoming an ordinary employee: retaining
     * MAYOR is what keeps their office, boon and Mayor UI authoritative.
     */
    @Nullable
    public static Building courierWorkplace(Settlement settlement, SettlerEntity settler) {
        if (settlement == null || settler == null) return null;
        if (settler.getProfession() == Profession.COURIER
            && professionOf(settlement, settler.getUUID()) == Profession.COURIER) {
            Building employer = employerOf(settlement, settler.getUUID());
            return validWarehouse(employer) ? employer : null;
        }
        if (!settler.getUUID().equals(settlement.mayorId)
            || settler.getProfession() != Profession.MAYOR
            || settlement.mayorCourierWarehouseId == null) {
            return null;
        }
        for (Building building : settlement.buildings) {
            if (building != null && settlement.mayorCourierWarehouseId.equals(building.id)
                && validWarehouse(building)) return building;
        }
        return null;
    }

    /**
     * Starts Mayor courier help at a real Warehouse.
     *
     * <p>The Mayor is not ordinary Warehouse employment: retaining MAYOR is
     * what preserves the office and its authority. Requiring an unused worker
     * post here therefore made the automatic settlement logistics fallback
     * disappear exactly when a Warehouse was fully staffed. The request
     * ledger still gives every physical trip one owner, so this does not let
     * the Mayor duplicate a normal Courier's cargo.
     */
    @Nullable
    public static Building ensureMayorCourierWorkplace(ServerLevel level,
                                                        Settlement settlement,
                                                        SettlerEntity settler) {
        Building existing = courierWorkplace(settlement, settler);
        if (existing != null || settlement == null || settler == null
            || !settler.getUUID().equals(settlement.mayorId)
            || settler.getProfession() != Profession.MAYOR) return existing;
        Building selected = settlement.buildings.stream()
            .filter(Employment::validWarehouse)
            .min(Comparator.comparingDouble((Building building) ->
                    settler.blockPosition().distSqr(building.plaquePos))
                .thenComparingInt(building -> building.plaquePos.getX())
                .thenComparingInt(building -> building.plaquePos.getY())
                .thenComparingInt(building -> building.plaquePos.getZ())
                .thenComparing(building -> building.id))
            .orElse(null);
        if (selected != null) {
            settlement.mayorCourierWarehouseId = selected.id;
            SettlementManager.data(level).setDirty();
        }
        return selected;
    }

    /**
     * Ordinary employment capacity. A Mayor logistics authority is not a
     * worker post, so Mayor-first and Courier-first settlements have the same
     * available Warehouse staffing.
     */
    public static boolean hasVacancy(Settlement settlement, Building building) {
        if (building == null) return false;
        return building.workers.size() < building.workerCapacity();
    }

    private static boolean validWarehouse(@Nullable Building building) {
        return building != null && building.valid && building.type == BuildingType.WAREHOUSE;
    }

    /**
     * Puts the derived profession back onto the settler's synced projection.
     *
     * <p>Call after anything that could change employment — hiring, dismissal,
     * a building dissolving, a settler loading back in. Doing nothing when it
     * already agrees keeps this cheap enough to call freely.
     */
    public static void refresh(Settlement settlement, SettlerEntity settler) {
        // Legacy seats keep their UUID and physical gear. Ordinary dismissal
        // owns arrow return; refusal retains the roster for a later retry.
        // The mayor projection below prevents the old job from executing even
        // while an unavailable physical return delays freeing its workplace.
        if (settler.getUUID().equals(settlement.mayorId)
            && settler.level() instanceof ServerLevel level
            && employerOf(settlement, settler.getUUID()) != null) {
            if (dismiss(level, settlement, settler) != null) {
                settlement.guardOrders.clear(settler.getUUID());
            }
        }
        Building current = employerOf(settlement, settler.getUUID());
        Profession currentProfession = current == null ? Profession.NONE
            : tradeOf(current.type);
        if (settlement.employmentAuthorizations.receipt(settler.getUUID())
                != null
            && (current == null || !settlement.employmentAuthorizations.matches(
                settlement.id, settler.getUUID(), current.id,
                currentProfession))) {
            settlement.employmentAuthorizations.clear(settler.getUUID());
        }
        if (settler.level() instanceof ServerLevel level
            && settler.carriedArrowCount() > 0
            && (current == null
                || !settler.carriedArrowsOwnedBy(current.id))) {
            settler.releaseCarriedArrows(level, settlement);
        }
        Profession should = professionOf(settlement, settler.getUUID());
        if (settler.getProfession() != should) {
            settler.setProfessionProjection(should);
        }
    }

    // ------------------------------------------------------------ hiring ---

    /**
     * What taking this settler would cost. Pure — call it to draw a button.
     */
    public static Cost costOfHiring(Settlement settlement, SettlerEntity settler) {
        Building current = employerOf(settlement, settler.getUUID());
        if (current == null) {
            return Cost.FREE;
        }
        return new Cost(current, current.workers.size() <= 1);
    }

    /**
     * Administrative and GameTest hiring seam.
     *
     * <p>This deliberately does not charge an emblem: the permission-level-2
     * {@code /hearthstead hire} QA command and deterministic fixtures need a
     * way to construct employment state. Ordinary player code must call
     * {@link #hireWithHeldEmblem} instead. Keeping that distinction explicit
     * prevents a test convenience from quietly becoming a free player path.
     *
     * <p>Atomic in the way that matters: they leave their old post in the same
     * operation that gives them the new one, so there is no instant in which a
     * settler holds two jobs or none.
     */
    public static Hired hire(ServerLevel level, Settlement settlement,
                             Building building, SettlerEntity settler) {
        Hired refusal = validateCoreHire(settlement, building, settler);
        return refusal == null
            ? commitHire(level, settlement, building, settler, true)
            : refusal;
    }

    /**
     * Player-facing hire/reassignment using the physical emblem visibly held
     * in the player's selected main hand.
     *
     * <p>Every live-world, membership, roster, capacity and emblem check runs
     * before the first mutation. The server thread then commits the move once
     * and shrinks that exact live hand stack once. A wrong/missing emblem, a
     * full or invalid workplace, a corrupt roster, or a replay against the now
     * occupied destination returns before either side changes. We intentionally
     * do not search the wider inventory: the player can see exactly which
     * physical authorization they are spending, and a refusal can never eat a
     * surprising item from another slot.
     */
    public static Hired hireWithHeldEmblem(ServerLevel level,
                                           Settlement settlement,
                                           Building building,
                                           SettlerEntity settler,
                                           ServerPlayer player) {
        Hired liveRefusal = validateLivePlayerHire(level, settlement, building,
            settler, player);
        if (liveRefusal != null) {
            return liveRefusal;
        }
        Hired coreRefusal = validateCoreHire(settlement, building, settler);
        if (coreRefusal != null) {
            return coreRefusal;
        }

        Profession expected = tradeOf(building.type);
        ItemStack expectedStack = JobEmblemItem.stackFor(expected);
        if (expectedStack.isEmpty()) {
            return Hired.refused(Component.translatable(
                "hearthstead.employ.refused.emblem_unavailable",
                expected.displayName()));
        }

        ItemStack held = player.getMainHandItem();
        Profession heldProfession = JobEmblemItem.professionOf(held);
        if (heldProfession == null) {
            return Hired.refused(Component.translatable(
                "hearthstead.employ.refused.emblem_missing",
                expectedStack.getHoverName()));
        }
        if (heldProfession != expected) {
            return Hired.refused(Component.translatable(
                "hearthstead.employ.refused.emblem_wrong",
                expectedStack.getHoverName(), held.getHoverName()));
        }

        var journeyProvenance = JourneyEmblemProvenance.read(held)
            .filter(provenance -> provenance.settlementId().equals(settlement.id)
                && provenance.profession() == expected);
        boolean employmentReturn = journeyProvenance
            .map(JourneyEmblemProvenance.Provenance::employmentReturn)
            .orElse(false);
        if (journeyProvenance.isPresent()
            && !settlement.employmentAuthorizations.canAuthorize(settlement.id,
                settler.getUUID(), building.id, expected,
                journeyProvenance.orElseThrow().transactionId())) {
            return Hired.refused(
                "hearthstead.employ.refused.authorization_ledger");
        }

        Hired hired = commitHire(level, settlement, building, settler,
            journeyProvenance.isEmpty());
        if (!hired.ok()) {
            return hired;
        }
        // No other task can touch a player's inventory midway through one
        // server-thread action. This is therefore the same stack validated
        // above, after the one successful roster commit and before returning
        // control to the event loop.
        if (!JobEmblemItem.consumeOneIfMatches(held, expected)) {
            throw new IllegalStateException("Validated job emblem changed during hire commit");
        }
        if (journeyProvenance.isPresent()
            && !settlement.employmentAuthorizations.authorize(settlement.id,
                settler.getUUID(), building.id, expected,
                journeyProvenance.orElseThrow().transactionId())) {
            throw new IllegalStateException(
                "Validated employment authorization changed during hire commit");
        }
        // Only the charged, ordinary-player path may advance onboarding. The
        // raw permission-level-2/GameTest seam deliberately stops at roster
        // construction and can never impersonate a consumed job emblem.
        if (!employmentReturn) {
            FoundingJourneyProgress.noteLumbererHired(level, settlement, building,
                settler);
            journeyProvenance.ifPresent(provenance ->
                JourneyServerHooks.noteChargedJobBinding(player, settlement,
                    building, settler, provenance.transactionId()));
            // This is deliberately after the exact held stack shrank. The raw
            // admin/GameTest seam reaches commitHire but can never author this
            // first-raid proof, and a rejected/replayed packet never reaches it.
            settlement.firstRaidReadiness.noteConsumedLumbererEmblemHire(
                level, settlement, building, settler);
        }
        return hired;
    }

    /**
     * Canonical ordinary-player job flow: give the physical emblem to the
     * person, then let Hearthstead choose their nearest valid compatible post.
     *
     * <p>This deliberately performs no partial fallback. A missing, invalid or
     * full compatible workplace leaves both the roster and the exact held item
     * untouched. If several posts are available, distance to the settler wins;
     * plaque coordinates and UUID provide deterministic tie-breakers so a
     * reload cannot silently choose a different workplace.
     */
    public static AutoHired autoHireWithHeldEmblem(ServerLevel level,
                                                   Settlement settlement,
                                                   SettlerEntity settler,
                                                   ServerPlayer player) {
        Hired liveRefusal = validateLivePlayerContext(level, settlement, settler, player);
        if (liveRefusal != null) {
            return AutoHired.refused(liveRefusal);
        }

        ItemStack held = player.getMainHandItem();
        Profession intended = JobEmblemItem.professionOf(held);
        if (intended == null) {
            return AutoHired.refused(Component.translatable(
                "hearthstead.employ.refused.emblem_missing_any"));
        }
        ItemStack expectedStack = JobEmblemItem.stackFor(intended);
        if (expectedStack.isEmpty()) {
            return AutoHired.refused(Component.translatable(
                "hearthstead.employ.refused.emblem_unavailable",
                intended.displayName()));
        }

        Building current = employerOf(settlement, settler.getUUID());
        if (current != null && tradeOf(current.type) == intended) {
            return AutoHired.refused(Component.translatable(
                "hearthstead.employ.refused.already_at",
                settler.getDisplayName(), current.type.displayName()));
        }

        List<Building> matching = new ArrayList<>();
        List<Building> ready = new ArrayList<>();
        for (Building building : settlement.buildings) {
            if (tradeOf(building.type) != intended) {
                continue;
            }
            matching.add(building);
            if (building.valid && hasVacancy(settlement, building)) {
                ready.add(building);
            }
        }

        if (ready.isEmpty()) {
            if (matching.isEmpty()) {
                return AutoHired.refused(Component.translatable(
                    "hearthstead.employ.refused.no_compatible_workplace",
                    intended.displayName()));
            }
            boolean anyValid = matching.stream().anyMatch(building -> building.valid);
            return AutoHired.refused(Component.translatable(anyValid
                    ? "hearthstead.employ.refused.compatible_full"
                    : "hearthstead.employ.refused.compatible_not_ready",
                intended.displayName()));
        }

        ready.sort(Comparator
            .comparingDouble((Building building) ->
                settler.blockPosition().distSqr(building.plaquePos))
            .thenComparingInt(building -> building.plaquePos.getX())
            .thenComparingInt(building -> building.plaquePos.getY())
            .thenComparingInt(building -> building.plaquePos.getZ())
            .thenComparing(building -> building.id));
        Building selected = ready.getFirst();

        ItemStack emblemBefore = held.copy();
        int workersBefore = selected.workers.size();
        Hired hired = hireWithHeldEmblem(level, settlement, selected, settler, player);
        if (!hired.ok()) {
            return AutoHired.refused(hired);
        }
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EMPLOYMENT_AUTO_HIRED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "building:" + selected.id, 0, 0, workersBefore,
                selected.workers.size(),
                BuiltInRegistries.ITEM.getKey(emblemBefore.getItem()).toString(),
                emblemBefore.getCount(), player.getMainHandItem().getCount(),
                -1, "settler:" + settler.getUUID()));
        return new AutoHired(true, selected, hired.cost(), null);
    }

    /** Pure validation shared by the free admin seam and charged player path. */
    @Nullable
    private static Hired validateCoreHire(Settlement settlement,
                                          Building building, SettlerEntity settler) {
        if (settler.getUUID().equals(settlement.mayorId)) {
            return Hired.refused("hearthstead.employ.refused.mayor");
        }
        if (!building.valid) {
            return Hired.refused("hearthstead.employ.refused.invalid");
        }
        if (!teaches(building.type)) {
            return Hired.refused("hearthstead.employ.refused.no_trade");
        }
        if (building.workers.contains(settler.getUUID())) {
            return Hired.refused("hearthstead.employ.refused.already");
        }
        if (building.type == BuildingType.FISHERY && settlement.buildings.stream()
            .filter(candidate -> candidate.type == BuildingType.FISHERY)
            .flatMap(candidate -> candidate.workers.stream())
            .anyMatch(worker -> !worker.equals(settler.getUUID()))) {
            return Hired.refused("hearthstead.employ.refused.one_fisher");
        }
        // BATTLE-ROLES: kill-switch and the Rune Mage cap (RoleHiring).
        net.minecraft.network.chat.Component roleRefusal =
            com.hearthstead.entity.combat.role.RoleHiring.refusal(settlement, building.type, settler);
        if (roleRefusal != null) {
            return Hired.refused(roleRefusal);
        }
        if (!hasVacancy(settlement, building)) {
            return Hired.refused("hearthstead.employ.refused.full");
        }
        return null;
    }

    /**
     * Extra authority checks required only for an ordinary player mutation.
     * Synthetic GameTests and the level-2 QA command retain the raw seam above.
     */
    @Nullable
    private static Hired validateLivePlayerHire(ServerLevel level,
                                                Settlement settlement,
                                                Building building,
                                                SettlerEntity settler,
                                                ServerPlayer player) {
        Hired contextRefusal = validateLivePlayerContext(level, settlement, settler, player);
        if (contextRefusal != null) {
            return contextRefusal;
        }
        if (building == null || !settlement.buildings.contains(building)) {
            return Hired.refused("hearthstead.employ.refused.changed");
        }
        return null;
    }

    /** Shared live-member and roster authority for direct and plaque paths. */
    @Nullable
    private static Hired validateLivePlayerContext(ServerLevel level,
                                                   Settlement settlement,
                                                   SettlerEntity settler,
                                                   ServerPlayer player) {
        if (level == null || settlement == null || settler == null || player == null
            || player.serverLevel() != level
            || SettlementManager.byId(level, settlement.id) != settlement) {
            return Hired.refused("hearthstead.employ.refused.changed");
        }
        if (!settler.isAlive() || settler.level() != level
            || level.getEntity(settler.getId()) != settler
            || settlement.record(settler.getUUID()) == null
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || settler.isTraveler()) {
            return Hired.refused("hearthstead.employ.refused.not_member");
        }

        if (settler.getUUID().equals(settlement.mayorId)) {
            return Hired.refused("hearthstead.employ.refused.mayor");
        }
        int rosterEntries = 0;
        for (Building candidate : settlement.buildings) {
            for (UUID worker : candidate.workers) {
                if (worker.equals(settler.getUUID())) {
                    rosterEntries++;
                }
            }
        }
        if (rosterEntries > 1) {
            return Hired.refused("hearthstead.employ.refused.roster");
        }
        return null;
    }

    /** Applies one already-validated roster move and its existing side effects. */
    private static Hired commitHire(ServerLevel level, Settlement settlement,
                                    Building building, SettlerEntity settler,
                                    boolean clearAuthorization) {
        Cost cost = costOfHiring(settlement, settler);
        if (settler.carriedArrowCount() > 0
            && !settler.releaseCarriedArrows(level, settlement)) {
            return Hired.refused("hearthstead.employ.refused.quiver_return");
        }
        if (clearAuthorization) {
            settlement.employmentAuthorizations.clear(settler.getUUID());
        }
        if (cost.loses() != null) {
            EquipmentRequests.cancelFor(level, cost.loses(), settler.getUUID());
            cost.loses().workers.remove(settler.getUUID());
        }
        building.workers.add(settler.getUUID());
        settler.setProfessionProjection(tradeOf(building.type));
        settler.onHired(level, building);
        EquipmentRequests.refreshFor(level, settlement, building, settler);
        SettlementManager.data(level).setDirty();
        return new Hired(true, cost, null);
    }

    /** Result of the player-facing Fire action. The key is safe to show to the actor. */
    public record FireResult(boolean applied, String messageKey) {
        static FireResult success() {
            return new FireResult(true, "");
        }
        static FireResult refused(String key) {
            return new FireResult(false, key);
        }
    }

    /**
     * Fire one exact workplace member and reserve their physical emblem for
     * the acting player. The settlement-owned outbox is persisted with the
     * roster, so a full inventory becomes an owner-locked drop/retry instead
     * of a loss. The reservation happens before {@link #dismiss}; a failed
     * dismissal cancels it, so neither path mints an emblem.
     */
    public static FireResult fireWithEmblem(ServerLevel level,
                                            Settlement settlement,
                                            Building expectedEmployer,
                                            SettlerEntity settler,
                                            ServerPlayer player,
                                            UUID deliveryId,
                                            long expectedEmploymentRevision) {
        if (level == null || settlement == null || expectedEmployer == null
            || settler == null || player == null || deliveryId == null
            || settler.getUUID().equals(settlement.mayorId)
            || !expectedEmployer.workers.contains(settler.getUUID())
            || employerOf(settlement, settler.getUUID()) != expectedEmployer) {
            return FireResult.refused("hearthstead.plaque.fire.not_employed");
        }
        Profession profession = tradeOf(expectedEmployer.type);
        EmploymentAuthorizationLedger.Receipt receipt = settlement
            .employmentAuthorizations.receipt(settler.getUUID());
        if (receipt == null || receipt.revision() != expectedEmploymentRevision
            || !settlement.employmentAuthorizations.matches(settlement.id,
                settler.getUUID(), expectedEmployer.id, profession)) {
            return FireResult.refused("hearthstead.plaque.fire.authorization_required");
        }
        ItemStack emblem = JobEmblemItem.stackFor(profession);
        if (emblem.isEmpty()) {
            return FireResult.refused("hearthstead.plaque.fire.emblem_unavailable");
        }
        if (!JourneyEmblemProvenance.stampEmploymentReturn(emblem,
                settlement.id, deliveryId, profession)) {
            return FireResult.refused("hearthstead.plaque.fire.delivery_unavailable");
        }
        PendingPlayerDeliveryLedger.Reservation reservation =
            PendingPlayerDeliveryLedger.reservation(level, deliveryId, player, emblem);
        if (reservation == null) {
            return FireResult.refused("hearthstead.plaque.fire.delivery_unavailable");
        }
        PendingPlayerDeliveryLedger.ReserveResult reserved =
            settlement.employmentReturns.reserve(reservation);
        if (!reserved.accepted()) {
            return FireResult.refused("hearthstead.plaque.fire.delivery_unavailable");
        }
        if (dismiss(level, settlement, settler) != expectedEmployer) {
            if (reserved == PendingPlayerDeliveryLedger.ReserveResult.INSERTED) {
                settlement.employmentReturns.cancel(deliveryId);
            }
            return FireResult.refused("hearthstead.plaque.fire.dismiss_refused");
        }
        return FireResult.success();
    }
    /**
     * Dismisses a settler from whatever employs them.
     *
     * <p>Dismissal has weight (PLAN_EMPLOYMENT 3.5): they take a morale hit and
     * they walk out. They are not deleted and they are not hidden — an
     * unemployed settler is visibly in the village, which is the point.
     *
     * @return the building they left, or null if they had no job
     */
    @Nullable
    public static Building dismiss(ServerLevel level, Settlement settlement,
                                   SettlerEntity settler) {
        Building employer = employerOf(settlement, settler.getUUID());
        if (employer == null) {
            return null;
        }
        if (settler.carriedArrowCount() > 0
            && !settler.releaseCarriedArrows(level, settlement)) {
            return null;
        }
        EquipmentRequests.cancelFor(level, employer, settler.getUUID());
        settlement.employmentAuthorizations.clear(settler.getUUID());
        employer.workers.remove(settler.getUUID());
        settler.setProfessionProjection(Profession.NONE);
        settler.onDismissed(level, employer);
        SettlementManager.data(level).setDirty();
        return employer;
    }

    /**
     * Keeps an already-employed martial post intact for one authored, sealed
     * first raid while a standing plaque is reporting physical damage.
     *
     * <p>This is deliberately narrower than generic building removal: the
     * building remains in the settlement and its Plaque keeps reporting the
     * failed survey. It only prevents a scan-time teardown from removing an
     * existing Guard or Archer's profession, physical weapon and persisted
     * order before the active raid has one terminal outcome. Once the raid is
     * terminal, the ordinary unlink path calls {@link #freeWorkers(ServerLevel,
     * Settlement, Building)} unchanged. A broken plaque and an explicit
     * dissolve never use this exception.
     */
    public static boolean defersMartialUnlinkForActiveFirstRaid(
            Settlement settlement, Building building) {
        if (settlement == null || building == null
            || !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || !settlement.raidLifecycle.participantsTracked()
            || building.workers.isEmpty()) {
            return false;
        }
        return building.type == BuildingType.BARRACKS
            || building.type == BuildingType.WATCHTOWER;
    }

    /** A recorded raid wound suspends work, not its existing assignment.
     * The ordinary saved roster stays authoritative. Removing the plaque or plan
     * still frees staff; damage without a recorded scar gets no exception. */
    public static boolean retainsWorkersForRaidRepair(
            ServerLevel level, Settlement settlement, Building building) {
        if (settlement == null || building == null
                || building.type == null || building.bounds == null
                || building.workers.isEmpty()) return false;
        return com.hearthstead.settlement.raid.RaidDirector.scarsOf(level, settlement.id)
            .stream().anyMatch(scar -> building.bounds.isInside(scar.pos()));
    }

    /**
     * Frees everyone a building employed, because the building is gone.
     *
     * <p>A settler pointing at a building that no longer exists is the exact
     * class of bug KF-013 and KF-014 both were. It is cheaper to make it
     * impossible than to find it twice.
     */
    public static boolean freeWorkers(ServerLevel level, Settlement settlement,
                                      Building building) {
        if (building.workers.isEmpty()) {
            return true;
        }
        List<UUID> leaving = List.copyOf(building.workers);
        List<SettlerEntity> loaded = SettlementManager.loadedMembers(level,
            settlement);
        Map<UUID, SettlerEntity> loadedById = new java.util.HashMap<>();
        for (SettlerEntity settler : loaded) {
            loadedById.put(settler.getUUID(), settler);
        }
        // Destruction is a two-phase transaction. An unloaded worker may own
        // a persisted quiver sourced from this building; without its entity
        // NBT we cannot materialize that stock. Retain authority and let the
        // plaque/sweep retry when every leaving member is loaded.
        for (UUID worker : leaving) {
            SettlerEntity settler = loadedById.get(worker);
            if (settler == null || (settler.carriedArrowCount() > 0
                && !settler.releaseCarriedArrows(level, settlement))) {
                return false;
            }
        }
        for (UUID worker : leaving) {
            EquipmentRequests.cancelFor(level, building, worker);
            settlement.employmentAuthorizations.clear(worker);
        }
        building.workers.clear();
        for (SettlerEntity settler : loaded) {
            if (leaving.contains(settler.getUUID())) {
                settler.setProfessionProjection(Profession.NONE);
            }
        }
        return true;
    }

    /**
     * Death-only employment cleanup. No dismissal morale or ceremony is
     * emitted: the entity's real terminal path already owns those effects.
     * Removing every roster occurrence, request, order and live authorization
     * in one pass makes the vacated post immediately available to a paid
     * replacement after Guard/Archer death.
     */
    public static void terminateMember(ServerLevel level, Settlement settlement,
                                       SettlerEntity settler) {
        if (level == null || settlement == null || settler == null) {
            return;
        }
        UUID workerId = settler.getUUID();
        for (Building building : settlement.buildings) {
            boolean employedHere = building.workers.contains(workerId);
            if (!employedHere) {
                continue;
            }
            EquipmentRequests.cancelFor(level, building, workerId);
            building.workers.removeIf(workerId::equals);
        }
        settlement.employmentAuthorizations.clear(workerId);
        settlement.guardOrders.clear(workerId);
    }

    // -------------------------------------------------------- the roster ---

    /**
     * Everyone who could take this post, best first.
     *
     * <p>Sorted so the answer is obvious without reading: people already doing
     * this trade, then the unemployed, then everyone else — and within that, by
     * how little taking them costs. The list is people, not a column of digits
     * (PLAN_EMPLOYMENT 3.1); {@link Candidate#fitness} is drawn as pips.
     */
    public static List<Candidate> candidatesFor(ServerLevel level,
                                                Settlement settlement,
                                                Building building) {
        List<Candidate> out = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.isTraveler() || settler.getUUID().equals(settlement.mayorId)) {
                continue;
            }
            Building current = employerOf(settlement, settler.getUUID());
            boolean here = current == building;
            out.add(new Candidate(settler, current,
                fitness(settlement, settler, building),
                here ? Cost.FREE : costOfHiring(settlement, settler), here));
        }
        out.sort((a, b) -> {
            if (a.worksHere() != b.worksHere()) {
                return a.worksHere() ? -1 : 1;
            }
            if (a.fitness() != b.fitness()) {
                return b.fitness() - a.fitness();
            }
            int costA = a.cost().leavesEmpty() ? 2 : a.cost().loses() != null ? 1 : 0;
            int costB = b.cost().leavesEmpty() ? 2 : b.cost().loses() != null ? 1 : 0;
            return costA - costB;
        });
        return out;
    }

    /**
     * Which of the five numbers this trade actually leans on.
     *
     * <p>Naming it per trade is what makes the hire screen a decision: the
     * strongest settler is the obvious lumberer and the wrong courier, and you
     * can see that without being told.
     */
    public static Attribute keyAttributeOf(BuildingType type) {
        return switch (tradeOf(type)) {
            case LUMBERER, GUARD, SPEARMAN, LONGSWORDSMAN -> Attribute.STRENGTH;
            case HEALER -> Attribute.SPIRIT;
            case RUNE_MAGE -> Attribute.FOCUS;
            case COURIER -> Attribute.STAMINA;
            // The hire screen's decision, made visible: the strongest settler
            // is the obvious barracks guard and the wrong tower archer.
            // TRADES-1: the same hands-not-force reasoning names HERDER,
            // FISHER and HUNTER here too.
            case FARMER, ARCHER, HERDER, FISHER, HUNTER, BUILDER -> Attribute.DEXTERITY;
            default -> Attribute.WITS;
        };
    }

    /**
     * How well suited a settler is, 0..5, drawn as pips.
     *
     * <p>Pips rather than the raw number, because you read "four of five" at a
     * glance and never read "62" at a glance — which is the concrete fix for
     * the wall-of-digits complaint about MineColonies' hire tab.
     *
     * <p>This was a placeholder until attributes existed; it now reads the real
     * thing, and nothing above it changed. That is what the seam was for.
     */
    public static int fitness(Settlement settlement, SettlerEntity settler,
                              Building building) {
        Attribute key = keyAttributeOf(building.type);
        int score = settler.attributes().pips(key);
        if (settler.getProfession() == tradeOf(building.type)
            && tradeOf(building.type) != Profession.NONE) {
            score += 1;
        }
        if (settler.getEnergy() < 25.0F || settler.getMorale() < 25.0F) {
            score -= 1;
        }
        return Math.max(0, Math.min(5, score));
    }

    /**
     * Why this candidate is the suggested one, in one sentence.
     *
     * <p>MineColonies sorts, and a sort order tells you <i>that</i> someone is
     * on top, never <i>why</i>. An explanation is a decision; a sort order is a
     * shrug. D-013: this is a suggestion the player accepts, never something
     * the settlement does on its own.
     */
    public static Component reasonFor(Settlement settlement, Candidate candidate,
                                      Building building) {
        Attribute key = keyAttributeOf(building.type);
        if (candidate.worksHere()) {
            return Component.translatable("hearthstead.employ.reason.already_here",
                candidate.settler().getSettlerName());
        }
        if (candidate.settler().attributes().knack() == key) {
            return Component.translatable("hearthstead.employ.reason.knack",
                candidate.settler().getSettlerName(), key.displayName());
        }
        if (candidate.current() == null) {
            return Component.translatable("hearthstead.employ.reason.free",
                candidate.settler().getSettlerName());
        }
        return Component.translatable("hearthstead.employ.reason.best",
            candidate.settler().getSettlerName(), key.displayName());
    }

    /**
     * The shift a guard stands, so a garrison is not all asleep at midnight.
     *
     * <p>Derived, never stored: a guard's index in their own barracks' worker
     * list decides it, which splits any garrison exactly in half and survives
     * a reload because the list does. A guard with no barracks falls back to
     * the parity of their UUID — still deterministic, still about half.
     *
     * <p>Deliberately trade-agnostic: it reads the employer's worker list,
     * whatever the building is, so a two-archer WATCHTOWER splits into a day
     * and a night archer by exactly the same rule as a barracks garrison —
     * the two martial posts keep one watch clock between them.
     */
    public static Watch watchOf(Settlement settlement, SettlerEntity settler) {
        Building employer = employerOf(settlement, settler.getUUID());
        int index = employer == null ? -1 : employer.workers.indexOf(settler.getUUID());
        if (index < 0) {
            return (settler.getUUID().hashCode() & 1) == 0 ? Watch.DAY : Watch.NIGHT;
        }
        return index % 2 == 0 ? Watch.DAY : Watch.NIGHT;
    }

    private Employment() {
    }
}
