package com.poker.discord;

import com.poker.engine.Card;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
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
    private static final long RESULT_LINGER_SECONDS = 8;

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
    private volatile long threadId;
    private volatile long ownerId;

    private long stateMessageId;
    private long actionMessageId;
    private long pendingActorId;
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
                ephem(hook, tableText());
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
                ephem(hook, "🂠 Your hole cards: **" + cards(p.hole) + "**");
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
                game.applyAction(userId, t, amount);
                // A voluntary action clears any timeout warning (strikes count only consecutively).
                Player self = game.playerById(userId);
                if (self != null) {
                    self.timeoutStrikes = 0;
                }
                cancelTimeout();
                ephem(hook, confirm(t, amount));
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
            TextChannel parent = manager.jda().getTextChannelById(parentChannelId);
            if (parent == null) {
                ephem(hook, "⚠️ I can no longer see this channel.");
                manager.unregister(this);
                return;
            }
            Player owner = game.addPlayer(ownerId, ownerName, ++joinCounter);
            manager.db().upsertPlayer(roomDbId, str(ownerId), ownerName, owner.joinOrder, owner.stack);

            ThreadChannel thread = parent.createThreadChannel("poker-" + sanitize(ownerName), true).complete();
            threadId = thread.getIdLong();
            manager.registerThread(threadId, this);
            manager.db().setThread(roomDbId, str(threadId));

            thread.addThreadMemberById(ownerId).queue(s -> {
            }, e -> {
            });
            thread.sendMessage("👋 Welcome " + mention(ownerId) + "! This private thread is your table. "
                    + "Other players join from the buttons in <#" + parentChannelId + "> or with `/poker join`.").queue();

            parent.sendMessage("🃏 **Poker table opened by " + mention(ownerId) + "**\n"
                            + "Buy-in **" + buyIn + "** • Blinds **" + sb + "/" + bb + "**\n"
                            + "Press **Join** to take a seat — the owner presses **Start** when everyone is in.")
                    .setComponents(ActionRow.of(
                            Button.success("room:join", "Join"),
                            Button.primary("room:start", "Start")))
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
        stateMessageId = 0;
        actionMessageId = 0;

        postHand("🃏 **Hand #" + game.handNumber() + "** — Dealer button: " + mention(game.buttonUserId())
                + " • Blinds **" + sb + "/" + bb + "**");
        postHand("Tap to privately check your hole cards (only you can see them):",
                ActionRow.of(Button.primary("act:cards", "🂠 View my cards")));
        postTableState();
        proceed();
    }

    /** Drives the hand forward until it needs a player to act, or the hand is over. */
    private void proceed() {
        while (true) {
            if (ended) {
                return;
            }
            if (game.aliveInHand() <= 1) {
                finishHand(false);
                return;
            }
            if (game.bettingRoundComplete()) {
                if (game.street() == com.poker.game.Street.RIVER) {
                    finishHand(true);
                    return;
                }
                int ableNow = game.ableToAct();
                game.dealNextStreet();
                postTableState();
                if (ableNow < 2) {
                    // No more betting possible — run the board out and show down.
                    while (game.street() != com.poker.game.Street.RIVER) {
                        game.dealNextStreet();
                    }
                    postTableState();
                    finishHand(true);
                    return;
                }
                // A fresh betting round opened; loop will prompt the first actor.
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
        pendingActorId = p.userId;
        disablePreviousActionButtons();

        long toCall = game.callAmountFor(p);
        long allInTo = p.streetCommitted + p.stack;
        String content = mention(p.userId) + " — **your turn**.\n"
                + "To call: **" + toCall + "** • Your stack: **" + p.stack + "** • Pot: **" + game.totalPot() + "**\n"
                + "Min " + (game.currentBet() == 0 ? "bet: **" + bb + "**" : "raise to: **" + game.minRaiseTo() + "**")
                + " • You have 30s to act.";

        Button fold = Button.danger("act:fold", "Fold");
        Button check = Button.secondary("act:check", "Check").withDisabled(toCall > 0);
        Button call = Button.success("act:call", toCall > 0 ? "Call " + toCall : "Call").withDisabled(toCall == 0);
        Button raise = Button.primary("act:raise", game.currentBet() == 0 ? "Bet" : "Raise").withDisabled(p.stack <= 0);
        Button allin = Button.secondary("act:allin", "All-in " + allInTo).withDisabled(p.stack <= 0);

        actionMessageId = postHand(content, ActionRow.of(fold, check, call, raise, allin));

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
        if (cur.timeoutStrikes == 0) {
            cur.timeoutStrikes = 1;
            postRoom("⏰ " + mention(uid) + " ran out of time and was folded automatically. "
                    + "(Warning 1/2 — a second timeout means a kick.)");
            game.applyAction(uid, ActionType.FOLD, 0);
        } else {
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
        long pot = game.totalPot();
        boolean showdown = reveal && game.aliveInHand() >= 2;
        HandResult result = game.settle(showdown);
        postTableState();
        postResults(result);
        persist(result, pot);

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

    private void persist(HandResult result, long pot) {
        try {
            long handId = manager.db().recordHand(roomDbId, game.handNumber(), cardsPlain(result.board), pot);
            for (HandResult.PotAward award : result.awards) {
                long share = award.winners.isEmpty() ? 0 : award.amount / award.winners.size();
                for (long winner : award.winners) {
                    Player p = game.playerById(winner);
                    String hole = (p != null) ? cardsPlain(p.hole) : "";
                    manager.db().recordResult(handId, str(winner), share, hole, award.handDesc);
                }
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
                        .append(cards(rv.hole)).append(" — ").append(rv.handDesc).append("\n");
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
        // Kept (not tracked for cleanup) so each hand's result stays in the thread as history.
        postKept(b.toString());
    }

    private void postTableState() {
        ThreadChannel t = thread();
        if (t == null) {
            return;
        }
        String txt = tableText();
        if (stateMessageId == 0) {
            try {
                long id = t.sendMessage(txt).complete().getIdLong();
                stateMessageId = id;
                handMessageIds.add(id);
            } catch (Exception e) {
                log.warn("postTableState failed", e);
            }
        } else {
            t.editMessageById(str(stateMessageId), txt).queue(s -> {
            }, e -> {
            });
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
            b.append(String.format("%s%s %-16s stack:%-7d bet:%-6d %s%n",
                    turn, dealer, trunc(p.name), p.stack, p.streetCommitted, st));
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
        stateMessageId = 0;
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
        pendingActorId = 0;
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

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    private String confirm(ActionType type, long amount) {
        return switch (type) {
            case FOLD -> "🃏 You folded.";
            case CHECK -> "✅ You checked.";
            case CALL -> "✅ You called.";
            case BET -> "✅ You bet " + amount + ".";
            case RAISE -> "✅ You raised to " + amount + ".";
            case ALL_IN -> "💥 You are all-in!";
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

    private String sanitize(String name) {
        String s = name == null ? "" : name.replaceAll("[^a-zA-Z0-9]", "");
        if (s.isEmpty()) {
            s = "table";
        }
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private static String str(long v) {
        return Long.toString(v);
    }
}
