package com.hearthstead.client.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettlerSplitArmContractTest {
    @Test
    void neutralSplitArmExactlyCoversLegacyStraightArm() {
        SettlerModel.SplitArmNeutralGeometry geometry =
            SettlerModel.splitArmNeutralGeometry();

        assertTrue(geometry.exactlyCoversLegacyArm());
        assertEquals(12.0F, geometry.legacyMaxY() - geometry.legacyMinY());
        assertEquals(6.0F, geometry.upperMaxY() - geometry.upperMinY());
        assertEquals(6.0F, geometry.forearmMaxY() - geometry.forearmMinY());
    }

    @Test
    void runtimeHierarchyExposesBothShoulderAndForearmChannels() {
        var torso = SettlerModel.createBodyLayer().bakeRoot()
            .getChild("root").getChild("torso");
        assertTrue(torso.hasChild("bag_right_upper_arm"));
        assertTrue(torso.hasChild("bag_left_upper_arm"));
        assertTrue(torso.getChild("bag_right_upper_arm").hasChild("bag_right_forearm"));
        assertTrue(torso.getChild("bag_left_upper_arm").hasChild("bag_left_forearm"));
    }

    @Test
    void presentationContractKeepsLidAndCommitOnAuthoredTicks() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/assets/hearthstead/animation_contracts/bag_to_chest_unload.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(80, json.get("duration_ticks").getAsInt());
            var contacts = json.getAsJsonObject("contacts");
            assertEquals(48, contacts.get("deposit_commit").getAsInt());
            assertEquals(31, contacts.get("lid_contact").getAsInt());
            assertEquals(64, contacts.get("lid_closed").getAsInt());
        }
    }
}
