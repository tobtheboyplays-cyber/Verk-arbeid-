package com.hearthstead.client.ui;

import com.hearthstead.entity.JobAttributeProfile;
import com.hearthstead.entity.Profession;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Small client projection of the authoritative profession attribute registry.
 *
 * <p>This class never ranks settlers and never invents a candidate. It only
 * explains the two core attributes and optional support attribute already
 * declared by {@link JobAttributeProfile}, so the player can make the choice.
 */
public final class JobProfileUi {

    public record Line(JobAttributeProfile.Slot slot, Component attribute,
                       Component effect) {
    }

    public static List<Line> lines(Profession profession) {
        return JobAttributeProfile.find(profession)
            .map(profile -> profile.slots().stream()
                .map(slot -> new Line(slot, slot.attribute().displayName(),
                    Component.translatable(effectKey(slot.effect()))))
                .toList())
            .orElse(List.of());
    }

    public static String effectKey(JobAttributeProfile.EffectId effect) {
        return "hearthstead.job_profile.effect." + effect.name().toLowerCase(java.util.Locale.ROOT);
    }

    private JobProfileUi() {
    }
}
