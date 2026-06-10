package com.poker.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.security.SecureRandom;

/** A standard 52-card deck, shuffled on construction. */
public class Deck {

    private final Deque<Card> cards = new ArrayDeque<>(52);

    public Deck() {
        this(new SecureRandom());
    }

    public Deck(Random rng) {
        List<Card> list = new ArrayList<>(52);
        for (Suit s : Suit.values()) {
            for (Rank r : Rank.values()) {
                list.add(new Card(r, s));
            }
        }
        Collections.shuffle(list, rng);
        cards.addAll(list);
    }

    /** Draw the top card. */
    public Card draw() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("Deck is empty");
        }
        return cards.pop();
    }

    public int remaining() {
        return cards.size();
    }
}
