package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernBard;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.raid.RaidBroadcast;
import com.hearthstead.settlement.techtree.EffectRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Commons &amp; Household branch effects (21 nodes). OWNED BY THE COMMONS
 * BRANCH LANE: only that lane edits this file and
 * data/hearthstead/techtree/commons.json.
 *
 * <p>Every hook the gameplay code calls lives here as a small static,
 * side-effect-free (or clearly effectful) helper that returns the untouched
 * base value when the node is not learned, when the call is client-side or
 * when there is no settlement. All bonuses are per settlement (one shared
 * tree, co-op), never per player.
 *
 * <p>Two things are run from this class itself (level tick, no gameplay
 * class needed): Infirmary healing and the Harvest Feast at dawn. Both skip
 * the GameTest server; tests drive them through the public seams.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CommonsEffects {

    // ------------------------------------------------------------ numbers

    /** warm_hearth: morale for warming at the Banner fire in the evening, once a day. */
    public static final float WARM_GATHER_MORALE = 3.0F;
    /** warm_hearth: extra chance an idle settler walks to the fire. */
    public static final float WARM_CHANCE_BONUS = 0.2F;
    public static final String WARM_DAY_KEY = "HearthsteadWarmHearthDay";
    /** sturdy_beds: residents of a level-2+ home. */
    public static final int COTTAGE_MORALE = 2;
    /** manors: residents of a level-3 home. */
    public static final int MANOR_MORALE = 2;
    /** two_storey_houses and manors: House resident places, each. */
    public static final int HOUSE_EXTRA_PLACES = 2;
    /** feather_quilts: effort refill on a calm wake in a bed. */
    public static final float WELL_RESTED_FRACTION = 1.10F;
    public static final String WELL_RESTED_DAY_KEY = "HearthsteadWellRestedDay";
    /** hearth_doctrine: study timers run this much faster. */
    public static final int STUDY_SPEED_PERCENT = 25;
    /** bards_songbook: the evening bard lift (base TavernBard.EVENING_LIFT = 5). */
    public static final float SONGBOOK_LIFT = 8.0F;
    public static final int BARD_TIPS_PER_EVENING = 3;
    /** alehouse: morale for a pint (base 3). */
    public static final float ALEHOUSE_ALE_MORALE = 5.0F;
    /** war_feast. */
    public static final int WAR_FEAST_MEALS = 12;
    public static final int WAR_FEAST_ALE = 4;
    public static final float WAR_FEAST_DAMAGE = 1.10F;
    /** war_feast: guards fall back to eat only below this, instead of 30%. */
    public static final float WAR_FEAST_RECOVER_FRACTION = 0.15F;
    /**
     * Stored as an absolute DAY-TIME (not game time): the dawn after the raid
     * night, so a longer day ([time] day length) or sleeping never shifts it.
     */
    public static final String WAR_FEAST_UNTIL_KEY = "HearthsteadWarFeastDawn";
    /** wayside_shrine / cathedral: morale for everyone on a raid held. */
    public static final float SHRINE_MORALE = 5.0F;
    public static final float CATHEDRAL_MORALE = 10.0F;
    /** infirmary: one HP every this many ticks while inside. */
    public static final int INFIRMARY_HEAL_TICKS = 40;
    public static final float INFIRMARY_HEAL = 1.0F;
    /** great_tavern: Minstrels and Caravan event weight. */
    public static final double GREAT_TAVERN_EVENT_SCALE = 2.0D;
    /** harvest_feast. */
    public static final int HARVEST_FEAST_EVERY_DAYS = 4;
    public static final int HARVEST_FEAST_BREAD = 48;
    public static final int HARVEST_FEAST_ALE = 16;
    public static final float HARVEST_FEAST_MORALE = 15.0F;
    /** Workshop craft ticks during the festival (10% faster). */
    public static final double FESTIVAL_CRAFT_PACE = 0.90D;
    public static final String HARVEST_DAY_KEY = "HearthsteadHarvestFeastDay";
    public static final String HARVEST_TRIED_KEY = "HearthsteadHarvestFeastTried";
    /** Absolute day-time of the dawn that ends the festival. */
    public static final String FESTIVAL_UNTIL_KEY = "HearthsteadFestivalDawn";
    /** hall_of_heroes. */
    public static final float HEROES_GRIEF_SCALE = 0.5F;
    public static final float HEROES_RECRUIT_MORALE = 5.0F;
    /** hall_and_learning: recruits start every trade at this level. */
    public static final int SCHOOL_TRADE_LEVEL = 2;
    /** home: a valid Well House raises every housed settler's morale target. */
    public static final int WELL_MORALE = 2;
    /** hospitality: a valid Market brings visiting merchants this many more Coins. */
    public static final int MARKET_PURSE = 6;
    /** hall_and_learning: with a valid School, recruits start one level higher still. */
    public static final int SCHOOL_BUILDING_TRADE_LEVEL = 3;
    /** hall_of_revels: bard lift multiplier, and one extra Coin per Coin a traveller pays. */
    public static final float REVELS_BARD_SCALE = 2.0F;

    private CommonsEffects() {
    }

    static void register(EffectRegistry r) {
        // Plans that had no unlocking node (handbook audit, 26 Sep): each is
        // claimed by the node it belongs to and given a real job.
        r.node("home")
            .building(BuildingType.WELL)
            .flag("CommonsEffects.homeMorale (SettlerEntity morale target)",
                "A valid Well House: +2 morale for every settler with a bed");
        r.node("hospitality")
            .building(BuildingType.MARKET)
            .flag("CommonsEffects.marketPurse (GoldCoinTrades merchant purse)",
                "A valid Market: visiting merchants carry 6 more Coins");
        r.node("warm_hearth")
            .flag("CommonsEffects.onWarmAtHearth (FreeTimeGoal WARM scene)",
                "Evening at the Banner fire: +3 morale once a day, and settlers gather there more often");
        r.node("sturdy_beds")
            .flag("CommonsEffects.homeMorale (SettlerEntity morale target)",
                "Residents of a level-2 House or Lodging: +2 more morale");
        r.node("feather_quilts")
            .flag("CommonsEffects.wakeRefillFraction (SettlerEntity.tickEffortRefill)",
                "Well Rested: a calm night in a bed refills daily work to 110% (not after an alarm)");
        r.node("hearth_doctrine")
            .building(BuildingType.LIBRARY)
            .bonus(TechTree.STUDY_SPEED, STUDY_SPEED_PERCENT);
        r.node("bards_songbook")
            .flag("CommonsEffects.bardLift (TavernBard.onRefreshmentCompleted)",
                "Bard's evening morale lift 5 -> 8")
            .flag("CommonsEffects.bardTip (TavernBard.onRefreshmentCompleted)",
                "Travellers who hear the bard tip 1 Coin into your stores (max 3 per evening)");
        r.node("kitchen_and_hall")
            .building(BuildingType.KITCHEN)
            .building(BuildingType.DINING_HALL)
            .profession(Profession.COOK)
            .grandfatheredBy("hall_and_learning");
        r.node("war_feast")
            .flag("CommonsEffects.onRaidWarning (RaidDirector recurring warning)",
                "On a raid warning the village feasts on 12 meals + 4 ale from the Banner and Warehouses")
            .flag("CommonsEffects.onIncomingDamage + guardRecoverFraction (GuardRecoveryGoal)",
                "Feasted guards deal +10% damage and only fall back to eat below 15% health");
        r.node("two_storey_houses")
            .flag("CommonsEffects.refreshHousing -> Settlement.houseBedCap (capacity, bed claims, plaque)",
                "House bed cap 4 -> 6: every House may hold 2 more settlers");
        r.node("wayside_shrine")
            .flag("CommonsEffects.onRaidWon (RaidDirector victory)",
                "After every raid held: +5 morale for everyone");
        r.node("alehouse")
            .building(BuildingType.BREWERY)
            .profession(Profession.BREWER)
            .grandfatheredBy("hall_and_learning")
            .flag("CommonsEffects.aleMorale (TavernServingEntity ale)",
                "A pint at the Tavern lifts morale 3 -> 5");
        r.node("infirmary")
            .building(BuildingType.INFIRMARY)
            .grandfatheredBy("first_raid_aftermath")
            .flag("CommonsEffects.infirmaryPass (level tick)",
                "Wounded settlers inside a valid Infirmary heal 1 HP every 2 seconds");
        r.node("battle_healer")
            .profession(Profession.HEALER)
            .grandfatheredBy("first_raid_aftermath");
        r.node("manors")
            .flag("CommonsEffects.refreshHousing -> Settlement.houseBedCap (capacity, bed claims, plaque)",
                "House bed cap 6 -> 8")
            .flag("CommonsEffects.homeMorale (SettlerEntity morale target)",
                "Residents of a level-3 House or Lodging: +2 more morale");
        r.node("great_tavern")
            .flag("CommonsEffects.eventWeightScale (WorldEventDirector plan)",
                "Minstrels and trade caravans visit twice as often");
        r.node("harvest_feast")
            .flag("CommonsEffects.harvestFeastAtDawn (level tick)",
                "Every 4 days at dawn: a feast of 48 bread + 16 ale from your stores gives everyone +15 morale")
            .flag("CommonsEffects.craftPace (Production.ticksFor)",
                "Festival day: every workshop crafts 10% faster until the next dawn");
        r.node("hall_of_heroes")
            .flag("CommonsEffects.griefScale (SettlementManager death, Mayor death)",
                "Morale lost when a settler or the Mayor dies is halved")
            .flag("CommonsEffects.onRecruited (SettlementManager.admitWaitingTraveler)",
                "New recruits arrive with +5 morale");
        r.node("hall_and_learning")
            // The School keeps the Library it always had, so a save that
            // owned this node before v3 never loses the plan (the Library
            // also comes with the Hearth Doctrine now).
            .building(BuildingType.LIBRARY)
            .building(BuildingType.SCHOOL)
            .flag("CommonsEffects.onRecruited (SettlementManager.admitWaitingTraveler)",
                "New recruits arrive schooled: trade level 2 in every trade (3 with a valid School)");
        r.node("cathedral")
            .flag("CommonsEffects.onRaidWon (RaidDirector victory)",
                "After every raid held: +10 morale for everyone");
        r.node("hall_of_revels")
            .flag("CommonsEffects.tavernCoinBonus (TavernGuestPayment)",
                "Every Coin a traveller pays at the Tavern earns 1 more Coin")
            .flag("CommonsEffects.bardLift (TavernBard.onRefreshmentCompleted)",
                "The bard's evening morale lift is doubled");
    }

    // ------------------------------------------------------------- basics

    private static boolean has(@Nullable ServerLevel level, @Nullable Settlement settlement, String id) {
        return level != null && settlement != null && TechTree.has(level, settlement, id);
    }

    @Nullable
    private static ServerLevel levelOf(@Nullable Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level ? level : null;
    }

    /** The settlement's own Banner block entity, or null when unloaded/mismatched. */
    @Nullable
    public static HearthBlockEntity banner(ServerLevel level, @Nullable Settlement settlement) {
        if (level == null || settlement == null || settlement.center == null
            || !level.hasChunkAt(settlement.center)) {
            return null;
        }
        return level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth
            && settlement.id.equals(hearth.getSettlementId()) ? hearth : null;
    }

    private static long bannerLong(ServerLevel level, Settlement settlement, String key, long fallback) {
        HearthBlockEntity hearth = banner(level, settlement);
        if (hearth == null) {
            return fallback;
        }
        CompoundTag data = hearth.getPersistentData();
        return data.contains(key, Tag.TAG_LONG) ? data.getLong(key) : fallback;
    }

    private static boolean putBannerLong(ServerLevel level, Settlement settlement, String key, long value) {
        HearthBlockEntity hearth = banner(level, settlement);
        if (hearth == null) {
            return false;
        }
        hearth.getPersistentData().putLong(key, value);
        hearth.setChanged();
        return true;
    }

    private static void cheer(ServerLevel level, SettlerEntity settler, int count) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, settler.getX(),
            settler.getY() + settler.getBbHeight() + 0.3D, settler.getZ(), count, 0.3D, 0.2D, 0.3D, 0.0D);
    }

    private static int moraleToAll(ServerLevel level, Settlement settlement, float amount) {
        int n = 0;
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (member.isAlive()) {
                member.addMorale(amount);
                cheer(level, member, 4);
                n++;
            }
        }
        return n;
    }

    // --------------------------------------------------------- warm_hearth

    /** FreeTimeGoal: chance an idle settler walks to the fire. */
    public static float warmChance(SettlerEntity settler, float base) {
        ServerLevel level = levelOf(settler);
        return has(level, settler.settlement(), "warm_hearth")
            ? Math.min(0.95F, base + WARM_CHANCE_BONUS) : base;
    }

    /**
     * FreeTimeGoal: the settler has just arrived at the Banner fire. In the
     * evening, with Warm Hearth, that is +3 morale once per game day.
     *
     * @return true when the lift was granted by this call
     */
    public static boolean onWarmAtHearth(SettlerEntity settler) {
        ServerLevel level = levelOf(settler);
        if (level == null || !settler.isAlive() || settler.isTraveler()
            || !settler.dayPhase().social() || !has(level, settler.settlement(), "warm_hearth")) {
            return false;
        }
        return grantWarmth(level, settler);
    }

    /** The once-a-day part of {@link #onWarmAtHearth}; public for GameTests. */
    public static boolean grantWarmth(ServerLevel level, SettlerEntity settler) {
        if (!has(level, settler.settlement(), "warm_hearth")) {
            return false;
        }
        long day = Math.floorDiv(level.getDayTime(), 24_000L);
        CompoundTag data = settler.getPersistentData();
        if (data.contains(WARM_DAY_KEY, Tag.TAG_LONG) && data.getLong(WARM_DAY_KEY) == day) {
            return false;
        }
        data.putLong(WARM_DAY_KEY, day);
        settler.addMorale(WARM_GATHER_MORALE);
        level.sendParticles(ParticleTypes.HEART, settler.getX(), settler.getY() + settler.getBbHeight() + 0.3D,
            settler.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        return true;
    }

    // ------------------------------------------------- sturdy_beds, manors

    /** SettlerEntity morale target: extra for residents of a well-furnished home. */
    public static int homeMorale(SettlerEntity settler, @Nullable Settlement settlement, @Nullable BlockPos bed) {
        ServerLevel level = levelOf(settler);
        if (level == null || settlement == null || bed == null) {
            return 0;
        }
        return homeMorale(level, settlement, bed);
    }

    public static int homeMorale(ServerLevel level, Settlement settlement, BlockPos bed) {
        int well = hasValid(settlement, BuildingType.WELL) ? WELL_MORALE : 0;
        boolean cottage = has(level, settlement, "sturdy_beds");
        boolean manor = has(level, settlement, "manors");
        if (!cottage && !manor) {
            return well;
        }
        int homeLevel = 0;
        for (Building building : settlement.buildings) {
            if (building.valid && building.type.housesResidents() && building.beds.contains(bed)) {
                homeLevel = building.level;
                break;
            }
        }
        return well + (cottage && homeLevel >= 2 ? COTTAGE_MORALE : 0)
            + (manor && homeLevel >= 3 ? MANOR_MORALE : 0);
    }

    /** True when the settlement has at least one valid building of {@code type}. */
    public static boolean hasValid(@Nullable Settlement settlement, BuildingType type) {
        if (settlement == null) {
            return false;
        }
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == type) {
                return true;
            }
        }
        return false;
    }

    /** GoldCoinTrades: extra merchant purse from a valid Market (Tavern &amp; Trade). */
    public static int marketPurse(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return level != null && hasValid(settlement, BuildingType.MARKET) ? MARKET_PURSE : 0;
    }

    /**
     * The tech node whose learning opens this build plan, for the plaque's
     * refusal line: the claiming node(s) first, else the legacy node that
     * lists it. Null when no node opens it.
     */
    @Nullable
    public static Component planSource(BuildingType type) {
        List<String> claimants = EffectRegistry.get().buildingClaimants(type);
        com.hearthstead.settlement.techtree.TechTreeData data = com.hearthstead.settlement.techtree.TechTreeData.get();
        for (String id : claimants) {
            var def = data.node(id);
            if (def != null && TechTree.implemented(def)) {
                return def.displayName();
            }
        }
        if (!claimants.isEmpty()) {
            return null;
        }
        for (var def : data.nodes()) {
            if (def.legacyNode()) {
                var node = com.hearthstead.settlement.development.DevelopmentNode.byId(def.legacyId());
                if (node != null && node.buildings().contains(type)) {
                    return def.displayName();
                }
            }
        }
        return null;
    }

    // -------------------------------------- two_storey_houses, manors (cap)

    /** Settlement-aware resident places of a home type (House +2 per node). */
    public static int houseCapacity(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                    BuildingType type) {
        int base = type.residentCapacity();
        if (type != BuildingType.HOUSE) {
            return base;
        }
        return base + (has(level, settlement, "two_storey_houses") ? HOUSE_EXTRA_PLACES : 0)
            + (has(level, settlement, "manors") ? HOUSE_EXTRA_PLACES : 0);
    }

    /** {@code BuildingManager.capacityOf} with the settlement's home nodes applied. */
    public static int capacityOf(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                 BuildingType type, Building building) {
        return type.housesResidents()
            ? Math.min(houseCapacity(level, settlement, type), building.beds.size())
            : type.workerCapacity();
    }

    /**
     * Keeps {@link Settlement#houseBedCap} current (4 / 6 / 8). Called every
     * 2 s by the level tick and directly by tests. Never evicts anyone: the
     * cap limits new bed claims and the capacity count only.
     */
    public static void refreshHousing(ServerLevel level, Settlement settlement) {
        if (level != null && settlement != null) {
            settlement.houseBedCap = houseCapacity(level, settlement, BuildingType.HOUSE);
        }
    }

    /**
     * True when {@code building} may take one more resident: a House under
     * its cap (counting beds already claimed in it), any other home always.
     */
    public static boolean hasRoom(Settlement settlement, Building building,
                                  java.util.Collection<BlockPos> claimedBeds) {
        if (settlement == null || settlement.houseBedCap <= 0 || building.type != BuildingType.HOUSE) {
            return true;
        }
        int used = 0;
        for (BlockPos bed : building.beds) {
            if (claimedBeds.contains(bed)) {
                used++;
            }
        }
        return used < settlement.houseBedCap;
    }

    /** The plaque line naming the House cap, or null for other buildings / unknown cap. */
    @Nullable
    public static String bedCapLineId(Settlement settlement, Building building) {
        if (settlement == null || building == null || settlement.houseBedCap <= 0
            || building.type != BuildingType.HOUSE) {
            return null;
        }
        return settlement.houseBedCap >= 8 ? "bed_cap.8" : settlement.houseBedCap >= 6 ? "bed_cap.6" : "bed_cap.4";
    }

    // ------------------------------------------------------ feather_quilts

    /**
     * SettlerEntity.tickEffortRefill, the moment a night in a bed ends: the
     * fraction of daily effort restored. 1.1 (Well Rested) with Feather
     * Quilts unless the village is alarmed or under threat.
     */
    public static float wakeRefillFraction(SettlerEntity settler) {
        ServerLevel level = levelOf(settler);
        Settlement settlement = settler.settlement();
        if (level == null || settlement == null || !has(level, settlement, "feather_quilts")) {
            return 1.0F;
        }
        long now = level.getGameTime();
        if (settlement.alertActive(now) || com.hearthstead.entity.LifeNeed.threatActive(settlement, now)) {
            return 1.0F;
        }
        settler.getPersistentData().putLong(WELL_RESTED_DAY_KEY, Math.floorDiv(level.getDayTime(), 24_000L));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, settler.getX(), settler.getY() + settler.getBbHeight() + 0.3D,
            settler.getZ(), 6, 0.3D, 0.2D, 0.3D, 0.0D);
        return WELL_RESTED_FRACTION;
    }

    // ------------------------------------------ bards_songbook, revels, ale

    /** TavernBard: the evening lift for this settlement. */
    public static float bardLift(@Nullable ServerLevel level, @Nullable Settlement settlement, float base) {
        float lift = has(level, settlement, "bards_songbook") ? Math.max(base, SONGBOOK_LIFT) : base;
        return has(level, settlement, "hall_of_revels") ? lift * REVELS_BARD_SCALE : lift;
    }

    private static final Map<UUID, long[]> TIPS = new ConcurrentHashMap<>();

    /**
     * TavernBard: a traveller finished a Tavern meal or ale while the bard
     * played. With the Songbook they tip one of their own Coins into the
     * village stores, at most three per Tavern per evening.
     */
    public static boolean bardTip(SettlerEntity traveller, UUID tavernId) {
        ServerLevel level = levelOf(traveller);
        if (level == null || !traveller.isAlive() || !traveller.isTraveler() || tavernId == null) {
            return false;
        }
        Settlement settlement = TavernSeating.visitSettlement(traveller);
        if (settlement == null || !has(level, settlement, "bards_songbook")) {
            return false;
        }
        Building tavern = TavernSeating.building(settlement, tavernId);
        if (tavern == null || TavernBard.bard(level, settlement, tavern) == null) {
            return false;
        }
        long day = Math.floorDiv(level.getDayTime(), 24_000L);
        long[] tally = TIPS.compute(tavernId, (id, old) -> old == null || old[0] != day ? new long[] {day, 0L} : old);
        if (tally[1] >= BARD_TIPS_PER_EVENING) {
            return false;
        }
        if (!canDeposit(level, settlement, new ItemStack(ModItems.GOLD_COIN.get()))) {
            return false; // No room anywhere: the Coin stays in the traveller's bag.
        }
        ItemStack coin = com.hearthstead.settlement.work.TavernGuestPayment.takeAleCoin(traveller);
        if (coin.isEmpty()) {
            return false;
        }
        if (!deposit(level, settlement, coin)) {
            ItemStack back = traveller.bag.addItem(coin);
            if (!back.isEmpty()) {
                com.hearthstead.util.ItemSpill.conserve(level, traveller.blockPosition(), back);
            }
            return false;
        }
        tally[1]++;
        level.sendParticles(ParticleTypes.NOTE, traveller.getX(), traveller.getY() + traveller.getBbHeight() + 0.4D,
            traveller.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
        Hearthstead.LOGGER.info("HEARTHSTEAD_TECH_BARD_TIP traveller={} tavern={} tipsToday={}",
            traveller.getUUID(), tavernId, tally[1]);
        return true;
    }

    /** TavernServingEntity: morale for a pint, before the Innkeeper's welcome scaling. */
    public static float aleMorale(SettlerEntity guest, float base) {
        ServerLevel level = levelOf(guest);
        Settlement settlement = guest.isTraveler() ? TavernSeating.visitSettlement(guest) : guest.settlement();
        return has(level, settlement, "alehouse") ? Math.max(base, ALEHOUSE_ALE_MORALE) : base;
    }

    /** TavernGuestPayment: extra Coins minted into the same till for {@code paid} Coins. */
    public static int tavernCoinBonus(@Nullable ServerLevel level, @Nullable Settlement settlement, int paid) {
        return paid > 0 && has(level, settlement, "hall_of_revels") ? paid : 0;
    }

    /**
     * TavernGuestPayment / TavernServingEntity, after a traveller's meal Coins
     * were really paid: with the Hall of Revels the house adds the same
     * number of Coins to the Banner or a Warehouse (skipped when full).
     *
     * @return Coins added
     */
    public static int payTavernBonus(@Nullable ServerLevel level, @Nullable Settlement settlement, int paid) {
        int bonus = tavernCoinBonus(level, settlement, paid);
        if (bonus <= 0 || !deposit(level, settlement, new ItemStack(ModItems.GOLD_COIN.get(), bonus))) {
            return 0;
        }
        Hearthstead.LOGGER.info("HEARTHSTEAD_TECH_REVELS_COIN settlement={} coins={}", settlement.id, bonus);
        return bonus;
    }

    /** True when {@link #deposit} would take the whole stack. */
    public static boolean canDeposit(ServerLevel level, Settlement settlement, ItemStack stack) {
        ItemStackHandler stores = CoinTreasury.open(level, settlement, banner(level, settlement), null);
        for (int slot = 0; slot < stores.getSlots(); slot++) {
            if (stores.insertItem(slot, stack.copy(), true).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Puts a stack into the Banner or a Warehouse; true only when all of it fit. */
    public static boolean deposit(ServerLevel level, Settlement settlement, ItemStack stack) {
        ItemStackHandler stores = CoinTreasury.open(level, settlement, banner(level, settlement), null);
        // Whole stack into one slot only: never a partial deposit.
        for (int slot = 0; slot < stores.getSlots(); slot++) {
            if (stores.insertItem(slot, stack.copy(), true).isEmpty()) {
                return stores.insertItem(slot, stack.copy(), false).isEmpty();
            }
        }
        return false;
    }

    // ------------------------------------------------------------ war_feast

    /**
     * RaidDirector, right after a recurring raid warning went out: with the
     * War Feast learned and 12 meals + 4 ale in the Banner and Warehouses,
     * they are eaten (all or nothing) and the guards are feasted until the
     * dawn after the raid.
     */
    public static boolean onRaidWarning(ServerLevel level, Settlement settlement) {
        if (!has(level, settlement, "war_feast") || warFeastActive(level, settlement)) {
            return false;
        }
        ItemStackHandler stores = CoinTreasury.open(level, settlement, banner(level, settlement), null);
        Predicate<ItemStack> meal = stack -> ReadyFood.isReadyMeal(stack) && !stack.is(ModItems.ALE.get());
        Predicate<ItemStack> ale = stack -> stack.is(ModItems.ALE.get());
        if (count(stores, meal) < WAR_FEAST_MEALS || count(stores, ale) < WAR_FEAST_ALE) {
            RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
                "hearthstead.techtree.commons.war_feast.short",
                "No War Feast tonight: the Banner and Warehouses need %s meals and %s ale.",
                WAR_FEAST_MEALS, WAR_FEAST_ALE));
            return false;
        }
        if (!putBannerLong(level, settlement, WAR_FEAST_UNTIL_KEY, warFeastDawn(level.getDayTime()))) {
            return false;
        }
        take(stores, meal, WAR_FEAST_MEALS);
        take(stores, ale, WAR_FEAST_ALE);
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (member.isAlive() && member.getProfession().martial()) {
                cheer(level, member, 5);
            }
        }
        level.playSound(null, settlement.center, net.minecraft.sounds.SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 2.0F, 1.0F);
        RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
            "hearthstead.techtree.commons.war_feast.held",
            "War Feast! The village eats %s meals and drinks %s ale: tonight your guards strike 10%% harder and hold the line.",
            WAR_FEAST_MEALS, WAR_FEAST_ALE));
        Hearthstead.LOGGER.info("HEARTHSTEAD_TECH_WAR_FEAST settlement={} until={}", settlement.id,
            warFeastDawn(level.getDayTime()));
        return true;
    }

    public static boolean warFeastActive(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        if (level == null || settlement == null) {
            return false;
        }
        return activeUntilDawn(bannerLong(level, settlement, WAR_FEAST_UNTIL_KEY, 0L), level.getDayTime());
    }

    /** GuardRecoveryGoal: health fraction at which a guard falls back to eat. */
    public static float guardRecoverFraction(SettlerEntity guard, float base) {
        return warFeastActive(levelOf(guard), guard.settlement()) ? Math.min(base, WAR_FEAST_RECOVER_FRACTION) : base;
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof SettlerEntity attacker)
            || !(attacker.level() instanceof ServerLevel level)
            || !attacker.getProfession().martial()
            || event.getEntity() instanceof SettlerEntity
            || !warFeastActive(level, attacker.settlement())) {
            return;
        }
        event.setAmount(event.getAmount() * WAR_FEAST_DAMAGE);
    }

    // --------------------------------------------- shrine, cathedral (win)

    /** RaidDirector: a raid was held (not lost, not a dawn retreat). */
    public static int onRaidWon(ServerLevel level, Settlement settlement) {
        float lift = (has(level, settlement, "wayside_shrine") ? SHRINE_MORALE : 0.0F)
            + (has(level, settlement, "cathedral") ? CATHEDRAL_MORALE : 0.0F);
        if (lift <= 0.0F) {
            return 0;
        }
        int n = moraleToAll(level, settlement, lift);
        level.playSound(null, settlement.center, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
            net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 0.8F);
        RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
            "hearthstead.techtree.commons.thanksgiving",
            "The bells ring for the victory: everyone in %s gains +%s morale.",
            settlement.name, Math.round(lift)));
        return n;
    }

    // ---------------------------------------------------------- infirmary

    /** One healing pass over every valid Infirmary of the settlement. */
    public static int infirmaryPass(ServerLevel level, Settlement settlement) {
        if (!has(level, settlement, "infirmary")) {
            return 0;
        }
        int healed = 0;
        for (Building building : List.copyOf(settlement.buildings)) {
            if (!building.valid || building.type != BuildingType.INFIRMARY || building.bounds == null
                || !level.hasChunkAt(building.plaquePos == null ? settlement.center : building.plaquePos)) {
                continue;
            }
            for (SettlerEntity patient : level.getEntitiesOfClass(SettlerEntity.class,
                    AABB.of(building.bounds).inflate(0.5D),
                    s -> s.isAlive() && settlement.id.equals(s.getSettlementId())
                        && s.getHealth() < s.getMaxHealth())) {
                patient.heal(INFIRMARY_HEAL);
                if ((level.getGameTime() / INFIRMARY_HEAL_TICKS) % 4 == 0) {
                    level.sendParticles(ParticleTypes.HEART, patient.getX(),
                        patient.getY() + patient.getBbHeight() + 0.3D, patient.getZ(), 1, 0.1D, 0.1D, 0.1D, 0.0D);
                }
                healed++;
            }
        }
        return healed;
    }

    // ------------------------------------------------------- great_tavern

    /** WorldEventDirector: weight multiplier for this settlement's day plan. */
    public static double eventWeightScale(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                          WorldEventType type) {
        if ((type == WorldEventType.MINSTRELS || type == WorldEventType.CARAVAN)
            && has(level, settlement, "great_tavern")) {
            return GREAT_TAVERN_EVENT_SCALE;
        }
        return 1.0D;
    }

    // ------------------------------------------------------ harvest_feast

    /**
     * Dawn check (level tick): every {@value #HARVEST_FEAST_EVERY_DAYS} days,
     * with 48 bread + 16 ale in the Banner and Warehouses, they are eaten (all
     * or nothing), everyone gains +15 morale and workshops craft 10% faster
     * until the next dawn. Short stores: one message that day, retried at
     * the next dawn.
     *
     * @return true when the feast was held by this call
     */
    public static boolean harvestFeastAtDawn(ServerLevel level, Settlement settlement, long day) {
        if (!has(level, settlement, "harvest_feast")) {
            return false;
        }
        long last = bannerLong(level, settlement, HARVEST_DAY_KEY, Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && day - last < HARVEST_FEAST_EVERY_DAYS && day >= last) {
            return false;
        }
        if (bannerLong(level, settlement, HARVEST_TRIED_KEY, Long.MIN_VALUE) == day) {
            return false;
        }
        if (!putBannerLong(level, settlement, HARVEST_TRIED_KEY, day)) {
            return false;
        }
        ItemStackHandler stores = CoinTreasury.open(level, settlement, banner(level, settlement), null);
        Predicate<ItemStack> bread = stack -> stack.is(Items.BREAD);
        Predicate<ItemStack> ale = stack -> stack.is(ModItems.ALE.get());
        if (count(stores, bread) < HARVEST_FEAST_BREAD || count(stores, ale) < HARVEST_FEAST_ALE) {
            RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
                "hearthstead.techtree.commons.harvest_feast.short",
                "The Harvest Feast waits: the Banner and Warehouses need %s bread and %s ale.",
                HARVEST_FEAST_BREAD, HARVEST_FEAST_ALE));
            return false;
        }
        take(stores, bread, HARVEST_FEAST_BREAD);
        take(stores, ale, HARVEST_FEAST_ALE);
        putBannerLong(level, settlement, HARVEST_DAY_KEY, day);
        putBannerLong(level, settlement, FESTIVAL_UNTIL_KEY, nextDawn(level.getDayTime()));
        moraleToAll(level, settlement, HARVEST_FEAST_MORALE);
        level.playSound(null, settlement.center, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_FLUTE.value(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 2.0F, 1.2F);
        RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
            "hearthstead.techtree.commons.harvest_feast.held",
            "Harvest Feast in %s! Everyone gains +%s morale, and every workshop works 10%% faster until tomorrow's dawn.",
            settlement.name, Math.round(HARVEST_FEAST_MORALE)));
        Hearthstead.LOGGER.info("HEARTHSTEAD_TECH_HARVEST_FEAST settlement={} day={}", settlement.id, day);
        return true;
    }

    public static boolean festivalActive(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return level != null && settlement != null
            && activeUntilDawn(bannerLong(level, settlement, FESTIVAL_UNTIL_KEY, 0L), level.getDayTime());
    }

    /** Production.ticksFor: craft-tick multiplier (0.9 on a festival day). */
    public static double craftPace(@Nullable ServerLevel level, @Nullable UUID settlementId) {
        if (level == null || settlementId == null) {
            return 1.0D;
        }
        Settlement settlement = SettlementManager.byId(level, settlementId);
        return festivalActive(level, settlement) ? FESTIVAL_CRAFT_PACE : 1.0D;
    }

    // ----------------------------------------- hall_of_heroes, recruitment

    /** Death/mourning morale loss multiplier. */
    public static float griefScale(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return has(level, settlement, "hall_of_heroes") ? HEROES_GRIEF_SCALE : 1.0F;
    }

    /**
     * SettlementManager.admitWaitingTraveler, after a committed admission:
     * the School (hall_and_learning) raises every trade to level 2, and the
     * Hall of Heroes gives +5 morale.
     */
    public static void onRecruited(ServerLevel level, Settlement settlement, SettlerEntity recruit) {
        if (has(level, settlement, "hall_and_learning")) {
            int xp = SkillLevels.xpForLevel(hasValid(settlement, BuildingType.SCHOOL)
                ? SCHOOL_BUILDING_TRADE_LEVEL : SCHOOL_TRADE_LEVEL);
            for (Profession profession : Profession.values()) {
                if (profession.employed() && recruit.tradeSkills().xp(profession) < xp) {
                    recruit.tradeSkills().set(profession, xp);
                }
            }
        }
        if (has(level, settlement, "hall_of_heroes")) {
            recruit.addMorale(HEROES_RECRUIT_MORALE);
            cheer(level, recruit, 6);
        }
    }

    // ------------------------------------------------------------- ticking

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % INFIRMARY_HEAL_TICKS != 0
            || level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null || data.settlements.isEmpty()) {
            return;
        }
        long timeOfDay = Math.floorMod(level.getDayTime(), 24_000L);
        long day = Math.floorDiv(level.getDayTime(), 24_000L);
        boolean dawn = timeOfDay < 2_000L;
        for (Settlement settlement : List.copyOf(data.settlements.values())) {
            try {
                refreshHousing(level, settlement);
                infirmaryPass(level, settlement);
                if (dawn) {
                    harvestFeastAtDawn(level, settlement, day);
                }
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Commons tech tick failed for {}", settlement.id, failure);
            }
        }
    }

    // ----------------------------------------------------------- utilities

    /** The next dawn (day-time 0 of the next day) after {@code dayTime}. */
    public static long nextDawn(long dayTime) {
        return (Math.floorDiv(dayTime, 24_000L) + 1L) * 24_000L;
    }

    /**
     * War Feast deadline: the warning comes one dusk before the raid night,
     * so the feast lasts until the dawn after that night (two dawns ahead
     * when warned in the evening, one when warned after midnight).
     */
    public static long warFeastDawn(long dayTime) {
        long timeOfDay = Math.floorMod(dayTime, 24_000L);
        return nextDawn(dayTime) + (timeOfDay >= 6_000L ? 24_000L : 0L);
    }

    /**
     * A day-time deadline is live while the clock has not reached it. A
     * deadline more than two days ahead means the clock was set backwards
     * (/time set): treat it as expired rather than lasting for days.
     */
    public static boolean activeUntilDawn(long dawn, long dayTime) {
        return dawn > dayTime && dawn - dayTime <= 48_000L;
    }

    static int count(ItemStackHandler inventory, Predicate<ItemStack> match) {
        long total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && match.test(stack)) {
                total += stack.getCount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Extracts exactly {@code amount} matching items; call only after {@link #count}. */
    static void take(ItemStackHandler inventory, Predicate<ItemStack> match, int amount) {
        int left = amount;
        for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && match.test(stack)) {
                left -= inventory.extractItem(slot, Math.min(left, stack.getCount()), false).getCount();
            }
        }
    }
}
