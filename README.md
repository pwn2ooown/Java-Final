# Java-Final — Discord Poker Bot 🃏

A **No-Limit Texas Hold'em** bot for Discord, written in Java (JDA 5) with a
SQLite database. Each table runs in its own **private thread**; public game state
is posted in the thread, and every player sees only their own hole cards via
**ephemeral** messages.

---

## Features

- Owner opens a room and sets **buy-in, small blind and big blind** (locked once the game starts).
- Players join a lobby, owner starts → **seats are randomized**.
- Full hand loop: blinds → deal hole cards → pre-flop / flop / turn / river betting → showdown → payout → cleanup → next hand.
- **Dealer button rotates** each hand (heads-up handled correctly).
- Correct **No-Limit betting rules**: bet ≥ 1 BB or all-in, proper **min-raise**, and the **incomplete all-in raise** rule (a short all-in does not re-open the betting for players who already acted).
- **Turn lock**: only the player to act can act; anyone else is rejected.
- **Side pots** computed automatically for any number of all-ins, with **uncalled bets refunded**.
- **30-second timer** per turn: 1st timeout = auto-fold (warning), 2nd timeout = kicked from the room.
- Quit any time (auto-folds the current hand, no longer dealt in), join any time (seated next hand).
- If the **owner leaves/is kicked**, the next player by join order becomes owner.
- At least **2 players** required to start.
- **Showdown reveals every remaining player's cards.**
- Game state, players, stacks and a hand history are persisted to **SQLite**.
- Owner can `end` (stop after the current hand) or `forceend` (stop immediately) from any state.

---

## Requirements

- **Java 17+** (developed and tested on Java 21)
- **Maven 3.8+**

---

## 1. Get a Discord Bot Token

1. Open the **Discord Developer Portal**: <https://discord.com/developers/applications>
2. Click **New Application**, give it a name (e.g. `PokerBot`), and create it.
3. In the left sidebar open the **Bot** tab.
4. Click **Reset Token** (or **Add Bot** → **Yes, do it**), then **Copy** the token.
   This long string is your `DISCORD_TOKEN`. **Treat it like a password — never commit it.**
5. **Privileged Gateway Intents:** this bot needs **none** of them. You can leave
   *Presence*, *Server Members* and *Message Content* **OFF**.

### Invite the bot to your server

1. Go to the **OAuth2 → URL Generator** tab.
2. Under **Scopes**, check **`bot`** and **`applications.commands`**.
3. Under **Bot Permissions**, check:
   - Send Messages
   - Embed Links
   - Read Message History
   - **Create Private Threads**
   - **Send Messages in Threads**
   - **Manage Threads** (needed to add/remove members and clean up messages)
   - Manage Messages
   - Use Application Commands
4. Copy the generated URL at the bottom, open it in your browser, pick your server and **Authorize**.

### (Optional) Get your Server ID for instant commands

Global slash commands can take up to ~1 hour to appear. To make them show up
**instantly during development**, register them to a single server:

1. In Discord: **User Settings → Advanced → enable Developer Mode**.
2. Right-click your server icon → **Copy Server ID**.
3. Put it in `.env` as `GUILD_ID` (see below).

---

## 2. Configure `.env`

Copy the example file and paste in your token:

```bash
cp .env.example .env
```

Then edit `.env`:

```ini
DISCORD_TOKEN=paste-your-token-here
GUILD_ID=                # optional: a server ID for instant slash-command updates
DB_PATH=poker.db         # optional: SQLite file path (default poker.db)
```

`.env` and `*.db` are already in `.gitignore`, so your secret and database stay local.

---

## 3. Build & Run

```bash
# Run the tests (pure poker engine: hand ranking, side pots, betting rules)
mvn test

# Build a single runnable jar
mvn -DskipTests package

# Run it
java -jar target/poker-bot.jar
```

When you see `Poker bot is ready.` the bot is online.

---

## 4. How to Play

All commands are **slash commands**. Convenient **buttons** appear during play, but
everything also has a slash-command equivalent.

| Command | Who | Where | What it does |
|---|---|---|---|
| `/poker open buyin:<n> sb:<n> bb:<n>` | anyone | a text channel | Opens a room, creates the private thread, you become the owner |
| `/poker join` | anyone | the lobby channel | Take a seat (or press the **Join** button) |
| `/poker start` | owner | thread/lobby | Randomize seats and deal the first hand |
| `/poker status` | players | thread | Show the current table |
| `/poker leave` | players | thread | Leave (auto-folds your current hand) |
| `/poker end` | owner | thread | Stop **after** the current hand |
| `/poker forceend` | owner | thread | Stop **immediately** |

**Betting** (when it's your turn — use the buttons or these commands):

| Command / Button | Action |
|---|---|
| `/fold` · **Fold** | Fold |
| `/check` · **Check** | Check (only when nothing to call) |
| `/call` · **Call** | Call the current bet |
| `/bet amount:<n>` | Open the betting (≥ 1 big blind) |
| `/raise amount:<n>` · **Raise** | Raise **to** a total amount |
| `/allin` · **All-in** | Put all your chips in |
| **🂠 View my cards** | Privately (ephemerally) see your hole cards |

A typical session:

```
/poker open buyin:1000 sb:10 bb:20      ← owner, in #poker
(other players press Join, or /poker join)
/poker start                            ← owner
... bot @-mentions each player in turn; act within 30s ...
```

---

## Project Layout

```
src/main/java/com/poker/
├── Main.java                  entry point + slash-command registration
├── Config.java                .env / environment loader
├── db/Database.java           SQLite persistence
├── engine/                    pure, unit-tested poker logic (no Discord)
│   ├── Card, Rank, Suit, Deck
│   ├── HandEvaluator / HandValue / HandCategory   best 5-of-7 evaluation
│   └── PotManager             main + side pots, uncalled-bet refunds
├── game/                      game state machine (no Discord)
│   ├── PokerGame              blinds, betting rules, streets, showdown
│   ├── Player, Street, ActionType, HandResult
└── discord/                   Discord integration
    ├── GameManager            room registry
    ├── GameSession            one table: flow, threads, timers, rendering
    └── PokerListener          routes slash commands / buttons / modals
```

The `engine` and `game` packages have **no Discord dependency** and are covered by
JUnit tests in `src/test/java`.

---

## Design Notes & Assumptions

- **Buy-in is fixed at open.** Busted players (stack 0) are removed before the next hand; there is no re-buy.
- **Timeout strikes are cumulative per player for the life of the room** (1st = auto-fold, 2nd = kick), not reset by acting in between.
- **Hand messages are deleted** a few seconds after each hand's result is shown, to keep the thread clean.
- A bot restart ends any in-progress hand; the database keeps room/player/stack and hand-history records.
- One active room per text channel.
