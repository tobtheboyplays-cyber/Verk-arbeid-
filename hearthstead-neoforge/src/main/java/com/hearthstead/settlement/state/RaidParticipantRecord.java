package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.UUID;

/**
 * Persisted identity for one sealed raid actor.
 *
 * <p>The participant UUID ledger remains completion authority. This bounded
 * companion record adds only the facts presentation needs after a restart:
 * which exact entity is the captain and which physical build a follower uses.
 * Neither fact is reconstructed from nearby loaded entities.</p>
 */
public record RaidParticipantRecord(UUID entityId, Build build, boolean captain) {
    public enum Build {
        SKIRMISHER(0, "skirmisher"),
        BRUTE(1, "brute"),
        /** Early-raid outlaw (26 Sep escalation curve). Appended: wire ids are saved. */
        BANDIT(2, "bandit");

        private final int wireId;
        private final String id;

        Build(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        private static Optional<Build> decode(int wireId, String id) {
            for (Build build : values()) {
                if (build.wireId == wireId && build.id.equals(id)) {
                    return Optional.of(build);
                }
            }
            return Optional.empty();
        }
    }

    public RaidParticipantRecord {
        if (entityId == null || build == null) {
            throw new IllegalArgumentException("raid participant identity is required");
        }
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", entityId);
        tag.putInt("BuildWireId", build.wireId());
        tag.putString("Build", build.id());
        tag.putBoolean("Captain", captain);
        return tag;
    }

    public static Optional<RaidParticipantRecord> tryReadNbt(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
            || !tag.hasUUID("Id")
            || !tag.contains("BuildWireId", Tag.TAG_INT)
            || !tag.contains("Build", Tag.TAG_STRING)
            || !tag.contains("Captain", Tag.TAG_BYTE)) {
            return Optional.empty();
        }
        Optional<Build> build = Build.decode(tag.getInt("BuildWireId"),
            tag.getString("Build"));
        return build.map(value -> new RaidParticipantRecord(tag.getUUID("Id"),
            value, tag.getBoolean("Captain")));
    }
}
