package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Bug-hunt lane, owner P1 (27 Sep 00:05, "kommandoene maa funke"): every
 * server command the mod registers, through the real dispatcher (batch
 * {@code command_sweep}).
 *
 * <p>The sweep walks the Brigadier tree of every mod root and builds each
 * executable leaf with sample arguments (the node's own suggestions first,
 * else a sensible value per argument type). A leaf is RUN for an op mock
 * player standing in a founded village, unless it is in {@link #PARSE_ONLY}
 * (world-building QA fixtures, mass spawns, raids, kills), which are only
 * parsed: they must resolve to an executable command. A run must not throw
 * ("command.failed"), must not be "unknown or incomplete", and its output
 * must not contain a raw translation key. A non-op player may run the
 * player-facing commands and is refused cleanly (the command is simply not
 * available) for every op-only one.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class CommandSweepGameTests {
    /** Mod command roots (server side). Client-only commands (hsmotion) are not on the server. */
    static final List<String> ROOTS = List.of("hearthstead", "hsevent", "hsgoblin", "hsstory", "hstalk", "hstech",
        "hsrevive", "hsalive", "hsfinisher", "builderstats", "builderfilm", "hearthsteadthiefdemo");

    /** Player-facing: must run for a non-op. */
    static final List<String> PLAYER_FACING = List.of("hearthstead info", "bannerhold info");

    /**
     * Parsed, not run: QA fixtures that build arenas, spawn crowds or raids,
     * kill or down players, or take over the world clock. They are proven to
     * resolve to a real command; running them belongs to their own lanes' tests.
     */
    static final Set<String> PARSE_ONLY = Set.of(
        "hearthstead merchantwestqa", "hearthstead socialqa", "hearthstead tavernqa", "hearthstead foodrestartqa",
        "hearthstead battleqa", "hearthstead earlycoinsqa", "hearthstead demo", "hearthstead raidqa",
        "hearthstead bagqa", "hearthstead packlineup", "hearthstead packfill", "hearthstead raiderfilm",
        "hearthstead lineup", "hearthstead recruit", "hearthstead blessing", "hearthstead hire",
        "hearthstead pose", "hearthstead pulse",
        "hstalk raid", "hstalk traveller", "hstalk peddler",
        "hsrevive bleedout", "hsrevive down", "hsrevive timings",
        "hearthstead soakqa", "hearthstead watchdog",
        "hsstory visit", "hsstory threat",
        "hsfinisher spawn", "hsfinisher double", "hsfinisher reel",
        "hearthsteadthiefdemo", "builderfilm", "hsgoblin thief");

    /** Run explicitly with safe arguments (their generic sample would change global state). */
    static final List<String> EXTRA_RUNS = List.of("hsrevive timings -1 -1", "hearthstead watchdog report",
        "hearthstead soakqa status");

    private static final Pattern RAW_KEY = Pattern.compile(
        "(?<![\\w/:.])(hearthstead|conversation|commands)\\.[a-z0-9_]+(\\.[a-z0-9_]+)+(?![\\w])");

    /** Collects everything a command says to its source. */
    static final class Capture implements CommandSource {
        final List<Component> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    record Leaf(String command, boolean run) {}

    // ------------------------------------------------------------- tree walk --

    static List<Leaf> leaves(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
        List<Leaf> out = new ArrayList<>();
        for (String root : ROOTS) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            if (node == null || !node.canUse(source)) continue;
            walk(dispatcher, source, node, root, out, 0);
        }
        return out;
    }

    private static void walk(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                             CommandNode<CommandSourceStack> node, String path, List<Leaf> out, int depth) {
        if (depth > 8) return;
        if (node.getCommand() != null) out.add(new Leaf(path, !parseOnly(path)));
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (!child.canUse(source)) continue;
            String token = child instanceof LiteralCommandNode<CommandSourceStack> literal ? literal.getLiteral()
                : sample(dispatcher, source, path, (ArgumentCommandNode<CommandSourceStack, ?>) child);
            if (token == null) continue;
            walk(dispatcher, source, child, path + " " + token, out, depth + 1);
        }
    }

    static boolean parseOnly(String path) {
        for (String prefix : PARSE_ONLY) {
            if (path.equals(prefix) || path.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    /** A sensible value for one argument: its own suggestion first, else by type. */
    static String sample(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source, String path,
                         ArgumentCommandNode<CommandSourceStack, ?> node) {
        ArgumentType<?> type = node.getType();
        if (type instanceof EntityArgument || type instanceof GameProfileArgument) return "@s";
        if (type instanceof BlockPosArgument || type instanceof Vec3Argument) return "~ ~ ~";
        if (type instanceof BoolArgumentType) return "true";
        if (type instanceof IntegerArgumentType i) return String.valueOf(Math.max(i.getMinimum(), Math.min(i.getMaximum(), 1)));
        if (type instanceof LongArgumentType l) return String.valueOf(Math.max(l.getMinimum(), Math.min(l.getMaximum(), 1L)));
        if (type instanceof DoubleArgumentType d) return String.valueOf(Math.max(d.getMinimum(), Math.min(d.getMaximum(), 2.0D)));
        if (type instanceof FloatArgumentType f) return String.valueOf(Math.max(f.getMinimum(), Math.min(f.getMaximum(), 2.0F)));
        try {
            String prefix = path + " ";
            List<Suggestion> list = dispatcher.getCompletionSuggestions(dispatcher.parse(prefix, source))
                .get(2, java.util.concurrent.TimeUnit.SECONDS).getList();
            for (Suggestion s : list) {
                String text = s.getText();
                if (!text.isBlank() && !text.contains(" ")) return text;
            }
        } catch (Exception ignored) {
            // fall through to a plain word
        }
        return "test";
    }

    // -------------------------------------------------------------- checks --

    /** Null when fine, else why the output is broken. */
    static String problem(List<Component> messages) {
        for (Component message : messages) {
            String problem = problem(message);
            if (problem != null) return problem;
        }
        return null;
    }

    private static String problem(Component message) {
        if (message.getContents() instanceof TranslatableContents t) {
            if (t.getKey().equals("command.failed")) {
                String hover = message.getStyle().getHoverEvent() == null ? ""
                    : String.valueOf(message.getStyle().getHoverEvent().getValue(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT) instanceof Component c ? c.getString() : "");
                return "threw: " + hover;
            }
            if (t.getKey().startsWith("command.unknown")) return "unknown or incomplete command";
        }
        String text = message.getString();
        var raw = RAW_KEY.matcher(text);
        if (raw.find()) return "raw translation key '" + raw.group() + "' in: " + text;
        for (Component sibling : message.getSiblings()) {
            String problem = problem(sibling);
            if (problem != null) return problem;
        }
        return null;
    }

    static String firstLine(List<Component> messages) {
        return messages.isEmpty() ? "(silent)" : messages.get(0).getString().replace('\n', ' ');
    }

    // ---------------------------------------------------------------- test --

    @GameTest(template = "empty64", timeoutTicks = 200, batch = "command_sweep")
    public void everyModCommandRunsOrRefusesCleanly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearth = h.absolutePos(new BlockPos(32, 1, 32));
        level.setBlockAndUpdate(hearth, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement fresh;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            fresh = SettlementManager.tryFound(level, hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(fresh != null, "fixture: founded");
        ((HearthBlockEntity) level.getBlockEntity(hearth)).bindSettlement(fresh.id);
        @SuppressWarnings("removal")
        ServerPlayer op = h.makeMockServerPlayerInLevel();
        op.setGameMode(GameType.SURVIVAL);
        op.teleportTo(hearth.getX() + .5, hearth.getY(), hearth.getZ() + 4.5);

        var commands = level.getServer().getCommands();
        CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
        CommandSourceStack opBase = op.createCommandSourceStack().withPermission(4);
        List<String> failures = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        List<Leaf> leaves = leaves(dispatcher, opBase);
        h.assertTrue(leaves.size() >= 40, "the sweep found the mod's command tree, got " + leaves.size() + " leaves");

        for (Leaf leaf : leaves) {
            Capture capture = new Capture();
            CommandSourceStack source = opBase.withSource(capture);
            ParseResults<CommandSourceStack> parse = dispatcher.parse(leaf.command(), source);
            if (parse.getReader().canRead() || parse.getContext().getCommand() == null) {
                failures.add("/" + leaf.command() + " -> does not parse to a command"
                    + (parse.getExceptions().isEmpty() ? "" : ": " + parse.getExceptions().values().iterator().next().getMessage()));
                continue;
            }
            if (!leaf.run()) {
                table.append("\n  PARSE /").append(leaf.command());
                continue;
            }
            try {
                commands.performPrefixedCommand(source, leaf.command());
            } catch (RuntimeException escaped) {
                failures.add("/" + leaf.command() + " -> escaped " + escaped);
                continue;
            }
            String problem = problem(capture.messages);
            if (problem != null) failures.add("/" + leaf.command() + " -> " + problem);
            table.append("\n  RUN   /").append(leaf.command()).append("  =>  ").append(firstLine(capture.messages));
        }
        for (String command : EXTRA_RUNS) {
            Capture capture = new Capture();
            commands.performPrefixedCommand(opBase.withSource(capture), command);
            String problem = problem(capture.messages);
            if (problem != null) failures.add("/" + command + " -> " + problem);
            table.append("\n  RUN   /").append(command).append("  =>  ").append(firstLine(capture.messages));
        }
        // Leave nothing running for the next batch.
        commands.performPrefixedCommand(opBase.withSource(new Capture()), "hsevent stop");

        // Non-op: player-facing commands work, op-only ones are simply unavailable.
        CommandSourceStack plain = op.createCommandSourceStack().withPermission(0);
        for (String command : PLAYER_FACING) {
            Capture capture = new Capture();
            ParseResults<CommandSourceStack> parse = dispatcher.parse(command, plain.withSource(capture));
            // A redirect alias (/bannerhold -> /hearthstead) keeps its command on the child context.
            var leafContext = parse.getContext();
            while (leafContext.getChild() != null) leafContext = leafContext.getChild();
            if (parse.getReader().canRead() || leafContext.getCommand() == null) {
                failures.add("non-op /" + command + " -> not available to a player");
                continue;
            }
            commands.performPrefixedCommand(plain.withSource(capture), command);
            String problem = problem(capture.messages);
            if (problem != null) failures.add("non-op /" + command + " -> " + problem);
            table.append("\n  PLAYER /").append(command).append("  =>  ").append(firstLine(capture.messages));
        }
        Capture respond = new Capture();
        commands.performPrefixedCommand(plain.withSource(respond), "hsevent respond none none");
        String respondProblem = problem(respond.messages);
        if (respondProblem != null) failures.add("non-op /hsevent respond -> " + respondProblem);
        for (Leaf leaf : leaves) {
            if (PLAYER_FACING.contains(leaf.command()) || leaf.command().startsWith("hsevent respond")) continue;
            ParseResults<CommandSourceStack> parse = dispatcher.parse(leaf.command(), plain);
            if (!parse.getReader().canRead() && parse.getContext().getCommand() != null) {
                failures.add("non-op can run op command /" + leaf.command());
            }
        }

        Hearthstead.LOGGER.info("HEARTHSTEAD_COMMAND_SWEEP leaves={} failures={}{}", leaves.size(), failures.size(), table);
        for (var actor : SettlementManager.loadedMembers(level, fresh)) actor.discard();
        SettlementSavedData.get(level).settlements.remove(fresh.id);
        level.setBlockAndUpdate(hearth, Blocks.AIR.defaultBlockState());
        h.assertTrue(failures.isEmpty(), failures.size() + " command(s) broken:\n" + String.join("\n", failures));
        h.succeed();
    }
}
