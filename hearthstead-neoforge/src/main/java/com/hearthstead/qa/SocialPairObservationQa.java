package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.VillageSocial;
import com.hearthstead.util.QaTrace;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Read-only, trace-gated observation of one genuinely selected social pair.
 *
 * <p>The observer is deliberately bound to the owned playtest world and the
 * two existing scenario tags. It never writes entity data, AI, activity,
 * inventory, health, navigation, social cues, settlement state, or files.
 * It merely emits bounded server-log receipts from the public synchronized
 * getters already used by VillageMomentGameTests.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class SocialPairObservationQa {
    private static final String FIRST_TAG = "hsqa_social_one";
    private static final String SECOND_TAG = "hsqa_social_two";
    private static final long ROLE_TIMEOUT_TICKS = 160L;
    private static final Map<ServerLevel, Probe> PROBES = new WeakHashMap<>();

    private SocialPairObservationQa() {
    }

    /** Arms one console-owned, read-only observation after the actors are staged. */
    public static int arm(CommandSourceStack source) {
        try {
            ServerLevel level = source.getLevel();
            requireOwnedPlaytest(source, level);
            require(!PROBES.containsKey(level), "already_armed");
            if (!(source.getEntity() instanceof SettlerEntity first)) {
                throw new IllegalStateException("tagged_actor_one_required");
            }
            require(first.getTags().contains(FIRST_TAG) && !first.getTags().contains(SECOND_TAG),
                "tagged_actor_one_required");
            List<SettlerEntity> seconds = level.getEntitiesOfClass(SettlerEntity.class,
                first.getBoundingBox().inflate(8.0D), candidate -> candidate.getTags().contains(SECOND_TAG));
            require(seconds.size() == 1, "exact_tagged_actor_two_required");
            SettlerEntity second = seconds.getFirst();
            require(first.getSettlementId() != null
                && first.getSettlementId().equals(second.getSettlementId()), "same_settlement_pair_required");
            Probe probe = new Probe(first.getUUID(), second.getUUID(), second.getHealth(), level.getGameTime());
            PROBES.put(level, probe);
            Hearthstead.LOGGER.info("HSQA_SOCIAL_OBSERVER_ARMED first={} second={} secondHealth={}",
                probe.first, probe.second, probe.secondHealthAtArm);
            source.sendSuccess(() -> Component.literal("HSQA_SOCIAL_OBSERVER_ARMED"), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_SOCIAL_OBSERVER_REFUSED " + failure.getMessage()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void afterLevelTick(LevelTickEvent.Post event) {
        if (!QaTrace.ENABLED || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Probe probe = PROBES.get(level);
        if (probe != null) {
            sample(level, probe);
        }
    }

    private static void sample(ServerLevel level, Probe probe) {
        if (!(level.getEntity(probe.first) instanceof SettlerEntity first)
            || !(level.getEntity(probe.second) instanceof SettlerEntity second)) {
            terminal(level, probe, "HSQA_SOCIAL_OBSERVER_ABORT missing_actor");
            return;
        }
        long now = level.getGameTime();
        if (!probe.rolesObserved) {
            if (isChatListenPair(first, second)) {
                probe.rolesObserved = true;
                probe.sceneStartedAt = first.villageSocialStart();
                UUID speaker = first.villageSocialMode() == VillageSocial.CHAT
                    ? first.getUUID() : second.getUUID();
                UUID listener = speaker.equals(first.getUUID()) ? second.getUUID() : first.getUUID();
                Hearthstead.LOGGER.info("HSQA_SOCIAL_ROLE_PAIR speaker={} listener={} start={} elapsed={}",
                    speaker, listener, probe.sceneStartedAt, now - probe.sceneStartedAt);
            } else if (now - probe.armedAt > ROLE_TIMEOUT_TICKS) {
                terminal(level, probe, "HSQA_SOCIAL_OBSERVER_TIMEOUT phase=roles");
            }
            return;
        }

        long elapsed = now - probe.sceneStartedAt;
        if (isCancelledEarly(first, second, probe, elapsed)) {
            terminal(level, probe, "HSQA_SOCIAL_CANCELLED early=true elapsed=" + elapsed
                + " secondHealthBefore=" + probe.secondHealthAtArm
                + " secondHealthAfter=" + second.getHealth());
        } else if (elapsed >= VillageSocial.CHAT_TICKS) {
            terminal(level, probe, "HSQA_SOCIAL_OBSERVER_TIMEOUT phase=cancel_natural_expiry");
        }
    }

    static boolean isChatListenPair(SettlerEntity first, SettlerEntity second) {
        boolean firstChats = first.villageSocialMode() == VillageSocial.CHAT
            && second.villageSocialMode() == VillageSocial.LISTEN;
        boolean secondChats = second.villageSocialMode() == VillageSocial.CHAT
            && first.villageSocialMode() == VillageSocial.LISTEN;
        return (firstChats || secondChats)
            && first.getActivity() == SettlerActivity.SOCIALIZING
            && second.getActivity() == SettlerActivity.SOCIALIZING
            && first.villageSocialStart() == second.villageSocialStart();
    }

    static boolean isCancelledEarly(SettlerEntity first, SettlerEntity second, Probe probe,
                                   long elapsed) {
        return elapsed >= VillageSocial.WELCOME_TICKS && elapsed < VillageSocial.CHAT_TICKS
            && first.villageSocialMode() == VillageSocial.NONE
            && second.villageSocialMode() == VillageSocial.NONE
            && first.getActivity() != SettlerActivity.SOCIALIZING
            && second.getActivity() != SettlerActivity.SOCIALIZING
            && second.getHealth() < probe.secondHealthAtArm;
    }

    private static void requireOwnedPlaytest(CommandSourceStack source, ServerLevel level) throws Exception {
        var world = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(QaTrace.ENABLED && source.hasPermission(4) && level.getServer().isSameThread()
            && level.dimension() == Level.OVERWORLD
            && supportedOwnedPlaytestGenerator(level)
            && world.getFileName().toString().equals("world")
            && world.getParent().getFileName().toString().equals("playtest")
            && Files.readString(world.getParent().resolve(".hsqa-instance-owned"))
                .trim().equals("hsqa-instance-v1:playtest"), "owned_trace_enabled_playtest_required");
    }

    private static boolean supportedOwnedPlaytestGenerator(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        return generator instanceof FlatLevelSource
            || generator instanceof NoiseBasedChunkGenerator noise
                && noise.stable(NoiseGeneratorSettings.OVERWORLD);
    }
    private static void terminal(ServerLevel level, Probe probe, String receipt) {
        if (PROBES.get(level) != probe) {
            return;
        }
        PROBES.remove(level);
        Hearthstead.LOGGER.info(receipt);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException(reason);
        }
    }

    static final class Probe {
        final UUID first;
        final UUID second;
        final float secondHealthAtArm;
        final long armedAt;
        long sceneStartedAt = Long.MIN_VALUE;
        boolean rolesObserved;

        Probe(UUID first, UUID second, float secondHealthAtArm, long armedAt) {
            this.first = first;
            this.second = second;
            this.secondHealthAtArm = secondHealthAtArm;
            this.armedAt = armedAt;
        }
    }
}