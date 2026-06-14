package com.poker.engine;

/** An immutable playing card. */
public record Card(Rank rank, Suit suit) {

    /** Compact rendering such as {@code A♠} or {@code 10♥}. */
    public String shortName() {
        return rank.symbol() + suit.symbol();
    }

    @Override
    public String toString() {
        return shortName();
    }
}
