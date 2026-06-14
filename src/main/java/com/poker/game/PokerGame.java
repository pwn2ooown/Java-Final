package com.poker.game;

import com.poker.engine.Card;
import com.poker.engine.Deck;
import com.poker.engine.HandEvaluator;
import com.poker.engine.HandValue;
import com.poker.engine.PotManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pure game-logic for one table of No-Limit Texas Hold'em. Knows nothing about
 * Discord. The surrounding session drives it: deal a hand, feed player actions,
 * advance streets, settle. Not thread-safe on its own — callers serialize access.
 */
public class PokerGame {

    public final long smallBlind;
    public final long bigBlind;
    public final long buyIn;

    private final List<Player> players = new ArrayList<>(); // seat order
    private final List<Card> board = new ArrayList<>(5);
    private final Set<Long> actedSinceFullRaise = new HashSet<>();
    private final Random rng;

    /** Seat order (user ids) of the previous hand — needed to advance the big blind
     *  correctly even when the players around it busted (dead button rule). */
    private final List<Long> prevSeatOrder = new ArrayList<>();
    private long lastBigBlindUserId = -1;

    private Deck deck;
    private Street street;
    private int buttonPos = -1;
    private int sbPos = -1;   // -1 = dead small blind this hand
    private int bbPos = -1;
    private int currentActorIndex = -1;

    private long currentBet;
    private long lastFullRaiseSize;
    /** Bet level of the last FULL bet/raise — cumulative short all-ins re-open
     *  the betting once they total a full raise above this (TDA rule 47-A). */
    private long lastFullBetTo;
    private int handNumber = 0;
    private boolean handInProgress = false;

