package com.poker;

import com.poker.db.Database;
import com.poker.discord.GameManager;
import com.poker.discord.PokerListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        System.setProperty("java.awt.headless", "true");
        Config cfg = new Config();
        String token = cfg.require("DISCORD_TOKEN");
        String dbPath = cfg.getOrDefault("DB_PATH", "poker.db");
        String guildId = cfg.get("GUILD_ID");

        Database db = new Database(dbPath);
        GameManager manager = new GameManager(db);

        JDA jda = JDABuilder.createLight(token)
                .setActivity(Activity.playing("No-Limit Hold'em"))
                .addEventListeners(new PokerListener(manager))
                .build();
        manager.setJda(jda);
        jda.awaitReady();

        log.info("Logged in as {} (id {})", jda.getSelfUser().getName(), jda.getSelfUser().getId());
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            log.warn("==================================================================");
            log.warn("The bot is not a member of ANY server!");
            log.warn("It was almost certainly invited WITHOUT the 'bot' scope.");
            log.warn("Re-invite using an OAuth2 URL with scope=bot+applications.commands");
            log.warn("(see README 'Invite the bot to your server'). Slash commands can");
            log.warn("appear without the bot actually joining — that causes 'timeouts'.");
            log.warn("==================================================================");
        } else {
            for (Guild g : guilds) {
                log.info("Member of guild: {} (id {})", g.getName(), g.getId());
            }
        }

        registerCommands(jda, guildId);
        log.info("Poker bot is ready.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // End live games cleanly (refund in-progress hands), then stop
            // Discord events, then close the database.
            manager.destroyAll();
            jda.shutdown();
            try {
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(5))) {
                    jda.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            db.close();
        }));
    }

    private static void registerCommands(JDA jda, String guildId) {
        final long maxChips = 10_000_000;
        SlashCommandData poker = Commands.slash("poker", "Poker room management")
                .addSubcommands(
                        new SubcommandData("open", "Open a new poker room (you become the owner)")
                                .addOptions(
                                        new OptionData(OptionType.INTEGER, "buyin", "Starting chips for every player", true)
                                                .setRequiredRange(1, maxChips),
                                        new OptionData(OptionType.INTEGER, "sb", "Small blind (chips)", true)
                                                .setRequiredRange(1, maxChips),
                                        new OptionData(OptionType.INTEGER, "bb", "Big blind (chips)", true)
                                                .setRequiredRange(1, maxChips)),
                        new SubcommandData("join", "Join the poker room in this channel"),
                        new SubcommandData("start", "(Owner) Randomize seats and start the game"),
                        new SubcommandData("leave", "Leave the room (auto-folds your current hand)"),
                        new SubcommandData("end", "(Owner) Stop the game after the current hand"),
                        new SubcommandData("forceend", "(Owner) Force-end the game immediately"),
                        new SubcommandData("status", "Show the current table state"));

        List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commands = List.of(
                poker,
                Commands.slash("fold", "Fold your hand"),
                Commands.slash("check", "Check (pass with no bet)"),
                Commands.slash("call", "Call the current bet"),
                Commands.slash("allin", "Put all your chips in"),
                Commands.slash("bet", "Open the betting")
                        .addOptions(new OptionData(OptionType.INTEGER, "amount", "Chips to bet", true)
                                .setRequiredRange(1, maxChips)),
                Commands.slash("raise", "Raise the bet")
                        .addOptions(new OptionData(OptionType.INTEGER, "amount", "Total chips to raise TO", true)
                                .setRequiredRange(1, maxChips)));

        if (guildId != null && !guildId.isBlank()) {
            Guild guild = null;
            try {
                guild = jda.getGuildById(guildId);
            } catch (NumberFormatException e) {
                log.warn("GUILD_ID '{}' is not a valid server ID (check .env for typos or inline comments); "
                        + "registering globally instead.", guildId);
            }
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue(
                        s -> log.info("Registered {} guild commands to {}", commands.size(), guildId),
                        e -> log.error("Guild command registration failed", e));
                return;
            }
            log.warn("GUILD_ID {} not found in cache; registering globally instead.", guildId);
        }
        jda.updateCommands().addCommands(commands).queue(
                s -> log.info("Registered {} global commands (may take up to ~1h to appear)", commands.size()),
                e -> log.error("Global command registration failed", e));
    }
}
