package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure regression over the exact particle arrays and sound count production uses. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingPresentationGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "plaque_blessing_rune_particle_and_sound_budget_by_type")
    public void plaqueRuneParticleAndSoundBudgetByType(GameTestHelper helper) {
        helper.assertTrue(BlessingPresentation.plaqueRuneOnsetParticleCount() == 2,
            "every APPLIED plaque binding must acknowledge with exactly two edge sparks");
        helper.assertTrue(BlessingPresentation.bindingContactSoundCount() == 1,
            "every completed plaque rune must emit exactly one positional sound");

        assertBudget(helper, BlessingId.WARDEN_OATH, 7, 9);
        assertBudget(helper, BlessingId.HEARTHWARD, 8, 10);
        assertBudget(helper, BlessingId.THORNED_ROADS, 9, 11);
        helper.succeed();
    }

    private static void assertBudget(GameTestHelper helper, BlessingId blessing,
                                     int expectedContactParticles,
                                     int expectedTotalParticles) {
        int contact = BlessingPresentation.bindingContactParticleCount(blessing);
        int total = BlessingPresentation.plaqueRuneOnsetParticleCount() + contact;
        helper.assertTrue(contact == expectedContactParticles,
            blessing.id() + " contact silhouette must contain exactly "
                + expectedContactParticles + " particles");
        helper.assertTrue(total == expectedTotalParticles,
            blessing.id() + " full onset+contact rune must contain exactly "
                + expectedTotalParticles + " particles, not an accidental burst");
    }
}
