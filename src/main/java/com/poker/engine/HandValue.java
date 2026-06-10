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

    /** Human-readable hand description, e.g. "Pair of Tens" or "Aces full of Kings". */
    public String describe() {
        return switch (category) {
            case HIGH_CARD -> rankSingular(tiebreakers.get(0)) + "-high";
            case ONE_PAIR -> "Pair of " + rankPlural(tiebreakers.get(0));
            case TWO_PAIR -> "Two Pair, " + rankPlural(tiebreakers.get(0))
                    + " and " + rankPlural(tiebreakers.get(1));
            case THREE_OF_A_KIND -> "Trip " + rankPlural(tiebreakers.get(0));
            case STRAIGHT -> rankSingular(tiebreakers.get(0)) + "-high Straight";
            case FLUSH -> rankSingular(tiebreakers.get(0)) + "-high Flush";
            case FULL_HOUSE -> rankPlural(tiebreakers.get(0)) + " full of "
                    + rankPlural(tiebreakers.get(1));
            case FOUR_OF_A_KIND -> "Quad " + rankPlural(tiebreakers.get(0));
            case STRAIGHT_FLUSH -> tiebreakers.get(0) == 14
                    ? "Royal Flush"
                    : rankSingular(tiebreakers.get(0)) + "-high Straight Flush";
        };
    }

    public String bestFiveStr() {
        if (bestFive == null || bestFive.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bestFive.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(bestFive.get(i).shortName());
        }
        return sb.toString();
    }

    private static String rankPlural(int value) {
        return switch (value) {
            case 14 -> "Aces"; case 13 -> "Kings"; case 12 -> "Queens"; case 11 -> "Jacks";
            case 10 -> "Tens"; case 9 -> "Nines"; case 8 -> "Eights"; case 7 -> "Sevens";
            case 6 -> "Sixes"; case 5 -> "Fives"; case 4 -> "Fours"; case 3 -> "Threes";
            case 2 -> "Twos"; default -> String.valueOf(value);
        };
    }

    private static String rankSingular(int value) {
        return switch (value) {
            case 14 -> "Ace"; case 13 -> "King"; case 12 -> "Queen"; case 11 -> "Jack";
            case 10 -> "Ten"; case 9 -> "Nine"; case 8 -> "Eight"; case 7 -> "Seven";
            case 6 -> "Six"; case 5 -> "Five"; case 4 -> "Four"; case 3 -> "Three";
            case 2 -> "Two"; default -> String.valueOf(value);
        };
    }

    @Override
    public String toString() {
        return category.display() + " " + tiebreakers;
    }
}
