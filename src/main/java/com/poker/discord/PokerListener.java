package com.poker.discord;

import com.poker.game.ActionType;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes Discord slash commands, buttons and modals to the right {@link GameSession}. */
public class PokerListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PokerListener.class);

    private final GameManager manager;

    public PokerListener(GameManager manager) {
        this.manager = manager;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        log.info("Slash '{}{}' from {} ({}) guild={} channel={} type={}",
                event.getName(),
                event.getSubcommandName() != null ? " " + event.getSubcommandName() : "",
                event.getUser().getName(), event.getUser().getId(),
                event.getGuild() != null ? event.getGuild().getId() : "none",
                event.getChannel().getId(), event.getChannelType());
        try {
            switch (event.getName()) {
                case "poker" -> handlePoker(event);
                case "fold" -> action(event, ActionType.FOLD, 0);
                case "check" -> action(event, ActionType.CHECK, 0);
                case "call" -> action(event, ActionType.CALL, 0);
                case "allin" -> action(event, ActionType.ALL_IN, 0);
                case "bet" -> action(event, ActionType.BET, longOpt(event, "amount"));
                case "raise" -> action(event, ActionType.RAISE, longOpt(event, "amount"));
                default -> event.reply("Unknown command.").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            log.error("slash command failed", e);
            if (!event.isAcknowledged()) {
                event.reply("⚠️ Something went wrong.").setEphemeral(true).queue();
            }
        }
    }

    private void handlePoker(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        long channelId = event.getChannel().getIdLong();
        long userId = event.getUser().getIdLong();
        String userName = event.getUser().getEffectiveName();

        if (sub.equals("open")) {
            if (event.getGuild() == null) {
                event.reply("Poker rooms can only be opened in a server.").setEphemeral(true).queue();
                return;
            }
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply("Please use `/poker open` in a normal text channel (not a thread).")
                        .setEphemeral(true).queue();
                return;
            }
            if (manager.roomsOwnedBy(userId) >= MAX_ROOMS_PER_OWNER) {
                event.reply("You already own " + MAX_ROOMS_PER_OWNER + " open rooms — end one first "
                        + "(`/poker end` in its thread).").setEphemeral(true).queue();
                return;
            }
            long buyin = longOpt(event, "buyin");
            long sb = longOpt(event, "sb");
            long bb = longOpt(event, "bb");
            String error = validateOpen(buyin, sb, bb);
            if (error != null) {
                event.reply(error).setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            GameSession session = new GameSession(manager, event.getGuild().getIdLong(),
                    channelId, userId, userName, sb, bb, buyin);
            session.init(event.getHook());
            return;
        }

        GameSession session = manager.resolve(channelId);
        if (session == null) {
            event.reply("No poker room is registered for **this** channel. Game commands work inside the "
                    + "room's private thread — use the **Join**/**Start** buttons on the table message, "
                    + "or open a new room with `/poker open`.").setEphemeral(true).queue();
            return;
        }
        switch (sub) {
            case "join" -> {
                event.deferReply(true).queue();
                session.onJoin(userId, userName, event.getHook());
            }
            case "start" -> {
                event.deferReply(true).queue();
                session.onStart(userId, event.getHook());
            }
            case "leave" -> {
                event.deferReply(true).queue();
                session.onLeave(userId, event.getHook());
            }
            case "end" -> {
                event.deferReply(true).queue();
                session.onEnd(userId, event.getHook());
            }
            case "forceend" -> {
                event.deferReply(true).queue();
                session.onForceEnd(userId, event.getHook());
            }
            case "status" -> {
                event.deferReply(true).queue();
                session.onStatus(userId, event.getHook());
            }
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void action(SlashCommandInteractionEvent event, ActionType type, long amount) {
        GameSession session = manager.resolve(event.getChannel().getIdLong());
        if (session == null) {
            event.reply("No poker game here — betting commands work inside the game thread.")
                    .setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        // Slash commands are always treated as current (token -1) and get an
        // ephemeral confirmation, since their deferred reply must be completed.
        session.onAction(event.getUser().getIdLong(), type, amount, event.getHook(), -1, true);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        long channelId = event.getChannel().getIdLong();
        long userId = event.getUser().getIdLong();
        String userName = event.getUser().getEffectiveName();
        log.info("Button '{}' from {} ({}) channel={}", id, userName, userId, channelId);

        // Room buttons (posted in the parent channel) carry the thread ID.
        if (id.startsWith("room:join:") || id.startsWith("room:start:")) {
            String[] parts = id.split(":", 3);
            long threadId;
            try {
                threadId = Long.parseLong(parts[2]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                event.reply("Invalid button — this room may no longer exist.").setEphemeral(true).queue();
                return;
            }
            GameSession session = manager.resolve(threadId);
            if (session == null) {
                event.reply("This poker room has ended.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            if (parts[1].equals("join")) {
                session.onJoin(userId, userName, event.getHook());
            } else {
                session.onStart(userId, event.getHook());
            }
            return;
        }

        // In-thread buttons resolve by the thread channel.
        GameSession session = manager.resolve(channelId);
        if (session == null) {
            event.reply("No poker game here.").setEphemeral(true).queue();
            return;
        }

        // Action buttons are "act:<verb>:<token>" — the token pins them to one
        // prompt so stale buttons can't fire into a different betting context.
        if (id.startsWith("act:")) {
            String[] parts = id.split(":");
            String verb = parts.length > 1 ? parts[1] : "";
            int token = parseToken(parts);
            switch (verb) {
                // The raise button opens a modal — it must be the initial response
                // (no defer). The token rides along in the modal ID so a stale
                // dialog submission is rejected too.
                case "raise" -> {
                    TextInput amount = TextInput.create("amount", "Bet / raise to (total chips)", TextInputStyle.SHORT)
                            .setRequired(true)
                            .setPlaceholder("e.g. 150")
                            .build();
                    Modal modal = Modal.create("act:raisemodal:" + token, "Bet / Raise")
                            .addComponents(ActionRow.of(amount))
                            .build();
                    event.replyModal(modal).queue();
                }
                case "fold" -> {
                    event.deferEdit().queue();
                    session.onAction(userId, ActionType.FOLD, 0, event.getHook(), token, false);
                }
                case "check" -> {
                    event.deferEdit().queue();
                    session.onAction(userId, ActionType.CHECK, 0, event.getHook(), token, false);
                }
                case "call" -> {
                    event.deferEdit().queue();
                    session.onAction(userId, ActionType.CALL, 0, event.getHook(), token, false);
                }
                case "allin" -> {
                    event.deferEdit().queue();
                    session.onAction(userId, ActionType.ALL_IN, 0, event.getHook(), token, false);
                }
                case "cards" -> {
                    event.deferReply(true).queue();
                    session.onViewCards(userId, event.getHook());
                }
                default -> event.reply("Unknown button.").setEphemeral(true).queue();
            }
            return;
        }

        if (id.startsWith("show:")) {
            String choice = id.substring(5);
            event.deferReply(true).queue();
            session.onShowCards(userId, choice, event.getHook());
            return;
        }
        event.reply("Unknown button.").setEphemeral(true).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        log.info("Modal '{}' from {} ({}) channel={}", event.getModalId(),
                event.getUser().getName(), event.getUser().getId(), event.getChannel().getId());
        if (!event.getModalId().startsWith("act:raisemodal")) {
            return;
        }
        GameSession session = manager.resolve(event.getChannel().getIdLong());
        if (session == null) {
            event.reply("No poker game here.").setEphemeral(true).queue();
            return;
        }
        int token = parseToken(event.getModalId().split(":"));
        String raw = event.getValue("amount") == null ? "" : event.getValue("amount").getAsString().trim();
        long amount;
        try {
            amount = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            event.reply("❌ '" + raw + "' is not a whole number.").setEphemeral(true).queue();
            return;
        }
        if (amount <= 0 || amount > MAX_CHIPS) {
            event.reply("❌ Amount must be between 1 and " + MAX_CHIPS + ".").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        // The engine normalizes BET vs RAISE based on whether there is already a bet.
        session.onAction(event.getUser().getIdLong(), ActionType.RAISE, amount, event.getHook(), token, true);
    }

    /**
     * Extracts the prompt token from a component/modal ID split on ':'.
     * Missing or malformed tokens (old-format buttons) map to -2, which the
     * session rejects as stale — only slash commands use the always-current -1.
     */
    private static int parseToken(String[] parts) {
        if (parts.length < 3) {
            return -2;
        }
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    private static long longOpt(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? 0 : opt.getAsLong();
    }

    private static final long MAX_CHIPS = 10_000_000;
    private static final int MAX_ROOMS_PER_OWNER = 3;

    private static String validateOpen(long buyin, long sb, long bb) {
        if (sb <= 0 || bb <= 0) {
            return "Blinds must be positive.";
        }
        if (sb > MAX_CHIPS || bb > MAX_CHIPS || buyin > MAX_CHIPS) {
            return "Values cannot exceed " + MAX_CHIPS + ".";
        }
        if (sb > bb) {
            return "The small blind cannot be larger than the big blind.";
        }
        if (buyin < bb) {
            return "The buy-in must be at least one big blind (" + bb + ").";
        }
        return null;
    }
}
