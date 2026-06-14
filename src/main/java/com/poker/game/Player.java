package com.poker.game;

import com.poker.engine.Card;

import java.util.ArrayList;
import java.util.List;

/** A seated player. Holds both room-level state (stack, leaving) and per-hand state. */
public class Player {

    public final long userId;
    public String name;
    /** Monotonic join sequence — used for seating and owner succession. */
    public final long joinOrder;
    public long stack;

    // --- per-hand state ---
    public boolean inHand;
    public boolean folded;
    public boolean allIn;
    public long streetCommitted;
    public long totalCommitted;
    public boolean hasActedThisStreet;
    public final List<Card> hole = new ArrayList<>(2);

    // --- room state ---
    /** True when the player joined while a hand was running; they are dealt in next hand. */
    public boolean sittingOut;
    /** True when the player asked to leave; they are removed once the current hand ends. */
    public boolean wantsLeave;
    /** 0 = next timeout auto-folds, 1 = next timeout kicks from the room. */
    public int timeoutStrikes;

    public Player(long userId, String name, long joinOrder, long stack) {
        this.userId = userId;
        this.name = name;
        this.joinOrder = joinOrder;
        this.stack = stack;
    }

    /** Clears per-hand state in preparation for a new deal. */
    public void resetForHand() {
        inHand = false;
        folded = false;
        allIn = false;
        streetCommitted = 0;
        totalCommitted = 0;
        hasActedThisStreet = false;
        hole.clear();
    }

    public boolean isActive() {
        return inHand && !folded;
    }
}
