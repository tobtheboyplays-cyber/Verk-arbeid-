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

    // Job standard, point 6: one distinct sound per work motion.
    public static final DeferredHolder<SoundEvent, SoundEvent> LEAP_SLAM =
        register("leap_slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> ARMOUR_CLINK =
        register("armour_clink");
    public static final DeferredHolder<SoundEvent, SoundEvent> PICK_STRIKE =
        register("pick_strike");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANVIL_RING =
        register("anvil_ring");
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLOWS_PUFF =
        register("bellows_puff");
    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_STROKE =
        register("saw_stroke");
    public static final DeferredHolder<SoundEvent, SoundEvent> OVEN_SLIDE =
        register("oven_slide");
    public static final DeferredHolder<SoundEvent, SoundEvent> KNEAD_PRESS =
        register("knead_press");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLEAVER_CHOP =
        register("cleaver_chop");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOOM_CLACK =
        register("loom_clack");
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
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_PANIC =
        register("settler_panic");
    public static final DeferredHolder<SoundEvent, SoundEvent> SHIELD_THUD =
        register("shield_thud");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHEER =
        register("cheer");

    // SLICE A2a -- the carry grammar.
    public static final DeferredHolder<SoundEvent, SoundEvent> HAUL_STEP =
        register("haul_step");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_GRIP =
        register("crate_grip");
    public static final DeferredHolder<SoundEvent, SoundEvent> HAUL_STRAIN =
        register("haul_strain");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_CREAK =
        register("crate_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_DOWN =
        register("crate_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> ITEM_PICKUP =
        register("item_pickup");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHEST_STOW =
        register("chest_stow");

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