    public PokerGame(long smallBlind, long bigBlind, long buyIn, Random rng) {
        if (smallBlind <= 0 || bigBlind < smallBlind || buyIn < bigBlind) {
            throw new IllegalArgumentException(
                    "Invalid stakes: need 0 < smallBlind <= bigBlind <= buyIn (got "
                            + smallBlind + "/" + bigBlind + "/" + buyIn + ").");
        }
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.rng = rng;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    public List<Player> seats() {
        return players;
    }

    public Player playerById(long userId) {
        for (Player p : players) {
            if (p.userId == userId) {
                return p;
            }
        }
        return null;
    }

    private int indexOf(Player p) {
        return players.indexOf(p);
    }

    private int indexOfUser(long userId) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).userId == userId) {
                return i;
            }
        }
        return -1;
    }

    /** Adds a player. If a hand is running they sit out until the next deal. */
    public Player addPlayer(long userId, String name, long joinOrder) {
        Player p = new Player(userId, name, joinOrder, buyIn);
        p.sittingOut = handInProgress;
        players.add(p);
        return p;
    }

    /**
     * Removes leavers and busted players right away (normally startHand does it
     * before the next deal). Used when the room drops to WAITING so a busted
     * player can re-join with a fresh buy-in. Returns the removed players.
     */
    public List<Player> pruneOut() {
        List<Player> removed = new ArrayList<>();
        for (Player p : players) {
            if (p.wantsLeave || p.stack <= 0) {
                removed.add(p);
            }
        }
        players.removeAll(removed);
        return removed;
    }

    /** Randomizes the seating order. Call once, before the first hand. */
    public void shuffleSeats() {
        Collections.shuffle(players, rng);
    }

    // ------------------------------------------------------------------
    // Hand lifecycle
    // ------------------------------------------------------------------

    public boolean handInProgress() {
        return handInProgress;
    }

    public int handNumber() {
        return handNumber;
    }

    public Street street() {
        return street;
    }

    public List<Card> board() {
        return board;
    }

    public long buttonUserId() {
        return (buttonPos >= 0 && buttonPos < players.size()) ? players.get(buttonPos).userId : -1;
    }

    /** True when nobody posts the small blind this hand (last hand's BB busted — dead button rule). */
    public boolean smallBlindDead() {
        return handInProgress && sbPos < 0;
    }

    /** User ID of the small blind, or -1 when the small blind is dead this hand. */
    public long smallBlindUserId() {
        return (sbPos >= 0 && sbPos < players.size()) ? players.get(sbPos).userId : -1;
    }

    public long bigBlindUserId() {
        return (bbPos >= 0 && bbPos < players.size()) ? players.get(bbPos).userId : -1;
    }

    /** Players that can be dealt into a new hand (have chips, not leaving). */
    public List<Player> eligibleForHand() {
        List<Player> list = new ArrayList<>();
        for (Player p : players) {
            if (!p.wantsLeave && p.stack > 0) {
                list.add(p);
            }
        }
        return list;
    }

    /**
     * Begins a new hand: removes leavers/busted players, rotates the button,
     * posts blinds and deals hole cards.
     *
     * @throws IllegalStateException if fewer than two players can play
     */
    public void startHand() {
        players.removeIf(p -> p.wantsLeave || p.stack <= 0);
        if (players.size() < 2) {
            throw new IllegalStateException("Need at least 2 players with chips to start a hand.");
        }
        for (Player p : players) {
            p.resetForHand();
            p.sittingOut = false;
            p.inHand = true;
        }

        computePositions();
        deck = new Deck(rng);
        board.clear();
        actedSinceFullRaise.clear();
        street = Street.PREFLOP;

        int n = players.size();

        // Deal two hole cards each, starting left of the button — heads-up the
        // big blind gets the first card and the button the last (TDA rule 34-B).
        int dealStart = (buttonPos + 1) % n;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < n; i++) {
                players.get((dealStart + i) % n).hole.add(deck.draw());
            }
        }

        if (sbPos >= 0) {
            postBlind(players.get(sbPos), smallBlind);
        }
        postBlind(players.get(bbPos), bigBlind);
        currentBet = bigBlind;
        lastFullRaiseSize = bigBlind;
        lastFullBetTo = bigBlind;

        // First to act pre-flop is the player after the big blind — this also
        // covers heads-up, where that wraps around to the button/small blind.
        currentActorIndex = nextActorFrom((bbPos + 1) % n);

        prevSeatOrder.clear();
        for (Player p : players) {
            prevSeatOrder.add(p.userId);
        }
        lastBigBlindUserId = players.get(bbPos).userId;

        handInProgress = true;
        handNumber++;
    }

    /**
     * Positions the blinds and button using the <em>dead button</em> rule (TDA
     * rule 32): the big blind advances exactly one surviving player every hand —
     * nobody is skipped and nobody pays it twice in a row. The small blind is
     * last hand's big-blind player if still seated, otherwise it is <em>dead</em>
     * (nobody posts it). The button is the last surviving seat before the blinds,
     * which keeps last action with the previous button player when the small
     * blind busted (the "dead button"). Heads-up the non-BB player is always
     * small blind and button (TDA rule 34-B).
     */
    private void computePositions() {
        int n = players.size();
        int bbIdx = -1;
        int start = prevSeatOrder.indexOf(lastBigBlindUserId);
        if (start >= 0) {
            // Walk last hand's seat order, starting after the old big blind;
            // the first player still seated is due the big blind now.
            for (int k = 1; k < prevSeatOrder.size() && bbIdx < 0; k++) {
                long id = prevSeatOrder.get((start + k) % prevSeatOrder.size());
                if (id != lastBigBlindUserId) {
                    bbIdx = indexOfUser(id);
                }
            }
        }
        if (bbIdx < 0) {
            int oldBbIdx = indexOfUser(lastBigBlindUserId);
            if (oldBbIdx >= 0) {
                // Everyone else from last hand is gone (replaced by new joiners):
                // the old BB becomes the small blind, the next seat the big blind.
                bbIdx = (oldBbIdx + 1) % n;
            } else {
                // First hand of the game: button at seat 0.
                buttonPos = 0;
                sbPos = (n == 2) ? 0 : 1;
                bbPos = (n == 2) ? 1 : 2;
                return;
            }
        }
        bbPos = bbIdx;
        if (n == 2) {
            sbPos = (bbPos + 1) % 2; // the other player is small blind AND button
            buttonPos = sbPos;
            return;
        }
        sbPos = indexOfUser(lastBigBlindUserId); // -1 = dead small blind (old BB busted)
        int anchor = (sbPos >= 0) ? sbPos : bbPos;
        buttonPos = (anchor - 1 + n) % n;
    }

    private void postBlind(Player p, long amount) {
        commit(p, amount);
    }

    // ------------------------------------------------------------------
    // Betting
    // ------------------------------------------------------------------

    public Player currentActor() {
        return (currentActorIndex >= 0 && currentActorIndex < players.size())
                ? players.get(currentActorIndex) : null;
    }

    public boolean bettingRoundComplete() {
        return currentActorIndex < 0;
    }

    public long currentBet() {
        return currentBet;
    }

    public long callAmountFor(Player p) {
        return Math.max(0, currentBet - p.streetCommitted);
    }

    /**
     * Minimum legal "raise to" total for a full raise (ignoring all-in
     * shortfalls): the current bet plus the largest full bet/raise of the round
     * (TDA rule 43-A). Over a short all-in opening bet this is the short bet
     * plus one full minimum bet.
     */
    public long minRaiseTo() {
        return currentBet + lastFullRaiseSize;
    }

    public long maxToFor(Player p) {
        return p.streetCommitted + p.stack;
    }

    public boolean canRaise(Player p) {
        return p.stack > callAmountFor(p) && !actedSinceFullRaise.contains(p.userId);
    }

    public long totalPot() {
        long sum = 0;
        for (Player p : players) {
            sum += p.totalCommitted;
        }
        return sum;
    }

    public int aliveInHand() {
        int c = 0;
        for (Player p : players) {
            if (p.isActive()) {
                c++;
            }
        }
        return c;
    }

    public int ableToAct() {
        int c = 0;
        for (Player p : players) {
            if (p.inHand && !p.folded && !p.allIn) {
                c++;
            }
        }
        return c;
    }

    private boolean needsToAct(Player p) {
        return p.inHand && !p.folded && !p.allIn
                && (p.streetCommitted < currentBet || !p.hasActedThisStreet);
    }

    private int nextActorFrom(int start) {
        int n = players.size();
        for (int k = 0; k < n; k++) {
            int idx = (start + k) % n;
            if (needsToAct(players.get(idx))) {
                return idx;
            }
        }
        return -1;
    }

    /**
     * Applies an action from {@code userId}. Enforces turn order (the "lock"):
     * a non-current player is rejected. BET and RAISE are normalized against the
     * current bet (a "raise" with no bet outstanding is a bet, and vice versa).
     *
     * @return the action type actually executed (after normalization)
     */
    public ActionType applyAction(long userId, ActionType type, long amountTo) {
        Player p = currentActor();
        if (p == null) {
            throw new InvalidActionException("No action is expected right now.");
        }
        if (p.userId != userId) {
            throw new InvalidActionException("It is not your turn to act.");
        }
        if (type == ActionType.RAISE && currentBet == 0) {
            type = ActionType.BET;
        } else if (type == ActionType.BET && currentBet > 0) {
            type = ActionType.RAISE;
        }
        int actingIndex = currentActorIndex;

        switch (type) {
            case FOLD -> doFold(p);
            case CHECK -> doCheck(p);
            case CALL -> doCall(p);
            case BET -> doBet(p, amountTo);
            case RAISE -> doRaise(p, amountTo);
            case ALL_IN -> doAllIn(p);
            default -> throw new InvalidActionException("Unknown action.");
        }
        p.hasActedThisStreet = true;
        currentActorIndex = nextActorFrom((actingIndex + 1) % players.size());
        return type;
    }

    /** Folds a player even when it is not their turn (used for quit / kick). */
    public void forceFold(long userId) {
        Player p = playerById(userId);
        if (p == null || !p.inHand || p.folded) {
            return;
        }
        boolean wasCurrent = currentActor() == p;
        int idx = indexOf(p);
        p.folded = true;
        p.hasActedThisStreet = true;
        if (wasCurrent) {
            currentActorIndex = nextActorFrom((idx + 1) % players.size());
        }
    }

    private void doFold(Player p) {
        p.folded = true;
        actedSinceFullRaise.add(p.userId);
    }

    private void doCheck(Player p) {
        if (callAmountFor(p) != 0) {
            throw new InvalidActionException("You cannot check — you owe " + callAmountFor(p)
                    + " to call. Use call, raise or fold.");
        }
        actedSinceFullRaise.add(p.userId);
    }

    private void doCall(Player p) {
        long need = callAmountFor(p);
        if (need <= 0) {
            throw new InvalidActionException("There is nothing to call — use check.");
        }
        commit(p, need);
        actedSinceFullRaise.add(p.userId);
    }

    private void doBet(Player p, long amountTo) {
        if (currentBet != 0) {
            throw new InvalidActionException("There is already a bet — use raise.");
        }
        long maxTo = maxToFor(p);
        if (amountTo <= 0) {
            throw new InvalidActionException("Bet amount must be positive.");
        }
        if (amountTo > maxTo) {
            throw new InvalidActionException("You only have " + p.stack + " chips (max bet " + maxTo + ").");
        }
        boolean allInBet = amountTo == maxTo;
        if (!allInBet && amountTo < bigBlind) {
            throw new InvalidActionException("Minimum bet is " + bigBlind + " (1 big blind), or go all-in.");
        }
        commitExact(p, amountTo - p.streetCommitted);
        applyAggression(p, amountTo);
    }

    private void doRaise(Player p, long amountTo) {
        if (currentBet == 0) {
            throw new InvalidActionException("There is no bet to raise — use bet.");
        }
        long maxTo = maxToFor(p);
        if (amountTo > maxTo) {
            throw new InvalidActionException("You only have " + p.stack + " chips (max raise to " + maxTo + ").");
        }
        if (amountTo <= currentBet) {
            throw new InvalidActionException("A raise must be greater than the current bet of " + currentBet + ".");
        }
        boolean allInRaise = amountTo == maxTo;
        if (!allInRaise && amountTo < minRaiseTo()) {
            throw new InvalidActionException("Minimum raise is to " + minRaiseTo()
                    + " (raise of at least " + lastFullRaiseSize + "), or go all-in.");
        }
        if (actedSinceFullRaise.contains(p.userId)) {
            throw new InvalidActionException(
                    "You may only call or fold — the previous all-in did not fully re-open the betting.");
        }
        commitExact(p, amountTo - p.streetCommitted);
        applyAggression(p, amountTo);
    }

    private void doAllIn(Player p) {
        if (p.stack <= 0) {
            throw new InvalidActionException("You have no chips to put in.");
        }
        long allInTo = p.streetCommitted + p.stack;
        if (allInTo > currentBet && currentBet > 0 && actedSinceFullRaise.contains(p.userId)) {
            // Not allowed to re-raise; the all-in can only stand as a call.
            commitExact(p, currentBet - p.streetCommitted);
            actedSinceFullRaise.add(p.userId);
            return;
        }
        commitExact(p, p.stack);
        if (allInTo > currentBet) {
            applyAggression(p, allInTo);
        } else {
            actedSinceFullRaise.add(p.userId);
        }
    }

    /** Shared bookkeeping for a bet/raise: update the call price and re-open (or not) the action. */
    private void applyAggression(Player p, long newBetTo) {
        long increment = newBetTo - currentBet;      // raise over the bet actually faced
        long sinceFull = newBetTo - lastFullBetTo;   // cumulative raise over the last FULL bet
        currentBet = newBetTo;
        if (increment >= lastFullRaiseSize) {
            // A full bet or raise: re-opens the betting and sets the new min-raise size.
            lastFullRaiseSize = increment;
            lastFullBetTo = newBetTo;
            actedSinceFullRaise.clear();
        } else if (sinceFull >= lastFullRaiseSize) {
            // Cumulative short all-ins that total a full raise re-open the betting,
            // but the minimum raise stays the last full bet/raise (TDA rule 47-A).
            lastFullBetTo = newBetTo;
            actedSinceFullRaise.clear();
        }
        // A single incomplete (short all-in) raise leaves the acted set intact.
        actedSinceFullRaise.add(p.userId);
    }

    private void commit(Player p, long desired) {
        commitExact(p, Math.min(desired, p.stack));
    }

    private void commitExact(Player p, long amount) {
        if (amount < 0) {
            amount = 0;
        }
        p.stack -= amount;
        p.streetCommitted += amount;
        p.totalCommitted += amount;
        if (p.stack == 0) {
            p.allIn = true;
        }
    }

    // ------------------------------------------------------------------
    // Street progression
    // ------------------------------------------------------------------

    /** Deals the next community card(s) and opens a fresh betting round. */
    public void dealNextStreet() {
        for (Player p : players) {
            p.streetCommitted = 0;
            p.hasActedThisStreet = false;
        }
        currentBet = 0;
        lastFullRaiseSize = bigBlind;
        lastFullBetTo = 0;
        actedSinceFullRaise.clear();

        switch (street) {
            case PREFLOP -> {
                dealBoard(3);
                street = Street.FLOP;
            }
            case FLOP -> {
                dealBoard(1);
                street = Street.TURN;
            }
            case TURN -> {
                dealBoard(1);
                street = Street.RIVER;
            }
            case RIVER -> throw new IllegalStateException("There is no street after the river.");
        }
        currentActorIndex = nextActorFrom((buttonPos + 1) % players.size());
    }

    private void dealBoard(int count) {
        for (int i = 0; i < count; i++) {
            board.add(deck.draw());
        }
    }

    // ------------------------------------------------------------------
    // Showdown / settlement
    // ------------------------------------------------------------------

    /**
     * Aborts a hand in progress, refunding every player's committed chips
     * (used when the owner force-ends the game mid-hand — nobody wins the pot).
     */
    public void abortHand() {
        if (!handInProgress) {
            return;
        }
        for (Player p : players) {
            if (p.totalCommitted > 0) {
                p.stack += p.totalCommitted;
                p.totalCommitted = 0;
            }
            p.streetCommitted = 0;
        }
        handInProgress = false;
    }

    /**
     * Computes side pots, awards them to the best hands, refunds uncalled bets,
     * and credits stacks. {@code reveal} controls whether showdown cards are
     * recorded in the result.
     */
    public HandResult settle(boolean reveal) {
        HandResult result = new HandResult();
        result.showdown = reveal;
        result.board = new ArrayList<>(board);

        Map<Integer, Long> contributions = new HashMap<>();
        Set<Integer> folded = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (p.totalCommitted > 0) {
                contributions.put(i, p.totalCommitted);
            }
            if (p.folded || !p.inHand) {
                folded.add(i);
            }
        }

        PotManager.Result pots = PotManager.compute(contributions, folded);

        // Eligibility only shrinks as the layers rise, so pots without a live
        // claimant (every contributor folded — possible when players quit or get
        // kicked mid-hand) form a suffix. Folding forfeits those chips to the
        // remaining live hands: merge them into the last pot that has one.
        int lastEligible = -1;
        for (int i = 0; i < pots.pots.size(); i++) {
            if (!pots.pots.get(i).eligible.isEmpty()) {
                lastEligible = i;
            }
        }
        if (lastEligible < 0) {
            // Every hand is dead (all players quit mid-hand): void the hand and
            // give everyone their chips back.
            for (Player p : players) {
                if (p.totalCommitted > 0) {
                    p.stack += p.totalCommitted;
                    result.refunds.merge(p.userId, p.totalCommitted, Long::sum);
                }
            }
            handInProgress = false;
            return result;
        }
        long orphaned = 0;
        for (int i = lastEligible + 1; i < pots.pots.size(); i++) {
            orphaned += pots.pots.get(i).amount;
        }

        // Refund uncalled bets first.
        for (Map.Entry<Integer, Long> e : pots.refunds.entrySet()) {
            Player p = players.get(e.getKey());
            p.stack += e.getValue();
            result.refunds.merge(p.userId, e.getValue(), Long::sum);
        }

        Map<Integer, HandValue> handCache = new HashMap<>();
        int n = players.size();
        for (int i = 0; i <= lastEligible; i++) {
            PotManager.Pot pot = pots.pots.get(i);
            long amount = pot.amount + (i == lastEligible ? orphaned : 0);
            String label = (i == 0) ? "Main pot" : "Side pot " + i;
            HandResult.PotAward award = new HandResult.PotAward(label, amount);

            List<Integer> winners = bestAmong(pot.eligible, handCache);
            award.handDesc = (pot.eligible.size() == 1)
                    ? "(uncontested)"
                    : handCache.get(winners.get(0)).describe();

            // Split, distributing odd chips to the earliest seats left of the button.
            long share = amount / winners.size();
            long remainder = amount % winners.size();
            List<Integer> order = orderFromButton(winners);
            for (int w = 0; w < order.size(); w++) {
                int seat = order.get(w);
                long amt = share + (w < remainder ? 1 : 0);
                Player winner = players.get(seat);
                winner.stack += amt;
                award.winners.add(winner.userId);
                award.payouts.merge(winner.userId, amt, Long::sum);
            }
            result.awards.add(award);
        }

        if (reveal) {
            for (int i = 0; i < n; i++) {
                Player p = players.get(i);
                if (p.isActive()) {
                    HandValue hv = handValue(i, handCache);
                    result.reveals.add(new HandResult.Reveal(
                            p.userId, new ArrayList<>(p.hole), hv.describe(),
                            new ArrayList<>(hv.bestFive())));
                }
            }
        }

        handInProgress = false;
        return result;
    }

    private List<Integer> bestAmong(Set<Integer> seats, Map<Integer, HandValue> cache) {
        if (seats.size() == 1) {
            return new ArrayList<>(seats);
        }
        List<Integer> winners = new ArrayList<>();
        HandValue best = null;
        for (int seat : seats) {
            HandValue hv = handValue(seat, cache);
            if (best == null || hv.compareTo(best) > 0) {
                best = hv;
                winners.clear();
                winners.add(seat);
            } else if (hv.compareTo(best) == 0) {
                winners.add(seat);
            }
        }
        return winners;
    }

    private HandValue handValue(int seat, Map<Integer, HandValue> cache) {
        return cache.computeIfAbsent(seat, s -> {
            List<Card> all = new ArrayList<>(board);
            all.addAll(players.get(s).hole);
            return HandEvaluator.evaluate(all);
        });
    }

    private List<Integer> orderFromButton(List<Integer> seats) {
        int n = players.size();
        int leftOfButton = (buttonPos + 1) % n;
        List<Integer> copy = new ArrayList<>(seats);
        copy.sort((a, b) -> Integer.compare((a - leftOfButton + n) % n, (b - leftOfButton + n) % n));
        return copy;
    }
}
