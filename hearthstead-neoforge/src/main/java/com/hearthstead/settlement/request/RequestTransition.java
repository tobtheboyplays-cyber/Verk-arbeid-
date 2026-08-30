package com.hearthstead.settlement.request;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.UUID;

/** One immutable state edge retained in the bounded terminal audit trace. */
public record RequestTransition(int sequence, RequestState from,
                                RequestState to, long gameTime,
                                @Nullable UUID courierId,
                                RequestBlocker blocker,
                                int movedCount, int deliveredCount) {
    public RequestTransition {
        if (sequence <= 0 || from == null || to == null
            || from == to && from != RequestState.IN_TRANSIT
            || gameTime < 0L || blocker == null || movedCount < 0
            || deliveredCount < 0 || deliveredCount > movedCount) {
            throw new IllegalArgumentException("invalid request transition");
        }
        if ((to == RequestState.BLOCKED) != (blocker != RequestBlocker.NONE)) {
            throw new IllegalArgumentException("blocked transition needs one concrete blocker");
        }
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Sequence", sequence);
        tag.putInt("From", from.wireId());
        tag.putInt("To", to.wireId());
        tag.putLong("GameTime", gameTime);
        if (courierId != null) {
            tag.putUUID("Courier", courierId);
        }
        tag.putInt("Blocker", blocker.wireId());
        tag.putInt("Moved", movedCount);
        tag.putInt("Delivered", deliveredCount);
        return tag;
    }

    @Nullable
    public static RequestTransition readNbt(CompoundTag tag) {
        if (tag == null || !tag.contains("Sequence", Tag.TAG_INT)
            || !tag.contains("From", Tag.TAG_INT)
            || !tag.contains("To", Tag.TAG_INT)
            || !tag.contains("GameTime", Tag.TAG_LONG)
            || !tag.contains("Blocker", Tag.TAG_INT)
            || !tag.contains("Moved", Tag.TAG_INT)
            || !tag.contains("Delivered", Tag.TAG_INT)
            || tag.contains("Courier") && !tag.hasUUID("Courier")) {
            return null;
        }
        var from = RequestState.fromWireId(tag.getInt("From"));
        var to = RequestState.fromWireId(tag.getInt("To"));
        var blocker = RequestBlocker.fromWireId(tag.getInt("Blocker"));
        if (from.isEmpty() || to.isEmpty() || blocker.isEmpty()) {
            return null;
        }
        try {
            return new RequestTransition(tag.getInt("Sequence"), from.get(),
                to.get(), tag.getLong("GameTime"),
                tag.hasUUID("Courier") ? tag.getUUID("Courier") : null,
                blocker.get(), tag.getInt("Moved"), tag.getInt("Delivered"));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
