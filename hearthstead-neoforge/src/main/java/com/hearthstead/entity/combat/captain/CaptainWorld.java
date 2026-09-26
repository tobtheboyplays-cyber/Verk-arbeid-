package com.hearthstead.entity.combat.captain;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidBroadcast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side world of the hero Captain: hero stats on/off, the one-time
 * Captain's Commission field promotion (the "moment"), Mark Target and the
 * Armour Breaker's shred, and the state sync to clients. Runtime marks and
 * shreds simply end on reload, never linger.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CaptainWorld {
    static final ResourceLocation SHRED_ID = Hearthstead.id("captain_shred");
    public static final int SERGEANT_STRENGTH = GuardRank.SERGEANT.threshold();

    private record Mark(UUID settlementId, long until) {
    }

    private record Shred(ResourceKey<Level> dim, long until) {
    }

    private static final class State {
        final Map<UUID, Mark> marks = new HashMap<>();
        final Map<UUID, Shred> shreds = new HashMap<>();
        final Map<Integer, Boolean> heroSeen = new HashMap<>();
        long promotions;
    }

    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    private CaptainWorld() {
    }

    private static State state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, s -> new State());
    }

    // ------------------------------------------------------------- state

    public static CaptainState stateOf(SettlerEntity captain) {
        return CaptainState.load(captain.getPersistentData().getCompound(CaptainState.KEY));
    }

    public static void save(SettlerEntity captain, CaptainState s) {
        captain.getPersistentData().put(CaptainState.KEY, s.save());
    }

    // ------------------------------------------------------ hero on/off

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof SettlerEntity settler)
            || !(settler.level() instanceof ServerLevel level) || settler.tickCount % 20 != 11) {
            return;
        }
        boolean hero = CaptainKit.sync(settler);
        State st = state(level.getServer());
        Boolean before = st.heroSeen.put(settler.getId(), hero);
        if (before == null ? hero : before != hero) {
            if (hero) {
                // The kit follows what he actually holds.
                CaptainState cs = stateOf(settler);
                CaptainLoadout held = CaptainKit.heldLoadout(settler);
                if (held != null && held != cs.loadout()) {
                    cs.switchTo(held, level.getGameTime());
                    save(settler, cs);
                }
            }
            CaptainNetwork.broadcastState(settler);
        }
    }

    // --------------------------------------------------- field promotion

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        State st = STATES.get(server);
        if (st != null) {
            st.marks.values().removeIf(m -> m.until() <= now);
            Iterator<Map.Entry<UUID, Shred>> it = st.shreds.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Shred> e = it.next();
                if (e.getValue().until() <= now) {
                    ServerLevel level = server.getLevel(e.getValue().dim());
                    if (level != null && level.getEntity(e.getKey()) instanceof LivingEntity living) {
                        AttributeInstance armor = living.getAttribute(Attributes.ARMOR);
                        if (armor != null) {
                            armor.removeModifier(SHRED_ID);
                        }
                    }
                    it.remove();
                }
            }
        }
        if (now % 40 != 3 || !CaptainConfig.enabled()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            SettlementSavedData data = SettlementSavedData.existing(level);
            if (data == null) {
                continue;
            }
            for (Settlement settlement : new ArrayList<>(data.settlements.values())) {
                tryPromote(level, settlement);
            }
        }
    }

    /**
     * Captain's Commission, once per settlement: the settlement's top guard
     * is raised to at least Sergeant and commissioned. With no guard yet, the
     * first guard hired later takes it. Returns the promoted captain or null.
     */
    @Nullable
    public static SettlerEntity tryPromote(ServerLevel level, Settlement settlement) {
        if (!CaptainConfig.enabled() || !CaptainStatus.commissioned(level, settlement)) {
            return null;
        }
        CaptainSavedData saved = CaptainSavedData.get(level);
        if (saved.promoted(settlement.id)) {
            return null;
        }
        SettlerEntity top = GuardRank.captainOf(SettlementManager.loadedMembers(level, settlement));
        if (top == null) {
            return null;   // waits for the first guard
        }
        top.attributes().raiseTo(Attribute.STRENGTH, SERGEANT_STRENGTH);
        saved.markPromoted(settlement.id);
        CaptainStatus.invalidate(settlement.id);
        CaptainState cs = stateOf(top);
        cs.setPromptPending(true);
        save(top, cs);
        state(level.getServer()).promotions++;
        CaptainFx.promoted(level, top);
        RaidBroadcast.send(level, settlement, Component.translatable("hearthstead.captain.commissioned",
            top.getSettlerName(), settlement.name));
        RaidBroadcast.send(level, settlement, Component.translatable("hearthstead.captain.prompt"));
        CaptainKit.sync(top);
        CaptainNetwork.broadcastState(top);
        return top;
    }

    public static long promotions(MinecraftServer server) {
        return state(server).promotions;
    }

    // ------------------------------------------------------ mark & shred

    public static void mark(ServerLevel level, LivingEntity target, UUID settlementId, int ticks) {
        state(level.getServer()).marks.put(target.getUUID(),
            new Mark(settlementId, level.getGameTime() + ticks));
    }

    public static boolean marked(ServerLevel level, LivingEntity target) {
        Mark m = state(level.getServer()).marks.get(target.getUUID());
        return m != null && m.until() > level.getGameTime();
    }

    public static void shred(ServerLevel level, LivingEntity target, double amount, int ticks) {
        AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
        if (armor == null) {
            return;
        }
        armor.removeModifier(SHRED_ID);
        armor.addTransientModifier(new AttributeModifier(SHRED_ID, amount, AttributeModifier.Operation.ADD_VALUE));
        state(level.getServer()).shreds.put(target.getUUID(),
            new Shred(level.dimension(), level.getGameTime() + ticks));
    }

    /** Marked enemies take +25% from the Captain's settlement's soldiers and players. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        State st = STATES.get(level.getServer());
        if (st == null) {
            return;
        }
        Mark m = st.marks.get(victim.getUUID());
        if (m == null || m.until() <= level.getGameTime()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        boolean ours = attacker instanceof Player
            || attacker instanceof SettlerEntity s && m.settlementId().equals(s.getSettlementId());
        if (ours) {
            event.setAmount(event.getAmount() * (1.0F + CaptainSpecial.MARK_DAMAGE_BONUS));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof SettlerEntity s && s.getSettlementId() != null) {
            CaptainStatus.invalidate(s.getSettlementId());
        }
    }

    public static void resetForTests(MinecraftServer server) {
        STATES.remove(server);
    }
}
