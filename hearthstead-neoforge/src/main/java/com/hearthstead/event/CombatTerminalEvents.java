package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardCombatLedger;
import com.hearthstead.entity.OwnedProjectileLedger;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Immutable final-damage observers for genuine shield and archer contacts. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CombatTerminalEvents {
    private static final int MAX_SHIELD_TERMINALS_PER_SERVER_TICK = 64;

    /**
     * Replay identity is issued only after NeoForge has produced the immutable
     * final-damage event. Incoming damage is deliberately not retained: vanilla
     * may reject it during its invulnerability window without ever posting a
     * {@link LivingDamageEvent.Post}, and a later same-priority listener may
     * still cancel an incoming event. Neither non-terminal is allowed to spend
     * this budget or keep a level/server alive.
     *
     * <p>The outer server keys and inner event identities are both weak. Bucket
     * values contain only a tick, UUIDs, and weak event references, never a
     * strong ServerLevel, entity, event, or MinecraftServer. Explicit
     * server-stop cleanup remains a defence in depth rather than the only thing
     * preventing a retained integrated world.</p>
     */
    private static final WeakTickEventLedger<MinecraftServer,
        LivingDamageEvent.Post> SHIELD_TERMINALS = new WeakTickEventLedger<>();

    /**
     * Hearthstead Archer arrows are settlement weapons, never friendly-fire
     * hazards. Exact live projectile ownership is enough to cancel an
     * unauthorized victim even when its optional contact ledger is malformed;
     * malformed evidence loses counters/training, but can never hurt a player,
     * settler, animal, or another settlement's raid. The victim-specific
     * counter then requires the strict persisted ledger as well.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event == null || !Float.isFinite(event.getAmount())
            || event.getAmount() <= 0.0F
            || !(event.getSource().getDirectEntity()
                instanceof AbstractArrow arrow)) {
            return;
        }

        boolean claimed = OwnedProjectileLedger.claimsOwnership(arrow);
        OwnedProjectileLedger.Inspection inspection = claimed
            ? OwnedProjectileLedger.inspect(arrow) : null;
        SettlerEntity archer = event.getSource().getEntity()
                instanceof SettlerEntity sourceSettler
            ? sourceSettler : null;

        // The ledger exists specifically so a physical arrow remains
        // recognizable after chunk unload/restart.  If that claim is
        // malformed, copied, or its exact Archer cannot be resolved, the
        // safe result is no damage.  Falling through to vanilla here would
        // turn a settlement weapon into uncontrolled friendly fire merely
        // because the owner chunk unloaded first.
        if (claimed && (inspection == null || archer == null
            || archer.getProfession() != Profession.ARCHER
            || !inspection.ownerId().equals(archer.getUUID())
            || arrow.getOwner() != archer)) {
            event.setCanceled(true);
            return;
        }
        if (archer == null || archer.getProfession() != Profession.ARCHER
            || arrow.getOwner() != archer) {
            return;
        }

        LivingEntity victim = event.getEntity();
        boolean authorized = victim instanceof Enemy
            && (!(victim instanceof RaiderEntity raider)
                || raider.settlementId() == null
                || raider.settlementId().equals(archer.getSettlementId()));
        if (!authorized) {
            event.setCanceled(true);
            return;
        }

        if (!(victim instanceof RaiderEntity raider)
            || raider.isCaptain()
            || inspection == null
            || raider.settlementId() == null
            || !raider.settlementId().equals(archer.getSettlementId())) {
            return;
        }
        double multiplier = ArcherAttackGoal.counterDamageMultiplier(raider);
        if (multiplier > 1.0D) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
    }

    /**
     * Post contains final health loss and final blocked damage after all
     * reductions. It is therefore strictly stronger than wind-up, impact,
     * hurt animation, projectile collision, or LivingShieldBlockEvent alone.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        tryCommitShield(event);
        tryCommitArcher(event);
    }

    /** @return true only for the first persisted genuine block terminal. */
    private static boolean tryCommitShield(LivingDamageEvent.Post event) {
        if (event == null || !Float.isFinite(event.getOriginalDamage())
            || event.getOriginalDamage() <= 0.0F
            || !(event.getEntity() instanceof SettlerEntity guard)
            || guard.getProfession() != Profession.GUARD
            || !(guard.level() instanceof ServerLevel level)
            || !guard.isUsingItem()
            || guard.getUsedItemHand() != InteractionHand.OFF_HAND
            || !guard.getUseItem().is(Items.SHIELD)
            || !guard.hasPhysicalOffhandShield()
            || !guard.isBlocking()
            || !(event.getSource().getEntity() instanceof LivingEntity attacker)
            || attacker.level() != level || attacker == guard
            || !Float.isFinite(event.getBlockedDamage())
            || event.getBlockedDamage() <= 0.0F) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return false;
        }
        long tick = level.getGameTime();
        UUID actionId = SHIELD_TERMINALS.actionFor(server, event, tick,
            MAX_SHIELD_TERMINALS_PER_SERVER_TICK);
        if (actionId == null) {
            return false;
        }
        GuardCombatLedger.ShieldCommit commit = guard.commitShieldBlock(
            actionId, tick);
        if (commit == null) {
            return false;
        }
        // A non-player LivingEntity has no vanilla shield-durability hook.
        // Presentation and physical wear therefore belong to this exact-once
        // terminal, after the persisted receipt and never to SettlerEntity#hurt.
        guard.presentCommittedShieldBlock();
        guard.consumeCommittedShieldDurability();
        Settlement settlement = guard.settlement();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.SHIELD_BLOCK_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(
                settlement == null ? null : settlement.id,
                "guard:" + guard.getUUID() + "/attacker:" + attacker.getUUID(),
                commit.revisionBefore(), commit.revisionAfter(),
                commit.countBefore(), commit.countAfter(),
                "block:" + commit.actionId()));
        return true;
    }

    /** @return true only for the first successful owned arrow/victim contact. */
    private static boolean tryCommitArcher(LivingDamageEvent.Post event) {
        if (event == null || !Float.isFinite(event.getNewDamage())
            || event.getNewDamage() <= 0.0F
            || !(event.getEntity() instanceof LivingEntity victim)
            || !(victim.level() instanceof ServerLevel level)
            || !(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)
            || !(event.getSource().getEntity() instanceof SettlerEntity archer)
            || archer.getProfession() != Profession.ARCHER
            || archer.level() != level || arrow.level() != level
            || victim == archer) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return false;
        }
        OwnedProjectileLedger.ContactCommit commit =
            OwnedProjectileLedger.commit(arrow, archer, victim);
        if (commit == null) {
            return false;
        }
        // The registered vanilla Arrow type survives entity reload; an
        // anonymous Arrow#doPostHurtEffects override does not. Training from
        // the committed persistent contact keeps live and restored hits equal
        // and makes replayed victim contacts teach nothing.
        if (archer.isAlive()) {
            archer.train(Attribute.DEXTERITY, ArcherRank.TRAIN_HIT);
        }
        Settlement settlement = archer.settlement();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.ARCHER_CONTACT_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(
                settlement == null ? null : settlement.id,
                "archer:" + archer.getUUID() + "/victim:" + victim.getUUID(),
                commit.revisionBefore(), commit.revisionAfter(),
                commit.countBefore(), commit.countAfter(),
                "projectile:" + commit.projectileId()));
        return true;
    }

    /** Releases the bounded replay bucket when an integrated/dedicated server stops. */
    public static void clear(MinecraftServer server) {
        SHIELD_TERMINALS.clear(server);
    }

    private CombatTerminalEvents() {
    }
}

