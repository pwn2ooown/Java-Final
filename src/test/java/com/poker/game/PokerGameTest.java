package com.poker.game;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PokerGameTest {

    private static PokerGame newGame(long buyIn, long sb, long bb, long... ids) {
        PokerGame g = new PokerGame(sb, bb, buyIn, new Random(42));
        long order = 0;
        for (long id : ids) {
            g.addPlayer(id, "P" + id, ++order);
        }
        return g;
    }

    private static long stackOf(PokerGame g, long id) {
        return g.playerById(id).stack;
    }

    @Test
    void turnLockRejectsOtherPlayers() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        Player actor = g.currentActor();
        long someoneElse = actor.userId == 100 ? 101 : 100;
        assertThrows(InvalidActionException.class,
                () -> g.applyAction(someoneElse, ActionType.FOLD, 0));
    }

    @Test
    void minRaiseEnforced() {
        PokerGame g = newGame(1000, 10, 20, 100, 101); // heads-up
        g.startHand();
        Player a = g.currentActor(); // button/SB acts first heads-up
        assertThrows(InvalidActionException.class,
                () -> g.applyAction(a.userId, ActionType.RAISE, 30)); // below min raise of 40
        g.applyAction(a.userId, ActionType.RAISE, 40);
        assertEquals(40, g.currentBet());
    }

    @Test
    void incompleteAllInDoesNotReopenBetting() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.playerById(102).stack = 70; // short stack -> its all-in will be an incomplete raise
        g.startHand();

        g.applyAction(100, ActionType.RAISE, 50);  // UTG full raise (size 30)
        g.applyAction(101, ActionType.CALL, 0);     // SB calls 50
        g.applyAction(102, ActionType.ALL_IN, 0);   // BB all-in to 70 (raise of only 20 = incomplete)

        // Action is back on seat 100, who already acted: may NOT re-raise.
        assertEquals(100, g.currentActor().userId);
        assertThrows(InvalidActionException.class,
                () -> g.applyAction(100, ActionType.RAISE, 120));
        // ...but calling the extra is fine.
        g.applyAction(100, ActionType.CALL, 0);
        assertEquals(70, g.playerById(100).streetCommitted);
    }

    @Test
    void shortAllInOpeningBetCanBeCompletedToBigBlind() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(101).stack = 5;
        g.applyAction(101, ActionType.BET, 5);

        assertEquals(20, g.minRaiseTo());
        g.applyAction(102, ActionType.RAISE, 20);
        assertEquals(20, g.currentBet());
        assertEquals(40, g.minRaiseTo());
    }

    @Test
    void completingShortAllInOpeningBetReopensAction() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(101).stack = 5;
        g.applyAction(101, ActionType.BET, 5);
        g.applyAction(102, ActionType.CALL, 0);
        g.applyAction(100, ActionType.RAISE, 20);

        assertEquals(102, g.currentActor().userId);
        g.applyAction(102, ActionType.RAISE, 40);
        assertEquals(40, g.currentBet());
    }

    @Test
    void foldToLastPlayerAwardsBlinds() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.FOLD, 0); // UTG folds
        assertEquals(101, g.currentActor().userId);
        g.applyAction(101, ActionType.FOLD, 0); // SB folds
        assertEquals(1, g.aliveInHand());
        g.settle(false);
        assertEquals(1000, stackOf(g, 100));
        assertEquals(990, stackOf(g, 101));  // lost the small blind
        assertEquals(1010, stackOf(g, 102)); // won the small blind
    }

    @Test
    void checkAroundAdvancesStreet() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        // Pre-flop: UTG calls, SB calls, BB checks
        g.applyAction(g.currentActor().userId, ActionType.CALL, 0);
        g.applyAction(g.currentActor().userId, ActionType.CALL, 0);
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertTrue(g.bettingRoundComplete());

        g.dealNextStreet();
        assertEquals(Street.FLOP, g.street());
        assertEquals(3, g.board().size());
        assertFalse(g.bettingRoundComplete());

        // Flop: all three check
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertFalse(g.bettingRoundComplete());
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertFalse(g.bettingRoundComplete());
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertTrue(g.bettingRoundComplete());

        g.dealNextStreet();
        assertEquals(Street.TURN, g.street());
        assertEquals(4, g.board().size());

        // Turn: all three check
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertTrue(g.bettingRoundComplete());

        g.dealNextStreet();
        assertEquals(Street.RIVER, g.street());
        assertEquals(5, g.board().size());

        // River: all three check → round complete, showdown
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        g.applyAction(g.currentActor().userId, ActionType.CHECK, 0);
        assertTrue(g.bettingRoundComplete());
    }

    @Test
    void chipsAreConservedThroughSidePots() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.playerById(100).stack = 300;
        g.playerById(101).stack = 1000;
        g.playerById(102).stack = 600;
        long total = 300 + 1000 + 600;

        g.startHand();
        while (!g.bettingRoundComplete()) {
            Player a = g.currentActor();
            g.applyAction(a.userId, ActionType.ALL_IN, 0);
        }
        while (g.street() != Street.RIVER) {
            g.dealNextStreet();
        }
        g.settle(true);

        long after = g.seats().stream().mapToLong(p -> p.stack).sum();
        assertEquals(total, after);
    }
}
