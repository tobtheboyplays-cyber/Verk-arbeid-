package com.hearthstead.event.worldevent;

import com.hearthstead.conversation.RelationSavedData;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidEscalation;
import com.hearthstead.settlement.raid.RaidObjective;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * The threat ladders (owner, 26 Sep: "some threats: 'you cannot stay here',
 * 'last warning'"). Hostile type: never on Peaceful, never during the
 * young-village grace ({@link WorldEventDirector#hostileReady}), never near
 * a raid, one ladder at a time, at least three days between steps.
 *
 * <ul>
 *   <li><b>Varg Ironjaw</b> (T1): his herald Sigrun Crowvoice warns ("You
 *       cannot stay here"), then returns with two enforcers ("This is your
 *       last warning"). Defying the last warning swears Varg to lead the
 *       NEXT regular raid himself (he joins the settlement's enemy gallery,
 *       a bolder captain, through one small hook in the raid director).
 *       Beat that raid and the ladder is over for good.</li>
 *   <li><b>Reeve Hamon Stark</b> (T2): the neighbour lord's bailiff, only
 *       once the players have made an enemy of that lord. Pay the land tax,
 *       make amends, or defy him twice and fight his men-at-arms.</li>
 * </ul>
 * The enforcers are real raiders (the brute toll's parley/fight states): they
 * are passive and untouchable while their herald talks, and fight with the
 * normal raider melee once the players choose to.
 */
@EventBusSubscriber(modid = com.hearthstead.Hearthstead.MODID)
final class StoryThreatEvent implements WorldEventHandler {
    static final String ROLE_HERALD = StoryWorld.ROLE_PREFIX + "herald";
    static final String ROLE_ENFORCER = StoryWorld.ROLE_PREFIX + "enforcer";
    static final int WARNING_TICKS = 60;
    /** Days before a ladder may play its next step. */
    static final int STEP_GAP_DAYS = 3;
    static final int CALM_DAYS = 6;
    static final int RETURN_DAYS = 10;
    /** Varg's record when sworn: a bolder captain than a fresh one (menace 1.2), still capped by the band limit. */
    static final int VARG_VICTORIES = 4;

    @Nullable
    static volatile StoryCharacter forceNext;
    /** GameTests: force the next start to play this step (1 or 2). */
    static volatile int forceStep;

    @Override
    public WorldEventType type() {
        return WorldEventType.STORY_THREAT;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        resolveLadders(level, settlement);
        VisitorMemory.Book book = VisitorMemory.get(level).book(settlement.id);
        return book != null && StoryRules.nextThreat(StoryFacts.of(level, settlement),
            StoryWorld.context(level, settlement), book) != null;
    }

    // -------------------------------------------------------------- start --

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        VisitorMemory memory = VisitorMemory.get(level);
        VisitorMemory.Book book = memory.book(settlement.id);
        if (book == null) return false;
        StoryFacts facts = StoryFacts.of(level, settlement);
        StoryRules.Context ctx = StoryWorld.context(level, settlement);
        StoryCharacter c = forceNext;
        if (c != null && c.threat()) forceNext = null;
        else c = StoryRules.nextThreat(facts, ctx, book);
        if (c == null) c = active.forced ? StoryCharacter.SIGRUN : null;
        if (c == null) return false;
        VisitorMemory.Ladder ladder = book.ladder(StoryRules.ladderOf(c));
        int step = forceStep > 0 ? forceStep : StoryRules.open(ladder) ? 2 : 1;
        forceStep = 0;

        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 5, 9, 1.4F, 2.4F, level.random);
        if (spot == null) return false;
        SettlerEntity herald = StoryWorld.spawn(level, settlement, active, spot, ROLE_HERALD, c.displayName(),
            c.costume(), c.seed(), 1.0D);
        if (herald == null) return false;
        herald.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(c == StoryCharacter.SIGRUN ? Items.BLACK_BANNER
            : Items.WRITABLE_BOOK));
        herald.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        if (step >= 2) {
            List<RaiderEntity.Variant> kinds = enforcers(level, settlement, c);
            for (int i = 0; i < kinds.size(); i++) {
                BlockPos feet = WorldEventCreatures.nearFeet(level, spot.offset(i * 2 - 1, 0, 2), 1.0F, 2.2F);
                if (feet == null) feet = spot;
                RaiderEntity enforcer = ModEntities.RAIDER.get().create(level);
                if (enforcer == null) return false;
                enforcer.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0F, 0F);
                enforcer.setVariant(kinds.get(i));
                enforcer.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
                enforcer.setPersistenceRequired();
                enforcer.setCanPickUpLoot(false);
                WorldEventDirector.tag(enforcer, settlement, active, ROLE_ENFORCER);
                String name = c == StoryCharacter.SIGRUN ? "Blackbriar enforcer" : "Stark's man-at-arms";
                enforcer.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", name);
                enforcer.setCustomName(Component.literal(name));
                BruteTollEvent.passive(enforcer, feet);
                if (!level.addFreshEntity(enforcer)) return false;
            }
        }
        int tribute = StoryRules.tribute(facts.population(), WorldEventVisitors.storeCoins(level, settlement), step);
        VisitorMemory.Person prev = book.person(c.id());
        StoryLines.Lines lines = StoryLines.pick(c, facts, prev,
            new StoryLines.Extra(tribute, ctx.daysSinceFounding(), ctx.lordRelation(), step));
        book.recordVisit(c.id(), facts);
        memory.changed();
        active.state.putString("Char", c.id());
        active.state.putInt("Step", step);
        active.state.put("Lines", StoryWorld.writeKeys(lines.keys()));
        active.state.put("Vars", StoryWorld.writeVars(lines.vars()));
        active.state.putLong("Spot", spot.asLong());
        active.state.putLong("LeavePos", StoryWorld.leavePos(settlement, spot).asLong());
        active.state.putBoolean("Seen", true);
        WorldEventActors.markWaiting(herald, Component.literal(c.displayName()), true);
        StoryTalk.bind(herald, settlement, c, step, lines);
        StoryWorld.holdRoles(level, active);
        level.playSound(null, spot, com.hearthstead.registry.ModSounds.EVENT_ENVOY_FANFARE.get(), SoundSource.HOSTILE,
            1.6F, 0.7F);
        Component name = Component.literal(c.displayName());
        Component master = Component.literal(master(c, settlement));
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.story." + c.id() + ".arrive" + step,
                "%s, sent by %s, has come to %s.", name, master, settlement.name),
            Component.translatableWithFallback("hearthstead.story.threat.cta" + step,
                "Talk to %s. Guards hold while they talk.", name), spot);
        StoryChat.arrival(level, settlement, Component.translatableWithFallback("hearthstead.story.chat.threat_arrive",
            "%s has come with a message from %s.", name, master));
        com.hearthstead.Hearthstead.LOGGER.info("HEARTHSTEAD_STORY_THREAT settlement={} who={} step={} tribute={}",
            settlement.id, c.id(), step, tribute);
        return true;
    }

    static String master(StoryCharacter c, Settlement settlement) {
        return c == StoryCharacter.HAMON ? RivalEnvoyEvent.lordName(settlement) : StoryCharacter.VARG;
    }

    /** Two enforcers; the bailiff brings a third man-at-arms to a town with three or more fighters (2-player balance). */
    static List<RaiderEntity.Variant> enforcers(ServerLevel level, Settlement settlement, StoryCharacter c) {
        List<RaiderEntity.Variant> out = new ArrayList<>();
        if (c == StoryCharacter.SIGRUN) {
            out.add(RaiderEntity.Variant.BANDIT);
            out.add(RaiderEntity.Variant.SKIRMISHER);
        } else {
            out.add(RaiderEntity.Variant.SKIRMISHER);
            out.add(RaiderEntity.Variant.SKIRMISHER);
            if (RaidEscalation.strength(level, settlement).fighters() >= 3) out.add(RaiderEntity.Variant.BANDIT);
        }
        return out;
    }

    // --------------------------------------------------------------- tick --

    private static List<RaiderEntity> band(ServerLevel level, WorldEventSavedData.Active active) {
        List<RaiderEntity> out = new ArrayList<>();
        for (Entity e : WorldEventActors.actors(level, active)) if (e instanceof RaiderEntity r) out.add(r);
        return out;
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (actor instanceof RaiderEntity r) {
            if (active.state.getBoolean("Hostile")) BruteTollEvent.hostile(r);
            else BruteTollEvent.passive(r, BlockPos.of(actor.getPersistentData().getCompound(WorldEventDirector.TAG)
                .getLong("Stop")));
            return;
        }
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        if (c != null && ROLE_HERALD.equals(WorldEventDirector.tagRole(actor))) {
            StoryTalk.register(c, Math.max(1, active.state.getInt("Step")), new StoryLines.Lines(
                StoryWorld.readKeys(active.state), StoryWorld.readVars(active.state.getCompound("Vars"))));
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        StoryWorld.holdRoles(level, active);
        List<RaiderEntity> band = band(level, active);
        if (active.state.contains("HostileAt") && !active.state.getBoolean("Hostile")
            && active.eligibleTicks >= active.state.getInt("HostileAt")) {
            turnHostile(level, settlement, active, null);
        }
        if (active.state.getBoolean("Hostile")) {
            if (band.isEmpty()) {
                var presence = WorldEventActors.presence(level, active, ROLE_ENFORCER);
                if (presence == WorldEventActors.Presence.ABSENT) return;
                boolean beaten = presence == WorldEventActors.Presence.DEAD;
                fought(level, settlement, active, beaten);
                WorldEventDirector.finish(level, settlement, beaten ? "enforcers_beaten" : "enforcers_gone",
                    beaten ? Component.translatableWithFallback("hearthstead.story.enforcers_beaten",
                        "The last of %s's men falls.", master(character(active), settlement))
                        .withStyle(ChatFormatting.GREEN) : null);
            }
            return;
        }
        if (active.state.getBoolean("Leaving")) {
            if (active.eligibleTicks - active.state.getInt("LeaveAt") >= 300 || WorldEventActors.actors(level, active).isEmpty()) {
                WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
            }
            return;
        }
        if (WorldEventActors.presence(level, active, ROLE_HERALD) == WorldEventActors.Presence.GONE) {
            WorldEventDirector.finish(level, settlement, "gone", null);
        }
    }

    static StoryCharacter character(WorldEventSavedData.Active active) {
        StoryCharacter c = StoryCharacter.byId(active.state.getString("Char"));
        return c == null ? StoryCharacter.SIGRUN : c;
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        StoryCharacter c = character(active);
        int step = Math.max(1, active.state.getInt("Step"));
        if (active.state.getBoolean("Hostile")) {
            fought(level, settlement, active, false);
            WorldEventDirector.finish(level, settlement, "enforcers_retreat", Component.translatableWithFallback(
                "hearthstead.story.enforcers_retreat", "%s's men fall back into the woods.", master(c, settlement)));
            return;
        }
        if (!WorldEventVisitors.answered(active)) {
            WorldEventVisitors.markAnswered(active, "ignored");
            // No answer is an answer: the warning stands (a displeased, remembered choice).
            consequence(level, settlement, active, c, step, "ignored");
        }
        WorldEventDirector.finish(level, settlement, "ignored", null);
    }

    // ------------------------------------------------------------ answers --

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Leaving") && !active.state.getBoolean("Hostile")
            && !active.state.contains("HostileAt") && ROLE_HERALD.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        StoryCharacter c = character(active);
        List<Component> lines = new ArrayList<>();
        Object[] args = StoryVisitEvent.args(c, settlement, player, active);
        for (String key : StoryWorld.readKeys(active.state)) lines.add(Component.translatable(key, args));
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("story_" + c.id(), settlement.id), "story_" + c.id(),
            Component.literal(c.displayName()), Component.literal(c.displayName()), lines, List.of(), "ignored", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return StoryVisitEvent.chatOptions(character(active), Math.max(1, active.state.getInt("Step")), settlement,
            active.state.getCompound("Vars").getInt("i"));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String outcome) {
        StoryCharacter c = character(active);
        int step = Math.max(1, active.state.getInt("Step"));
        WorldEventConversations.unbind(actor, null);
        String p = "hearthstead.story." + c.id() + ".outcome" + step + ".";
        Component master = Component.literal(master(c, settlement));
        consequence(level, settlement, active, c, step, outcome);
        switch (outcome) {
            case "defy", "talk_failed" -> {
                if (step >= 2) {
                    active.state.putInt("HostileAt", active.eligibleTicks + WARNING_TICKS);
                    level.playSound(null, actor.blockPosition(), com.hearthstead.registry.ModSounds.EVENT_BRUTE_DEMAND.get(),
                        SoundSource.HOSTILE, 1.3F, 1.1F);
                    return Component.translatableWithFallback(p + outcome,
                        "\"So be it.\" %s's men draw their weapons!", master).withStyle(ChatFormatting.RED);
                }
            }
            case "attack" -> {
                turnHostile(level, settlement, active, null);
                return Component.translatableWithFallback(p + outcome, "%s strikes first! To arms!",
                    player.getDisplayName()).withStyle(ChatFormatting.RED);
            }
            default -> { }
        }
        leave(level, settlement, active, outcome);
        return Component.translatableWithFallback(p + outcome, "%s rides off.", c.displayName());
    }

    /**
     * What a choice does to the ladder, the lord's standing and the memory.
     * The "will remember this" cue is posted here, after the record exists.
     */
    static void consequence(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            StoryCharacter c, int step, String outcome) {
        VisitorMemory memory = VisitorMemory.get(level);
        VisitorMemory.Book book = memory.book(settlement.id);
        if (book == null) return;
        long day = WorldEventSchedule.dayOf(level.getDayTime());
        VisitorMemory.Ladder ladder = book.ladder(StoryRules.ladderOf(c));
        int mood = StoryOptions.mood(c, outcome);
        String choice = switch (outcome) {
            case "pay", "pay_tax", "make_amends" -> "paid";
            default -> outcome;
        };
        switch (choice) {
            case "paid" -> {
                ladder.payments++;
                if (step >= 2 || c == StoryCharacter.HAMON || ladder.payments >= 2) {
                    ladder.status = VisitorMemory.Ladder.PAID;
                } else {
                    ladder.status = VisitorMemory.Ladder.IDLE;
                    ladder.step = 1;
                    ladder.notBeforeDay = day + CALM_DAYS;
                }
                if (c == StoryCharacter.HAMON) lord(level, settlement, "make_amends".equals(outcome) ? 10 : 15, outcome);
            }
            case "talked_down" -> {
                ladder.status = VisitorMemory.Ladder.IDLE;
                ladder.step = 1;
                ladder.notBeforeDay = day + CALM_DAYS;
            }
            default -> {
                // Defied, ignored, a failed talk, an attack, a dead herald.
                if (step >= 2) {
                    if (c == StoryCharacter.SIGRUN) {
                        swear(level, settlement, ladder);
                    } else {
                        // The bailiff comes back with his last warning until it is answered or fought out.
                        ladder.status = VisitorMemory.Ladder.ARMED;
                        ladder.notBeforeDay = day + STEP_GAP_DAYS;
                    }
                } else {
                    ladder.step = 2;
                    ladder.status = VisitorMemory.Ladder.ARMED;
                    ladder.notBeforeDay = day + STEP_GAP_DAYS;
                }
                if (c == StoryCharacter.HAMON) lord(level, settlement, -10, outcome);
            }
        }
        memory.changed();
        String name = c == StoryCharacter.SIGRUN ? StoryCharacter.VARG : c.displayName();
        StoryChat.remember(level, settlement, c.id(), name, choice, mood);
    }

    private static void lord(ServerLevel level, Settlement settlement, int delta, String outcome) {
        RelationSavedData.get(level.getServer()).change(settlement.id, RivalEnvoyEvent.lordProfile(settlement), delta, 0,
            "conversation.hearthstead.story.hamon.memory." + outcome, level.getGameTime());
    }

    /** The fight at the last warning is over: the bailiff's ladder ends when his men are beaten. */
    private static void fought(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, boolean beaten) {
        StoryCharacter c = character(active);
        VisitorMemory memory = VisitorMemory.get(level);
        VisitorMemory.Book book = memory.book(settlement.id);
        if (book == null) return;
        if (c == StoryCharacter.HAMON) {
            VisitorMemory.Ladder ladder = book.ladder(StoryRules.LADDER_HAMON);
            if (beaten) {
                ladder.status = VisitorMemory.Ladder.BEATEN;
                StoryChat.remember(level, settlement, "hamon", c.displayName(), "men_beaten", VisitorMemory.NEUTRAL);
            } else {
                ladder.status = VisitorMemory.Ladder.IDLE;
                ladder.step = 1;
                ladder.notBeforeDay = WorldEventSchedule.dayOf(level.getDayTime()) + RETURN_DAYS;
            }
        } else if (beaten) {
            book.setFlag("varg_enforcers_beaten");
        }
        memory.changed();
    }

    /**
     * Varg Ironjaw swears to lead the next raid: he joins the settlement's
     * enemy gallery (a fixed captain with a stable id) and the raid
     * director's captain pick takes him once ({@link StoryHooks#swornCaptain}).
     */
    static void swear(ServerLevel level, Settlement settlement, VisitorMemory.Ladder ladder) {
        UUID id = WorldEventActors.stableId("varg_ironjaw", settlement.id);
        RaidCaptain varg = null;
        for (RaidCaptain captain : settlement.raidCaptains) if (captain.id().equals(id)) varg = captain;
        if (varg == null) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putString("Name", StoryCharacter.VARG);
            tag.putInt("Victories", VARG_VICTORIES);
            varg = RaidCaptain.readNbt(tag);
            settlement.raidCaptains.add(varg);
            while (settlement.raidCaptains.size() > com.hearthstead.settlement.raid.RaidDirector.MAX_REMEMBERED_CAPTAINS) {
                RaidCaptain oldest = settlement.raidCaptains.get(0);
                if (oldest == varg) break;
                settlement.raidCaptains.remove(0);
            }
            SettlementManager.data(level).setDirty();
        }
        ladder.status = VisitorMemory.Ladder.SWORN;
        ladder.captain = id;
        ladder.defeatsAt = varg.defeats();
        ladder.victoriesAt = varg.victories();
        ladder.planned = false;
        StoryChat.town(level, settlement, Component.translatableWithFallback("hearthstead.story.varg.sworn",
            "%s has sworn to lead the next raid on %s himself.", StoryCharacter.VARG, settlement.name)
            .withStyle(ChatFormatting.RED));
    }

    /** Settles sworn ladders after the raid they were sworn for. Cheap; safe to call often. */
    static void resolveLadders(ServerLevel level, Settlement settlement) {
        VisitorMemory memory = VisitorMemory.existing(level);
        VisitorMemory.Book book = memory == null ? null : memory.existingBook(settlement.id);
        VisitorMemory.Ladder ladder = book == null ? null : book.existingLadder(StoryRules.LADDER_VARG);
        if (ladder == null || !VisitorMemory.Ladder.SWORN.equals(ladder.status) || ladder.captain == null) return;
        RaidCaptain varg = null;
        for (RaidCaptain captain : settlement.raidCaptains) if (captain.id().equals(ladder.captain)) varg = captain;
        long day = WorldEventSchedule.dayOf(level.getDayTime());
        if (varg == null) {
            ladder.status = VisitorMemory.Ladder.IDLE;
            ladder.step = 1;
            ladder.notBeforeDay = day + RETURN_DAYS;
        } else if (varg.defeats() > ladder.defeatsAt) {
            ladder.status = VisitorMemory.Ladder.BEATEN;
            book.remember("sigrun", StoryCharacter.VARG, "beaten", VisitorMemory.NEUTRAL, day);
            StoryChat.town(level, settlement, Component.translatableWithFallback("hearthstead.story.varg.beaten",
                "%s is beaten. The Blackbriar band will not trouble %s again.", StoryCharacter.VARG, settlement.name)
                .withStyle(ChatFormatting.GREEN));
        } else if (varg.victories() > ladder.victoriesAt) {
            ladder.status = VisitorMemory.Ladder.IDLE;
            ladder.step = 1;
            ladder.notBeforeDay = day + RETURN_DAYS;
            book.remember("sigrun", StoryCharacter.VARG, "won", VisitorMemory.NEUTRAL, day);
        } else {
            return;
        }
        memory.changed();
    }

    static void turnHostile(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            @Nullable Component notice) {
        if (active.state.getBoolean("Hostile")) return;
        active.state.putBoolean("Hostile", true);
        if (!WorldEventVisitors.answered(active)) WorldEventVisitors.markAnswered(active, "fight");
        for (Entity e : WorldEventActors.actors(level, active)) {
            WorldEventConversations.unbind(e, null);
            if (e instanceof RaiderEntity r) BruteTollEvent.hostile(r);
        }
        // The herald does not fight: she rides off while her men do.
        active.state.putLong("Spot", StoryWorld.leavePos(settlement, BlockPos.of(active.state.getLong("Spot"))).asLong());
        if (notice != null) WorldEventDirector.notice(level, settlement, notice.copy().withStyle(ChatFormatting.RED));
        level.playSound(null, settlement.center, com.hearthstead.registry.ModSounds.RAIDER_BRUTE_ROAR.get(),
            SoundSource.HOSTILE, 1.5F, 1.2F);
        WorldEventSavedData.get(level).markChanged();
    }

    private static void leave(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", outcome);
        for (Entity e : WorldEventActors.actors(level, active)) {
            e.setCustomNameVisible(false);
            if (e instanceof RaiderEntity r) {
                r.setInvulnerable(true);
                r.getPersistentData().getCompound(WorldEventDirector.TAG).putLong("Stop",
                    BlockPos.of(active.state.getLong("LeavePos")).asLong());
            }
        }
        StoryWorld.holdRoles(level, active);
        StoryChat.arrival(level, settlement, Component.translatableWithFallback("hearthstead.story.chat.leave",
            "%s has gone on their way.", character(active).displayName()));
        WorldEventDirector.finish(level, settlement, outcome, null);
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, Entity actor,
                          DamageSource source) {
        if (!ROLE_HERALD.equals(WorldEventDirector.tagRole(actor))) return;
        StoryCharacter c = character(active);
        int step = Math.max(1, active.state.getInt("Step"));
        if (!WorldEventVisitors.answered(active)) WorldEventVisitors.markAnswered(active, "herald_killed");
        consequence(level, settlement, active, c, Math.max(step, 2), "herald_killed");
        if (!band(level, active).isEmpty()) {
            turnHostile(level, settlement, active, Component.translatableWithFallback("hearthstead.story.herald_killed",
                "%s is cut down. Her men want blood!", c.displayName()));
        } else {
            WorldEventDirector.finish(level, settlement, "herald_killed", null);
        }
    }

    // ---------------------------------------------------- struck in parley --

    @SubscribeEvent
    public static void attacked(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) strike(player.serverLevel(), event.getTarget(), player);
    }

    @SubscribeEvent
    public static void shot(ProjectileImpactEvent event) {
        if (event.getProjectile().level() instanceof ServerLevel level
            && event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit
            && event.getProjectile().getOwner() instanceof ServerPlayer player) {
            strike(level, hit.getEntity(), player);
        }
    }

    /** A player hitting a parleying enforcer starts the fight (and defies the warning). */
    private static void strike(ServerLevel level, Entity target, ServerPlayer player) {
        if (!(target instanceof RaiderEntity) || !target.getPersistentData().contains(WorldEventDirector.TAG)) return;
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, target);
        if (owner == null || owner.active().type != WorldEventType.STORY_THREAT
            || owner.active().state.getBoolean("Hostile") || owner.active().state.getBoolean("Leaving")) {
            return;
        }
        WorldEventSavedData.Active active = owner.active();
        if (!WorldEventVisitors.answered(active)) {
            WorldEventVisitors.markAnswered(active, "attack");
            consequence(level, owner.settlement(), active, character(active), 2, "attack");
        }
        turnHostile(level, owner.settlement(), active, Component.translatableWithFallback(
            "hearthstead.story.struck", "%s strikes first! To arms!", player.getDisplayName()));
    }
}
