package com.poker.discord;

import com.poker.db.Database;
import net.dv8tion.jda.api.JDA;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of active poker rooms. Multiple rooms can exist in the same parent
 * channel — each is keyed by its private thread ID.
 */
public class GameManager {

    private final Database db;
    private JDA jda;

    private final Map<Long, GameSession> byThread = new ConcurrentHashMap<>();

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

    /** Finds the session for an interaction in {@code channelId} (must be the thread). */
    public GameSession resolve(long channelId) {
        return byThread.get(channelId);
    }

    public void registerThread(long threadId, GameSession session) {
        byThread.put(threadId, session);
    }

    public void unregister(GameSession session) {
        if (session.threadId() != 0) {
            byThread.remove(session.threadId());
        }
    }
}
