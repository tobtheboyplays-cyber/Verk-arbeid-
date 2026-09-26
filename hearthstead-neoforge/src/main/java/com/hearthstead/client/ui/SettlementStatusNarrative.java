package com.hearthstead.client.ui;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * One truthful player-facing explanation shared by settlement screens.
 *
 * <p>The simulation remains server-authored.  This record only gives every
 * screen the same readable contract: current state, concrete cause, physical
 * owner and the next useful player action.  Keeping those four fields
 * separate prevents a generic status sentence from hiding the actual fix.
 */
public record SettlementStatusNarrative(Component state,
                                        Component cause,
                                        Component owner,
                                        Component nextAction,
                                        HsUi.Tone tone) {

    public SettlementStatusNarrative {
        state = Objects.requireNonNull(state, "state");
        cause = Objects.requireNonNull(cause, "cause");
        owner = Objects.requireNonNull(owner, "owner");
        nextAction = Objects.requireNonNull(nextAction, "nextAction");
        tone = Objects.requireNonNull(tone, "tone");
    }

    public List<Component> tooltip() {
        return List.of(state, cause, owner, nextAction);
    }
}
