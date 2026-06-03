package com.poker.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandEvaluatorTest {

    private static Card c(String s) {
        char r = s.charAt(0);
        char suit = s.charAt(s.length() - 1);
        Rank rank = switch (r) {
            case '2' -> Rank.TWO;
            case '3' -> Rank.THREE;
            case '4' -> Rank.FOUR;
            case '5' -> Rank.FIVE;
            case '6' -> Rank.SIX;
            case '7' -> Rank.SEVEN;
            case '8' -> Rank.EIGHT;
            case '9' -> Rank.NINE;
            case 'T' -> Rank.TEN;
            case 'J' -> Rank.JACK;
            case 'Q' -> Rank.QUEEN;
            case 'K' -> Rank.KING;
            case 'A' -> Rank.ACE;
            default -> throw new IllegalArgumentException("bad rank " + r);
        };
        Suit su = switch (suit) {
            case 's' -> Suit.SPADES;
            case 'h' -> Suit.HEARTS;
            case 'd' -> Suit.DIAMONDS;
            case 'c' -> Suit.CLUBS;
            default -> throw new IllegalArgumentException("bad suit " + suit);
        };
        return new Card(rank, su);
    }

    private static HandValue ev(String... cards) {
        List<Card> list = new ArrayList<>();
        for (String s : cards) {
            list.add(c(s));
        }
        return HandEvaluator.evaluate(list);
    }

    @Test
    void royalFlushBeatsQuads() {
        HandValue rf = ev("As", "Ks", "Qs", "Js", "Ts", "2d", "3c");
        HandValue quads = ev("Ah", "Ad", "Ac", "As", "Kd", "2c", "3h");
        assertEquals(HandCategory.STRAIGHT_FLUSH, rf.category());
        assertEquals(HandCategory.FOUR_OF_A_KIND, quads.category());
        assertTrue(rf.compareTo(quads) > 0);
    }

    @Test
    void wheelIsFiveHighStraight() {
        HandValue wheel = ev("Ah", "2d", "3c", "4s", "5h", "Kd", "Qc");
        HandValue sixHigh = ev("2h", "3d", "4c", "5s", "6h", "Kd", "Qc");
        assertEquals(HandCategory.STRAIGHT, wheel.category());
        assertEquals(HandCategory.STRAIGHT, sixHigh.category());
        assertTrue(sixHigh.compareTo(wheel) > 0);
    }

    @Test
    void fullHouseBeatsFlush() {
        HandValue fh = ev("Ah", "Ad", "As", "Kd", "Kc", "2h", "3h");
        HandValue flush = ev("2s", "5s", "8s", "Js", "Ks", "Ad", "3c");
        assertEquals(HandCategory.FULL_HOUSE, fh.category());
        assertEquals(HandCategory.FLUSH, flush.category());
        assertTrue(fh.compareTo(flush) > 0);
    }

    @Test
    void twoPairBrokenByKicker() {
        HandValue qKicker = ev("Ah", "Ad", "Kh", "Kd", "Qs", "2c", "3c");
        HandValue jKicker = ev("Ah", "Ad", "Kh", "Kd", "Js", "2c", "3c");
        assertEquals(HandCategory.TWO_PAIR, qKicker.category());
        assertTrue(qKicker.compareTo(jKicker) > 0);
    }

    @Test
    void flushOutranksStraight() {
        // Spades 2-3-4-5-7 is a flush; 2-3-4-5-6 (with 6d) is only a straight.
        HandValue v = ev("2s", "3s", "4s", "5s", "7s", "6d", "8c");
        assertEquals(HandCategory.FLUSH, v.category());
    }

    @Test
    void identicalHandsTie() {
        HandValue a = ev("Ah", "Kh", "Qs", "Jd", "Tc", "2s", "3s");
        HandValue b = ev("Ad", "Kd", "Qh", "Jh", "Ts", "4s", "5s");
        assertEquals(0, a.compareTo(b));
    }
}
