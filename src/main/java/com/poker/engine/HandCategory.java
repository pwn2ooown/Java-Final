package com.poker.engine;

/** Poker hand categories, ordered weakest ({@code ordinal 0}) to strongest. */
public enum HandCategory {
    HIGH_CARD("High Card"),
    ONE_PAIR("One Pair"),
    TWO_PAIR("Two Pair"),
    THREE_OF_A_KIND("Three of a Kind"),
    STRAIGHT("Straight"),
    FLUSH("Flush"),
    FULL_HOUSE("Full House"),
    FOUR_OF_A_KIND("Four of a Kind"),
    STRAIGHT_FLUSH("Straight Flush");

    private final String display;

    HandCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
