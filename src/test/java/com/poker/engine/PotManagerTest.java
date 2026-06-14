package com.poker.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotManagerTest {

    @Test
    void singlePotNoFolds() {
        PotManager.Result r = PotManager.compute(Map.of(0, 100L, 1, 100L, 2, 100L), Set.<Integer>of());
        assertEquals(1, r.pots.size());
        assertEquals(300, r.pots.get(0).amount);
        assertEquals(Set.of(0, 1, 2), r.pots.get(0).eligible);
        assertTrue(r.refunds.isEmpty());
    }

    @Test
    void mainAndSidePot() {
        // Seat 0 is all-in for 50, seats 1 & 2 each put in 200.
        PotManager.Result r = PotManager.compute(Map.of(0, 50L, 1, 200L, 2, 200L), Set.<Integer>of());
        assertEquals(2, r.pots.size());
        assertEquals(150, r.pots.get(0).amount);
        assertEquals(Set.of(0, 1, 2), r.pots.get(0).eligible);
        assertEquals(300, r.pots.get(1).amount);
        assertEquals(Set.of(1, 2), r.pots.get(1).eligible);
    }

    @Test
    void uncalledBetIsRefunded() {
        // Seat 0 bets 100, seat 1 can only call 60 all-in: 40 must come back.
        PotManager.Result r = PotManager.compute(Map.of(0, 100L, 1, 60L), Set.<Integer>of());
        assertEquals(1, r.pots.size());
        assertEquals(120, r.pots.get(0).amount);
        assertEquals(40L, r.refunds.get(0));
    }

    @Test
    void foldedPlayerFundsButCannotWin() {
        PotManager.Result r = PotManager.compute(Map.of(0, 100L, 1, 100L, 2, 100L), Set.of(0));
        assertEquals(1, r.pots.size());
        assertEquals(300, r.pots.get(0).amount);
        assertEquals(Set.of(1, 2), r.pots.get(0).eligible);
    }
}
