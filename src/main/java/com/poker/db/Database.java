package com.poker.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin SQLite persistence layer. Stores rooms, their players and a history of
 * settled hands. DB problems are logged but never crash the game — the bot can
 * keep running on in-memory state if a write fails.
 */
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Connection conn;

    public Database(String path) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            initSchema();
            log.info("SQLite database ready at {}", path);
        } catch (SQLException e) {
            throw new RuntimeException("Could not open SQLite database at " + path, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rooms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id TEXT,
                        parent_channel_id TEXT,
                        thread_id TEXT,
                        owner_id TEXT,
                        small_blind INTEGER,
                        big_blind INTEGER,
                        buy_in INTEGER,
                        state TEXT,
                        created_at INTEGER
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS room_players (
                        room_id INTEGER,
                        user_id TEXT,
                        name TEXT,
                        join_order INTEGER,
                        stack INTEGER,
                        PRIMARY KEY (room_id, user_id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS hands (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        room_id INTEGER,
                        hand_no INTEGER,
                        board TEXT,
                        pot INTEGER,
                        created_at INTEGER
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS hand_results (
                        hand_id INTEGER,
                        user_id TEXT,
                        amount_won INTEGER,
                        hole TEXT,
                        hand_desc TEXT
                    )""");
        }
    }

    public synchronized long createRoom(String guildId, String parentChannelId, String ownerId,
                                        long sb, long bb, long buyIn) {
        String sql = "INSERT INTO rooms(guild_id, parent_channel_id, owner_id, small_blind, big_blind, buy_in, state, created_at) "
                + "VALUES(?,?,?,?,?,?, 'WAITING', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, guildId);
            ps.setString(2, parentChannelId);
            ps.setString(3, ownerId);
            ps.setLong(4, sb);
            ps.setLong(5, bb);
            ps.setLong(6, buyIn);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.warn("createRoom failed", e);
        }
        return -1;
    }

    public synchronized void setThread(long roomId, String threadId) {
        run("UPDATE rooms SET thread_id=? WHERE id=?", ps -> {
            ps.setString(1, threadId);
            ps.setLong(2, roomId);
        });
    }

    public synchronized void setState(long roomId, String state) {
        run("UPDATE rooms SET state=? WHERE id=?", ps -> {
            ps.setString(1, state);
            ps.setLong(2, roomId);
        });
    }

    public synchronized void upsertPlayer(long roomId, String userId, String name, long joinOrder, long stack) {
        run("INSERT INTO room_players(room_id,user_id,name,join_order,stack) VALUES(?,?,?,?,?) "
                + "ON CONFLICT(room_id,user_id) DO UPDATE SET name=excluded.name, stack=excluded.stack", ps -> {
            ps.setLong(1, roomId);
            ps.setString(2, userId);
            ps.setString(3, name);
            ps.setLong(4, joinOrder);
            ps.setLong(5, stack);
        });
    }

    public synchronized void updateStack(long roomId, String userId, long stack) {
        run("UPDATE room_players SET stack=? WHERE room_id=? AND user_id=?", ps -> {
            ps.setLong(1, stack);
            ps.setLong(2, roomId);
            ps.setString(3, userId);
        });
    }

    public synchronized void removePlayer(long roomId, String userId) {
        run("DELETE FROM room_players WHERE room_id=? AND user_id=?", ps -> {
            ps.setLong(1, roomId);
            ps.setString(2, userId);
        });
    }

    public synchronized long recordHand(long roomId, int handNo, String board, long pot) {
        String sql = "INSERT INTO hands(room_id,hand_no,board,pot,created_at) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, roomId);
            ps.setInt(2, handNo);
            ps.setString(3, board);
            ps.setLong(4, pot);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.warn("recordHand failed", e);
        }
        return -1;
    }

    public synchronized void recordResult(long handId, String userId, long amountWon, String hole, String handDesc) {
        run("INSERT INTO hand_results(hand_id,user_id,amount_won,hole,hand_desc) VALUES(?,?,?,?,?)", ps -> {
            ps.setLong(1, handId);
            ps.setString(2, userId);
            ps.setLong(3, amountWon);
            ps.setString(4, hole);
            ps.setString(5, handDesc);
        });
    }

    public synchronized void closeRoom(long roomId) {
        setState(roomId, "CLOSED");
    }

    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing database", e);
        }
    }

    // --- small helper to cut down on boilerplate ---

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void run(String sql, Binder binder) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("SQL failed: {}", sql, e);
        }
    }
}
