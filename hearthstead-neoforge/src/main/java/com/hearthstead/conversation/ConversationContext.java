package com.hearthstead.conversation;

import com.hearthstead.settlement.Settlement;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * What an action or condition sees: the player, the speaking entity, the
 * settlement the talk belongs to (may be null in the wilds), the graph and
 * the chosen reply, the binding's variables and whether a persuasion check
 * on this reply succeeded.
 */
public final class ConversationContext {
    private final ServerPlayer player;
    private final Entity speaker;
    @Nullable private final Settlement settlement;
    private final String graphId;
    private final String optionId;
    private final Map<String, Integer> vars;
    private final SpeakerProfile profile;
    private final boolean succeeded;
    private final List<ServerPlayer> participants;
    @Nullable private Component notice;
    private boolean closeRequested;

    public ConversationContext(ServerPlayer player, Entity speaker, @Nullable Settlement settlement,
                               String graphId, String optionId, Map<String, Integer> vars,
                               SpeakerProfile profile, boolean succeeded) {
        this(player, speaker, settlement, graphId, optionId, vars, profile, succeeded, List.of(player));
    }

    /**
     * With the players in a shared (co-op) talk. An action runs once, for the
     * reply's chooser ({@link #player()}); {@link #participants()} lets it
     * reach everyone who shared the talk.
     */
    public ConversationContext(ServerPlayer player, Entity speaker, @Nullable Settlement settlement,
                               String graphId, String optionId, Map<String, Integer> vars,
                               SpeakerProfile profile, boolean succeeded, List<ServerPlayer> participants) {
        this.player = player;
        this.speaker = speaker;
        this.settlement = settlement;
        this.graphId = graphId;
        this.optionId = optionId;
        this.vars = vars;
        this.profile = profile;
        this.succeeded = succeeded;
        this.participants = participants == null || participants.isEmpty() ? List.of(player) : List.copyOf(participants);
    }

    public ServerPlayer player() { return player; }
    public Entity speaker() { return speaker; }
    @Nullable public Settlement settlement() { return settlement; }
    public String graphId() { return graphId; }
    public String optionId() { return optionId; }
    public Map<String, Integer> vars() { return vars; }
    public SpeakerProfile profile() { return profile; }
    /** False only when this reply's persuasion roll failed. */
    public boolean succeeded() { return succeeded; }
    public ServerLevel level() { return player.serverLevel(); }
    /** Everyone online in this talk, the chooser included (just the chooser when talking alone). */
    public List<ServerPlayer> participants() { return participants; }

    public int var(String key, int fallback) {
        Integer value = vars.get(key);
        return value == null ? fallback : value;
    }

    /** A line broadcast to nearby players when the talk ends ("Tobias paid the toll."). */
    public void notice(Component text) { this.notice = text; }
    @Nullable public Component notice() { return notice; }

    /** Ends the conversation after the actions ran, whatever the outcome's next node. */
    public void close() { this.closeRequested = true; }
    public boolean closeRequested() { return closeRequested; }
}
