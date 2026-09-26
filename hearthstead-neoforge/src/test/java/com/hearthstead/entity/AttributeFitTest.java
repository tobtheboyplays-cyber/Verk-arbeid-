package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeFitTest {

    @Test
    void weightsFollowTheProfile() {
        assertEquals(3, AttributeFit.weight(Profession.SMITH, Attribute.STRENGTH));
        assertEquals(2, AttributeFit.weight(Profession.SMITH, Attribute.DEXTERITY));
        assertEquals(1, AttributeFit.weight(Profession.SMITH, Attribute.FOCUS));
        assertEquals(0, AttributeFit.weight(Profession.SMITH, Attribute.PRESENCE));
        assertEquals(0, AttributeFit.weight(Profession.NONE, Attribute.STRENGTH));
    }

    @Test
    void strongAndDeftSuitsTheHeavyCrafts() {
        List<Profession> jobs = AttributeFit.suitedJobs(Attribute.STRENGTH, Attribute.DEXTERITY, 2);
        assertEquals(2, jobs.size());
        for (Profession p : jobs) {
            assertEquals(3, AttributeFit.weight(p, Attribute.STRENGTH), p.name());
            assertEquals(2, AttributeFit.weight(p, Attribute.DEXTERITY), p.name());
        }
    }

    @Test
    void everyAttributePairNamesAtLeastOneJob() {
        for (Attribute a : Attribute.ALL) {
            for (Attribute b : Attribute.ALL) {
                assertTrue(!AttributeFit.suitedJobs(a, b, 2).isEmpty(), a + "/" + b);
            }
        }
    }
}
