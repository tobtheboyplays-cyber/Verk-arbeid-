package com.hearthstead.event.worldevent;

import com.hearthstead.conversation.RelationSavedData;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Friendly story visits (owner picks 26 Sep plus the approved extras): the
 * neighbour family with a gift, the neighbour lord's herald, the merchant
 * who remembers you, a settler's thanks, the bard who sings of the victory,
 * newcomers drawn by the village's fame, the pilgrim, the healer, the old
 * soldier and the storyteller. One character per visit, chosen by
 * {@link StoryRules} from real milestones; every visit is written to
 * {@link VisitorMemory} with a snapshot of the village, so the next visit
 * can say how it changed.
 */
final class StoryVisitEvent implements WorldEventHandler, WorldEventDirector.RoleListener {
    static final String ROLE_MAIN = StoryWorld.ROLE_PREFIX + "main";
    static final String ROLE_COMPANION = StoryWorld.ROLE_PREFIX + "companion";
    /** THANKS: after this many eligible ticks the settler calls out from where they are. */
    static final int THANKS_WALK_TICKS = 1_200;

    /** QA/GameTest override: the next story visit is this character (consumed on start). */
    @Nullable
    static volatile StoryCharacter forceNext;

    @Override
    public WorldEventType type() {
        return WorldEventType.STORY_VISIT;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        StoryThreatEvent.resolveLadders(level, settlement);
        VisitorMemory.Book book = VisitorMemory.get(level).book(settlement.id);
        if (book == null) return false;
        return StoryRules.nextVisitor(StoryFacts.of(level, settlement), StoryWorld.context(level, settlement), book) != null;
    }

