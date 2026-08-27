package com.hearthstead.settlement.state;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/**
 * One settlement-wide guard command, independent of panic/alert state.
 * Valid commands use last-valid-order-wins semantics; malformed attempts are
 * rejected without erasing the last usable command.
 */
public final class GuardOrder {
    public enum Mode {
        NONE(0, "none"),
        RALLY_HERE(1, "rally_here"),
        DEFEND_HEARTH(2, "defend_hearth");

        private final int wireId;
        private final String id;

        Mode(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        public static Optional<Mode> tryFromWireId(int wireId) {
            for (Mode mode : values()) {
                if (mode.wireId == wireId) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }

        public static Optional<Mode> tryFromId(String id) {
            if (id == null) {
                return Optional.empty();
            }
            for (Mode mode : values()) {
                if (mode.id.equals(id)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    private Mode mode = Mode.NONE;
    private BlockPos pos;
    private long untilGameTime;
    private int revision;

    public Mode mode() {
        return mode;
    }

    public Optional<BlockPos> pos() {
        return Optional.ofNullable(pos);
    }

    public long untilGameTime() {
        return untilGameTime;
    }

    public int revision() {
        return revision;
    }

    public boolean activeAt(long gameTime) {
        return mode != Mode.NONE && pos != null && gameTime < untilGameTime;
    }

    public Mode modeAt(long gameTime) {
        return activeAt(gameTime) ? mode : Mode.NONE;
    }

    /**
     * Replaces the previous command only when the whole new command is valid.
     * {@link Mode#NONE} is an explicit clear and ignores position/timeout.
     */
    public boolean issue(Mode newMode, BlockPos newPos, long newUntilGameTime) {
        if (newMode == null || revision == Integer.MAX_VALUE) {
            return false;
        }
        if (newMode == Mode.NONE) {
            if (mode == Mode.NONE) {
                return false; // idempotent clear: no state change, no revision
            }
            clearInternal();
            return true;
        }
        if (newPos == null || newUntilGameTime <= 0L) {
            return false;
        }
        mode = newMode;
        pos = newPos.immutable();
        untilGameTime = newUntilGameTime;
        revision++;
        return true;
    }

    public boolean clear() {
        return issue(Mode.NONE, null, 0L);
    }

    /** Clears an expired command once and reports whether state changed. */
    public boolean expireIfNeeded(long gameTime) {
        if (mode == Mode.NONE || gameTime < untilGameTime) {
            return false;
        }
        if (revision == Integer.MAX_VALUE) {
            // The command is still observationally expired through activeAt;
            // refusing a wrapping revision is safer than making it look new.
            return false;
        }
        clearInternal();
        return true;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ModeWireId", mode.wireId());
        tag.putString("Mode", mode.id());
        if (mode != Mode.NONE && pos != null) {
            tag.put("Pos", NbtUtils.writeBlockPos(pos));
            tag.putLong("Until", untilGameTime);
        }
        tag.putInt("Revision", revision);
        return tag;
    }

    public static GuardOrder readNbt(CompoundTag tag) {
        GuardOrder order = new GuardOrder();
        order.revision = Math.max(0, tag.getInt("Revision"));
        Optional<Mode> decoded = decodeMode(tag);
        if (decoded.isEmpty() || decoded.get() == Mode.NONE) {
            return order;
        }
        BlockPos decodedPos = tag.contains("Pos", Tag.TAG_INT_ARRAY)
            ? NbtUtils.readBlockPos(tag, "Pos").orElse(null) : null;
        long decodedUntil = tag.getLong("Until");
        if (decodedPos == null || decodedUntil <= 0L) {
            return order;
        }
        order.mode = decoded.get();
        order.pos = decodedPos.immutable();
        order.untilGameTime = decodedUntil;
        return order;
    }

    private void clearInternal() {
        mode = Mode.NONE;
        pos = null;
        untilGameTime = 0L;
        revision++;
    }

    private static Optional<Mode> decodeMode(CompoundTag tag) {
        if (tag.contains("ModeWireId", Tag.TAG_INT)) {
            Optional<Mode> wire = Mode.tryFromWireId(tag.getInt("ModeWireId"));
            if (wire.isEmpty()) {
                return Optional.empty();
            }
            if (tag.contains("Mode", Tag.TAG_STRING)
                && !wire.get().id().equals(tag.getString("Mode"))) {
                return Optional.empty();
            }
            return wire;
        }
        return Mode.tryFromId(tag.getString("Mode"));
    }
}
