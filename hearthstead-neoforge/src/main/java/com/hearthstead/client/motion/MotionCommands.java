package com.hearthstead.client.motion;

import com.google.gson.GsonBuilder;
import com.hearthstead.Hearthstead;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Client-only {@code /hsmotion} command and the clip reload listener.
 *
 * <ul>
 *   <li>{@code /hsmotion on|off} -- engine for this session (film before/after).</li>
 *   <li>{@code /hsmotion secondary on|off} -- procedural secondary layer only.</li>
 *   <li>{@code /hsmotion reload} -- same as F3+T for the clips.</li>
 *   <li>{@code /hsmotion status} -- loaded overrides and parse warnings.</li>
 *   <li>{@code /hsmotion export <key|all>} -- write the CURRENT clip (authored
 *       or converted legacy) as bedrock JSON under
 *       {@code <game dir>/hearthstead-motion-export/}, ready for Blockbench.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class MotionCommands {

    private MotionCommands() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(MotionLibrary.INSTANCE);
    }

    /** Scripted clips and overlays are keyed by entity network id: drop them on logout. */
    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        MotionOverrides.clear();
    }

    /** Dimension change / respawn: a new level restarts the id space. */
    @SubscribeEvent
    public static void onClone(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone event) {
        MotionOverrides.clear();
    }

    /** An entity leaving the client level takes its scripted clip and overlay with it. */
    @SubscribeEvent
    public static void onEntityLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            MotionOverrides.forget(event.getEntity().getId());
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hsmotion")
            .then(Commands.literal("on").executes(ctx -> engine(ctx, true)))
            .then(Commands.literal("off").executes(ctx -> engine(ctx, false)))
            .then(Commands.literal("secondary")
                .then(Commands.literal("on").executes(ctx -> secondary(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> secondary(ctx, false))))
            .then(Commands.literal("reload").executes(MotionCommands::reload))
            .then(Commands.literal("force")
                .then(Commands.argument("key", StringArgumentType.word())
                    .then(Commands.argument("variant", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 9))
                        .executes(ctx -> force(ctx, StringArgumentType.getString(ctx, "key"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "variant"))))))
            .then(Commands.literal("status").executes(MotionCommands::status))
            .then(Commands.literal("export")
                .then(Commands.argument("key", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> {
                        builder.suggest("all");
                        for (String key : LegacyClipBridge.allKeys().keySet()) {
                            builder.suggest(key);
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> export(ctx, StringArgumentType.getString(ctx, "key"))))));
    }

    private static int engine(CommandContext<CommandSourceStack> ctx, boolean on) {
        MotionSettings.setSessionEngine(on);
        say(ctx, "Motion engine " + (on ? "ON (new clips, bending limbs, layers)" : "OFF (original keyframes)"));
        return 1;
    }

    private static int secondary(CommandContext<CommandSourceStack> ctx, boolean on) {
        MotionSettings.setSessionSecondary(on);
        say(ctx, "Secondary motion " + (on ? "ON" : "OFF"));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MotionLibrary.INSTANCE.reloadNow(Minecraft.getInstance().getResourceManager());
        say(ctx, "Motion clips reloaded: " + MotionLibrary.overrides().size() + " authored, "
            + MotionLibrary.warnings().size() + " warning(s)");
        return 1;
    }

    /** Filming aid: /hsmotion force idle_lumberer 2 pins variant 2; 0 releases the pin. */
    private static int force(CommandContext<CommandSourceStack> ctx, String key, int variant) {
        String full = key.contains("/") ? key : "settler/" + key;
        if (variant == 0) {
            LimbMotion.FORCED_VARIANT.remove(full);
            say(ctx, "Variant pin cleared for " + full);
        } else {
            LimbMotion.FORCED_VARIANT.put(full, variant);
            say(ctx, "Pinned " + full + " to " + (variant == 1 ? "the base clip" : "variant " + variant)
                + " (new cycles only)");
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        say(ctx, "Motion engine " + (MotionSettings.engineEnabled() ? "ON" : "OFF")
            + ", secondary " + (MotionSettings.secondaryEnabled() ? "ON" : "OFF")
            + ", authored clips: " + MotionLibrary.overrides().keySet());
        for (String warning : MotionLibrary.warnings()) {
            say(ctx, "warn: " + warning);
        }
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String key) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("hearthstead-motion-export");
        int written = 0;
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, AnimationDefinition> entry : LegacyClipBridge.allKeys().entrySet()) {
                if (!key.equals("all") && !entry.getKey().equals(key)) {
                    continue;
                }
                MotionClip clip = MotionLibrary.resolve(entry.getValue());
                String[] parts = entry.getKey().split("/");
                String name = "animation." + parts[0] + "." + parts[1];
                Path file = dir.resolve(parts[0]).resolve(parts[1] + ".animation.json");
                Files.createDirectories(file.getParent());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().create()
                    .toJson(BedrockClipCodec.write(name, clip)), StandardCharsets.UTF_8);
                written++;
            }
        } catch (IOException failure) {
            say(ctx, "Export failed: " + failure.getMessage());
            return 0;
        }
        say(ctx, "Exported " + written + " clip(s) to " + dir);
        return written;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSystemMessage(Component.literal("[motion] " + text));
    }
}
