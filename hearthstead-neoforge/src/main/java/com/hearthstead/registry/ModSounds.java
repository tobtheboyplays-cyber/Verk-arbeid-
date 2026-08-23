package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(Registries.SOUND_EVENT, Hearthstead.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLEMENT_FOUNDED =
        register("settlement_founded");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROFESSION_ASSIGNED =
        register("profession_assigned");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_RECRUITED =
        register("settler_recruited");
    public static final DeferredHolder<SoundEvent, SoundEvent> FARMER_WORK =
        register("farmer_work");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHOP =
        register("chop");
    public static final DeferredHolder<SoundEvent, SoundEvent> GUARD_ALERT =
        register("guard_alert");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_HM =
        register("settler_hm");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
            () -> SoundEvent.createVariableRangeEvent(Hearthstead.id(name)));
    }

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }

    private ModSounds() {
    }
}
