package com.hearthstead.entity.work;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkerLifecycleTest {
    @Test
    void interruptAndResumePreserveStableTaskWithoutOwningCargo() {
        WorkerLifecycle lifecycle = new WorkerLifecycle();
        WorkerLifecycle.TaskRef task = new WorkerLifecycle.TaskRef(UUID.randomUUID(), UUID.randomUUID());
        lifecycle.recovering(task);
        lifecycle.activate();
        lifecycle.interrupt();
        assertEquals(WorkerLifecycle.State.INTERRUPTED, lifecycle.state());
        assertEquals(task, lifecycle.task());
        lifecycle.recovering(task);
        lifecycle.activate();
        assertEquals(WorkerLifecycle.State.ACTIVE, lifecycle.state());
        assertEquals(task, lifecycle.task());
        lifecycle.clear();
        assertNull(lifecycle.task());
        assertEquals(WorkerLifecycle.State.IDLE, lifecycle.state());
    }

    @Test
    void missingAuthorityCannotBeActivatedOrInventAReference() {
        WorkerLifecycle lifecycle = new WorkerLifecycle();
        lifecycle.activate();
        lifecycle.interrupt();
        assertEquals(WorkerLifecycle.State.IDLE, lifecycle.state());
        assertNull(lifecycle.task());
        assertThrows(NullPointerException.class, () -> lifecycle.recovering(null));
        assertThrows(NullPointerException.class, () -> new WorkerLifecycle.TaskRef(null, UUID.randomUUID()));
    }
}
