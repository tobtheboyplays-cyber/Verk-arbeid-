package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Hearthstead.MODID);

    public static final RegistryObject<SoundEvent> SETTLEMENT_FOUNDED = register("settlement_founded");
    public static final RegistryObject<SoundEvent> PROFESSION_ASSIGNED = register("profession_assigned");
    public static final RegistryObject<SoundEvent> SETTLER_RECRUITED = register("settler_recruited");
    public static final RegistryObject<SoundEvent> FARMER_WORK = register("farmer_work");
    public static final RegistryObject<SoundEvent> CHOP = register("chop");
    public static final RegistryObject<SoundEvent> GUARD_ALERT = register("guard_alert");
    public static final RegistryObject<SoundEvent> SETTLER_HM = register("settler_hm");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
            () -> SoundEvent.createVariableRangeEvent(Hearthstead.id(name)));
    }

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }

    private ModSounds() {
    }
}
