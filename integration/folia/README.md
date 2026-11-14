# The Folia session

Dialog Engine on a real **Folia** server, with two real players in two
different regions, checked from both ends: what the players receive, and what
the server logs.

```bash
cd integration/folia
npm install                 # once: mineflayer, for the two players
node run.mjs                # build, boot Folia, play, check
node run.mjs --no-build     # the jar already in build/libs
node run.mjs --jar <jar>    # any Dialog Engine jar
node run.mjs --folia <jar>  # another server jar (a Paper one runs the same session on Paper)
node run.mjs --keep         # leave build/folia behind
```

It downloads a Folia build (cached in `integration/.cache`, gitignored), boots
it in `build/folia` with a few test dialogs, and two
[mineflayer](https://github.com/PrismarineJS/mineflayer) players join. One is
sent 3,000 blocks away, so the two are in different regions. Then:

- a dialog opened for the far player by the console and by the other player
- a dialog's own command
- button clicks, sent as the game's own `custom_click_action` packet: a
  `[console]` action and what follows it, a `[delay]`, an `[open]`, and a
  failing console command that has to stop the actions after it
- a drawn dialog (the samples' Social menu), its pack, a page turn and a setting
- `/de reload` typed by a player and by the console
- the API's events, as a plugin hears them: `probe/EventProbe.java` is
  compiled against the jar under test and logs each one, and the session
  checks every `DialogCloseEvent` reason (exit, button, `[close]`, another
  dialog, a chest, quitting, and on Paper another world), `DialogClickEvent`
  named by `[event]` and fired by a drawn dialog with its settings,
  `DialogSettingChangeEvent`, and that each arrives on the player's own thread
- a clean stop

Every `CHECK` line it prints is one thing a player saw. **The run also fails on
any stack trace in the server's log**, because on Folia a thread mistake is an
exception wherever it happens. Pass `VERBOSE=1` to see the players' chat and
every dialog they are sent.

## What it cannot reach

Anything a client has to draw, the Quick Actions key (G), `/de edit`, and
PlaceholderAPI.
