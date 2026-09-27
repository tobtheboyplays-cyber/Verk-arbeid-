package com.hearthstead.entity;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Summons;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Arrival edges and sparse hospitality gestures; owns no inventory or navigation. */
public final class InnkeeperAtmosphere {
    public static final int NONE = 0, WELCOME = 1, HUM = 2;
    public static final int WELCOME_TICKS = 36, HUM_TICKS = 96;
    private final Set<UUID> inside = new HashSet<>();
    private final Map<UUID, Long> greetedUntil = new HashMap<>();
    private UUID buildingId, pendingGuest, greetingGuest;
    private long pendingUntil, nextScan, nextHum;
    private boolean observed;

    /** True reserves empty hands for the short greeting, before new service starts. */
    public boolean tick(SettlerEntity host, Building tavern, boolean handsBusy) {
        if (!(host.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        var settlement = host.settlement();
        if (!host.isAlive() || host.getProfession() != Profession.INNKEEPER
            || settlement == null || tavern == null || !tavern.valid
            || !tavern.contains(host.blockPosition())
            || Employment.employerOf(settlement, host.getUUID()) != tavern
            || host.getTarget() != null || host.hurtTime > 0 || host.isOnFire()
            || host.isSleeping() || Summons.active(host)
            || settlement.pendingRaid != null || settlement.alertUntilGameTime > now) {
            stop(host);
            return false;
        }
        if (!tavern.id.equals(buildingId)) {
            inside.clear(); observed = false; buildingId = tavern.id;
            pendingGuest = null;
            nextHum = now + 600 + host.getRandom().nextInt(400);
        }
        if (now >= nextScan) {
            nextScan = now + 10;
            Set<UUID> current = new HashSet<>();
            greetedUntil.entrySet().removeIf(e -> e.getValue() <= now);
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive() || player.isSpectator() || !tavern.contains(player.blockPosition())) continue;
                UUID id = player.getUUID();
                if (current.size() < 64) current.add(id);
                if (observed && !inside.contains(id) && pendingGuest == null
                    && !greetedUntil.containsKey(id)) {
                    // Remember the real entry even while a doorway occludes
                    // contact. Visibility/range gate the gesture, not arrival.
                    pendingGuest = id;
                    pendingUntil = now + 100;
                }
            }
            inside.clear(); inside.addAll(current); observed = true;
            boolean seatedAudience = !level.getEntitiesOfClass(SettlerEntity.class,
                new AABB(host.blockPosition()).inflate(12), guest -> guest != host && guest.isAlive()
                    && com.hearthstead.settlement.TavernSeating.visitSettlement(guest) == settlement
                    && tavern.contains(guest.blockPosition()) && guest.hasTavernSeat()).isEmpty();
            // Music is independent of empty-handed gestures: service must not
            // cut every phrase short. The short lease expires if host ticks stop.
            host.setTavernMusicUntil(!inside.isEmpty() || seatedAudience ? now + 30 : 0);
        }
        if (pendingGuest != null) {
            var pending = level.getPlayerByUUID(pendingGuest);
            if (now > pendingUntil || pending == null || !pending.isAlive()
                || pending.isSpectator() || !tavern.contains(pending.blockPosition())) {
                pendingGuest = null;
            }
        }
        int mode = host.innkeeperSocialMode();
        if (mode != NONE && now - host.innkeeperSocialStart() >= duration(mode)) {
            host.setInnkeeperSocial(NONE, now);
            greetingGuest = null;
            mode = NONE;
        }
        boolean occupiedHands = handsBusy || !host.getMainHandItem().isEmpty()
            || !host.getOffhandItem().isEmpty() || host.hasMeal();
        if (occupiedHands) {
            if (mode != NONE) host.setInnkeeperSocial(NONE, now);
            greetingGuest = null;
            return false;
        }
        if (pendingGuest != null && mode != WELCOME) {
            var player = level.getPlayerByUUID(pendingGuest);
            if (player != null && tavern.contains(player.blockPosition())
                && host.distanceToSqr(player) <= 64 && host.hasLineOfSight(player)) {
                greetingGuest = pendingGuest;
                if (greetedUntil.size() < 64) greetedUntil.put(pendingGuest, now + 1200);
                host.setInnkeeperSocial(WELCOME, now);
                nextHum = Math.max(nextHum, now + 600);
                mode = WELCOME;
                pendingGuest = null;
            }
            // Still inside but not yet visible/near: keep the same bounded
            // pending deadline. Do not fabricate another entry to retry.
        }
        if (mode == WELCOME) {
            var player = greetingGuest == null ? null : level.getPlayerByUUID(greetingGuest);
            if (player == null || !player.isAlive() || !tavern.contains(player.blockPosition())
                || !host.hasLineOfSight(player)) {
                host.setInnkeeperSocial(NONE, now);
                greetingGuest = null;
                return false;
            }
            host.getLookControl().setLookAt(player, 25F, 25F);
            float yaw = (float) (Mth.atan2(player.getZ() - host.getZ(), player.getX() - host.getX())
                * 180.0 / Math.PI) - 90F;
            host.setYBodyRot(Mth.rotLerp(.2F, host.yBodyRot, yaw));
            return true;
        }
        if (mode == NONE && now >= nextHum) {
            nextHum = now + 900 + host.getRandom().nextInt(700);
            boolean audience = !inside.isEmpty() || !level.getEntitiesOfClass(SettlerEntity.class,
                new AABB(host.blockPosition()).inflate(8), guest -> guest != host && guest.isAlive()
                    && settlement.id.equals(guest.getSettlementId())
                    && tavern.contains(guest.blockPosition()) && guest.hasTavernSeat()).isEmpty();
            if (audience) host.setInnkeeperSocial(HUM, now);
        }
        return false;
    }

    public void stop(SettlerEntity host) {
        host.setTavernMusicUntil(0);
        if (!host.level().isClientSide) host.setInnkeeperSocial(NONE, host.level().getGameTime());
        pendingGuest = null; greetingGuest = null;
        // Preserve presence across short interruptions: resuming isn't an arrival.
    }

    public static int duration(int mode) {
        return mode == WELCOME ? WELCOME_TICKS : mode == HUM ? HUM_TICKS : 0;
    }
}
