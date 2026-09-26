package com.hearthstead.entity.work;

import java.util.Objects;
import java.util.UUID;

/** Runtime observation only. The referenced owner retains all durable work and inventory authority. */
public final class WorkerLifecycle {
    public enum State { IDLE, ACTIVE, INTERRUPTED, RECOVERING }

    public record TaskRef(UUID settlementId, UUID requestId) {
        public TaskRef {
            Objects.requireNonNull(settlementId, "settlementId");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    private State state = State.IDLE;
    private TaskRef task;

    public State state() { return state; }
    public TaskRef task() { return task; }

    public void recovering(TaskRef reference) {
        task = Objects.requireNonNull(reference, "reference");
        state = State.RECOVERING;
    }

    public void activate() {
        if (task != null) state = State.ACTIVE;
    }

    public void interrupt() {
        if (task != null) state = State.INTERRUPTED;
    }

    public void clear() {
        task = null;
        state = State.IDLE;
    }
}
