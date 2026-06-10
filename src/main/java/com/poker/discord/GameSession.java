package com.poker.discord;

import com.poker.engine.Card;
import com.poker.engine.HandEvaluator;
import com.poker.engine.HandValue;
import com.poker.game.ActionType;
import com.poker.game.HandResult;
import com.poker.game.InvalidActionException;
import com.poker.game.Player;
import com.poker.game.PokerGame;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * One poker room. Owns a {@link PokerGame}, the private Discord thread it is
 * played in, and the flow that ties player interactions to game progress.
 *
 * <p>Concurrency: every mutation runs on a single-threaded executor, so the game
 * is effectively single-threaded. Discord events are acknowledged on the JDA
 * thread and then handed to this executor.
 */
public class GameSession {

    private static final Logger log = LoggerFactory.getLogger(GameSession.class);
    private static final long ACTION_TIMEOUT_SECONDS = 30;
    private static final long REMINDER_BEFORE_SECONDS = 10;
    private static final long RESULT_LINGER_SECONDS = 10;

    private final GameManager manager;
    private final long guildId;
    private final long parentChannelId;
    private final String ownerName;
    private final long sb;
    private final long bb;
    private final long buyIn;
    private final long roomDbId;
    private final PokerGame game;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "poker-session");
        t.setDaemon(true);
        return t;
    });

    private final List<Long> handMessageIds = new ArrayList<>();
    private final Map<Long, List<Card>> lastHoleCards = new ConcurrentHashMap<>();
    private final Set<Long> shownCards = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> lastActions = new ConcurrentHashMap<>();
    private volatile long threadId;
    private volatile long ownerId;

    private long actionMessageId;
    private int actionSeq;
    private ScheduledFuture<?> timeoutTask;
    private ScheduledFuture<?> reminderTask;
    private long joinCounter;

    private volatile boolean started;
    private volatile boolean stopRequested;
    private volatile boolean ended;

    public GameSession(GameManager manager, long guildId, long parentChannelId,
                       long ownerId, String ownerName, long sb, long bb, long buyIn) {
        this.manager = manager;
        this.guildId = guildId;
        this.parentChannelId = parentChannelId;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.sb = sb;
        this.bb = bb;
        this.buyIn = buyIn;
        this.game = new PokerGame(sb, bb, buyIn, new Random());
        this.roomDbId = manager.db().createRoom(str(guildId), str(parentChannelId), str(ownerId), sb, bb, buyIn);
    }

    public long parentChannelId() {
        return parentChannelId;
    }

    public long threadId() {
        return threadId;
    }

    // ------------------------------------------------------------------
    // Public entry points (called from the listener; each runs on exec)
    // ------------------------------------------------------------------

    public void init(InteractionHook hook) {
        exec.execute(() -> doInit(hook));
    }

    public void onJoin(long userId, String name, InteractionHook hook) {
        exec.execute(() -> {
            try {
                doJoin(userId, name, hook);
            } catch (Exception e) {
                log.error("join failed", e);
                ephem(hook, "⚠️ Could not join the room.");
            }
        });
    }

    public void onStart(long userId, InteractionHook hook) {
        exec.execute(() -> {
            try {
                doStart(userId, hook);
            } catch (Exception e) {
                log.error("start failed", e);
                ephem(hook, "⚠️ Could not start the game.");
            }
        });
    }

    public void onLeave(long userId, InteractionHook hook) {
        exec.execute(() -> {
            try {
                doLeave(userId, hook);
            } catch (Exception e) {
                log.error("leave failed", e);
                ephem(hook, "⚠️ Could not process leave.");
            }
        });
    }

    public void onEnd(long userId, InteractionHook hook) {
        exec.execute(() -> {
            if (userId != ownerId) {
                ephem(hook, "Only the room owner can end the game.");
                return;
            }
            stopRequested = true;
            if (!game.handInProgress()) {
                ephem(hook, "🛑 Ending the game.");
                endGame("🛑 Game ended by the owner.");
            } else {
                ephem(hook, "🛑 The game will stop after the current hand.");
                postRoom("🛑 The owner ended the game — it will stop after this hand.");
            }
        });
    }

    public void onForceEnd(long userId, InteractionHook hook) {
        exec.execute(() -> {
            if (userId != ownerId) {
                ephem(hook, "Only the room owner can force-end the game.");
                return;
            }
            ephem(hook, "🛑 Force-ending now.");
            endGame("🛑 Game force-ended by the owner.");
        });
    }

    public void onStatus(long userId, InteractionHook hook) {
        exec.execute(() -> {
            if (started && game.handInProgress()) {
                String status = tableText();
                if (!game.board().isEmpty()) {
                    try {
                        byte[] img = CardRenderer.renderCards(game.board());
                        hook.sendMessage(status)
                                .addFiles(FileUpload.fromData(img, "board.png"))
                                .queue(s -> {}, e -> {});
                        return;
                    } catch (Exception ignored) {}
                }
                ephem(hook, status);
            } else {
                ephem(hook, "Room is waiting. Seated players: " + game.seats().size()
                        + ". Owner: " + mention(ownerId) + ". Use `/poker start` to begin.");
            }
        });
    }

    public void onViewCards(long userId, InteractionHook hook) {
        exec.execute(() -> {
            Player p = game.playerById(userId);
            if (p == null) {
                ephem(hook, "You are not seated in this room.");
            } else if (p.hole.isEmpty()) {
                ephem(hook, "You have no cards right now.");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("🂠 Your hole cards: **").append(cards(p.hole)).append("**");
                if (!game.board().isEmpty()) {
                    List<Card> all = new ArrayList<>(game.board());
                    all.addAll(p.hole);
                    if (all.size() >= 5) {
                        HandValue hv = HandEvaluator.evaluate(all);
                        sb.append("\n🃏 Current best hand: **").append(hv.describe())
                          .append("** (").append(hv.bestFiveStr()).append(")");
                    }
                }
                try {
                    byte[] holeImg = CardRenderer.renderCards(p.hole);
                    hook.sendMessage(sb.toString())
                            .addFiles(FileUpload.fromData(holeImg, "hole.png"))
                            .queue(s -> {}, e -> {});
                } catch (Exception e) {
                    ephem(hook, sb.toString());
                }
            }
        });
    }

    public void onShowCards(long userId, String choice, InteractionHook hook) {
        exec.execute(() -> {
            List<Card> hole = lastHoleCards.get(userId);
            if (hole == null || hole.isEmpty()) {
                ephem(hook, "You have no cards to show.");
                return;
            }
            if (!shownCards.add(userId)) {
                ephem(hook, "You already showed your cards.");
                return;
            }
            List<Card> toShow;
            switch (choice) {
                case "card1" -> toShow = List.of(hole.get(0));
                case "card2" -> toShow = hole.size() > 1 ? List.of(hole.get(1)) : List.of(hole.get(0));
                default -> toShow = hole;
            }
            String label = cards(toShow);
            try {
                byte[] img = CardRenderer.renderCards(toShow);
                ThreadChannel t = thread();
                if (t != null) {
                    t.sendMessage("🃏 " + mention(userId) + " shows: **" + label + "**")
                            .addFiles(FileUpload.fromData(img, "shown.png"))
                            .queue(s -> {}, e -> {});
                }
                ephem(hook, "✅ Cards shown!");
            } catch (Exception e) {
                postRoom("🃏 " + mention(userId) + " shows: **" + label + "**");
                ephem(hook, "✅ Cards shown!");
            }
        });
    }

    public void onAction(long userId, ActionType type, long amount, InteractionHook hook) {
        exec.execute(() -> {
            try {
                if (ended) {
                    ephem(hook, "This room is closed.");
                    return;
                }
                if (!started || !game.handInProgress()) {
                    ephem(hook, "No hand is in progress.");
                    return;
                }
                ActionType t = type;
                if (t == ActionType.RAISE && game.currentBet() == 0) {
                    t = ActionType.BET;
                } else if (t == ActionType.BET && game.currentBet() > 0) {
                    t = ActionType.RAISE;
                }
                Player self = game.playerById(userId);
                long beforeStack = self == null ? 0 : self.stack;
                log.debug("Action: user={} type={} amount={} stack={}", userId, t, amount, beforeStack);
                game.applyAction(userId, t, amount);
                // A voluntary action clears any timeout warning (strikes count only consecutively).
                if (self != null) {
                    self.timeoutStrikes = 0;
                }
                long committed = self == null ? 0 : beforeStack - self.stack;
                boolean nowAllIn = self != null && self.stack == 0;
                cancelTimeout();
                lastActions.put(userId, actionShort(t, amount, committed, nowAllIn));
                proceed();
            } catch (InvalidActionException e) {
                ephemExplicit(hook, "❌ " + e.getMessage());
            } catch (Exception e) {
                log.error("action failed", e);
                ephemExplicit(hook, "⚠️ Internal error processing your action.");
            }
        });
    }

    // ------------------------------------------------------------------
    // Implementations (already on exec)
    // ------------------------------------------------------------------

    private void doInit(InteractionHook hook) {
        try {
            log.info("Opening room: guild={} parentChannel={} owner={} ({})",
                    guildId, parentChannelId, ownerName, ownerId);
            TextChannel parent = manager.jda().getTextChannelById(parentChannelId);
            if (parent == null) {
                log.warn("doInit: text channel {} not visible to the bot (missing access or not a guild member)",
                        parentChannelId);
                ephem(hook, "⚠️ I can no longer see this channel.");
                manager.unregister(this);
                return;
            }
            Player owner = game.addPlayer(ownerId, ownerName, ++joinCounter);
            manager.db().upsertPlayer(roomDbId, str(ownerId), ownerName, owner.joinOrder, owner.stack);

            String roomCode = UUID.randomUUID().toString().substring(0, 8);
            ThreadChannel thread = parent.createThreadChannel("game-" + roomCode, true).complete();
            threadId = thread.getIdLong();
            log.info("Created private thread {} (game-{}) for room (parent {})", threadId, roomCode, parentChannelId);
            manager.registerThread(threadId, this);
            manager.db().setThread(roomDbId, str(threadId));

            thread.addThreadMemberById(ownerId).queue(s -> {
            }, e -> {
            });
            thread.sendMessage("👋 Welcome " + mention(ownerId) + "! This private thread is your table.\n"
                    + "Other players join from the buttons in <#" + parentChannelId + "> or with `/poker join` in this thread.").queue();

            parent.sendMessage("🃏 **Poker table opened by " + mention(ownerId) + "** → <#" + threadId + ">\n"
                            + "Buy-in **" + buyIn + "** • Blinds **" + sb + "/" + bb + "**\n"
                            + "Press **Join** to take a seat — the owner presses **Start** when everyone is in.")
                    .setComponents(ActionRow.of(
                            Button.success("room:join:" + threadId, "Join"),
                            Button.primary("room:start:" + threadId, "Start")))
                    .queue();

            ephem(hook, "✅ Table created: <#" + threadId + ">");
        } catch (Exception e) {
            log.error("Failed to create room", e);
            ephem(hook, "⚠️ I couldn't create the private thread. I need **Create Private Threads**, "
                    + "**Send Messages in Threads** and **Manage Threads** permissions in this channel.");
            manager.unregister(this);
            manager.db().closeRoom(roomDbId);
        }
    }

    private void doJoin(long userId, String name, InteractionHook hook) {
        if (ended) {
            ephem(hook, "This room is closed.");
            return;
        }
        Player existing = game.playerById(userId);
        if (existing != null) {
            if (existing.wantsLeave) {
                existing.wantsLeave = false;
                addToThread(userId);
                ephem(hook, "✅ You rejoined the room.");
                postRoom(mention(userId) + " rejoined.");
            } else {
                ephem(hook, "You are already seated in this room.");
            }
            return;
        }
        Player p = game.addPlayer(userId, name, ++joinCounter);
        addToThread(userId);
        manager.db().upsertPlayer(roomDbId, str(userId), name, p.joinOrder, p.stack);
        if (p.sittingOut) {
            postRoom(mention(userId) + " joined — will be dealt in next hand.");
            ephem(hook, "✅ Joined! You'll be dealt in next hand. Table: <#" + threadId + ">");
        } else {
            postRoom(mention(userId) + " joined the table. (" + game.eligibleForHand().size() + " players)");
            ephem(hook, "✅ Joined! Table: <#" + threadId + ">");
        }
    }

    private void doStart(long userId, InteractionHook hook) {
        if (ended) {
            ephem(hook, "This room is closed.");
            return;
        }
        if (userId != ownerId) {
            ephem(hook, "Only the room owner can start the game.");
            return;
        }
        if (started) {
            ephem(hook, "The game has already started.");
            return;
        }
        if (game.eligibleForHand().size() < 2) {
            ephem(hook, "You need at least 2 players to start.");
            return;
        }
        game.shuffleSeats();
        started = true;
        stopRequested = false;
        ephem(hook, "✅ Starting the game!");
        postRoom("🎲 **Game starting** with " + game.eligibleForHand().size() + " players. Seats randomized!");
        beginHand();
    }

    private void doLeave(long userId, InteractionHook hook) {
        Player p = game.playerById(userId);
        if (p == null) {
            ephem(hook, "You are not seated in this room.");
            return;
        }
        p.wantsLeave = true;
        boolean wasActor = game.currentActor() != null && game.currentActor().userId == userId;
        if (game.handInProgress() && p.inHand && !p.folded) {
            game.forceFold(userId);
        }
        removeFromThread(userId);
        ephem(hook, "👋 You left the room.");
        postRoom(mention(userId) + " left the table" + (game.handInProgress() ? " (folded this hand)" : "") + ".");

        if (game.handInProgress()) {
            if (wasActor || game.aliveInHand() <= 1) {
                cancelTimeout();
                proceed();
            }
        } else {
            reassignOwnerIfNeeded();
            if (game.eligibleForHand().size() < 2 && started) {
                postRoom("⏸️ Not enough players to continue. Waiting for more with `/poker join`.");
            }
        }
    }

    // ------------------------------------------------------------------
    // Hand flow
    // ------------------------------------------------------------------

    private void beginHand() {
        try {
            game.startHand();
        } catch (IllegalStateException e) {
            started = false;
            postRoom("Cannot start a hand: " + e.getMessage());
            return;
        }
        manager.db().setState(roomDbId, "PLAYING");
        handMessageIds.clear();
        actionMessageId = 0;
        lastActions.clear();

        log.info("Hand #{} starting: {} players, button={}",
                game.handNumber(), game.seats().size(), game.buttonUserId());
        postHand("🃏 **Hand #" + game.handNumber() + "** — Dealer button: " + mention(game.buttonUserId())
                + " • Blinds **" + sb + "/" + bb + "**");
        proceed();
    }

    /** Drives the hand forward until it needs a player to act, or the hand is over. */
    private void proceed() {
        while (true) {
            if (ended) {
                return;
            }
            if (game.aliveInHand() <= 1) {
                log.debug("Hand #{}: only {} alive — finishing (no showdown)",
                        game.handNumber(), game.aliveInHand());
                finishHand(false);
                return;
            }
            if (game.bettingRoundComplete()) {
                if (game.street() == com.poker.game.Street.RIVER) {
                    log.debug("Hand #{}: river betting complete — going to showdown", game.handNumber());
                    finishHand(true);
                    return;
                }
                int ableNow = game.ableToAct();
                lastActions.clear();
                game.dealNextStreet();
                log.debug("Hand #{}: dealt {} — board: {} (ableToAct={})",
                        game.handNumber(), game.street(), cardsPlain(game.board()), ableNow);
                postStreetAnnouncement();
                if (ableNow < 2) {
                    log.debug("Hand #{}: all-in run-out from {} to RIVER", game.handNumber(), game.street());
                    while (game.street() != com.poker.game.Street.RIVER) {
                        game.dealNextStreet();
                        log.debug("Hand #{}: dealt {} — board: {}",
                                game.handNumber(), game.street(), cardsPlain(game.board()));
                    }
                    postStreetAnnouncement();
                    finishHand(true);
                    return;
                }
            } else {
                promptCurrentActor();
                return;
            }
        }
    }

    private void promptCurrentActor() {
        Player p = game.currentActor();
        if (p == null) {
            return;
        }
        disablePreviousActionButtons();

        long toCall = game.callAmountFor(p);
        long allInTo = p.streetCommitted + p.stack;
        boolean canRaise = p.stack > toCall;

        StringBuilder content = new StringBuilder();
        content.append(tableText()).append("\n");
        content.append("🎯 ").append(mention(p.userId)).append(" — **YOUR TURN** ⏰ 30s");

        List<Button> buttons = new ArrayList<>();
        if (toCall == 0) {
            buttons.add(Button.primary("act:check", "Check"));
            buttons.add(Button.success("act:raise", "Bet"));
            buttons.add(Button.success("act:allin", "🔺 All-in " + allInTo));
            buttons.add(Button.danger("act:fold", "Fold"));
        } else if (canRaise) {
            buttons.add(Button.primary("act:call", "Call " + toCall));
            buttons.add(Button.success("act:raise", "Raise"));
            buttons.add(Button.success("act:allin", "🔺 All-in " + allInTo));
            buttons.add(Button.danger("act:fold", "Fold"));
        } else {
            buttons.add(Button.primary("act:call",
                    toCall >= p.stack ? "Call all-in " + p.stack : "Call " + toCall));
            buttons.add(Button.danger("act:fold", "Fold"));
        }
        buttons.add(Button.secondary("act:cards", "🂠 View my cards"));

        actionMessageId = postHand(content.toString(), ActionRow.of(buttons));

        int token = ++actionSeq;
        reminderTask = exec.schedule(() -> onReminder(token),
                ACTION_TIMEOUT_SECONDS - REMINDER_BEFORE_SECONDS, TimeUnit.SECONDS);
        timeoutTask = exec.schedule(() -> onTimeout(token), ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void onReminder(int token) {
        if (token != actionSeq || ended) {
            return; // stale or finished
        }
        Player cur = game.currentActor();
        if (cur == null) {
            return;
        }
        postHand("⏰ " + mention(cur.userId) + " — about " + REMINDER_BEFORE_SECONDS
                + " seconds left to act, or you'll be folded automatically.");
    }

    private void onTimeout(int token) {
        if (token != actionSeq || ended) {
            return; // stale timer
        }
        Player cur = game.currentActor();
        if (cur == null) {
            return;
        }
        long uid = cur.userId;
        long toCall = game.callAmountFor(cur);
        if (cur.timeoutStrikes == 0) {
            cur.timeoutStrikes = 1;
            if (toCall == 0) {
                lastActions.put(uid, "check (timeout)");
                postRoom("⏰ " + mention(uid) + " ran out of time — auto-checked. "
                        + "(Warning 1/2 — a second timeout means a kick.)");
                game.applyAction(uid, ActionType.CHECK, 0);
            } else {
                lastActions.put(uid, "fold (timeout)");
                postRoom("⏰ " + mention(uid) + " ran out of time — auto-folded. "
                        + "(Warning 1/2 — a second timeout means a kick.)");
                game.applyAction(uid, ActionType.FOLD, 0);
            }
        } else {
            lastActions.put(uid, "fold (kicked)");
            postRoom("⏰ " + mention(uid) + " timed out again and was **kicked** from the room.");
            game.applyAction(uid, ActionType.FOLD, 0);
            cur.wantsLeave = true;
            removeFromThread(uid);
        }
        cancelTimeout();
        proceed();
    }

    private void finishHand(boolean reveal) {
        cancelTimeout();
        boolean showdown = reveal && game.aliveInHand() >= 2;
        log.info("Hand #{} finished: reveal={} showdown={} board={} pot={}",
                game.handNumber(), reveal, showdown, cardsPlain(game.board()), game.totalPot());

        lastHoleCards.clear();
        shownCards.clear();
        Set<Long> revealedUserIds = ConcurrentHashMap.newKeySet();
        for (Player p : game.seats()) {
            if (p.inHand && !p.hole.isEmpty()) {
                lastHoleCards.put(p.userId, new ArrayList<>(p.hole));
            }
        }

        HandResult result = game.settle(showdown);
        if (showdown) {
            for (HandResult.Reveal rv : result.reveals) {
                log.info("  Showdown — user={} hole={} hand={}",
                        rv.userId, cardsPlain(rv.hole), rv.handDesc);
                revealedUserIds.add(rv.userId);
            }
        }
        for (HandResult.PotAward a : result.awards) {
            log.info("  {} ({}): winners={} desc={}", a.label, a.amount, a.winners, a.handDesc);
        }
        shownCards.addAll(revealedUserIds);
        postResults(result);
        persist(result);

        exec.schedule(() -> {
            cleanupHandMessages();
            afterHand();
        }, RESULT_LINGER_SECONDS, TimeUnit.SECONDS);
    }

    private void afterHand() {
        if (ended) {
            return;
        }
        reassignOwnerIfNeeded();
        for (Player p : new ArrayList<>(game.seats())) {
            if (p.wantsLeave || p.stack <= 0) {
                manager.db().removePlayer(roomDbId, str(p.userId));
            }
        }
        if (stopRequested) {
            endGame("🛑 Game ended by the owner.");
            return;
        }
        if (game.eligibleForHand().size() < 2) {
            started = false;
            manager.db().setState(roomDbId, "WAITING");
            postRoom("⏸️ Not enough players with chips to continue. The room is waiting — "
                    + "use `/poker join` then `/poker start`, or `/poker forceend` to close.");
            return;
        }
        beginHand();
    }

    private void reassignOwnerIfNeeded() {
        Player owner = game.playerById(ownerId);
        boolean ownerGone = owner == null || owner.wantsLeave || owner.stack <= 0;
        if (!ownerGone) {
            return;
        }
        Player next = null;
        for (Player p : game.seats()) {
            if (p.wantsLeave || p.stack <= 0) {
                continue;
            }
            if (next == null || p.joinOrder < next.joinOrder) {
                next = p;
            }
        }
        if (next != null) {
            ownerId = next.userId;
            postRoom("👑 " + mention(ownerId) + " is now the room owner (by join order).");
        }
    }

    private void endGame(String reason) {
        if (ended) {
            return;
        }
        ended = true;
        started = false;
        cancelTimeout();
        postRoom(reason + "\n" + standingsBlock());
        manager.db().closeRoom(roomDbId);
        manager.unregister(this);
        ThreadChannel t = thread();
        if (t != null) {
            t.getManager().setArchived(true).queueAfter(3, TimeUnit.SECONDS, s -> {
            }, e -> {
            });
        }
        exec.schedule(exec::shutdown, 5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persist(HandResult result) {
        try {
            long pot = result.awards.stream().mapToLong(a -> a.amount).sum();
            long handId = manager.db().recordHand(roomDbId, game.handNumber(), cardsPlain(result.board), pot);
            if (handId < 0) {
                return;
            }
            for (HandResult.PotAward award : result.awards) {
                for (var payout : award.payouts.entrySet()) {
                    Player p = game.playerById(payout.getKey());
                    String hole = (p != null) ? cardsPlain(p.hole) : "";
                    manager.db().recordResult(handId, str(payout.getKey()), payout.getValue(), hole, award.handDesc);
                }
            }
            for (var refund : result.refunds.entrySet()) {
                manager.db().recordResult(handId, str(refund.getKey()), refund.getValue(), "",
                        "Uncalled bet returned");
            }
            for (Player p : game.seats()) {
                manager.db().updateStack(roomDbId, str(p.userId), p.stack);
            }
        } catch (Exception e) {
            log.warn("persist failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void postResults(HandResult r) {
        StringBuilder b = new StringBuilder();
        b.append(r.showdown ? "🎴 **Showdown**\n" : "🏆 **Hand result**\n");
        if (r.showdown) {
            for (HandResult.Reveal rv : r.reveals) {
                b.append("• ").append(mention(rv.userId)).append(": ")
                        .append(cards(rv.hole)).append(" — **").append(rv.handDesc).append("**");
                if (rv.bestFive != null && !rv.bestFive.isEmpty()) {
                    b.append(" (").append(cards(rv.bestFive)).append(")");
                }
                b.append("\n");
            }
            b.append("\n");
        }
        for (HandResult.PotAward a : r.awards) {
            String winners = a.winners.stream().map(this::mention).collect(Collectors.joining(", "));
            b.append("**").append(a.label).append("** (").append(a.amount).append("): ")
                    .append(winners).append(" — ").append(a.handDesc).append("\n");
        }
        if (!r.refunds.isEmpty()) {
            r.refunds.forEach((uid, amt) ->
                    b.append("↩️ Returned ").append(amt).append(" (uncalled) to ").append(mention(uid)).append("\n"));
        }
        b.append("\n").append(standingsBlock());

        List<Card> winnerBestFive = findWinnerBestFive(r);
        if (r.showdown && !r.board.isEmpty() && !winnerBestFive.isEmpty()) {
            try {
                List<Card> allCards = new ArrayList<>(r.board);
                byte[] img = CardRenderer.renderCardsHighlighted(allCards, winnerBestFive);
                postKeptWithImage(b.toString(), img, "board.png");
            } catch (Exception e) {
                postKept(b.toString());
            }
        } else {
            postKept(b.toString());
        }

        postShowCardButtons();
    }

    private List<Card> findWinnerBestFive(HandResult r) {
        if (!r.showdown || r.awards.isEmpty()) return List.of();
        List<Long> winners = r.awards.get(0).winners;
        if (winners.isEmpty()) return List.of();
        long winnerId = winners.get(0);
        for (HandResult.Reveal rv : r.reveals) {
            if (rv.userId == winnerId && rv.bestFive != null) {
                return rv.bestFive;
            }
        }
        return List.of();
    }

    private void postShowCardButtons() {
        boolean anyCanShow = lastHoleCards.keySet().stream().anyMatch(uid -> !shownCards.contains(uid));
        if (!anyCanShow) return;
        postKeptRows("🃏 Show your cards? (10s)", ActionRow.of(
                Button.primary("show:card1", "Show card 1"),
                Button.primary("show:card2", "Show card 2"),
                Button.success("show:both", "Show both")));
    }

    private void postStreetAnnouncement() {
        if (game.board().isEmpty()) return;
        try {
            byte[] img = CardRenderer.renderCards(game.board());
            String label = "🃏 **— " + game.street().display().toUpperCase() + " —**";
            postHandWithImage(label, img, "board.png", null);
        } catch (Exception e) {
            postHand("🃏 **— " + game.street().display().toUpperCase()
                    + " —**  " + cardsPlain(game.board()));
        }
    }

    private String tableText() {
        StringBuilder b = new StringBuilder("```\n");
        b.append("Hand #").append(game.handNumber()).append("   ").append(game.street().display()).append("\n");
        b.append("Board: ").append(game.board().isEmpty() ? "—" : cardsPlain(game.board())).append("\n");
        b.append("Pot: ").append(game.totalPot()).append("    Blinds: ").append(sb).append("/").append(bb).append("\n");
        b.append("--------------------------------------\n");
        Player cur = game.currentActor();
        for (Player p : game.seats()) {
            String turn = (cur != null && cur.userId == p.userId) ? ">" : " ";
            String dealer = (p.userId == game.buttonUserId()) ? "D" : " ";
            String st = p.folded ? "folded" : p.allIn ? "all-in" : (!p.inHand ? "sitting out" : "");
            String act = lastActions.getOrDefault(p.userId, "");
            String status = !st.isEmpty() ? st : act;
            b.append(String.format("%s%s %-16s stack:%-7d bet:%-6d %s%n",
                    turn, dealer, trunc(p.name), p.stack, p.streetCommitted, status));
        }
        if (cur != null) {
            long toCall = game.callAmountFor(cur);
            b.append("--------------------------------------\n");
            if (toCall == 0) {
                b.append("To act: ").append(cur.name).append("  Min bet: ").append(bb).append("\n");
            } else if (cur.stack > toCall) {
                b.append("To act: ").append(cur.name).append("  Call: ").append(toCall)
                        .append("  Min raise: ").append(game.minRaiseTo()).append("\n");
            } else {
                b.append("To act: ").append(cur.name).append("  Call: ").append(toCall).append(" (all-in)\n");
            }
        }
        b.append("```");
        return b.toString();
    }

    private String standingsBlock() {
        StringBuilder b = new StringBuilder("```\nStacks:\n");
        for (Player p : game.seats()) {
            b.append(String.format("%-18s %d%s%n", trunc(p.name), p.stack, p.stack <= 0 ? " (busted)" : ""));
        }
        b.append("```");
        return b.toString();
    }

    // ------------------------------------------------------------------
    // Discord helpers
    // ------------------------------------------------------------------

    private long postHand(String content) {
        ThreadChannel t = thread();
        if (t == null) {
            return 0;
        }
        try {
            long id = t.sendMessage(content).complete().getIdLong();
            handMessageIds.add(id);
            return id;
        } catch (Exception e) {
            log.warn("postHand failed", e);
            return 0;
        }
    }

    private long postHand(String content, ActionRow row) {
        ThreadChannel t = thread();
        if (t == null) {
            return 0;
        }
        try {
            long id = t.sendMessage(content).setComponents(row).complete().getIdLong();
            handMessageIds.add(id);
            return id;
        } catch (Exception e) {
            log.warn("postHand(row) failed", e);
            return 0;
        }
    }

    private long postHandWithImage(String content, byte[] image, String filename, ActionRow row) {
        ThreadChannel t = thread();
        if (t == null) {
            return 0;
        }
        try {
            var action = t.sendMessage(content)
                    .addFiles(FileUpload.fromData(image, filename));
            if (row != null) {
                action = action.setComponents(row);
            }
            long id = action.complete().getIdLong();
            handMessageIds.add(id);
            return id;
        } catch (Exception e) {
            log.warn("postHandWithImage failed", e);
            return 0;
        }
    }

    private void postRoom(String content) {
        ThreadChannel t = thread();
        if (t != null) {
            t.sendMessage(content).queue(s -> {
            }, e -> {
            });
        }
    }

    /** Posts a message that is deliberately NOT tracked for cleanup (kept as history). */
    private void postKept(String content) {
        ThreadChannel t = thread();
        if (t != null) {
            t.sendMessage(content).queue(s -> {
            }, e -> {
            });
        }
    }

    private void postKeptWithImage(String content, byte[] image, String filename) {
        ThreadChannel t = thread();
        if (t != null) {
            t.sendMessage(content)
                    .addFiles(FileUpload.fromData(image, filename))
                    .queue(s -> {}, e -> {});
        }
    }

    private void postKeptRows(String content, ActionRow... rows) {
        ThreadChannel t = thread();
        if (t != null) {
            t.sendMessage(content).setComponents(rows).queue(s -> {
            }, e -> {
            });
        }
    }

    private void disablePreviousActionButtons() {
        if (actionMessageId == 0) {
            return;
        }
        ThreadChannel t = thread();
        if (t != null) {
            t.editMessageComponentsById(str(actionMessageId), Collections.emptyList()).queue(s -> {
            }, e -> {
            });
        }
    }

    private void cleanupHandMessages() {
        ThreadChannel t = thread();
        if (t != null) {
            for (long id : handMessageIds) {
                t.deleteMessageById(str(id)).queue(s -> {
                }, e -> {
                });
            }
        }
        handMessageIds.clear();
        actionMessageId = 0;
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        if (reminderTask != null) {
            reminderTask.cancel(false);
            reminderTask = null;
        }
        actionSeq++;
    }

    private void addToThread(long userId) {
        ThreadChannel t = thread();
        if (t != null) {
            t.addThreadMemberById(userId).queue(s -> {
            }, e -> {
            });
        }
    }

    private void removeFromThread(long userId) {
        ThreadChannel t = thread();
        if (t != null) {
            t.removeThreadMemberById(userId).queue(s -> {
            }, e -> {
            });
        }
    }

    private ThreadChannel thread() {
        return threadId == 0 ? null : manager.jda().getThreadChannelById(threadId);
    }

    private void ephem(InteractionHook hook, String message) {
        hook.sendMessage(message).queue(s -> {
        }, e -> {
        });
    }

    private void ephemExplicit(InteractionHook hook, String message) {
        hook.sendMessage(message).setEphemeral(true).queue(s -> {
        }, e -> {
        });
    }

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    static String confirm(ActionType type, long amount, long committed, boolean nowAllIn) {
        return switch (type) {
            case FOLD -> "🃏 You folded.";
            case CHECK -> "✅ You checked.";
            case CALL -> "✅ You called " + committed + ".";
            case BET -> "✅ You bet " + amount + ".";
            case RAISE -> "✅ You raised to " + amount + ".";
            case ALL_IN -> nowAllIn
                    ? "🔺 You are all-in for " + committed + "!"
                    : "✅ You called " + committed + ".";
        };
    }

    private static String actionShort(ActionType type, long amount, long committed, boolean nowAllIn) {
        return switch (type) {
            case FOLD -> "fold";
            case CHECK -> "check";
            case CALL -> "call " + committed;
            case BET -> "bet " + amount;
            case RAISE -> "raise " + amount;
            case ALL_IN -> nowAllIn ? "all-in " + committed : "call " + committed;
        };
    }

    private String mention(long userId) {
        return "<@" + userId + ">";
    }

    private String cards(List<Card> cs) {
        return cs.stream().map(Card::shortName).collect(Collectors.joining(" "));
    }

    private String cardsPlain(List<Card> cs) {
        return cs.stream().map(Card::shortName).collect(Collectors.joining(" "));
    }

    private String trunc(String name) {
        if (name == null) {
            return "?";
        }
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    private static String str(long v) {
        return Long.toString(v);
    }
}
