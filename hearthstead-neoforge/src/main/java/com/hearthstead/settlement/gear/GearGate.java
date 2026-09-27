package com.hearthstead.settlement.gear;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementHeraldry;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server authority for {@link GearTier}: may THIS settler use THIS item?
 *
 * <p>Every path that puts gear on a settler asks here first: the settler's
 * own pack (player hand-over), the workplace chest, a Courier delivery, a
 * ground pickup ({@code EquipmentRequirement} carries the cap) and the
 * armoury kit ({@code GuardRank.applyEquipment}). A refusal never moves or
 * deletes anything: the item simply stays where it was (the pack, the chest)
 * and the settler keeps what they already hold. The moment the settler is
 * promoted or the settlement learns the tech, the once-a-second
 * {@link #tickSecond} puts pack armour on unprompted.
 *
 * <p>Items already worn are never stripped by the gate (old saves keep what
 * their guards wear); the gate only guards the hand-over.
 *
 * <p>Clients cannot see Strength, Dexterity or Development state, so the
 * answer is published as one packed int on the settler
 * ({@link #pack}): role, personal tier and the per-tier knowledge bits. The
 * settler sheet and the armour renderer read that projection only.
 */
public final class GearGate {
    private GearGate() {
    }

    // ---------------------------------------------------------- kill switch ---

    @Nullable
    private static Boolean enabledOverrideForTest;

    /**
     * {@code [features] gearTiers} (HearthsteadServerConfig). When false every
     * settler follows the pre-tier rules: nothing is gated, nothing dresses
     * from the pack, the armoury kit is the fixed rank kit, and the armour
     * layer falls back to the old rank overlays. Nothing is ever deleted.
     */
    public static boolean enabled() {
        Boolean forced = enabledOverrideForTest;
        if (forced != null) {
            return forced;
        }
        return com.hearthstead.HearthsteadServerConfig.gearTiersEnabled();
    }

    /** GameTest seam for the kill switch; null restores the config value. */
    public static void setEnabledOverrideForTest(@Nullable Boolean enabled) {
        enabledOverrideForTest = enabled;
    }

    /** Everything open: the clearance the pre-tier rules imply. */
    public static Clearance open(GearTier.Role role) {
        return new Clearance(role, GearTier.MAX, 0x1F);
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    // ------------------------------------------------------------ clearance ---

    /** Both halves of the rule for one settler, as the client also sees it. */
    public record Clearance(GearTier.Role role, int personal, int knowledgeMask) {
        public boolean allows(int itemTier) {
            return GearTier.allowed(itemTier, personal, knowledgeMask);
        }

        public boolean personalMet(int tier) {
            return personal >= tier;
        }

        public boolean knowledgeMet(int tier) {
            return tier <= 0 || (knowledgeMask & (1 << tier)) != 0;
        }

        public int usable() {
            return GearTier.usableTier(personal, knowledgeMask);
        }

        @Nullable
        public GearTier nextLocked() {
            return GearTier.nextLocked(personal, knowledgeMask);
        }

        public int pack() {
            return GearGate.pack(role, personal, knowledgeMask);
        }
    }

    /** bits 0-2 personal tier, 3-7 knowledge bits (tier t at bit 3+t), 8-9 role, 10 = computed. */
    public static int pack(GearTier.Role role, int personal, int knowledgeMask) {
        return (Math.max(0, Math.min(GearTier.MAX, personal)) & 0x7)
            | ((knowledgeMask & 0x1F) << 3)
            | ((role.ordinal() & 0x3) << 8)
            | (1 << 10);
    }

    public static Clearance unpack(int packed) {
        return new Clearance(GearTier.Role.fromId((packed >> 8) & 0x3),
            packed & 0x7, (packed >> 3) & 0x1F);
    }

    /**
     * Fighters on the guard rank ladder. Matched by name so the battle-role
     * professions (Spearman, Longswordsman) join the moment they exist
     * without this class depending on their enum constants.
     */
    private static final java.util.Set<String> GUARD_LADDER =
        java.util.Set.of("GUARD", "SPEARMAN", "LONGSWORDSMAN");

    public static GearTier.Role roleOf(Profession profession) {
        if (profession != null && GUARD_LADDER.contains(profession.name())) {
            return GearTier.Role.GUARD;
        }
        if (profession == Profession.ARCHER) {
            return GearTier.Role.ARCHER;
        }
        return GearTier.Role.WORKER;
    }

    /** Server truth when available, the synced projection on the client. */
    public static Clearance clearance(SettlerEntity settler) {
        if (!enabled()) {
            return open(roleOf(settler.getProfession()));
        }
        if (settler.level() instanceof ServerLevel level) {
            return compute(level, settler);
        }
        return unpack(settler.gearClearancePacked());
    }

    public static Clearance compute(ServerLevel level, SettlerEntity settler) {
        GearTier.Role role = roleOf(settler.getProfession());
        if (!enabled()) {
            return open(role);
        }
        int personal = switch (role) {
            case GUARD -> GearTier.personalTier(GuardRank.of(settler));
            case ARCHER -> GearTier.personalTier(ArcherRank.of(settler));
            case WORKER -> settler.getProfession() == Profession.NONE ? 0
                : GearTier.personalTier(SkillLevels.levelOf(settler));
        };
        Settlement settlement = settler.isTraveler() ? null : settler.settlement();
        int mask = knowledgeMask(level, settlement);
        return new Clearance(role, personal, mask);
    }

    public static int knowledgeMask(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        if (level == null || settlement == null) {
            return 1; // T0 only
        }
        return GearTier.knowledgeMask(id -> owns(level, settlement, id));
    }

    /** Resolves one knowledge id ({@code node:}, {@code upgrade:}, {@code building:}). */
    public static boolean owns(ServerLevel level, Settlement settlement, String id) {
        Set<String> granted = TEST_GRANTS.get(settlement.id);
        if (granted != null && granted.contains(id)) {
            return true;
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String kind = id.substring(0, colon);
        String key = id.substring(colon + 1);
        switch (kind) {
            case "node": {
                DevelopmentNode node = DevelopmentNode.byId(key);
                // v3 tech tree ids without a legacy entry (castle_charter,
                // master_armoury, kingdom_crown) resolve through the tree.
                return node != null ? Development.hasNode(level, settlement, node)
                    : Development.has(level, settlement, key);
            }
            case "upgrade": {
                PostRaidUpgrade upgrade = PostRaidUpgrade.byId(key);
                return upgrade != null && Development.hasUpgrade(level, settlement, upgrade);
            }
            case "building": {
                for (Building building : settlement.buildings) {
                    if (building != null && building.valid && building.type != null
                        && building.type.id().equals(key)) {
                        return true;
                    }
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** A v3 tech node that exists and can be learned in this build. */
    private static boolean techNodeLive(String key) {
        var def = com.hearthstead.settlement.techtree.TechTreeData.get().node(key);
        return def != null && com.hearthstead.settlement.development.TechTree.implemented(def);
    }

    /** True for ids the running code can ever satisfy (UI marks the rest "coming"). */
    public static boolean knownId(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String key = id.substring(colon + 1);
        return switch (id.substring(0, colon)) {
            case "node" -> DevelopmentNode.byId(key) != null
                || techNodeLive(key);
            case "upgrade" -> PostRaidUpgrade.byId(key) != null;
            case "building" -> {
                for (BuildingType type : BuildingType.values()) {
                    if (type.id().equals(key)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    // ----------------------------------------------------------- decisions ---

    public static boolean allows(SettlerEntity settler, ItemStack stack) {
        if (!enabled()) {
            return true;
        }
        int tier = GearTiers.tierOf(stack);
        return tier <= 0 || clearance(settler).allows(tier);
    }

    /** Highest tier this settler may pick up through the request/tool paths. */
    public static int maxTier(SettlerEntity settler) {
        return clearance(settler).usable();
    }

    /** {@code requirement} narrowed to what this settler may take (acquisition paths). */
    public static EquipmentRequirement limit(EquipmentRequirement requirement, SettlerEntity settler) {
        if (requirement == null || !enabled()) {
            return requirement;
        }
        return requirement.withMaxGearTier(maxTier(settler));
    }

    /**
     * "Brenna is a Recruit: Diamond Sword needs Sergeant rank and the Castle
     * Charter + Master Armoury. She keeps it in her pack."
     */
    public static Component refusal(SettlerEntity settler, ItemStack stack) {
        Clearance c = clearance(settler);
        GearTier tier = GearTiers.gearTierOf(stack);
        return Component.translatable("hearthstead.gear.refuse",
            settler.getSettlerName(), who(settler, c), stack.getHoverName(),
            missing(c, tier));
    }

    /** "a Recruit" / "a level 2 Farmer". */
    public static Component who(SettlerEntity settler, Clearance c) {
        return switch (c.role()) {
            case GUARD -> Component.translatable("hearthstead.gear.who.rank",
                guardRankFor(settler, c).displayName());
            case ARCHER -> Component.translatable("hearthstead.gear.who.rank",
                archerRankFor(settler, c).displayName());
            case WORKER -> Component.translatable("hearthstead.gear.who.level",
                SkillLevels.levelOf(settler.tradeXp()), settler.getProfession().displayName());
        };
    }

    private static GuardRank guardRankFor(SettlerEntity settler, Clearance c) {
        if (settler.level() instanceof ServerLevel) {
            return GuardRank.of(settler);
        }
        // Client: the highest rank the published personal tier implies.
        GuardRank best = GuardRank.RECRUIT;
        for (GearTier t : GearTier.values()) {
            if (c.personal() >= t.level()) {
                best = t.guardRank();
            }
        }
        return best;
    }

    private static ArcherRank archerRankFor(SettlerEntity settler, Clearance c) {
        if (settler.level() instanceof ServerLevel) {
            return ArcherRank.of(settler);
        }
        ArcherRank best = ArcherRank.RECRUIT;
        for (GearTier t : GearTier.values()) {
            if (c.personal() >= t.level()) {
                best = t.archerRank();
            }
        }
        return best;
    }

    /** What is still missing for {@code tier}: "Veteran rank and an Armoury, ...". */
    public static Component missing(Clearance c, GearTier tier) {
        List<Component> parts = new ArrayList<>(2);
        if (!c.personalMet(tier.level())) {
            parts.add(tier.personalRequirement(c.role()));
        }
        if (!c.knowledgeMet(tier.level()) && tier.knowledgeRequirement() != null) {
            parts.add(tier.knowledgeRequirement());
        }
        if (parts.isEmpty()) {
            return Component.translatable("hearthstead.gear.need.nothing");
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return Component.translatable("hearthstead.gear.need.both", parts.get(0), parts.get(1));
    }

    /** "Next: Veteran rank + Armoury → Plate (iron armour, crossbows)", or null at the top. */
    @Nullable
    public static Component nextUnlock(Clearance c) {
        GearTier next = c.nextLocked();
        if (next == null) {
            return null;
        }
        return Component.translatable("hearthstead.gear.next", missing(c, next),
            next.displayName(), next.itemsName());
    }

    // -------------------------------------------------------------- ticking ---

    /**
     * Once a second from the settler's server tick: publish the clearance and
     * heraldry projections, then let a guard or archer put on any pack armour
     * they have just become allowed to wear.
     */
    public static void tickSecond(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Clearance c = compute(level, settler);
        settler.setGearProjection(c.pack(), heraldryPacked(settler));
        if (!enabled()) {
            return; // pre-tier rules: nobody dresses from their pack
        }
        GearTier.Role role = c.role();
        if (role == GearTier.Role.GUARD || role == GearTier.Role.ARCHER) {
            equipArmourFromPack(settler, c);
        }
    }

    public static boolean equipArmourFromPack(SettlerEntity settler) {
        if (!enabled() || !wearsArmour(settler)) {
            return false;
        }
        return equipArmourFromPack(settler, clearance(settler));
    }

    /** Only guards and archers dress from their pack or care about armour tiers. */
    public static boolean wearsArmour(SettlerEntity settler) {
        GearTier.Role role = roleOf(settler.getProfession());
        return role == GearTier.Role.GUARD || role == GearTier.Role.ARCHER;
    }

    /** Whether handing {@code stack} to this settler is a gear hand-over worth answering. */
    public static boolean relevant(SettlerEntity settler, ItemStack stack) {
        if (!enabled() || GearTiers.tierOf(stack) <= 0) {
            return false;
        }
        return !(stack.getItem() instanceof ArmorItem) || wearsArmour(settler);
    }

    /**
     * Pack to body, exact swap: the best allowed armour piece in the pack that
     * beats what the slot holds goes on, and the replaced piece takes its pack
     * slot. Never creates, never deletes. Only guards and archers dress from
     * their pack; a Courier's pack is cargo, not a wardrobe.
     */
    public static boolean equipArmourFromPack(SettlerEntity settler, Clearance c) {
        if (settler.level().isClientSide) {
            return false;
        }
        boolean changed = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = settler.getItemBySlot(slot);
            int best = -1;
            for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                ItemStack candidate = settler.bag.getItem(i);
                if (candidate.isEmpty() || candidate.getCount() != 1
                    || !(candidate.getItem() instanceof ArmorItem armor)
                    || armor.getEquipmentSlot() != slot) {
                    continue;
                }
                int tier = GearTiers.tierOf(candidate);
                if (!c.allows(tier) || !beats(candidate, worn)) {
                    continue;
                }
                if (best < 0 || beats(candidate, settler.bag.getItem(best))) {
                    best = i;
                }
            }
            if (best < 0) {
                continue;
            }
            ItemStack wearing = settler.bag.getItem(best).copy();
            settler.bag.setItem(best, worn.isEmpty() ? ItemStack.EMPTY : worn.copy());
            settler.setItemSlot(slot, wearing);
            settler.setDropChance(slot, 0.0F);
            changed = true;
        }
        return changed;
    }

    /** Strictly better armour: higher gear tier, then more protection. */
    public static boolean beats(ItemStack candidate, ItemStack worn) {
        if (candidate.isEmpty()) {
            return false;
        }
        if (worn.isEmpty()) {
            return true;
        }
        int ct = GearTiers.tierOf(candidate);
        int wt = GearTiers.tierOf(worn);
        if (ct != wt) {
            return ct > wt;
        }
        return defense(candidate) > defense(worn);
    }

    public static int defense(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem armor ? armor.getDefense() : 0;
    }

    // ------------------------------------------------------------ heraldry ---

    /** bits 0-3 field colour, 4-7 charge/trim colour, bit 8 = known. 0 = unknown. */
    public static int packHeraldry(DyeColor field, DyeColor trim) {
        return (field.getId() & 0xF) | ((trim.getId() & 0xF) << 4) | (1 << 8);
    }

    public static int heraldryPacked(SettlerEntity settler) {
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            return 0;
        }
        // The village design (Banner designer, a hung banner, or the founding
        // colours): field = surcoat, first contrasting layer colour = trim.
        com.hearthstead.heraldry.VillageDesign design = hearth.effectiveDesign();
        return packHeraldry(design.base(), design.trim());
    }

    // ------------------------------------------------------ GameTest seam ---

    /**
     * Future tech ids (Castle Charter, Master Armoury, ...) cannot be learned
     * in code yet. GameTests grant them here to prove the T3/T4 path end to
     * end; production code never writes this map.
     */
    private static final Map<UUID, Set<String>> TEST_GRANTS = new HashMap<>();

    public static void grantForTest(UUID settlementId, String knowledgeId) {
        TEST_GRANTS.computeIfAbsent(settlementId, k -> new HashSet<>()).add(knowledgeId);
    }

    public static void clearTestGrants(UUID settlementId) {
        TEST_GRANTS.remove(settlementId);
    }
}
