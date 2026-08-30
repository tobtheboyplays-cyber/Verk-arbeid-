package com.hearthstead.event;

import net.neoforged.api.distmarker.Dist;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModBusEventsDistributionTest {
    @Test
    void dedicatedServerNeverEvaluatesClientHookSupplier() {
        AtomicBoolean supplierEvaluated = new AtomicBoolean();

        boolean ran = ModBusEvents.runClientOnly(Dist.DEDICATED_SERVER, () -> {
            supplierEvaluated.set(true);
            throw new AssertionError("client-only supplier resolved on server");
        });

        assertFalse(ran);
        assertFalse(supplierEvaluated.get(),
            "dedicated registration/dispatch must not resolve ClientHooks");
    }
}
