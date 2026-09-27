package com.hearthstead.client.guildmaster;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.GuildmasterEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Renders the seated Guildmaster; his name shows only while he is looked at. */
public final class GuildmasterRenderer extends MobRenderer<GuildmasterEntity, GuildmasterModel> {
    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/entity/guildmaster.png");

    public GuildmasterRenderer(EntityRendererProvider.Context context) {
        super(context, new GuildmasterModel(context.bakeLayer(GuildmasterClientSetup.LAYER)), 0.45F);
    }

    @Override
    public ResourceLocation getTextureLocation(GuildmasterEntity entity) {
        return TEXTURE;
    }

    @Override
    protected boolean shouldShowName(GuildmasterEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        return mc.crosshairPickEntity == entity && !entity.isInvisible()
            && Minecraft.renderNames();
    }
}
