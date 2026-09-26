package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Execution sound events (files and sounds.json entries by the sound lane,
 * 2026-09-26). Registered here rather than in ModSounds so the finisher lane
 * owns its ids end to end.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FinisherSounds {
    public static final SoundEvent STINGER = create("combat.execution_stinger");
    public static final SoundEvent WINDUP = create("combat.execution_windup");
    public static final SoundEvent DOUBLE = create("combat.execution_double");
    public static final SoundEvent BODY_FALL = create("combat.execution_body_fall");
    public static final SoundEvent GUARD_CHEER = create("combat.guard_cheer");

    private static final SoundEvent[] ALL = {STINGER, WINDUP, DOUBLE, BODY_FALL, GUARD_CHEER};

    private FinisherSounds() {
    }

    private static SoundEvent create(String path) {
        return SoundEvent.createVariableRangeEvent(Hearthstead.id(path));
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.SOUND_EVENT, helper -> {
            for (SoundEvent sound : ALL) {
                ResourceLocation id = sound.getLocation();
                helper.register(id, sound);
            }
        });
    }
}
