package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.mojang.serialization.Codec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Small, persistent facts owned by a player rather than a settlement. */
public final class ModAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
            Hearthstead.MODID);

    /**
     * Set only after the physical starter handbook is in the inventory.
     * copyOnDeath keeps respawn from reopening the one-time grant.
     */
    public static final Supplier<AttachmentType<Boolean>>
        STARTER_HANDBOOK_DELIVERED = ATTACHMENTS.register(
            "starter_handbook_delivered",
            () -> AttachmentType.builder(() -> false)
                .serialize(Codec.BOOL)
                .copyOnDeath()
                .build());

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    private ModAttachments() {
    }
}
