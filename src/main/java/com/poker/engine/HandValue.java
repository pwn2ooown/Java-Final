package com.poker.engine;

import java.util.List;

/**
 * The strength of a five-card poker hand: a {@link HandCategory} plus a list of
 * tie-breaker rank values (highest first). Two {@code HandValue}s compare exactly
 * the way poker hands do.
 */
public final class HandValue implements Comparable<HandValue> {

    private final HandCategory category;
    private final List<Integer> tiebreakers;
    private final List<Card> bestFive;

    public HandValue(HandCategory category, List<Integer> tiebreakers, List<Card> bestFive) {
        this.category = category;
        this.tiebreakers = tiebreakers;
        this.bestFive = bestFive;
    }

    public HandCategory category() {
        return category;
    }

    public List<Card> bestFive() {
        return bestFive;
    }

    @Override
    public int compareTo(HandValue other) {
        if (category != other.category) {
            return Integer.compare(category.ordinal(), other.category.ordinal());
        }
        int n = Math.min(tiebreakers.size(), other.tiebreakers.size());
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(tiebreakers.get(i), other.tiebreakers.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Human-readable category name, e.g. "Full House". */
    public String describe() {
        return category.display();
    }

    @Override
    public String toString() {
        return category.display() + " " + tiebreakers;
    }
}
