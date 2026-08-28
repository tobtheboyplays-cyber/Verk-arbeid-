package com.hearthstead.entity;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What a guard has learned to do, earned by fighting.
 *
 * <h2>Rank is not a number you spend, it is a number you reach</h2>
 *
 * <p>Owner's ask, 2026-08-25: abilities that unlock as a guard levels, one per
 * twenty points, and TekTopia's leap that hits several enemies at once. So
 * rank reads straight off {@link Attribute#STRENGTH} — the attribute a guard's
 * own work trains — and there is nothing to allocate. A veteran guard is
 * evidence of nights survived, which is the only currency this mod has that
 * cannot be farmed quickly.
 *
 * <p>Every twenty points is deliberately a long way: with growth slowing as it
 * rises, {@link #SERGEANT} is weeks of patrols and fights, and the leap is
 * meant to be a thing you remember the first time you see one of your own
 * guards do it.
 *
 * <h2>The abilities</h2>
 *
 * <table>
 *   <caption>Ranks</caption>
 *   <tr><th>rank</th><th>at</th><th>what they can do</th><th>wears</th></tr>
 *   <tr><td>RECRUIT</td><td>0</td><td>swings a sword</td><td>nothing</td></tr>
 *   <tr><td>SPEARMAN</td><td>20</td><td><b>Shield Bash</b> — hits knock back and stagger</td><td>a leather chestplate</td></tr>
 *   <tr><td>VETERAN</td><td>40</td><td><b>Cleave</b> — the swing also catches a second enemy</td><td>full leather</td></tr>
 *   <tr><td>SERGEANT</td><td>60</td><td><b>Leap Strike</b> — leaps a gap and lands on everyone at once</td><td>iron chest and legs over a leather cap and boots</td></tr>
 *   <tr><td>CAPTAIN</td><td>80</td><td><b>Rally</b> — a kill lifts every guard nearby</td><td>full iron</td></tr>
 * </table>
 *
 * <h2>Armor is earned too</h2>
 *
 * <p>Owner's second ask, 2026-08-25: "guards must not have good armor before
 * they upgrade — they need experience." A settlement can afford iron the
 * moment it has a smith, but a recruit does not get to wear it just because
 * the chest has it; {@link #applyEquipment} only ever puts a rank's own gear
 * on a guard who has actually reached that rank, and the sword stays
 * untouched — it is the profession's tool ({@link Profession#GUARD}), not a
 * reward, and every rank keeps it.
 *
 * <p>The ramp is deliberately readable at a glance: bare, then a leather vest,
 * then full leather, then iron creeping in at the core while the cap and
 * boots stay leather, then head to toe iron. A player who has never opened a
 * settler screen should be able to eyeball who the veterans are just by
 * walking the wall.
 *
 * <h2>The kit is bought, not conjured</h2>
 *
 * <p>Owner-critic verdict #1, krav 4: rank used to reach straight into thin
 * air for a rank's pieces — a fresh {@code ItemStack} materialised the
 * instant Strength crossed a threshold, which broke the mod's oldest
 * invariant (every item is physically real; chest truth, INV-3) and left the
 * armoury a building with nothing to do. {@link #applyEquipment} now
 * WITHDRAWS every piece from the settlement's own stores, in the same order
 * a player would actually look: the ARMOURY's own chests first (that is what
 * an armoury is for), then any WAREHOUSE, then the communal hearth as the
 * last resort — the same chest-true idiom {@code RepairWorkGoal} and
 * {@code CourierWorkGoal} use, re-reading live containers rather than
 * trusting a remembered count, one real item leaving one real slot in the
 * same operation it lands on the guard.
 *
 * <p><b>The resulting economy</b> (FLOWS.md's Ring 3: "barracks + watchtower
 * + armoury — arms/armor → guard rank ceilings"): the smelter turns ore into
 * ingots, the smithy turns ingots into iron armor, and now that armor has to
 * actually sit in a chest somewhere the smith's product finally has demand
 * — the visible progression the owner asked for, "guards must not have good
 * armor before they upgrade", now cuts both ways. A settlement that never
 * builds a smithy — or never routes its ingots there — fields veterans who
 * stay in leather forever, because iron that was never forged is iron that
 * can never be withdrawn. Nothing here manufactures a shortfall away.
 *
 * <p><b>If the stores cannot afford it, the kit waits.</b> Rank is never
 * gated on equipment — every ability check in {@code GuardMeleeGoal} and
 * {@code GuardLeapGoal} reads {@link #of} off raw Strength, exactly as
 * before, so a bare-chested Sergeant still leaps. {@link #applyEquipment}
 * simply leaves a slot exactly as it was worn when the rank's own piece is
 * not in stock anywhere — never stripping a guard down to bare skin for gear
 * the settlement cannot yet afford, and never inventing the gap. The moment
 * a matching piece exists in the stores, the very next call dresses it — see
 * {@link #isFullyEquipped} for the tool a caller needs to notice that moment
 * even when the rank itself has not moved (a settlement's whole reason to
 * finally build that smithy).
 *
 * <p><b>Superseded gear goes back, not into the void.</b> The leather
 * chestplate a Veteran outgrows on the way to Sergeant's iron one is
 * deposited into the very same store chain — armoury, then warehouse, then
 * hearth — the withdrawal came from, and only popped onto the ground at the
 * armoury's own anchor (INV-3's {@code popResource} idiom, mirroring
 * {@code Production}'s own give-back) if every one of those is genuinely
 * full. A settlement that promotes ten guards still owns its old kit.
 *
 * <h2>Whether armor can go backwards</h2>
 *
 * <p>There is no separate "rank ever reached" record — {@link #of} always
 * reads the <i>current</i> Strength, and {@link #applyEquipment} always
 * dresses a guard for the rank that comes back. Nothing in ordinary play
 * lowers Strength once trained, so in practice this never bites: a guard's
 * gear only ever gets better. But the mechanism itself has no memory, on
 * purpose — a high-water-mark rank would mean a settler could be dressed
 * above what they currently measure up to, which is the opposite of "armor
 * gated by experience". If Strength ever *can* drop (a future injury system,
 * a debuff), the guard's kit drops with it — and, exactly like an ordinary
 * supersession, the piece that no longer fits is returned to the stores
 * rather than deleted.
 */
public enum GuardRank {
    RECRUIT("recruit", 0,
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY,
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY),
    SPEARMAN("spearman", 20,
        () -> ItemStack.EMPTY, () -> new ItemStack(Items.LEATHER_CHESTPLATE),
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY),
    VETERAN("veteran", 40,
        () -> new ItemStack(Items.LEATHER_HELMET), () -> new ItemStack(Items.LEATHER_CHESTPLATE),
        () -> new ItemStack(Items.LEATHER_LEGGINGS), () -> new ItemStack(Items.LEATHER_BOOTS)),
    SERGEANT("sergeant", 60,
        () -> new ItemStack(Items.LEATHER_HELMET), () -> new ItemStack(Items.IRON_CHESTPLATE),
        () -> new ItemStack(Items.IRON_LEGGINGS), () -> new ItemStack(Items.LEATHER_BOOTS)),
    CAPTAIN("captain", 80,
        () -> new ItemStack(Items.IRON_HELMET), () -> new ItemStack(Items.IRON_CHESTPLATE),
        () -> new ItemStack(Items.IRON_LEGGINGS), () -> new ItemStack(Items.IRON_BOOTS));

    /** Secondary targets take this share, the way vanilla's sweep does. */
    public static final float CLEAVE_SHARE = 0.6F;

    // ------------------------------------------------------------ training ---
    //
    // Rank reads Attribute.STRENGTH, so the guard's OWN trade has to be able
    // to climb the ladder: before these existed, only lumberjacking and
    // mining trained Strength and a career guard could never leave RECRUIT.

    /**
     * STRENGTH per waypoint drilled on patrol ({@code GuardPatrolGoal}).
     *
     * <p>The arithmetic, from {@link SettlerAttributes} (RATE 0.05/unit at
     * value 0, growth ∝ (1 − v/100)²): climbing a fresh guard's ~5 Strength
     * to {@link #SPEARMAN}'s 20 integrates to ~395 train units. The effort
     * pool caps a full-time guard at ~20–21 waypoints a day
     * ({@code Effort.BASE_CAPACITY} 20, +1 per 5 STAMINA, 1 effort per
     * waypoint), so at 5.0 units a waypoint the first stripe lands after
     * ~77 waypoints ≈ <b>3.5–4 in-game days</b> of full-time patrolling —
     * the intended pace. Drilling every waypoint (rather than every Nth)
     * fell out of that arithmetic: spacing the reps out would force each
     * rep — and combat's 5× multiple of it — absurdly large.
     *
     * <p>On the same curve the later stripes stretch out exactly as this
     * class's doc promises: {@link #VETERAN} ≈ +8 more days of pure
     * drilling, {@link #SERGEANT} ≈ +16, {@link #CAPTAIN} ≈ +47 — past the
     * first stripe, a guard who never fights barely climbs.
     */
    public static final float TRAIN_DRILL = 5.0F;

    /**
     * STRENGTH per blow actually <i>landed</i> — a melee hit that connected
     * ({@code GuardMeleeGoal}), a leap that came down on someone
     * ({@code GuardLeapGoal}). Five times {@link #TRAIN_DRILL} per event, on
     * purpose: at roughly a point per hit for a recruit, one real fight
     * teaches more than a day on the wall — "a veteran guard is evidence of
     * nights survived", and the drill only exists so a guard the raids never
     * reach still gets there, slower.
     */
    public static final float TRAIN_COMBAT = 25.0F;

    /**
     * Flat bonus damage per rank ordinal on a guard's own swing
     * ({@code GuardMeleeGoal}): {@link #RECRUIT} +0.0 … {@link #CAPTAIN}
     * +2.0. Experience swings harder — the ATTACK_DAMAGE attribute stays
     * flat, the goal adds the edge.
     */
    public static final float MELEE_EDGE_PER_RANK = 0.5F;

    /** How far a sergeant will leap. Short enough to read as a lunge. */
    public static final double LEAP_MIN = 3.5;
    public static final double LEAP_MAX = 9.0;
    /** Everything within this of the landing takes the blow. */
    public static final double LEAP_RADIUS = 3.0;
    /** Ticks between leaps, so it stays an event rather than a walk cycle. */
    public static final int LEAP_COOLDOWN = 200;

    private final String key;
    private final int threshold;
    // One fresh ItemStack per call, never a shared instance -- two guards
    // dressed at the same rank must never be able to corrupt each other's
    // gear through one stack's NBT or stack count. NOTE: this fresh stack is
    // a SPEC only -- what item this rank wants in this slot -- and is never
    // itself equipped; see targetPiece() and applyEquipment().
    private final Supplier<ItemStack> helmet;
    private final Supplier<ItemStack> chest;
    private final Supplier<ItemStack> legs;
    private final Supplier<ItemStack> boots;

    GuardRank(String key, int threshold,
              Supplier<ItemStack> helmet, Supplier<ItemStack> chest,
              Supplier<ItemStack> legs, Supplier<ItemStack> boots) {
        this.key = key;
        this.threshold = threshold;
        this.helmet = helmet;
        this.chest = chest;
        this.legs = legs;
        this.boots = boots;
    }

    public String key() {
        return key;
    }

    public int threshold() {
        return threshold;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.rank." + key);
    }

    /** The rank this strength has earned. */
    public static GuardRank of(int strength) {
        GuardRank best = RECRUIT;
        for (GuardRank rank : values()) {
            if (strength >= rank.threshold) {
                best = rank;
            }
        }
        return best;
    }

    public static GuardRank of(SettlerEntity settler) {
        return of(settler.attribute(Attribute.STRENGTH));
    }

    public boolean atLeast(GuardRank other) {
        return ordinal() >= other.ordinal();
    }

    // ---------------------------------------------------------- equipment ---

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /**
     * The rank's own piece for one armor slot — a fresh, unworn spec stack
     * (an empty {@link ItemStack} for a slot this rank does not fill). Used
     * only to know WHAT item to look for and to compare against what is
     * already worn; it is never itself equipped — see {@link #applyEquipment}.
     */
    private ItemStack targetPiece(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> helmet.get();
            case CHEST -> chest.get();
            case LEGS -> legs.get();
            case FEET -> boots.get();
            default -> ItemStack.EMPTY;
        };
    }

    /** Same physical piece, ignoring stack size and components (durability,
     *  enchantments a player may have added) — the question is only "is this
     *  slot already dressed the way this rank wants", not "is it the exact
     *  same NBT". Both empty counts as a match. */
    private static boolean matches(ItemStack worn, ItemStack target) {
        if (worn.isEmpty() && target.isEmpty()) {
            return true;
        }
        if (worn.isEmpty() || target.isEmpty()) {
            return false;
        }
        return worn.is(target.getItem());
    }

    /**
     * Dresses a guard for the rank {@link #of(SettlerEntity)} says they have
     * actually reached — no more, no less — by WITHDRAWING each piece from
     * the settlement's own stores rather than conjuring it (see the class
     * doc's "kit is bought, not conjured"). Safe to call on any settler at
     * any cadence: a slot already holding the right piece is left untouched
     * (no needless withdraw/deposit cycle), a slot whose piece is not in
     * stock anywhere is left exactly as it was worn, and only an actual
     * change moves anything — so a caller never has to know what the guard
     * was wearing a moment ago, and calling this twice in a row with no
     * change in the world does nothing the second time.
     *
     * <p>Order of withdrawal, cheapest-to-reach first: the ARMOURY's own
     * chests, then any WAREHOUSE, then the communal hearth — the same
     * container-first idiom {@code RepairWorkGoal} uses for repair material.
     * A superseded piece (the slot's old occupant, once the new one is
     * actually in hand) is returned through the identical chain, INV-3's
     * {@code popResource} idiom only as the very last resort if every store
     * is genuinely full.
     *
     * <p>{@code setDropChance(0)} on every armor slot regardless of whether
     * this call touched it: a settlement's investment in its guards must not
     * evaporate the first time one loses a fight (mirrors
     * {@link SettlerEntity}'s own MAINHAND tool, which is dropChance-0 for
     * the same reason).
     */
    public static void applyEquipment(SettlerEntity settler) {
        GuardRank rank = of(settler);
        if (settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack target = rank.targetPiece(slot);
                ItemStack worn = settler.getItemBySlot(slot);
                if (matches(worn, target)) {
                    continue; // already dressed correctly for this slot
                }
                ItemStack acquired = target.isEmpty() ? ItemStack.EMPTY
                    : withdrawOne(level, settlement, settler, target.getItem());
                if (!target.isEmpty() && acquired.isEmpty()) {
                    // Not in stock anywhere right now. The rank still stands
                    // (of() reads Strength alone) but this piece waits --
                    // whatever is currently worn, bare skin or an earlier
                    // tier's piece the guard already owns, stays exactly as
                    // it is. Nothing is invented to fill the gap.
                    continue;
                }
                settler.setItemSlot(slot, acquired);
                if (!worn.isEmpty()) {
                    // Superseded: the piece this slot held a moment ago is
                    // real and must not simply vanish (INV-3).
                    depositToStores(level, settlement, settler, worn);
                }
            }
        }
        // Chest access is server-only; on the client (this hook only ever
        // runs server-side in practice, see SettlerEntity's equipment-
        // refresh hook) nothing above ran and no slot was touched. The
        // drop-chance reset below is harmless and idempotent either way.
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            settler.setDropChance(slot, 0.0F);
        }
    }

    /**
     * Whether {@code settler} already wears the exact kit their current rank
     * has earned — true once every armor slot either holds the rank's own
     * piece or the rank calls for nothing there.
     *
     * <p>Exists for a caller with a periodic tick to tell "fully kitted, no
     * need to look again" apart from "still waiting on the smith":
     * {@link #applyEquipment} only ever re-attempts a stalled withdrawal when
     * it is actually called, and a caller that only calls it when the RANK
     * itself has changed (today's {@code SettlerEntity} equipment-refresh
     * hook does exactly that, change-detecting against a cached rank) will
     * never notice new stock arriving for a guard stalled mid-kit at the
     * SAME rank. The fix is one line at that call site — also consult this
     * before skipping the refresh — which is outside this file's ownership;
     * see the fix-worker's report.
     */
    public static boolean isFullyEquipped(SettlerEntity settler) {
        GuardRank rank = of(settler);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!matches(settler.getItemBySlot(slot), rank.targetPiece(slot))) {
                return false;
            }
        }
        return true;
    }

    /** Strips the four armor slots bare — a settler who has stopped being a
     *  guard keeps their earned Strength, but the armor was the guard's, not
     *  theirs; see {@link SettlerEntity}'s equipment-refresh hook. The
     *  stripped pieces are real items and return to the settlement's stores
     *  exactly like an ordinary supersession (INV-3) — losing a trade must
     *  not also lose the kit it was issued. */
    public static void clearEquipment(SettlerEntity settler) {
        ServerLevel level = settler.level() instanceof ServerLevel sl ? sl : null;
        Settlement settlement = settler.settlement();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = settler.getItemBySlot(slot);
            settler.setItemSlot(slot, ItemStack.EMPTY);
            if (!worn.isEmpty() && level != null) {
                depositToStores(level, settlement, settler, worn);
            }
        }
    }

    // ------------------------------------------------------ store access ---
    //
    // The armoury-then-warehouse-then-hearth idiom, both directions. Chests
    // are the truth (INV-3): every withdrawal and every deposit re-reads the
    // real container it touches, exactly like RepairWorkGoal's material
    // lookup and CourierWorkGoal's restock/deposit legs -- nothing here ever
    // trusts a remembered count.

    /**
     * Every valid building of one type in the settlement — every ARMOURY, or
     * every WAREHOUSE, since either can be built more than once. Empty (never
     * null) when the settlement has none yet, which is exactly the state a
     * settlement with no smithy-fed armoury lives in: the withdrawal below
     * simply finds nothing there and falls through to the next tier.
     */
    private static List<Building> buildingsOfType(Settlement settlement, BuildingType type) {
        List<Building> found = new ArrayList<>();
        for (Building b : settlement.buildings) {
            if (b.type == type && b.valid) {
                found.add(b);
            }
        }
        return found;
    }

    /** Every real chest/barrel across every valid building of one type,
     *  doubly bounded exactly like {@code RepairWorkGoal#ownChests}: one
     *  building's scan is capped by {@link WarehouseIndex#MAX_CONTAINERS},
     *  and a settlement's building count is itself bounded. */
    private static List<Container> containersOfType(ServerLevel level, Settlement settlement,
                                                     BuildingType type) {
        List<Container> found = new ArrayList<>();
        for (Building b : buildingsOfType(settlement, type)) {
            for (BlockPos pos : WarehouseIndex.containers(level, b)) {
                if (level.getBlockEntity(pos) instanceof Container container) {
                    found.add(container);
                }
            }
        }
        return found;
    }

    /** Takes exactly one real item out of the first matching slot found, or
     *  {@link ItemStack#EMPTY} if none holds it — the item's own stack,
     *  components and all, never a substitute. */
    private static ItemStack extractOneFrom(List<Container> containers, Item item) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack in = container.getItem(slot);
                if (!in.isEmpty() && in.is(item)) {
                    ItemStack removed = container.removeItem(slot, 1);
                    if (!removed.isEmpty()) {
                        container.setChanged();
                        return removed;
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack extractOneFromHearth(@Nullable HearthBlockEntity hearth, Item item) {
        if (hearth == null) {
            return ItemStack.EMPTY;
        }
        ItemStackHandler inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack in = inventory.getStackInSlot(slot);
            if (!in.isEmpty() && in.is(item)) {
                return inventory.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Withdraws exactly one {@code item}, armoury chests first (that is what
     * an armoury is for), then any warehouse, then the communal hearth as
     * the last resort — the priority FLOWS.md's Ring 3 describes for hub
     * arms/armor. Returns {@link ItemStack#EMPTY} if the settlement's stores
     * hold none of it anywhere; nothing is ever taken partway.
     */
    private static ItemStack withdrawOne(ServerLevel level, @Nullable Settlement settlement,
                                         SettlerEntity settler, Item item) {
        if (settlement != null) {
            ItemStack got = extractOneFrom(containersOfType(level, settlement, BuildingType.ARMOURY), item);
            if (!got.isEmpty()) {
                return got;
            }
            got = extractOneFrom(containersOfType(level, settlement, BuildingType.WAREHOUSE), item);
            if (!got.isEmpty()) {
                return got;
            }
        }
        return extractOneFromHearth(settler.hearth(), item);
    }

    /** Destination-first insert (D-A2a-3) into every building of one type in
     *  turn, stopping the moment nothing is left over. Reuses
     *  {@link WarehouseStorage#insert}, the same conserving transfer every
     *  courier route already trusts — not a second insert implementation. */
    private static ItemStack depositInto(ServerLevel level, List<Building> buildings, ItemStack stack) {
        ItemStack remaining = stack;
        for (Building building : buildings) {
            if (remaining.isEmpty()) {
                break;
            }
            remaining = WarehouseStorage.of(level, building).insert(level, building, remaining);
        }
        return remaining;
    }

    /**
     * Returns a superseded piece to the settlement rather than deleting it
     * (INV-3): armoury chests first, then any warehouse, then the hearth
     * larder, and only as the true last resort — every one of those full or
     * absent — popped onto the ground at the armoury's own anchor (or, with
     * no armoury built yet, at the guard's own feet) exactly like
     * {@code Production}'s own give-back.
     */
    private static void depositToStores(ServerLevel level, @Nullable Settlement settlement,
                                        SettlerEntity settler, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        List<Building> armouries = settlement == null ? List.of()
            : buildingsOfType(settlement, BuildingType.ARMOURY);
        ItemStack remaining = depositInto(level, armouries, stack);
        if (!remaining.isEmpty() && settlement != null) {
            remaining = depositInto(level, buildingsOfType(settlement, BuildingType.WAREHOUSE), remaining);
        }
        if (!remaining.isEmpty()) {
            HearthBlockEntity hearth = settler.hearth();
            if (hearth != null) {
                remaining = hearth.insertGoods(remaining);
            }
        }
        if (!remaining.isEmpty()) {
            BlockPos dropAt = !armouries.isEmpty() ? armouries.get(0).anchor : settler.blockPosition();
            Block.popResource(level, dropAt, remaining);
        }
    }

    // ------------------------------------------------------------ captain ---

    /**
     * The settlement's highest-ranked living guard, or {@code null} if it has
     * none — computed fresh from {@code loadedMembers} every call, never
     * stored. The Vaktkaptein is a fact about who the guards currently are,
     * not a record that could quietly go stale the moment a better guard
     * grows into the role or the old one falls.
     *
     * <p>Ties (two guards who share a rank tier) go to whichever has the
     * higher raw Strength, so the answer is still a single settler and not an
     * arbitrary "whoever the list happened to put first".
     *
     * @param loadedMembers a settlement's currently-loaded settlers, e.g.
     *                      {@code SettlementManager.loadedMembers(level, s)}
     */
    @Nullable
    public static SettlerEntity captainOf(List<SettlerEntity> loadedMembers) {
        SettlerEntity best = null;
        GuardRank bestRank = null;
        for (SettlerEntity settler : loadedMembers) {
            if (settler.getProfession() != Profession.GUARD || !settler.isAlive()) {
                continue;
            }
            GuardRank rank = of(settler);
            if (bestRank == null
                || rank.ordinal() > bestRank.ordinal()
                || (rank.ordinal() == bestRank.ordinal()
                    && settler.attribute(Attribute.STRENGTH) > best.attribute(Attribute.STRENGTH))) {
                best = settler;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Progress towards the next rank, 0..1; 1 at the top. */
    public static float progress(int strength) {
        GuardRank now = of(strength);
        if (now == CAPTAIN) {
            return 1.0F;
        }
        GuardRank next = values()[now.ordinal() + 1];
        int span = next.threshold - now.threshold;
        return span <= 0 ? 1.0F : (strength - now.threshold) / (float) span;
    }
}
