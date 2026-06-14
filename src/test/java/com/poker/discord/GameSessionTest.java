package com.poker.discord;

import com.poker.game.ActionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSessionTest {

    @Test
    void allInConfirmationFallsBackToCallWhenPlayerStillHasChips() {
        assertEquals("✅ You called 20.", GameSession.confirm(ActionType.ALL_IN, 0, 20, false));
    }

    @Test
    void allInConfirmationIncludesActualCommittedAmount() {
        assertEquals("🔺 You are all-in for 75!", GameSession.confirm(ActionType.ALL_IN, 0, 75, true));
    }
}
