package com.poker.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluates the best five-card poker hand out of five, six, or seven cards.
 *
 * <p>The implementation simply scores every 5-card subset with a robust 5-card
 * evaluator and keeps the maximum. For 7 cards that is only C(7,5) = 21 subsets,
 * so it is more than fast enough and very easy to verify for correctness.
 */
public final class HandEvaluator {

    private HandEvaluator() {
    }

    public static HandValue evaluate(List<Card> cards) {
        if (cards.size() < 5) {
            throw new IllegalArgumentException("Need at least 5 cards to evaluate, got " + cards.size());
        }
        int n = cards.size();
        HandValue best = null;
        for (int a = 0; a < n - 4; a++) {
            for (int b = a + 1; b < n - 3; b++) {
                for (int c = b + 1; c < n - 2; c++) {
                    for (int d = c + 1; d < n - 1; d++) {
                        for (int e = d + 1; e < n; e++) {
                            List<Card> five = List.of(
                                    cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e));
                            HandValue v = evaluateFive(five);
                            if (best == null || v.compareTo(best) > 0) {
                                best = v;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static HandValue evaluateFive(List<Card> five) {
        int[] rankCount = new int[15]; // index by rank value 2..14
        int[] suitCount = new int[4];
        List<Integer> values = new ArrayList<>(5);
        for (Card card : five) {
            rankCount[card.rank().value()]++;
            suitCount[card.suit().ordinal()]++;
            values.add(card.rank().value());
        }
        values.sort(Collections.reverseOrder());

        boolean flush = false;
        for (int sc : suitCount) {
            if (sc == 5) {
                flush = true;
                break;
            }
        }
        int straightHigh = straightHigh(rankCount);

        // Groups of equal ranks, sorted by (count desc, rank desc).
        List<int[]> groups = new ArrayList<>(); // each entry: {rankValue, count}
        for (int v = 14; v >= 2; v--) {
            if (rankCount[v] > 0) {
                groups.add(new int[]{v, rankCount[v]});
            }
        }
        groups.sort((x, y) -> y[1] != x[1] ? y[1] - x[1] : y[0] - x[0]);

        if (flush && straightHigh > 0) {
            return new HandValue(HandCategory.STRAIGHT_FLUSH, List.of(straightHigh), five);
        }
        if (groups.get(0)[1] == 4) {
            return new HandValue(HandCategory.FOUR_OF_A_KIND,
                    List.of(groups.get(0)[0], groups.get(1)[0]), five);
        }
        if (groups.get(0)[1] == 3 && groups.size() > 1 && groups.get(1)[1] >= 2) {
            return new HandValue(HandCategory.FULL_HOUSE,
                    List.of(groups.get(0)[0], groups.get(1)[0]), five);
        }
        if (flush) {
            return new HandValue(HandCategory.FLUSH, values, five);
        }
        if (straightHigh > 0) {
            return new HandValue(HandCategory.STRAIGHT, List.of(straightHigh), five);
        }
        if (groups.get(0)[1] == 3) {
            List<Integer> tb = new ArrayList<>();
            for (int[] g : groups) {
                tb.add(g[0]);
            }
            return new HandValue(HandCategory.THREE_OF_A_KIND, tb, five);
        }
        if (groups.get(0)[1] == 2 && groups.size() > 1 && groups.get(1)[1] == 2) {
            return new HandValue(HandCategory.TWO_PAIR,
                    List.of(groups.get(0)[0], groups.get(1)[0], groups.get(2)[0]), five);
        }
        if (groups.get(0)[1] == 2) {
            List<Integer> tb = new ArrayList<>();
            for (int[] g : groups) {
                tb.add(g[0]);
            }
            return new HandValue(HandCategory.ONE_PAIR, tb, five);
        }
        return new HandValue(HandCategory.HIGH_CARD, values, five);
    }

    /**
     * Returns the high card value of the best straight present, or 0 if none.
     * Handles the Ace-low "wheel" (A-2-3-4-5) which is a 5-high straight.
     */
    private static int straightHigh(int[] rankCount) {
        for (int high = 14; high >= 6; high--) {
            boolean ok = true;
            for (int k = 0; k < 5; k++) {
                if (rankCount[high - k] == 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return high;
            }
        }
        // Wheel: A counts as 1.
        if (rankCount[14] > 0 && rankCount[5] > 0 && rankCount[4] > 0
                && rankCount[3] > 0 && rankCount[2] > 0) {
            return 5;
        }
        return 0;
    }
}
