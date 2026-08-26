package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * The settler's synced profession is a projection kept for the client (outfit,
 * tool in hand, animation set), the way the plaque's occupancy is: recomputed
 * on the server, never a second source of truth. Two places to write one fact
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
            case INNKEEPER -> SettlerActivity.SORTING;
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
            case FARMER, LUMBERER, FISHER, HUNTER -> false;
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
        if (tradeOf(type) == Profession.SCHOLAR) {
            return com.hearthstead.registry.ModSounds.FEATHER_PINCH.get();
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
            default -> com.hearthstead.registry.ModSounds.KNEAD_PRESS.get();
        };
    }

    /**
     * How often that sound repeats, in ticks — the clip's own loop length, so
     * the sound lands on the motion rather than on a timer of its own.
     */
    public static int soundPeriodOf(BuildingType type) {
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
        if (tradeOf(type) == Profession.SCHOLAR) {
            // Period 40 over an 18-tick clip: the quill scratch is sparser
            // than the loop by design, so seam alignment does not exist --
            // mid-period simply keeps it off the wrap.
            return 20;
        }
        return switch (motionOf(type)) {
            case WORK_HAMMER -> 9;   // §18.4: strike 0.30-0.45s, first hold tick
            case WORK_STOKE -> 14;   // §18.3: arms compressed at stroke's end, 0.60-0.85s window
            case WORK_SAW -> 11;     // §18.5: far-end reversal bite (est.)
            case WORK_OVEN -> 12;    // §18.8: peel held in the oven mouth (est.)
            case WORK_KNEAD -> 8;    // §18.1: press bottoms out, torso still driving (est.)
            case WORK_CLEAVE -> 8;   // §18.2: parked at the board (est.)
            case WORK_WEAVE -> 9;    // §18.6: deeper second pass, mid-loop (est.)
            case WORK_MINE -> 9;     // §8.1: pick_strike t=0.45s -- MinerWorkGoal's own tick
            case WORK_STIR -> 24;    // §7.2: pot_stir accent documented at t=1.20s
            case WORK_PLANE -> 13;   // §20.2: full extension, shaving clears (est.)
            case WORK_CHISEL -> 10;  // §20.3: strike lands 0.45-0.50s, hold from 0.50s
            case WORK_FLETCH -> 15;  // §20.4: middle pinch of three, t=0.75s
            case WORK_SCRAPE -> 13;  // §20.5: two-tick hold at the stroke's bottom (est.)
            // TRADES-1: HERDER_SHEAR's snip lands at t=0.45s of its 1.00s
            // loop; FISHER_CAST's bite at t=1.45s of its 2.00s loop;
            // HUNTER_LOOSE's release at t=0.70s of its 1.20s loop -- all
            // exact accent ticks straight from each clip's own keyframes
            // (catalogue §24), not estimates.
            case WORK_SHEAR -> 9;
            case WORK_FISH -> 29;
            case WORK_HUNT -> 14;
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
            default -> Attribute.WITS;
        };
    }

    /** The trade practised in this kind of building, or NONE if none yet is. */
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
        for (Building building : settlement.buildings) {
            if (building.workers.contains(settler)) {
                return building;
            }
        }
        return null;
    }

    /** The profession this settler should have, derived from their employer. */
    public static Profession professionOf(Settlement settlement, UUID settler) {
        Building employer = employerOf(settlement, settler);
        return employer == null ? Profession.NONE : tradeOf(employer.type);
    }

    /**
     * Puts the derived profession back onto the settler's synced projection.
     *
     * <p>Call after anything that could change employment — hiring, dismissal,
     * a building dissolving, a settler loading back in. Doing nothing when it
     * already agrees keeps this cheap enough to call freely.
     */
    public static void refresh(Settlement settlement, SettlerEntity settler) {
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
     * Hires a settler into a building.
     *
     * <p>Atomic in the way that matters: they leave their old post in the same
     * operation that gives them the new one, so there is no instant in which a
     * settler holds two jobs or none.
     */
    public static Hired hire(ServerLevel level, Settlement settlement,
                             Building building, SettlerEntity settler) {
        if (!building.valid) {
            return Hired.refused("hearthstead.employ.refused.invalid");
        }
        if (!teaches(building.type)) {
            return Hired.refused("hearthstead.employ.refused.no_trade");
        }
        if (building.workers.contains(settler.getUUID())) {
            return Hired.refused("hearthstead.employ.refused.already");
        }
        if (building.workers.size() >= building.type.workerCapacity()) {
            return Hired.refused("hearthstead.employ.refused.full");
        }
        Cost cost = costOfHiring(settlement, settler);
        if (cost.loses() != null) {
            cost.loses().workers.remove(settler.getUUID());
        }
        building.workers.add(settler.getUUID());
        settler.setProfessionProjection(tradeOf(building.type));
        settler.onHired(level, building);
        SettlementManager.data(level).setDirty();
        return new Hired(true, cost, null);
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
        employer.workers.remove(settler.getUUID());
        settler.setProfessionProjection(Profession.NONE);
        settler.onDismissed(level, employer);
        SettlementManager.data(level).setDirty();
        return employer;
    }

    /**
     * Frees everyone a building employed, because the building is gone.
     *
     * <p>A settler pointing at a building that no longer exists is the exact
     * class of bug KF-013 and KF-014 both were. It is cheaper to make it
     * impossible than to find it twice.
     */
    public static void freeWorkers(ServerLevel level, Settlement settlement,
                                   Building building) {
        if (building.workers.isEmpty()) {
            return;
        }
        List<UUID> leaving = List.copyOf(building.workers);
        building.workers.clear();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (leaving.contains(settler.getUUID())) {
                settler.setProfessionProjection(Profession.NONE);
            }
        }
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
            if (settler.isTraveler()) {
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
            case LUMBERER, GUARD -> Attribute.STRENGTH;
            case COURIER -> Attribute.STAMINA;
            // The hire screen's decision, made visible: the strongest settler
            // is the obvious barracks guard and the wrong tower archer.
            case FARMER, ARCHER -> Attribute.DEXTERITY;
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
