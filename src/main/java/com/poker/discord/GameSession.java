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
import java.security.SecureRandom;
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
    /** Seat cap — far below the 23-player limit of a single 52-card deck. */
    static final int MAX_PLAYERS = 10;
    /** Discord caps messages at 2000 chars; clip with headroom for the marker. */
    private static final int MAX_MESSAGE_CHARS = 1900;

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
    /** The kept results message — its show-cards buttons are stripped after the 10s window. */
    private volatile long resultsMessageId;
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
        this.game = new PokerGame(sb, bb, buyIn, new SecureRandom());
        this.roomDbId = manager.db().createRoom(str(guildId), str(parentChannelId), str(ownerId), sb, bb, buyIn);
    }

    public long parentChannelId() {
        return parentChannelId;
    }

    public long threadId() {
        return threadId;
    }

    public long ownerId() {
        return ownerId;
    }

    public boolean ended() {
        return ended;
    }

    /** Allows an external thread (e.g. shutdown hook) to safely tear down this session. */
    void destroy() {
        onExec("destroy", () -> {
            if (!ended) {
                endGame("🛑 Game terminated (server shutdown).");
            }
        });
    }

    // ------------------------------------------------------------------
    // Executor plumbing — every task is wrapped so an unexpected exception is
    // logged instead of vanishing inside the executor's discarded future.
    // ------------------------------------------------------------------

    private void onExec(String what, Runnable task) {
        try {
            exec.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("{} failed", what, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("{} rejected — executor already shut down", what);
        }
    }

    private ScheduledFuture<?> later(String what, long delay, TimeUnit unit, Runnable task) {
        try {
            return exec.schedule(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("{} failed", what, e);
                }
            }, delay, unit);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("{} rejected — executor already shut down", what);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Public entry points (called from the listener; each runs on exec)
    // ------------------------------------------------------------------

    public void init(InteractionHook hook) {
        onExec("init", () -> doInit(hook));
    }

    public void onJoin(long userId, String name, InteractionHook hook) {
        onExec("join", () -> {
            try {
                doJoin(userId, name, hook);
            } catch (Exception e) {
                log.error("join failed", e);
                ephem(hook, "⚠️ Could not join the room.");
            }
        });
    }

    public void onStart(long userId, InteractionHook hook) {
        onExec("start", () -> {
            try {
                doStart(userId, hook);
            } catch (Exception e) {
                log.error("start failed", e);
                ephem(hook, "⚠️ Could not start the game.");
            }
        });
    }

    public void onLeave(long userId, InteractionHook hook) {
        onExec("leave", () -> {
            try {
                doLeave(userId, hook);
            } catch (Exception e) {
                log.error("leave failed", e);
                ephem(hook, "⚠️ Could not process leave.");
            }
        });
    }

    public void onEnd(long userId, InteractionHook hook) {
        onExec("end", () -> {
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
        onExec("forceend", () -> {
            if (userId != ownerId) {
                ephem(hook, "Only the room owner can force-end the game.");
                return;
            }
            ephem(hook, "🛑 Force-ending now.");
            endGame("🛑 Game force-ended by the owner.");
        });
    }

    public void onStatus(long userId, InteractionHook hook) {
        onExec("status", () -> {
            if (started && game.handInProgress()) {
                String status = tableText();
                if (!game.board().isEmpty()) {
                    try {
                        byte[] img = CardRenderer.renderCards(game.board());
                        hook.sendMessage(status)
                                .addFiles(FileUpload.fromData(img, "board.png"))
                                .setEphemeral(true)
                                .queue(s -> {}, e -> {});
                        return;
                    } catch (Exception ignored) {}
                }
                ephem(hook, status);
            } else if (started) {
                ephem(hook, "Between hands — the next hand starts in a few seconds.\n" + standingsBlock());
            } else {
                ephem(hook, "Room is waiting. Seated players: " + game.seats().size()
                        + ". Owner: " + mention(ownerId) + ". Use `/poker start` to begin.");
            }
        });
    }

    public void onViewCards(long userId, InteractionHook hook) {
        onExec("viewcards", () -> {
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
                            .setEphemeral(true)
                            .queue(s -> {}, e -> {});
                } catch (Exception e) {
                    ephem(hook, sb.toString());
                }
            }
        });
    }

    public void onShowCards(long userId, String choice, InteractionHook hook) {
        onExec("showcards", () -> {
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

    /**
     * @param token the action sequence number embedded in the button/modal that
     *              triggered this (rejects stale interactions), or -1 for slash
     *              commands, which are always treated as current
     * @param ack   true when the interaction was deferred as an ephemeral reply
     *              (slash/modal) and expects a confirmation followup; buttons
     *              are acknowledged silently
     */
    public void onAction(long userId, ActionType type, long amount, InteractionHook hook,
                         int token, boolean ack) {
        onExec("action", () -> {
            try {
                if (ended) {
                    ephem(hook, "This room is closed.");
                    return;
                }
                if (!started || !game.handInProgress()) {
                    ephem(hook, "No hand is in progress.");
                    return;
                }
                if (token != -1 && token != actionSeq) {
                    // -1 = slash command (always current); anything else must match
                    // the live prompt, so stale buttons/dialogs can't act.
                    ephem(hook, "⌛ That button belongs to an earlier turn — use the current prompt.");
                    return;
                }
                Player self = game.playerById(userId);
                long beforeStack = self == null ? 0 : self.stack;
                log.debug("Action: user={} type={} amount={} stack={}", userId, type, amount, beforeStack);
                ActionType executed = game.applyAction(userId, type, amount);
                // A voluntary action clears any timeout warning (strikes count only consecutively).
                if (self != null) {
                    self.timeoutStrikes = 0;
                }
                long committed = self == null ? 0 : beforeStack - self.stack;
                boolean nowAllIn = self != null && self.stack == 0;
                cancelTimeout();
                lastActions.put(userId, actionShort(executed, amount, committed, nowAllIn));
                if (ack) {
                    ephem(hook, confirm(executed, amount, committed, nowAllIn));
                }
                proceed();
            } catch (InvalidActionException e) {
                ephem(hook, "❌ " + e.getMessage());
            } catch (Exception e) {
                log.error("action failed", e);
                ephem(hook, "⚠️ Internal error processing your action.");
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
                abandonInit();
                return;
            }
            Player owner = game.addPlayer(ownerId, ownerName, ++joinCounter);
            manager.db().upsertPlayer(roomDbId, str(ownerId), ownerName, owner.joinOrder, owner.stack);

            String roomCode = UUID.randomUUID().toString().substring(0, 8);
            ThreadChannel thread = parent.createThreadChannel("game-" + roomCode, true)
                    .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
                    .complete();
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
            ThreadChannel t = thread();
            if (t != null) {
                t.delete().queue(s -> {
                }, e2 -> {
                });
            }
            abandonInit();
        }
    }

    /** Tears down a session whose init failed: registry, DB row and executor. */
    private void abandonInit() {
        ended = true;
        manager.unregister(this);
        manager.db().closeRoom(roomDbId);
        exec.shutdown();
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
                // afterHand may have already deleted the row; restore it.
                manager.db().upsertPlayer(roomDbId, str(userId), existing.name, existing.joinOrder, existing.stack);
                ephem(hook, "✅ You rejoined the room.");
                postRoom(mention(userId) + " rejoined.");
                reassignOwnerIfNeeded();
            } else {
                ephem(hook, "You are already seated in this room.");
            }
            return;
        }
        if (game.seats().size() >= MAX_PLAYERS) {
            ephem(hook, "The table is full (" + MAX_PLAYERS + " players max).");
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
        // If the room's owner left while it sat empty, hand control to a live player.
        reassignOwnerIfNeeded();
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

        // A departing owner hands control over immediately — the remaining
        // players must be able to end the game even mid-hand.
        reassignOwnerIfNeeded();

        if (game.handInProgress()) {
            if (wasActor || game.aliveInHand() <= 1) {
                cancelTimeout();
                proceed();
            }
        } else if (game.eligibleForHand().size() < 2 && started) {
            postRoom("⏸️ Not enough players to continue. Waiting for more with `/poker join`.");
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
        String deadSb = game.smallBlindDead()
                ? "\n⚪ Small blind is **dead** this hand (the big blind never skips a player)."
                : "";
        postHand("🃏 **Hand #" + game.handNumber() + "** — Dealer button: " + mention(game.buttonUserId())
                + " • Blinds **" + sb + "/" + bb + "**" + deadSb);
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
                        game.handNumber(), game.street(), cards(game.board()), ableNow);
                postStreetAnnouncement();
                if (ableNow < 2) {
                    log.debug("Hand #{}: all-in run-out from {} to RIVER", game.handNumber(), game.street());
                    while (game.street() != com.poker.game.Street.RIVER) {
                        game.dealNextStreet();
                        log.debug("Hand #{}: dealt {} — board: {}",
                                game.handNumber(), game.street(), cards(game.board()));
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
        boolean canRaise = game.canRaise(p);

        StringBuilder content = new StringBuilder();
        content.append(tableText()).append("\n");
        content.append("🎯 ").append(mention(p.userId)).append(" — **YOUR TURN** ⏰ 30s");

        // The token in each component ID pins the button to THIS prompt: clicks
        // from earlier prompts (or a stale raise dialog) are rejected instead of
        // executing against a different betting context.
        int token = ++actionSeq;
        List<Button> buttons = new ArrayList<>();
        if (toCall == 0) {
            buttons.add(Button.primary("act:check:" + token, "Check"));
            if (canRaise) {
                // With a live bet (big-blind option) the action is a raise, not a bet.
                buttons.add(Button.success("act:raise:" + token, game.currentBet() > 0 ? "Raise" : "Bet"));
                buttons.add(Button.success("act:allin:" + token, "🔺 All-in " + allInTo));
            }
            buttons.add(Button.danger("act:fold:" + token, "Fold"));
        } else if (canRaise) {
            buttons.add(Button.primary("act:call:" + token, "Call " + toCall));
            buttons.add(Button.success("act:raise:" + token, "Raise"));
            buttons.add(Button.success("act:allin:" + token, "🔺 All-in " + allInTo));
            buttons.add(Button.danger("act:fold:" + token, "Fold"));
        } else {
            buttons.add(Button.primary("act:call:" + token,
                    toCall >= p.stack ? "Call all-in " + p.stack : "Call " + toCall));
            buttons.add(Button.danger("act:fold:" + token, "Fold"));
        }
        buttons.add(Button.secondary("act:cards", "🂠 View my cards"));

        actionMessageId = postHand(content.toString(), ActionRow.of(buttons));

        reminderTask = later("reminder", ACTION_TIMEOUT_SECONDS - REMINDER_BEFORE_SECONDS,
                TimeUnit.SECONDS, () -> onReminder(token));
        timeoutTask = later("timeout", ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS, () -> onTimeout(token));
    }

    private void onReminder(int token) {
        if (token != actionSeq || ended) {
            return; // stale or finished
        }
        Player cur = game.currentActor();
        if (cur == null) {
            return;
        }
        String consequence = game.callAmountFor(cur) == 0 ? "checked" : "folded";
        postHand("⏰ " + mention(cur.userId) + " — about " + REMINDER_BEFORE_SECONDS
                + " seconds left to act, or you'll be " + consequence + " automatically.");
    }

    private void onTimeout(int token) {
        if (token != actionSeq || ended) {
            return; // stale timer
        }
        Player cur = game.currentActor();
        if (cur == null) {
            return;
        }
        if (thread() == null) {
            // The thread is archived/invisible: pause instead of silently folding
            // players out of a game nobody can see. Retry until it comes back.
            log.warn("Thread {} not visible — pausing the action clock", threadId);
            timeoutTask = later("timeout-retry", 60, TimeUnit.SECONDS, () -> onTimeout(token));
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
                game.handNumber(), reveal, showdown, cards(game.board()), game.totalPot());

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
                        rv.userId, cards(rv.hole), rv.handDesc);
                revealedUserIds.add(rv.userId);
            }
        }
        for (HandResult.PotAward a : result.awards) {
            log.info("  {} ({}): winners={} desc={}", a.label, a.amount, a.winners, a.handDesc);
        }
        shownCards.addAll(revealedUserIds);
        postResults(result);
        persist(result);

        later("hand-cleanup", RESULT_LINGER_SECONDS, TimeUnit.SECONDS, () -> {
            closeShowCardsWindow();
            cleanupHandMessages();
            afterHand();
        });
    }

    /** Ends the post-hand show-cards window: buttons are removed and the cards forgotten. */
    private void closeShowCardsWindow() {
        lastHoleCards.clear();
        shownCards.clear();
        long msgId = resultsMessageId;
        resultsMessageId = 0;
        if (msgId != 0) {
            ThreadChannel t = thread();
            if (t != null) {
                t.editMessageComponentsById(str(msgId), Collections.emptyList()).queue(s -> {
                }, e -> {
                });
            }
        }
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
            // Drop leavers and busted players NOW (startHand won't run to do it),
            // so a busted player can re-join for a fresh buy-in.
            game.pruneOut();
            started = false;
            manager.db().setState(roomDbId, "WAITING");
            postRoom("⏸️ Not enough players with chips to continue. The room is waiting — "
                    + "busted players can `/poker join` again for a fresh buy-in, then the owner "
                    + "uses `/poker start`, or `/poker forceend` to close.");
            return;
        }
        beginHand();
    }

    private void reassignOwnerIfNeeded() {
        // During a hand, stack 0 means all-in, not busted — only treat an empty
        // stack as gone between hands.
        boolean handRunning = game.handInProgress();
        Player owner = game.playerById(ownerId);
        boolean ownerGone = owner == null || owner.wantsLeave || (owner.stack <= 0 && !handRunning);
        if (!ownerGone) {
            return;
        }
        Player next = null;
        for (Player p : game.seats()) {
            if (p.wantsLeave || (p.stack <= 0 && !handRunning)) {
                continue;
            }
            if (next == null || p.joinOrder < next.joinOrder) {
                next = p;
            }
        }
        if (next != null && next.userId != ownerId) {
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
        String refundNote = "";
        if (game.handInProgress()) {
            // Force-ended mid-hand: nobody wins the pot — every committed chip
            // goes back to its owner so no chips vanish from the table.
            game.abortHand();
            refundNote = "↩️ The unfinished hand was cancelled — all bets were returned.\n";
        }
        for (Player p : game.seats()) {
            manager.db().updateStack(roomDbId, str(p.userId), p.stack);
        }
        postRoom(reason + "\n" + refundNote + standingsBlock());
        manager.db().closeRoom(roomDbId);
        manager.unregister(this);
        ThreadChannel t = thread();
        if (t != null) {
            t.getManager().setArchived(true).queueAfter(3, TimeUnit.SECONDS, s -> {
            }, e -> {
            });
        }
        try {
            exec.schedule(exec::shutdown, 5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            exec.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persist(HandResult result) {
        try {
            manager.db().inTransaction(() -> {
                // Stacks are the state that matters for recovery — update them
                // even if recording the hand history fails.
                for (Player p : game.seats()) {
                    manager.db().updateStack(roomDbId, str(p.userId), p.stack);
                }
                long pot = result.awards.stream().mapToLong(a -> a.amount).sum();
                long handId = manager.db().recordHand(roomDbId, game.handNumber(), cards(result.board), pot);
                if (handId < 0) {
                    return;
                }
                for (HandResult.PotAward award : result.awards) {
                    for (var payout : award.payouts.entrySet()) {
                        Player p = game.playerById(payout.getKey());
                        String hole = (p != null) ? cards(p.hole) : "";
                        manager.db().recordResult(handId, str(payout.getKey()), payout.getValue(), hole, award.handDesc);
                    }
                }
                for (var refund : result.refunds.entrySet()) {
                    manager.db().recordResult(handId, str(refund.getKey()), refund.getValue(), "",
                            "Uncalled bet returned");
                }
            });
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

        boolean anyCanShow = lastHoleCards.keySet().stream().anyMatch(uid -> !shownCards.contains(uid));
        if (anyCanShow) {
            b.append("\n🃏 Show your cards? (").append(RESULT_LINGER_SECONDS).append("s)");
        }
        ActionRow showRow = anyCanShow ? ActionRow.of(
                Button.primary("show:card1", "Show card 1"),
                Button.primary("show:card2", "Show card 2"),
                Button.success("show:both", "Show both")) : null;

        List<Card> winnerBestFive = findWinnerBestFive(r);
        ThreadChannel t = thread();
        if (t == null) {
            log.warn("postResults: thread {} not visible, result not posted", threadId);
            return;
        }

        String text = clip(b.toString());
        resultsMessageId = 0;
        try {
            var action = t.sendMessage(text);
            if (r.showdown && !r.board.isEmpty() && !winnerBestFive.isEmpty()) {
                byte[] img = CardRenderer.renderCardsHighlighted(new ArrayList<>(r.board), winnerBestFive);
                action = action.addFiles(FileUpload.fromData(img, "board.png"));
            }
            if (showRow != null) {
                action = action.setComponents(showRow);
            }
            action.queue(s -> resultsMessageId = s.getIdLong(), e -> {});
        } catch (Exception e) {
            // Image rendering / attachment failed — fall back to plain text.
            log.warn("postResults rich message failed, falling back to text", e);
            try {
                t.sendMessage(text).queue(s -> {}, e2 -> {});
            } catch (Exception e2) {
                log.error("postResults fallback failed", e2);
            }
        }
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

    private void postStreetAnnouncement() {
        if (game.board().isEmpty()) return;
        try {
            byte[] img = CardRenderer.renderCards(game.board());
            String label = "🃏 **— " + game.street().display().toUpperCase() + " —**";
            postHand(label, null, img, "board.png");
        } catch (Exception e) {
            postHand("🃏 **— " + game.street().display().toUpperCase()
                    + " —**  " + cards(game.board()));
        }
    }

    private String tableText() {
        StringBuilder b = new StringBuilder("```\n");
        b.append("Hand #").append(game.handNumber()).append("   ").append(game.street().display()).append("\n");
        b.append("Board: ").append(game.board().isEmpty() ? "—" : cards(game.board())).append("\n");
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
            boolean canRaise = game.canRaise(cur);
            b.append("--------------------------------------\n");
            if (toCall == 0 && canRaise && game.currentBet() > 0) {
                // Big-blind option: the blind is live, so the next step up is a raise.
                b.append("To act: ").append(trunc(cur.name)).append("  Min raise: ").append(game.minRaiseTo()).append("\n");
            } else if (toCall == 0 && canRaise) {
                b.append("To act: ").append(trunc(cur.name)).append("  Min bet: ").append(bb).append("\n");
            } else if (toCall == 0) {
                b.append("To act: ").append(trunc(cur.name)).append("  Check only\n");
            } else if (canRaise) {
                b.append("To act: ").append(trunc(cur.name)).append("  Call: ").append(toCall)
                        .append("  Min raise: ").append(game.minRaiseTo()).append("\n");
            } else {
                b.append("To act: ").append(trunc(cur.name)).append("  Call: ").append(toCall)
                        .append(toCall >= cur.stack ? " (all-in)" : " (call or fold)").append("\n");
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
        return postHand(content, null, null, null);
    }

    private long postHand(String content, ActionRow row) {
        return postHand(content, row, null, null);
    }

    /** Sends a per-hand message (tracked for cleanup). Row and image are optional. */
    private long postHand(String content, ActionRow row, byte[] image, String filename) {
        ThreadChannel t = thread();
        if (t == null) {
            log.warn("postHand: thread {} not visible, message dropped", threadId);
            return 0;
        }
        try {
            var action = t.sendMessage(clip(content));
            if (image != null) {
                action = action.addFiles(FileUpload.fromData(image, filename));
            }
            if (row != null) {
                action = action.setComponents(row);
            }
            long id = action.complete().getIdLong();
            handMessageIds.add(id);
            return id;
        } catch (Exception e) {
            log.warn("postHand failed", e);
            return 0;
        }
    }

    private void postRoom(String content) {
        ThreadChannel t = thread();
        if (t == null) {
            log.warn("postRoom: thread {} not visible, message dropped", threadId);
            return;
        }
        try {
            t.sendMessage(clip(content)).queue(s -> {
            }, e -> {
            });
        } catch (Exception e) {
            log.warn("postRoom failed", e);
        }
    }

    /** Keeps messages under Discord's 2000-char limit, closing an open code block if cut. */
    private static String clip(String s) {
        if (s.length() <= MAX_MESSAGE_CHARS) {
            return s;
        }
        String cut = s.substring(0, MAX_MESSAGE_CHARS);
        int fences = cut.split("```", -1).length - 1;
        return cut + "…" + (fences % 2 != 0 ? "\n```" : "") + "\n*(truncated)*";
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
        if (t != null && !handMessageIds.isEmpty()) {
            try {
                // Bulk endpoint: one REST call per 100 messages instead of one each.
                t.purgeMessagesById(handMessageIds.stream().map(GameSession::str).toList());
            } catch (Exception e) {
                log.warn("cleanup failed", e);
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

    /**
     * Sends a private followup to the interacting user. Always sets the
     * ephemeral flag explicitly: followups after {@code deferEdit()} (buttons)
     * are public by default, while after {@code deferReply(true)} the flag is
     * simply redundant — so one helper is safe for every hook.
     */
    private void ephem(InteractionHook hook, String message) {
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

    /** Sanitizes a display name for the code-block table: strips formatting
     *  characters plus control/zero-width/bidi characters that could forge or
     *  scramble table rows. */
    private String trunc(String name) {
        if (name == null) {
            return "?";
        }
        String safe = name.replaceAll("[`@\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "");
        if (safe.isBlank()) safe = "player";
        return safe.length() > 16 ? safe.substring(0, 16) : safe;
    }

    private static String str(long v) {
        return Long.toString(v);
    }
}
