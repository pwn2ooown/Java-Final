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
            event.reply("No poker room here. Open one with `/poker open`.").setEphemeral(true).queue();
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
            event.reply("No poker game here.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        session.onAction(event.getUser().getIdLong(), type, amount, event.getHook());
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

        // The raise button opens a modal — it must be the initial response (no defer).
        if (id.equals("act:raise")) {
            if (session == null) {
                event.reply("No poker game here.").setEphemeral(true).queue();
                return;
            }
            TextInput amount = TextInput.create("amount", "Bet / raise to (total chips)", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setPlaceholder("e.g. 150")
                    .build();
            Modal modal = Modal.create("act:raisemodal", "Bet / Raise")
                    .addComponents(ActionRow.of(amount))
                    .build();
            event.replyModal(modal).queue();
            return;
        }

        if (session == null) {
            event.reply("No poker game here.").setEphemeral(true).queue();
            return;
        }
        switch (id) {
            case "act:fold" -> {
                event.deferReply(true).queue();
                session.onAction(userId, ActionType.FOLD, 0, event.getHook());
            }
            case "act:check" -> {
                event.deferReply(true).queue();
                session.onAction(userId, ActionType.CHECK, 0, event.getHook());
            }
            case "act:call" -> {
                event.deferReply(true).queue();
                session.onAction(userId, ActionType.CALL, 0, event.getHook());
            }
            case "act:allin" -> {
                event.deferReply(true).queue();
                session.onAction(userId, ActionType.ALL_IN, 0, event.getHook());
            }
            case "act:cards" -> {
                event.deferReply(true).queue();
                session.onViewCards(userId, event.getHook());
            }
            case "show:card1", "show:card2", "show:both" -> {
                event.deferReply(true).queue();
                session.onShowCards(userId, id.substring(5), event.getHook());
            }
            default -> event.reply("Unknown button.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        log.info("Modal '{}' from {} ({}) channel={}", event.getModalId(),
                event.getUser().getName(), event.getUser().getId(), event.getChannel().getId());
        if (!"act:raisemodal".equals(event.getModalId())) {
            return;
        }
        GameSession session = manager.resolve(event.getChannel().getIdLong());
        if (session == null) {
            event.reply("No poker game here.").setEphemeral(true).queue();
            return;
        }
        String raw = event.getValue("amount") == null ? "" : event.getValue("amount").getAsString().trim();
        long amount;
        try {
            amount = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            event.reply("❌ '" + raw + "' is not a whole number.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        // onAction normalizes BET vs RAISE based on whether there is already a bet.
        session.onAction(event.getUser().getIdLong(), ActionType.RAISE, amount, event.getHook());
    }

    private static long longOpt(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? 0 : opt.getAsLong();
    }

    private static String validateOpen(long buyin, long sb, long bb) {
        if (sb <= 0 || bb <= 0) {
            return "Blinds must be positive.";
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
