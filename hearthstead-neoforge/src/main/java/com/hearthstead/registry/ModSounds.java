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

    // SLICE ANIM-1 additions.
    public static final DeferredHolder<SoundEvent, SoundEvent> SEED_PRESS =
        register("seed_press");
    public static final DeferredHolder<SoundEvent, SoundEvent> CROP_PULL =
        register("crop_pull");
    public static final DeferredHolder<SoundEvent, SoundEvent> BAG_STOW =
        register("bag_stow");
    public static final DeferredHolder<SoundEvent, SoundEvent> WATER_POUR =
        register("water_pour");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLADE_HIT =
        register("blade_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> YAWN =
        register("yawn");
    public static final DeferredHolder<SoundEvent, SoundEvent> LADDER_CREAK =
        register("ladder_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_EAT =
        register("settler_eat");

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
