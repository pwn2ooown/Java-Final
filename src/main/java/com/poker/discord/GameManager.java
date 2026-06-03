package com.poker.discord;

import com.poker.db.Database;
import net.dv8tion.jda.api.JDA;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of active poker rooms. A room is reachable both by its lobby (parent)
 * channel — where players run {@code /poker join} — and by its private thread,
 * where the actual game and betting happen.
 */
public class GameManager {

    private final Database db;
    private JDA jda;

    private final Map<Long, GameSession> byThread = new ConcurrentHashMap<>();
    private final Map<Long, GameSession> byParent = new ConcurrentHashMap<>();

    public GameManager(Database db) {
        this.db = db;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public JDA jda() {
        return jda;
    }

    public Database db() {
        return db;
    }

    /** Finds the session for an interaction in {@code channelId} (thread or lobby). */
    public GameSession resolve(long channelId) {
        GameSession s = byThread.get(channelId);
        return (s != null) ? s : byParent.get(channelId);
    }

    public boolean hasRoomInChannel(long parentChannelId) {
        return byParent.containsKey(parentChannelId);
    }

    public void registerParent(long parentChannelId, GameSession session) {
        byParent.put(parentChannelId, session);
    }

    public void registerThread(long threadId, GameSession session) {
        byThread.put(threadId, session);
    }

    public void unregister(GameSession session) {
        byParent.remove(session.parentChannelId());
        if (session.threadId() != 0) {
            byThread.remove(session.threadId());
        }
    }
}