/**
 * Small identity ledger shared with plain JVM tests. It intentionally stores
 * events through {@link WeakReference}: an event's level/server graph can never
 * become the strong value behind this map's weak server key.
 */
final class WeakTickEventLedger<S, E> {
    private final Map<S, Bucket<E>> buckets = new WeakHashMap<>();

    UUID actionFor(S server, E event, long tick, int cap) {
        if (server == null || event == null || tick < 0L || cap <= 0) {
            return null;
        }
        Bucket<E> bucket = buckets.get(server);
        if (bucket == null || bucket.tick != tick) {
            bucket = new Bucket<>(tick);
            buckets.put(server, bucket);
        }
        for (Iterator<Entry<E>> iterator = bucket.entries.iterator();
             iterator.hasNext();) {
            Entry<E> entry = iterator.next();
            E existing = entry.event.get();
            if (existing == null) {
                iterator.remove();
            } else if (existing == event) {
                return entry.actionId;
            }
        }
        if (bucket.entries.size() >= cap) {
            return null;
        }
        UUID actionId = UUID.randomUUID();
        bucket.entries.add(new Entry<>(event, actionId));
        return actionId;
    }

    void clear(S server) {
        if (server != null) {
            buckets.remove(server);
        }
    }

    int serverCount() {
        return buckets.size();
    }

    int eventCount(S server) {
        Bucket<E> bucket = buckets.get(server);
        return bucket == null ? 0 : bucket.entries.size();
    }

    private static final class Bucket<E> {
        private final long tick;
        private final List<Entry<E>> entries = new ArrayList<>();

        private Bucket(long tick) {
            this.tick = tick;
        }
    }

    private static final class Entry<E> {
        private final WeakReference<E> event;
        private final UUID actionId;

        private Entry(E event, UUID actionId) {
            this.event = new WeakReference<>(event);
            this.actionId = actionId;
        }
    }
}