    /**
     * Who may come today, in order: a forced character alone, else every due
     * visitor, and on a forced start (/hsevent) the pilgrim as the one who
     * needs nothing. The settler's thanks is skipped when no settler can speak.
     */
    private static List<StoryCharacter> candidates(boolean forced, VisitorMemory.Book book, StoryFacts facts,
                                                   StoryRules.Context ctx) {
        StoryCharacter forcedChar = forceNext;
        if (forcedChar != null && !forcedChar.threat()) {
            forceNext = null;
            return List.of(forcedChar);
        }
        List<StoryCharacter> out = new ArrayList<>(StoryRules.dueVisitors(facts, ctx, book));
        if (forced && !out.contains(StoryCharacter.ANSELM)) out.add(StoryCharacter.ANSELM);
        return out;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        VisitorMemory memory = VisitorMemory.get(level);
        VisitorMemory.Book book = memory.book(settlement.id);
        if (book == null) return false;
        StoryFacts facts = StoryFacts.of(level, settlement);
        StoryRules.Context ctx = StoryWorld.context(level, settlement);
        StoryCharacter c = null;
        for (StoryCharacter candidate : candidates(active.forced, book, facts, ctx)) {
            active.state.putString("Char", candidate.id());
            if (candidate != StoryCharacter.THANKS) {
                c = candidate;
                break;
            }
            // Spawns nothing; when no settler can give thanks, the next due visitor comes instead.
            if (startThanks(level, settlement, active, book, facts, ctx)) return true;
        }
        if (c == null) return false;

        BlockPos spot = null;
        if ((c == StoryCharacter.WENNA || c == StoryCharacter.HILDE) && facts.tavern()) {
            Building tavern = WorldEventActors.tavern(level, settlement);
            List<BlockPos> spots = tavern == null ? List.of() : WorldEventActors.standingSpots(level, tavern, 6);
            if (!spots.isEmpty()) spot = spots.get(Math.min(spots.size() - 1, 1));
        }
        if (spot == null) {
            spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 4, 8, 1.4F, 2.0F, level.random);
        }
        if (spot == null) return false;
        SettlerEntity main = StoryWorld.spawn(level, settlement, active, spot, ROLE_MAIN, c.displayName(), c.costume(),
            c.seed(), 1.0D);
        if (main == null) return false;
        int i = 0;
        for (StoryLooks.Companion comp : StoryLooks.companions(c)) {
            i++;
            BlockPos feet = WorldEventCreatures.nearFeet(level, spot.offset(i, 0, i % 2), 0.6F, 1.95F);
            if (feet == null) continue;
            SettlerEntity e = StoryWorld.spawn(level, settlement, active, feet, ROLE_COMPANION, comp.name(),
                comp.costume(), comp.seed(), comp.scale());
            if (e != null) e.setCustomNameVisible(false);
        }
        if (c == StoryCharacter.WENNA) {
            main.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.NOTE_BLOCK));
            main.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
        }
        if (c == StoryCharacter.PELL_ROOK) {
            main.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.WRITABLE_BOOK));
            main.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
        }

        // Lines first (they read the memory of the LAST visit), then record this visit.
        VisitorMemory.Person prev = book.person(c.id());
        StoryLines.Lines lines = StoryLines.pick(c, facts, prev,
            new StoryLines.Extra(giftAmount(c, facts, ctx, prev), ctx.daysSinceFounding(), ctx.lordRelation(), 1,
                book.lastMood("refugees") == VisitorMemory.DISPLEASED));
        book.recordVisit(c.id(), facts);
        switch (c) {
            case PELL_ROOK -> book.setFlag("letter:" + facts.rank());
            case WENNA -> book.setFlag("sung:" + ctx.lastHeldNight());
            case GERD -> book.setFlag("healed:" + ctx.lastRaidNight());
            default -> { }
        }
        memory.changed();
        active.state.put("Lines", StoryWorld.writeKeys(lines.keys()));
        active.state.put("Vars", StoryWorld.writeVars(lines.vars()));
        active.state.putLong("Spot", spot.asLong());
        active.state.putLong("LeavePos", StoryWorld.leavePos(settlement, spot).asLong());
        active.state.putInt("Visit", book.visits(c.id()));
        WorldEventActors.markWaiting(main, Component.literal(c.displayName()), true);
        StoryTalk.bind(main, settlement, c, 1, lines);
        StoryWorld.holdRoles(level, active);

        Component name = Component.literal(c.displayName());
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.story." + c.id() + ".arrive",
                "%s has come to %s.", name, settlement.name),
            Component.translatableWithFallback("hearthstead.story.cta", "Talk to %s.", name), spot);
        StoryChat.arrival(level, settlement, Component.translatableWithFallback("hearthstead.story.chat.arrive",
            "%s has arrived.", name));
        if (c == StoryCharacter.WENNA) sing(level, settlement, prev);
        logVisit(settlement, c, book.visits(c.id()));
        return true;
    }

    private static void logVisit(Settlement settlement, StoryCharacter c, int visit) {
        com.hearthstead.Hearthstead.LOGGER.info("HEARTHSTEAD_STORY_VISIT settlement={} who={} visit={}",
            settlement.id, c.id(), visit);
    }

    /** The bard's song names the real captain from the raid log, in chat (names are not talk-UI numbers). */
    private static void sing(ServerLevel level, Settlement settlement, @Nullable VisitorMemory.Person prev) {
        String captain = StoryWorld.lastHeldCaptain(settlement);
        if (captain.isBlank()) return;
        StoryChat.town(level, settlement, Component.translatableWithFallback("hearthstead.story.wenna.song",
            "Wenna Lark sings: \"...and %s came in the dark, and %s did not break.\"", captain, settlement.name)
            .withStyle(ChatFormatting.ITALIC));
        level.playSound(null, settlement.center, com.hearthstead.registry.ModSounds.EVENT_PEDDLER_BELLS.get(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 1.3F);
    }

    /** Gift size shown in the lines ("i") and given on thanks. */
    static int giftAmount(StoryCharacter c, StoryFacts f, StoryRules.Context ctx, @Nullable VisitorMemory.Person prev) {
        return switch (c) {
            case HOLLINS -> 6;
            case PELL_ROOK -> ctx.lordRelation() >= 10 ? 6 : ctx.lordRelation() <= -20 ? 0 : 3;
            case GERD -> 2;
            default -> 0;
        };
    }

    // ------------------------------------------------------------ thanks --

    private boolean startThanks(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                VisitorMemory.Book book, StoryFacts facts, StoryRules.Context ctx) {
        String milestone = StoryRules.dueMilestone(facts, ctx, book);
        if (milestone == null) milestone = active.forced ? "day10" : null;
        if (milestone == null) return false;
        SettlerEntity speaker = null;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (level.getEntity(record.entityId) instanceof SettlerEntity s && s.isAlive() && !s.isSleeping()
                && s.getTarget() == null) {
                speaker = s;
                break;
            }
        }
        if (speaker == null) return false;
        ServerPlayer player = nearestPlayer(level, settlement, speaker);
        if (player == null) return false;
        active.state.putString("Milestone", milestone);
        active.state.putUUID("Settler", speaker.getUUID());
        active.state.putUUID("Player", player.getUUID());
        active.state.putString("SettlerName", speaker.getSettlerName());
        book.setFlag("thanks:" + milestone);
        book.recordVisit(StoryCharacter.THANKS.id(), facts);
        VisitorMemory.get(level).changed();
        chase(level, active, speaker, player);
        return true;
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, Settlement settlement, Entity from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isSpectator() || !WorldEventDirector.insideArea(settlement, p.blockPosition())) continue;
            double d = p.distanceToSqr(from);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static void chase(ServerLevel level, WorldEventSavedData.Active active, SettlerEntity settler, Entity player) {
        WorldEventDirector.assign(settler, new WorldEventDirector.Role(WorldEventDirector.RoleKind.CHASE, active.id,
            player.getUUID(), null, level.getGameTime() + 60, null, 0.7D));
    }

    private void tickThanks(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity settler = active.state.hasUUID("Settler") ? level.getEntity(active.state.getUUID("Settler")) : null;
        Entity player = active.state.hasUUID("Player") ? level.getEntity(active.state.getUUID("Player")) : null;
        if (!(settler instanceof SettlerEntity s) || !s.isAlive()) {
            WorldEventDirector.finish(level, settlement, "thanks_lost", null);
            return;
        }
        ServerPlayer p = player instanceof ServerPlayer sp && sp.isAlive() ? sp : null;
        if (p == null) {
            p = nearestPlayer(level, settlement, s);
            if (p == null) return;
            active.state.putUUID("Player", p.getUUID());
        }
        if (s.distanceToSqr(p) <= 9.0D || active.eligibleTicks >= THANKS_WALK_TICKS) {
            thank(level, settlement, active, s, p);
            return;
        }
        chase(level, active, s, p);
    }

    @Override
    public void roleReached(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            SettlerEntity settler, WorldEventDirector.Role role) {
        if (!StoryCharacter.THANKS.id().equals(active.state.getString("Char"))) return;
        Entity player = role.target() == null ? null : level.getEntity(role.target());
        if (player instanceof ServerPlayer p) thank(level, settlement, active, settler, p);
    }

    private void thank(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, SettlerEntity s,
                       ServerPlayer p) {
        if (active.state.getBoolean("Thanked")) return;
        active.state.putBoolean("Thanked", true);
        String milestone = active.state.getString("Milestone");
        WorldEventDirector.clearRole(s);
        s.getLookControl().setLookAt(p, 30F, 30F);
        StoryChat.town(level, settlement, Component.translatableWithFallback(
            "hearthstead.story.thanks.line." + milestone, "%s: \"Thank you, %s.\"",
            s.getSettlerName(), p.getDisplayName()).withStyle(ChatFormatting.ITALIC));
        StoryWorld.give(p, thanksGift(s.getProfession()));
        s.addMorale(4.0F);
        StoryWorld.moraleAll(level, settlement, 1.0F);
        com.hearthstead.Hearthstead.LOGGER.info("HEARTHSTEAD_STORY_THANKS settlement={} settler={} milestone={}",
            settlement.id, s.getSettlerName(), milestone);
        WorldEventDirector.finish(level, settlement, "thanked", null);
    }

    /** A small gift from the settler's own trade. */
    static ItemStack thanksGift(Profession p) {
        return switch (p) {
            case FARMER -> new ItemStack(Items.BREAD, 3);
            case HUNTER -> new ItemStack(Items.COOKED_BEEF, 2);
            case FISHER -> new ItemStack(Items.COOKED_COD, 3);
            case LUMBERER -> new ItemStack(Items.OAK_LOG, 8);
            case MINER -> new ItemStack(Items.IRON_NUGGET, 6);
            default -> p.martial() ? new ItemStack(Items.ARROW, 8) : new ItemStack(Items.POPPY, 1);
        };
    }

    // -------------------------------------------------------------- tick --

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (StoryCharacter.THANKS.id().equals(active.state.getString("Char"))) {
            tickThanks(level, settlement, active);
            return;
        }
        StoryWorld.holdRoles(level, active);
        if (active.state.getBoolean("Leaving")) {
            if (active.eligibleTicks - active.state.getInt("LeaveAt") >= 300 || WorldEventActors.actors(level, active).isEmpty()) {
                WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
            }
            return;
        }
        if (WorldEventActors.presence(level, active, ROLE_MAIN) == WorldEventActors.Presence.GONE) {
            WorldEventDirector.finish(level, settlement, "gone", null);
        }
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        // After a restart the visit's talk graph is rebuilt from the saved lines.
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c == null || c == StoryCharacter.THANKS || !ROLE_MAIN.equals(WorldEventDirector.tagRole(actor))) return;
        StoryTalk.register(c, 1, new StoryLines.Lines(StoryWorld.readKeys(active.state),
            StoryWorld.readVars(active.state.getCompound("Vars"))));
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, Entity actor,
                          net.minecraft.world.damagesource.DamageSource source) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c == null || !ROLE_MAIN.equals(WorldEventDirector.tagRole(actor))) return;
        // Killing a guest is remembered by everyone who hears of it.
        StoryChat.remember(level, settlement, c.id(), c.displayName(), "killed", VisitorMemory.DISPLEASED);
        WorldEventDirector.finish(level, settlement, "killed", Component.translatableWithFallback(
            "hearthstead.story.killed", "%s is dead. Word of it will travel the roads.", c.displayName())
            .withStyle(ChatFormatting.RED));
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c == StoryCharacter.THANKS) {
            WorldEventDirector.finish(level, settlement, "thanks_lost", null);
            return;
        }
        if (c != null && !WorldEventVisitors.answered(active)) {
            // Nobody came to greet them: remembered, but no cue (players may simply have been busy).
            StoryChat.remember(level, settlement, c.id(), c.displayName(), "ignored_quietly", VisitorMemory.NEUTRAL);
            StoryChat.arrival(level, settlement, Component.translatableWithFallback("hearthstead.story.chat.left_unmet",
                "%s waited, but nobody came. They have gone on their way.", c.displayName()));
        }
        WorldEventDirector.finish(level, settlement, "left", null);
    }

    // ----------------------------------------------------------- answers --

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Leaving") && ROLE_MAIN.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c == null) return null;
        List<Component> lines = new ArrayList<>();
        Object[] args = args(c, settlement, player, active);
        for (String key : StoryWorld.readKeys(active.state)) lines.add(Component.translatable(key, args));
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("story_" + c.id(), settlement.id), "story_" + c.id(),
            Component.literal(c.displayName()), Component.literal(c.displayName()), lines, List.of(), "left", -1L);
    }

    /** The same arguments the talk UI passes (speaker, player, settlement, then the variables in key order). */
    static Object[] args(StoryCharacter c, Settlement settlement, ServerPlayer player, WorldEventSavedData.Active active) {
        List<Object> out = new ArrayList<>();
        out.add(c.displayName());
        out.add(player.getName().getString());
        out.add(settlement.name);
        new java.util.TreeMap<>(StoryWorld.readVars(active.state.getCompound("Vars"))).values().forEach(out::add);
        return out.toArray();
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        return c == null ? List.of() : chatOptions(c, 1, settlement, active.state.getCompound("Vars").getInt("i"));
    }

    /** The chat-fallback answers (same ids, costs and persuasion as the talk graph). */
    static List<WorldEventVisitors.Option> chatOptions(StoryCharacter c, int step, Settlement settlement, int tribute) {
        List<WorldEventVisitors.Option> out = new ArrayList<>();
        boolean defended = com.hearthstead.conversation.TownFactsLive.of(settlement).defended();
        for (StoryOptions.Spec spec : StoryOptions.of(c, step)) {
            if ("town.defended".equals(spec.condition()) && !defended) continue;
            if ("!town.defended".equals(spec.condition()) && defended) continue;
            WorldEventVisitors.Cost cost = spec.food() > 0 ? WorldEventVisitors.Cost.food(spec.food())
                : spec.coins() > 0 ? WorldEventVisitors.Cost.coins(spec.coins())
                : spec.coinsVar() != null ? WorldEventVisitors.Cost.coins(tribute) : WorldEventVisitors.Cost.FREE;
            WorldEventVisitors.OptionStyle style = switch (spec.style()) {
                case PRIMARY -> WorldEventVisitors.OptionStyle.PRIMARY;
                case DANGER -> WorldEventVisitors.OptionStyle.DANGER;
                default -> WorldEventVisitors.OptionStyle.SECONDARY;
            };
            WorldEventVisitors.Option option = WorldEventVisitors.Option.of(spec.id(), Component.translatable(
                StoryLines.PREFIX + c.id() + ".opt." + spec.id()), cost, style).withRelation(spec.relation());
            if (spec.persuade() > 0) {
                option = option.persuade(new WorldEventVisitors.Persuasion(spec.persuade(), spec.success(), spec.failure()));
            }
            out.add(option);
        }
        return out;
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String outcome) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        WorldEventConversations.unbind(actor, null);
        if (c == null) {
            leave(level, settlement, active, outcome);
            return null;
        }
        Component result = apply(level, settlement, active, player, c, outcome);
        int mood = StoryOptions.mood(c, outcome);
        String who = c == StoryCharacter.PELL_ROOK ? "rival_lord" : c.id();
        String name = c == StoryCharacter.PELL_ROOK ? RivalEnvoyEvent.lordName(settlement) : c.displayName();
        StoryChat.remember(level, settlement, who, name, outcome, mood);
        if (who.equals("rival_lord")) {
            // The herald's master keeps the letter's fate on his own record too.
            StoryChat.remember(level, settlement, c.id(), c.displayName(), outcome, VisitorMemory.NEUTRAL);
        }
        leave(level, settlement, active, outcome);
        return result;
    }

    private static Component apply(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                   ServerPlayer player, StoryCharacter c, String outcome) {
        int visit = Math.max(1, active.state.getInt("Visit"));
        String p = "hearthstead.story." + c.id() + ".outcome.";
        switch (c) {
            case HOLLINS -> {
                if (!"refuse".equals(outcome)) {
                    StoryWorld.give(player, new ItemStack(Items.BREAD, 6));
                    StoryWorld.give(player, new ItemStack(Items.EGG, 4));
                    StoryWorld.give(player, new ItemStack(Items.WHEAT_SEEDS, 8));
                    if (visit >= 2) StoryWorld.give(player, new ItemStack(Items.CAKE, 1));
                    if ("give_back".equals(outcome)) StoryWorld.moraleAll(level, settlement, 2.0F);
                }
            }
            case PELL_ROOK -> {
                int relation = RivalEnvoyEvent.relation(level, settlement);
                int coins = relation >= 10 ? 6 : relation <= -20 ? 0 : 3;
                int delta = switch (outcome) {
                    case "reply_warm" -> 10;
                    case "tear" -> -15;
                    default -> 0;
                };
                if (!"tear".equals(outcome) && coins > 0) StoryWorld.give(player, new ItemStack(ModItems.GOLD_COIN.get(), coins));
                if (delta != 0) {
                    RelationSavedData.get(level.getServer()).change(settlement.id, RivalEnvoyEvent.lordProfile(settlement),
                        delta, 0, "conversation.hearthstead.story.pell_rook.memory." + outcome, level.getGameTime());
                }
            }
            case ODO -> {
                if (!"brush_off".equals(outcome)) {
                    List<PeddlerEvent.Ware> table = PeddlerEvent.TABLE;
                    PeddlerEvent.Ware ware = table.get(Math.floorMod(settlement.id.hashCode() + visit * 7, table.size()));
                    StoryWorld.give(player, new ItemStack(ware.item(), ware.count()));
                    if ("buy_round".equals(outcome)) StoryWorld.moraleAll(level, settlement, 2.0F);
                }
            }
            case WENNA -> StoryWorld.moraleAll(level, settlement, switch (outcome) {
                case "tip" -> 8.0F;
                case "listen" -> 4.0F;
                default -> 0.0F;
            });
            case HILDE -> StoryWorld.moraleAll(level, settlement, switch (outcome) {
                case "tip" -> 6.0F;
                case "listen" -> 3.0F;
                default -> 0.0F;
            });
            case BRISKS -> {
                if ("take_in".equals(outcome)) {
                    List<String> names = joinFamily(level, settlement, active);
                    return Component.translatableWithFallback(p + "take_in", "%s join %s.",
                        String.join(", ", names), settlement.name).withStyle(ChatFormatting.GREEN);
                }
            }
            case ANSELM -> {
                if (!"send_away".equals(outcome)) {
                    int luck = "donate".equals(outcome) ? 24_000 : 12_000;
                    for (ServerPlayer near : StoryChat.members(level, settlement)) {
                        if (near.distanceToSqr(player) > 32 * 32) continue;
                        near.addEffect(new MobEffectInstance(MobEffects.LUCK, luck, 0));
                        near.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
                    }
                    StoryWorld.moraleAll(level, settlement, "donate".equals(outcome) ? 4.0F : 2.0F);
                }
            }
            case GERD -> {
                if (!"send_away".equals(outcome)) {
                    for (SettlerEntity s : WorldEventActors.members(level, settlement, x -> true)) s.heal(s.getMaxHealth());
                    int potions = "buy_potions".equals(outcome) ? 4 : 2;
                    for (int k = 0; k < potions; k++) {
                        StoryWorld.give(player, PotionContents.createItemStack(Items.POTION, Potions.HEALING));
                    }
                }
            }
            case BRANNOC -> {
                if (!"dismiss".equals(outcome)) {
                    for (SettlerEntity s : WorldEventActors.members(level, settlement, WorldEventActors::isMartial)) {
                        s.addMorale(6.0F);
                    }
                    VisitorMemory.Book book = VisitorMemory.get(level).book(settlement.id);
                    if (book != null && book.setFlag("brannoc_shield")) {
                        StoryWorld.give(player, new ItemStack(Items.SHIELD));
                        VisitorMemory.get(level).changed();
                    }
                }
            }
            default -> { }
        }
        return Component.translatableWithFallback(p + outcome, "%s nods and goes on their way.", c.displayName());
    }

    /** The newcomer family becomes settlers (the refugees' join path). */
    static List<String> joinFamily(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<String> names = new ArrayList<>();
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (!(actor instanceof SettlerEntity newcomer)) continue;
            String name = WorldEventActors.plainName(newcomer);
            WorldEventConversations.unbind(newcomer, null);
            WorldEventDirector.release(newcomer, active);
            newcomer.setLookCostume(0);
            newcomer.setSettlerName(name);
            newcomer.bindTo(settlement.id, settlement.center);
            settlement.putRecord(newcomer.getUUID(), name, Profession.NONE);
            names.add(name);
        }
        SettlementManager.data(level).setDirty();
        return names;
    }

    private static void leave(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", outcome);
        for (Entity actor : WorldEventActors.actors(level, active)) actor.setCustomNameVisible(false);
        StoryWorld.holdRoles(level, active);
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c != null) {
            StoryChat.arrival(level, settlement, Component.translatableWithFallback("hearthstead.story.chat.leave",
                "%s has gone on their way.", c.displayName()));
        }
        WorldEventDirector.finish(level, settlement, outcome, null); // they walk out; never a puff
    }
}
