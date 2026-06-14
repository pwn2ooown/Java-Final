package com.poker.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits the chips committed during a hand into a main pot and any side pots,
 * and returns uncalled bets that must be refunded.
 *
 * <p>Players are identified by an opaque integer key (their seat index in the
 * game). The algorithm peels the contributions layer by layer:
 * <ul>
 *   <li>If only one player still has chips in the current layer, that amount was
 *       an uncalled bet and is refunded to them.</li>
 *   <li>Otherwise every contributor at that layer matches the smallest remaining
 *       contribution, forming one pot whose eligible winners are the
 *       <em>non-folded</em> contributors at that layer.</li>
 * </ul>
 * Adjacent pots with identical eligibility are merged for tidiness.
 */
public final class PotManager {

    private PotManager() {
    }

    /** A single (main or side) pot and the seats eligible to win it. */
    public static final class Pot {
        public final long amount;
        public final Set<Integer> eligible;

        public Pot(long amount, Set<Integer> eligible) {
            this.amount = amount;
            this.eligible = eligible;
        }
    }

    public static final class Result {
        public final List<Pot> pots;
        public final Map<Integer, Long> refunds;

        public Result(List<Pot> pots, Map<Integer, Long> refunds) {
            this.pots = pots;
            this.refunds = refunds;
        }
    }

    /**
     * @param contributions seat -&gt; total chips that seat put into the pot this hand
     * @param folded        seats that folded (they still funded pots but cannot win)
     */
    public static Result compute(Map<Integer, Long> contributions, Set<Integer> folded) {
        Map<Integer, Long> contrib = new HashMap<>();
        for (Map.Entry<Integer, Long> e : contributions.entrySet()) {
            if (e.getValue() > 0) {
                contrib.put(e.getKey(), e.getValue());
            }
        }

        List<Pot> pots = new ArrayList<>();
        Map<Integer, Long> refunds = new HashMap<>();

        while (true) {
            List<Integer> positives = new ArrayList<>();
            for (Map.Entry<Integer, Long> e : contrib.entrySet()) {
                if (e.getValue() > 0) {
                    positives.add(e.getKey());
                }
            }
            if (positives.isEmpty()) {
                break;
            }
            if (positives.size() == 1) {
                int only = positives.get(0);
                refunds.merge(only, contrib.get(only), Long::sum);
                contrib.put(only, 0L);
                break;
            }

            long min = Long.MAX_VALUE;
            for (int p : positives) {
                min = Math.min(min, contrib.get(p));
            }
            long amount = min * positives.size();
            Set<Integer> eligible = new HashSet<>();
            for (int p : positives) {
                if (!folded.contains(p)) {
                    eligible.add(p);
                }
                contrib.put(p, contrib.get(p) - min);
            }
            pots.add(new Pot(amount, eligible));
        }

        // Merge adjacent pots that have identical eligibility.
        List<Pot> merged = new ArrayList<>();
        for (Pot pot : pots) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).eligible.equals(pot.eligible)) {
                Pot last = merged.remove(merged.size() - 1);
                merged.add(new Pot(last.amount + pot.amount, last.eligible));
            } else {
                merged.add(pot);
            }
        }
        return new Result(merged, refunds);
    }
}
