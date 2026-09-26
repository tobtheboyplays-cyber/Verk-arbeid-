package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.look.CharacterLooks;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WanderingTraderRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * Wandering traders (the peddler, the caravan master and the coin merchant
 * are all vanilla WanderingTraders) in Bannerhold costumes on the vanilla
 * trader rig. Role from the synced custom name, variant from the UUID.
 */
public final class LookTraderRenderer extends WanderingTraderRenderer {
    private static final String[] KINDS = {"peddler", "caravan_master", "merchant"};

    public LookTraderRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(WanderingTrader trader) {
        if (!CharacterLooks.enabled()) {
            return super.getTextureLocation(trader);
        }
        int kind = CharacterLooks.traderKind(trader);
        int variant = CharacterLooks.traderVariant(trader, kind);
        return Hearthstead.id("textures/entity/look/trader/" + KINDS[kind] + "_" + variant + ".png");
    }
}
