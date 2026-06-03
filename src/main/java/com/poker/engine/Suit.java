package com.poker.engine;

/** The four card suits. {@code symbol} is used for human-readable rendering. */
public enum Suit {
    SPADES('s', "♠"),   // ♠
    HEARTS('h', "♥"),   // ♥
    DIAMONDS('d', "♦"), // ♦
    CLUBS('c', "♣");    // ♣

    private final char code;
    private final String symbol;

    Suit(char code, String symbol) {
        this.code = code;
        this.symbol = symbol;
    }

    public char code() {
        return code;
    }

    public String symbol() {
        return symbol;
    }
}
