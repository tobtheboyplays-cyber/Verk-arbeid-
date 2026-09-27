package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

/** Denies vanilla labels only for the exact entity in a synchronous portrait draw. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class PortraitLabels {
    private static final ThreadLocal<Object> SUBJECT = new ThreadLocal<>();

    public static void withoutLabels(Entity entity, Runnable draw) {
        duringPortrait(entity, draw);
    }

    static void duringPortrait(Object subject, Runnable draw) {
        Object previous = SUBJECT.get();
        SUBJECT.set(subject);
        try {
            draw.run();
        } finally {
            if (previous == null) SUBJECT.remove();
            else SUBJECT.set(previous);
        }
    }

    static boolean hidden(Object entity) {
        return entity != null && SUBJECT.get() == entity;
    }

    @SubscribeEvent
    public static void nameTag(RenderNameTagEvent event) {
        if (hidden(event.getEntity())) event.setCanRender(TriState.FALSE);
    }

    private PortraitLabels() { }
}
