package com.poker.game;

/** The four betting rounds of Texas Hold'em. */
public enum Street {
    PREFLOP("Pre-flop"),
    FLOP("Flop"),
    TURN("Turn"),
    RIVER("River");

    private final String display;

    Street(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
