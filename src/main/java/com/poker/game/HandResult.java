package com.poker.game;

import com.poker.engine.Card;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The outcome of a single hand: who won which pot, refunds, and showdown reveals. */
public class HandResult {

    /** One pot (main or side) and who won it. */
    public static class PotAward {
        public final String label;     // "Main pot", "Side pot 1", ...
        public final long amount;
        public final List<Long> winners = new ArrayList<>();
        public String handDesc;         // winning hand category, or "(uncontested)"

        public PotAward(String label, long amount) {
            this.label = label;
            this.amount = amount;
        }
    }

    /** A player's revealed hand at showdown. */
    public static class Reveal {
        public final long userId;
        public final List<Card> hole;
        public final String handDesc;

        public Reveal(long userId, List<Card> hole, String handDesc) {
            this.userId = userId;
            this.hole = hole;
            this.handDesc = handDesc;
        }
    }

    public final List<PotAward> awards = new ArrayList<>();
    public final Map<Long, Long> refunds = new LinkedHashMap<>();
    public final List<Reveal> reveals = new ArrayList<>();
    public boolean showdown;
    public List<Card> board = new ArrayList<>();
}
