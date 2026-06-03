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
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
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

        registerCommands(jda, guildId);
        log.info("Poker bot is ready.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            db.close();
            jda.shutdown();
        }));
    }

    private static void registerCommands(JDA jda, String guildId) {
        SlashCommandData poker = Commands.slash("poker", "Poker room management")
                .addSubcommands(
                        new SubcommandData("open", "Open a new poker room (you become the owner)")
                                .addOption(OptionType.INTEGER, "buyin", "Starting chips for every player", true)
                                .addOption(OptionType.INTEGER, "sb", "Small blind (chips)", true)
                                .addOption(OptionType.INTEGER, "bb", "Big blind (chips)", true),
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
                        .addOption(OptionType.INTEGER, "amount", "Chips to bet", true),
                Commands.slash("raise", "Raise the bet")
                        .addOption(OptionType.INTEGER, "amount", "Total chips to raise TO", true));

        if (guildId != null && !guildId.isBlank()) {
            Guild guild = jda.getGuildById(guildId);
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
