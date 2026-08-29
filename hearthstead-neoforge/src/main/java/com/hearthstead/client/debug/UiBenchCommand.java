package com.hearthstead.client.debug;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.List;

/**
 * QA-only client commands that put a screen on the glass without needing a
 * synthetic mouse click to land on the right block or entity.
 *
 * <h2>Why this is not "a test hook in production code"</h2>
 *
 * <p>Measuring a screen's cost before and after a change is only meaningful
 * if both measurements open the <em>same</em> screen the same way. Driving
 * that through {@code xdotool} right-clicks means the measurement depends on
 * where a wandering settler happened to be standing — which is exactly how
 * the first attempt at this measurement opened the hearth twice and the
 * settler sheet never (live, 2026-08-29). This removes the aiming problem
 * from the experiment.
 *
 * <p>It is registered only when {@code -Dhearthstead.uiprofile=true} is set,
 * the same QA-only switch {@link UiProfiler} reads, so no player ever has
 * these commands in their completion list. They are client commands: they
 * never reach the server, and they only ever call {@code setScreen} on data
 * the client already legitimately has.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class UiBenchCommand {

    private static final boolean ENABLED = Boolean.getBoolean("hearthstead.uiprofile");

    /**
     * QA-only A/B switch for the settler sheet's live entity preview.
     *
     * <p>The preview is the one thing on that sheet whose cost cannot be
     * reasoned about from the source -- it is a full entity render, and how
     * much it costs depends on the rasteriser. Toggling it at RUNTIME lets
     * both halves of the comparison come from one client boot, on one world,
     * with nothing else changed. Always true unless a QA session says
     * otherwise, and unreachable at all without the profiler flag.
     */
    public static boolean entityPreview = true;

    /**
     * QA-only bisect switch: draw the screen's background and nothing else.
     *
     * <p>The settler sheet measured 118ms a frame with the entity preview on
     * and 117ms with it off, which rules the preview out and says the cost is
     * somewhere in the other several hundred draw calls -- or is not this
     * screen's drawing at all. Skipping the panel entirely separates those two
     * answers in one measurement instead of one client boot per guess.
     */
    public static boolean panelBody = true;

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!ENABLED) {
            return;
        }
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hsui");
        root.then(Commands.literal("settler").executes(ctx -> openSettler()));
        root.then(Commands.literal("entity").executes(ctx -> {
            entityPreview = !entityPreview;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("[hsui] entity preview " + (entityPreview ? "on" : "off")),
                false);
            return 1;
        }));
        root.then(Commands.literal("bare").executes(ctx -> {
            panelBody = !panelBody;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("[hsui] panel body " + (panelBody ? "on" : "off")), false);
            return 1;
        }));
        root.then(Commands.literal("close").executes(ctx -> {
            Minecraft.getInstance().setScreen(null);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    /**
     * Opens the sheet for the settler nearest the camera. The entity is taken
     * from the client level the player is already looking at, so this shows
     * exactly what a right-click would have shown.
     */
    private static int openSettler() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return 0;
        }
        List<SettlerEntity> found = mc.level.getEntitiesOfClass(SettlerEntity.class,
            mc.player.getBoundingBox().inflate(64.0));
        if (found.isEmpty()) {
            mc.player.displayClientMessage(
                Component.literal("[hsui] no settler within 64 blocks"), false);
            return 0;
        }
        SettlerEntity nearest = found.get(0);
        double best = nearest.distanceToSqr(mc.player);
        for (SettlerEntity candidate : found) {
            double d = candidate.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                nearest = candidate;
            }
        }
        // Deferred, not immediate: this runs inside ChatScreen's own key
        // handling, and the chat screen calls setScreen(null) straight after
        // the command returns -- which closed the sheet in the same frame it
        // opened. Handing it to Minecraft#tell runs it after that close.
        int id = nearest.getId();
        mc.tell(() -> com.hearthstead.client.ClientHooks.openSettlerScreen(id));
        return 1;
    }

    private UiBenchCommand() {
    }
}
