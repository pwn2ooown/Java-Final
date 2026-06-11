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
    void raiseOverShortAllInOpeningBetNeedsFullMinBet() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(101).stack = 5;
        g.applyAction(101, ActionType.BET, 5); // short all-in opening bet

        // Min raise = short bet + one full minimum bet (TDA 43-A); NL has no "completion".
        assertEquals(25, g.minRaiseTo());
        assertThrows(InvalidActionException.class,
                () -> g.applyAction(102, ActionType.RAISE, 20));
        g.applyAction(102, ActionType.RAISE, 25);
        assertEquals(25, g.currentBet());
        assertEquals(45, g.minRaiseTo());
    }

    @Test
    void fullRaiseOverShortAllInReopensAction() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(101).stack = 5;
        g.applyAction(101, ActionType.BET, 5);
        g.applyAction(102, ActionType.CALL, 0);
        g.applyAction(100, ActionType.RAISE, 25); // full raise (increment 20 = min bet)

        assertEquals(102, g.currentActor().userId);
        g.applyAction(102, ActionType.RAISE, 45); // betting re-opened for 102
        assertEquals(45, g.currentBet());
    }

    @Test
    void largeRaiseOverShortAllInSetsFullMinRaise() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(101).stack = 15;
        g.applyAction(101, ActionType.BET, 15);    // short all-in opening bet
        g.applyAction(102, ActionType.RAISE, 100); // genuine full raise of 85

        // The min re-raise must reflect the real raise size (85), not the big blind.
        assertEquals(185, g.minRaiseTo());
        assertThrows(InvalidActionException.class,
                () -> g.applyAction(100, ActionType.RAISE, 120));
        g.applyAction(100, ActionType.RAISE, 185);
        assertEquals(185, g.currentBet());
    }

    @Test
    void cumulativeShortAllInsReopenBetting() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.CALL, 0);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.CHECK, 0);
        g.dealNextStreet();

        g.playerById(102).stack = 30;
        g.playerById(100).stack = 45;
        g.applyAction(101, ActionType.BET, 20);    // full bet
        g.applyAction(102, ActionType.ALL_IN, 0);  // short all-in to 30 (+10)
        g.applyAction(100, ActionType.ALL_IN, 0);  // short all-in to 45 (+15)

        // The two short all-ins total 25 >= 20 over the last full bet, so betting
        // re-opens for 101 (TDA 47-A) and the min raise stays one full bet.
        assertEquals(101, g.currentActor().userId);
        assertTrue(g.canRaise(g.playerById(101)));
        assertEquals(65, g.minRaiseTo());
        g.applyAction(101, ActionType.RAISE, 65);
        assertEquals(65, g.currentBet());
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

    // ------------------------------------------------------------------
    // Dead button rule (TDA 32): the big blind advances one live player per
    // hand; the small blind / button go dead instead of skipping anyone.
    // ------------------------------------------------------------------

    private static void finishHandByFolds(PokerGame g) {
        while (g.aliveInHand() > 1) {
            g.applyAction(g.currentActor().userId, ActionType.FOLD, 0);
        }
        g.settle(false);
    }

    @Test
    void deadButtonWhenSmallBlindBusts() {
        PokerGame g = newGame(1000, 10, 20, 1, 2, 3, 4);
        g.startHand(); // hand 1: button=1, SB=2, BB=3
        assertEquals(1, g.buttonUserId());
        assertEquals(2, g.smallBlindUserId());
        assertEquals(3, g.bigBlindUserId());
        finishHandByFolds(g);

        g.playerById(2).stack = 0; // the small blind busts
        g.startHand(); // hand 2: BB advances to 4, SB = old BB (3), dead button stays with 1
        assertEquals(4, g.bigBlindUserId());
        assertEquals(3, g.smallBlindUserId());
        assertEquals(1, g.buttonUserId());
        assertFalse(g.smallBlindDead());
        assertEquals(10, g.playerById(3).streetCommitted);
        assertEquals(20, g.playerById(4).streetCommitted);
    }

    @Test
    void deadSmallBlindWhenBigBlindBusts() {
        PokerGame g = newGame(1000, 10, 20, 1, 2, 3, 4);
        g.startHand(); // hand 1: button=1, SB=2, BB=3
        finishHandByFolds(g);

        g.playerById(3).stack = 0; // the big blind busts
        g.startHand(); // hand 2: BB advances to 4, SB is dead, button moves to old SB (2)
        assertEquals(4, g.bigBlindUserId());
        assertTrue(g.smallBlindDead());
        assertEquals(-1, g.smallBlindUserId());
        assertEquals(2, g.buttonUserId());
        // Nobody posted a small blind: the only chips in are the big blind.
        assertEquals(20, g.totalPot());
    }

    @Test
    void headsUpBlindsAlternate() {
        PokerGame g = newGame(1000, 10, 20, 1, 2);
        g.startHand();
        assertEquals(1, g.buttonUserId()); // button = SB heads-up
        assertEquals(1, g.smallBlindUserId());
        assertEquals(2, g.bigBlindUserId());
        finishHandByFolds(g);

        g.startHand();
        assertEquals(1, g.bigBlindUserId());
        assertEquals(2, g.smallBlindUserId());
        assertEquals(2, g.buttonUserId());
        finishHandByFolds(g);

        g.startHand();
        assertEquals(2, g.bigBlindUserId());
        assertEquals(1, g.smallBlindUserId());
    }

    @Test
    void threeToTwoNobodyPaysBigBlindTwice() {
        PokerGame g = newGame(1000, 10, 20, 1, 2, 3);
        g.startHand(); // button=1, SB=2, BB=3
        assertEquals(3, g.bigBlindUserId());
        finishHandByFolds(g);

        g.playerById(1).stack = 0; // the button busts
        g.startHand(); // heads-up: BB advances past busted 1 to 2; 3 is SB/button
        assertEquals(2, g.bigBlindUserId());
        assertEquals(3, g.smallBlindUserId());
        assertEquals(3, g.buttonUserId());
    }

    // ------------------------------------------------------------------
    // Pot integrity
    // ------------------------------------------------------------------

    @Test
    void orphanedSidePotGoesToLastLiveHand() {
        PokerGame g = newGame(2000, 10, 20, 100, 101, 102);
        g.playerById(102).stack = 100;
        g.startHand(); // button=100, SB=101, BB=102

        g.applyAction(100, ActionType.RAISE, 1000);
        g.applyAction(101, ActionType.CALL, 0);
        g.applyAction(102, ActionType.ALL_IN, 0); // short call all-in for 100 total

        // Both big stacks quit mid-hand (leave/kick force-folds them).
        g.forceFold(100);
        g.forceFold(101);
        assertEquals(1, g.aliveInHand());
        g.settle(false);

        // Folding forfeits the chips: the last live hand wins the whole 2100,
        // not just the 300 main pot it was "eligible" for.
        assertEquals(2100, stackOf(g, 102));
        assertEquals(1000, stackOf(g, 100));
        assertEquals(1000, stackOf(g, 101));
    }

    @Test
    void allHandsDeadVoidsTheHandAndRefunds() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.RAISE, 200);
        g.forceFold(100);
        g.forceFold(101);
        g.forceFold(102);
        assertEquals(0, g.aliveInHand());
        g.settle(false);
        assertEquals(1000, stackOf(g, 100));
        assertEquals(1000, stackOf(g, 101));
        assertEquals(1000, stackOf(g, 102));
    }

    @Test
    void abortHandRefundsCommittedChips() {
        PokerGame g = newGame(1000, 10, 20, 100, 101, 102);
        g.startHand();
        g.applyAction(100, ActionType.RAISE, 100);
        g.applyAction(101, ActionType.CALL, 0);
        g.abortHand();
        assertFalse(g.handInProgress());
        assertEquals(1000, stackOf(g, 100));
        assertEquals(1000, stackOf(g, 101));
        assertEquals(1000, stackOf(g, 102));
    }

    @Test
    void invalidStakesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PokerGame(20, 10, 1000, new Random(1)));  // sb > bb
        assertThrows(IllegalArgumentException.class,
                () -> new PokerGame(0, 20, 1000, new Random(1)));   // zero blind
        assertThrows(IllegalArgumentException.class,
                () -> new PokerGame(10, 20, 5, new Random(1)));     // buy-in below bb
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
