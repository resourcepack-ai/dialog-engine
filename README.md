# Dialog Engine

Minecraft's dialog screens (the real menus the client draws since 1.21.6:
text, items, text boxes, dropdowns, switches, sliders and buttons), written
in YAML and driven from Java. One plugin that every other plugin can build its
menus on.

- **Server owners** write dialogs in `plugins/DialogEngine/dialogs/` and give
  them commands, permissions, conditions, lists and actions. No code.
- **Developers** ship dialogs with their plugin, add their own actions,
  placeholders and lists, and hear every click as an event.

Runs on **Spigot or Paper 1.21.7 or newer**, one jar for both, with players
on a 1.21.6+ client.
[PlaceholderAPI](https://www.spigotmc.org/resources/6245/) is optional.

## Installing

Download the jar from
[SpigotMC](https://www.spigotmc.org/resources/139513/), drop it in
`plugins/` and restart. The first start writes `config.yml`, the sample
dialogs and `AI-GUIDE.md` into `plugins/DialogEngine/`.

Two things talk to the internet, and both can be turned off in `config.yml`:
`update-check` asks SpigotMC for the latest version, and `telemetry` sends an
anonymous count (a random id, the number of players online and the names of
plugins that depend on Dialog Engine) every 30 seconds. Drawn dialogs are the
third: `/de edit`, and a drawn dialog whose `dialog.yml` was edited by hand,
send that one dialog to the editor at `edit.url` to be drawn. Plain YAML
dialogs never leave the server.

## Commands

| Command | | Permission |
|---|---|---|
| `/de list` | Every dialog, by namespace, with its command | `dialogengine.list` |
| `/de open <id> [player] [key=value ...]` | Open one, with values | `dialogengine.open` |
| `/de info <id>` | What a dialog is made of | `dialogengine.info` |
| `/de edit <id>` | Draw one in the browser editor, or make a new one | `dialogengine.edit` |
| `/de pack` | Write the drawn dialogs' resource pack to a file | `dialogengine.pack` |
| `/de reload` | Read everything again, and say what is wrong | `dialogengine.reload` |

`/dialogengine` and `/dialogs` are the same command.

## Permissions

Every node is op-only by default.

| Node | Allows |
|---|---|
| `dialogengine.list` | `/de list` |
| `dialogengine.open` | `/de open` on yourself. It opens any dialog, whatever `permission:` the dialog asks for |
| `dialogengine.open.others` | `/de open <id> <player>` on somebody else, on top of `dialogengine.open` |
| `dialogengine.info` | `/de info` |
| `dialogengine.edit` | `/de edit`. Pressing Send in the editor writes files into the server's dialog folder |
| `dialogengine.pack` | `/de pack` |
| `dialogengine.reload` | `/de reload` |
| `dialogengine.pack.notify` | Being told the address players download the drawn dialogs' pack from, when it fails to load for you |
| `dialogengine.samples` | Opening the sample dialogs |
| `dialogengine.update` | Being told on joining that a newer Dialog Engine is out |
| `dialogengine.admin` | All of the above, in one node |

A dialog's own `permission:` (who may open it with its command) and a
button's are whatever node you write there; Dialog Engine declares none of
them.

## Samples

On the first start the plugin writes ten sample dialogs into
`plugins/DialogEngine/dialogs/samples/`, one file each: a welcome notice,
server rules, a shop, kits, warps, a player list, a profile, a report form and
a settings page, plus a menu of them all. Open the menu with
`/dialog-samples`. Only ops (`dialogengine.samples`) can open them. Set
`samples: false` in config.yml and they are never written.

Two more are **drawn** dialogs, shown with a resource pack, in
`plugins/DialogEngine/drawn/samples/`: a punishment panel
(`/sample-punish <player>`) and a six-page Social settings menu
(`/sample-social`). `/de edit samples:punish` opens one in the editor. Their
pack is sent when a player opens one, never on join, so the samples alone never
ask anybody to download anything.

## Writing a dialog

In the browser: `/de edit <namespace:id>` draws a dialog in Studio's dialog
editor (no account), a new one or a drawn one you made there before, and Send
to server writes it into `drawn/<namespace>/<id>/`. A YAML dialog is edited in
its file, not in the browser. `edit.url` in config.yml says where the editor is, and must
be https (plain http only for one on the same machine).


With an AI: hand it `plugins/DialogEngine/AI-GUIDE.md`, which the plugin
writes on every start. It is this section plus the rules that catch the usual
mistakes.


Every folder in `dialogs/` is a namespace, and every key in a `.yml` file in
it is a dialog: `welcome` in `dialogs/samples/menus.yml` is
`samples:welcome`. Files sitting directly in `dialogs/` are in the namespace
`server`. Text is [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

```yaml
shop:
  command: shop                 # /shop opens it (optional)
  aliases: [store]
  permission: myserver.shop     # who may open it themselves (optional)
  title: "<gold><bold>Shop"
  columns: 2                    # buttons side by side
  escape: true                  # Escape closes it (default true)
  after: seamless               # seamless | wait | close | none, see config.yml
  body:
    - "<gray>You have <white>%vault_eco_balance%</white> coins."   # text
    - item: diamond             # an item, with words beside it
      name: "<aqua>Diamond"
      description: "<gray>A coin each"
  inputs:
    - key: amount
      type: slider
      label: "How many"
      min: 1
      max: 64
      step: 1
      initial: 16
  buttons:
    - label: "<green>Buy"
      tooltip: "Buy {amount} diamonds"
      actions:
        - "[if] %vault_eco_balance% >= {amount}"    # stop unless they can pay (see Actions)
        - "[console] eco take {player} {amount}"
        - "[console] give {player} diamond {amount}"
        - "[message] <green>Bought {amount} diamonds."
        - "[close]"
  exit:                         # the button Escape also presses
    label: "Close"
```

### Body

A body entry is a string (text), or a map:

| Key | |
|---|---|
| `text:` | MiniMessage; a list is several lines. `width:` 1-1024, default 200 |
| `item:` | An item id, drawn with `description:` beside it. Also `name:`, `lore:`, `amount:`, `model:` (an item model id), `glow:`, `tooltip:` (hover shows it), `decorations:` (count and bar), `width:`/`height:` 1-256 |
| `head:` | A player's name: the item is their head (`item:` defaults to `player_head`) |
| `if:` | Only drawn when this holds (see Conditions) |
| `for-each:` | Drawn once per row of a list (see Lists) |

### Inputs

| `type:` | Keys | Value in `{key}` |
|---|---|---|
| `text` | `max-length`, `lines` + `height` for a text area, `initial` | What was typed |
| `options` | `options:` a list of `{id, text, initial}` (or `- id: Text`), or a `for-each` | The chosen `id`; `{key.label}` is its text |
| `toggle` | `initial`, `on-true`, `on-false` | `true` / `false`; `{key.text}` is `on-true`/`on-false` |
| `slider` | `min`, `max`, `step`, `initial`, `format` (a translation key) | The number |

All take `label:`, `label-visible:`, `width:` and `if:`.

**Checks.** A text input can have `required: true`, `min-length:`,
`pattern:` (a Java regex the whole value must match) and `error:` (the
message). A button press with a failing input draws the dialog again with what
was typed still in it and the message on top (`{error}`, which a dialog can
also print where it likes). A button with `validate: false` skips the checks.
The exit button never checks.

What a player typed is escaped wherever it is printed, so nobody can put a
colour or a click into somebody else's screen, and a command takes it only as
a name, a number or an id unless the dialog says otherwise (see Actions).

### Buttons

`label:`, `tooltip:`, `width:` (1-1024, default 150), `permission:` (hidden
without it), `if:`, `for-each:`, `validate:`, and `actions:`. Shorthands:
`command:`, `console:`, `open:`, `url:`, `copy:`, `suggest:`, `message:`,
`close: true`, `back: true`.

The layout follows the buttons: one is a notice, more are a grid of
`columns:`. Say `type: confirmation` with `confirm:` and `cancel:` buttons for
a yes/no, or `type: links` for the server links screen.

### Actions

Run in order. A line without a `[tag]` is a command the player runs.

| Action | |
|---|---|
| `[player] cmd` | The player runs it, with their own permissions |
| `[console] cmd` | The console runs it |
| `[message] text` / `[broadcast] text` / `[actionbar] text` | MiniMessage |
| `[title] title \| subtitle` | |
| `[sound] id [volume] [pitch]` | `entity.experience_orb.pickup 1 1.4` |
| `[open] id [key=value ...]` | Another dialog, carrying this one's values (and what was typed) over |
| `[back]` | The dialog this one was opened from, as it was |
| `[refresh]` | This one again, with what was typed still in it |
| `[page] next\|prev\|first\|last\|N` | Turn the page of a paged list |
| `[set] key=value ...` | Set a value for the actions after, and any dialog opened |
| `[clear] [keys]` | Forget what was typed (all inputs, or the keys named) |
| `[close]` | |
| `[delay] ticks` | Wait before the rest |
| `[if] condition` | Stop here unless it holds |
| `[permission] node` | Stop here unless the player has it |
| `[url] link` / `[copy] text` / `[suggest] /cmd` | As a button's only action these are the client's own (open the link, copy, fill the chat box). The server never hears that such a button was pressed, so a dialog with one is shown as `after: close` even under `wait` |
| `[myplugin:tag] ...` | Whatever another plugin registered |

**What a player typed, in a command.** A `[console]` or `[player]` command
takes a typed value (a text input, a command's `args:`, values from
`/de open`, `{player_display}`) only when it is letters, digits, `_`, `.` and
`-`: a name, a number, an id. Anything else (`@a`, a space, a quote) and the
button runs nothing (or nothing more, for a value an earlier `[set]` made): a
text input is drawn again with the message `unsafe-value` on it, anything else
gets it in chat, and the console says which placeholder it was. Where more is
safe, say how the value is used:

| | |
|---|---|
| `{key\|raw}` | As typed, on one line. For the last, free-text argument of a plugin's command: `staffchat {player}: {details\|raw}`. Not for vanilla's `say`, `msg` or `tell`, which read `@a` in it. In a `[player]` command it is usually fine: the player could type that command themselves |
| `{key\|json}` | Escaped for the inside of a quoted JSON string: `tellraw @a {"text":"{msg\|json}"}` |
| `{key\|snbt}` | Escaped for the inside of a quoted SNBT string: `give {player} paper[custom_name='{name\|snbt}']` |

Values from the dialog itself, from plugins and PlaceholderAPI, and from
sliders, toggles and dropdowns (only options the dialog drew count) are not
checked. A filter works on them too.

**A command that fails stops the rest.** When a `[console]` or `[player]`
command fails as far as the server can tell, the actions after it do not run,
and for `[console]` the player is told (`command-failed`) and the console
logs it. The server can only tell when the command does not exist (the plugin
behind it is missing, say) or throws an error. A command that ran and refused,
like an `eco take` without the money in the account, has succeeded as far as
the server knows, whatever it printed. So check before taking anything, as the
shop above does with `[if] %vault_eco_balance% >= {amount}` (PlaceholderAPI's
Vault expansion).

### Placeholders

`{name}` anywhere: titles, text, labels, tooltips, actions, conditions.

- What the dialog was opened with (`/de open shop Steve coins=40`, an API
  call, or a command's `args:`), and everything typed into its inputs.
- `{player}` `{player_uuid}` `{player_display}` `{player_world}` `{player_x}`
  `{player_y}` `{player_z}` `{player_health}` `{player_level}`
  `{player_gamemode}` `{online}` `{max_players}` `{dialog}`.
- Whatever a plugin registered, and every PlaceholderAPI `%placeholder%`.
  One in what a player typed is printed as typed, never filled.
- **A choice**: `{mode?on:Enabled|off:Disabled|Unknown}` prints the words for
  the value `mode` has, and the last entry when none matches. `$value` in the
  words prints the value itself.
- **A filter**: `{name|raw}`, `{name|json}`, `{name|snbt}`, for putting what a
  player typed into a command (see Actions).

A name nothing knows is left as written.

A dialog can be **about somebody**: `args: [target]` makes `/punish Steve`
open it with `{target}` = Steve. The last argument takes the rest of the line.
`if-missing: other_dialog` opens another one when an argument is left out.
`defaults:` gives values the dialog starts with.

### Conditions

`if:` on a body entry, input or button, and the `[if]` action.

```
{muted}                            true unless empty, false, 0, no, off, null or unfilled
!{muted}
{warnings} >= 3                    numbers compare as numbers, anything else as words
{rank} == admin && perm:server.staff
({page} > 1 || {mode} != "quiet mode")
```

### Lists

`for-each: name` on a body entry, a button or an input's `options:` repeats it
once per row, with `{row.field}` for the row's fields (`as:` renames `row`),
`{row.index}`, `{row.first}` and `{row.last}`. `per-page: N` pages it:
`{page}`, `{pages}`, `{has_next}` and `{has_prev}` are set, and `[page] next`
turns. `limit: N` caps it.

Built in: `online_players` and `other_players` (`name`, `uuid`, `world`,
`ping`; vanished players stay hidden) and `worlds`. A dialog can declare its
own under `lists:`, and plugins register more.

```yaml
warps:
  title: Warps
  lists:
    places:
      - { id: spawn, name: "Spawn" }
      - { id: arena, name: "<red>Arena" }
  buttons:
    - for-each: places
      label: "{row.name}"
      actions: ["[player] warp {row.id}", "[close]"]
```

### A dialog Minecraft's own format can say and this one can't

`json: file.json` (beside the YAML) sends a `minecraft:dialog` object exactly
as written, with `{placeholders}` filled. Its buttons do whatever the JSON
says.

## For developers

`depend: [DialogEngine]` (or `softdepend`), and compile against the jar.

```java
DialogEngineAPI dialogs = DialogEngine.api();

// Ship your dialogs: copy defaults into your data folder, then hand it over.
// Server owners edit them there; /de reload reads them again.
saveResource("dialogs/menu.yml", false);
dialogs.registerFolder(this, "myplugin", new File(getDataFolder(), "dialogs"));

// Open one, with values for its {placeholders}.
dialogs.open(player, "myplugin:menu", Map.of("coins", "120"));
dialogs.refresh(player);   // the player's current dialog, drawn again
dialogs.close(player);

// A new action: [myplugin:give] diamond 3
dialogs.registerAction(this, "myplugin:give", (ctx, arg) -> {
    String[] p = arg.split(" ");
    ctx.viewer().getInventory().addItem(new ItemStack(Material.matchMaterial(p[0]), Integer.parseInt(p[1])));
    ctx.refresh();
});

// {myplugin_coins}
dialogs.registerPlaceholders(this, "myplugin_", (viewer, ctx, key) ->
        key.equals("coins") ? String.valueOf(economy.balance(viewer)) : null);

// for-each: myplugin_quests
dialogs.registerList(this, "myplugin_quests", (viewer, ctx) -> quests.of(viewer).stream()
        .map(q -> Map.of("id", q.id(), "name", dialogs.escape(q.name()), "done", String.valueOf(q.done())))
        .toList());
```

A dialog built in code is a `ConfigurationSection` in the same format:
`registerDialog(plugin, "myplugin:x", section)`.

### Shipping drawn dialogs

A plugin can ship dialogs drawn in the browser editor inside its jar: put each
one's folder (as `/de edit` writes it) under `drawn/<namespace>/` in your
resources, list every file in `drawn/<namespace>/files.txt`, and call

```java
dialogs.installDrawn(this, "myplugin");   // then open "myplugin:<name>" as usual
```

They are copied into `plugins/DialogEngine/drawn/myplugin/` the first time
each version of your plugin runs. One the server owner has changed since (with
`/de edit` or by hand) is kept as they made it. Their resource pack is sent
when a player first opens one, never on join.

A drawn dialog's switches, sliders and dropdowns are settings kept on the
player, which your plugin reads with `dialogs.setting(player, "name")`. A
list's rows are values like any other: `warps_3` is row three, `warps_3_note`
its `note` field and `warps_count` how many there are.

### Events

| Event | |
|---|---|
| `DialogOpenEvent` | A dialog is about to be drawn. Cancellable; `values()` can be changed |
| `DialogClickEvent` | A button was pressed, its inputs passed, its actions not yet run. Cancellable; `getButtonId()`, `getInputs()`, `getActions()` (changeable), and `getContext()` to open, refresh or close |
| `DialogInputErrorEvent` | An input failed its check; the message can be changed |
| `DialogEngineReloadEvent` | Everything was read again |

A button with an `id:` and no actions is a button your plugin answers in
`DialogClickEvent`.

Values you pass are read as MiniMessage. Anything a player wrote goes through
`dialogs.escape(...)` first.

Everything a plugin registers is dropped when it disables.

## How a click works

Every button is a server callback bound to the drawing of the dialog the
player saw: the values it was drawn with, and its inputs. Nothing a player can
type in chat presses a button. Only the latest drawing can be pressed: a new
one (another dialog, a refresh, a failed check) retires the buttons of the one
before. A button's `permission:` and `if:` are asked again when it is pressed,
so one that has since stopped applying does nothing. Buttons stop working
`click-lifetime-minutes` after the dialog was drawn, and a player gets at most
ten clicks a second.

A drawn dialog's clicks (`/de edit`) are the same: each action click works
once per showing, only while that dialog is on the player's screen, and only
runs a command that is in its `dialog.yml`.

Under `after: seamless` (the default) a dialog stays on the screen exactly as it
is until the engine answers the click: the next dialog takes its place with
nothing in between, and when none follows, the engine closes it on the next
tick. There is no flash of the world and no waiting screen. A button that runs
a command closes the dialog before the command runs, unless one of its later
actions opens the next dialog itself, so a screen that command opens (another
plugin's dialog, a chest) is never closed after it arrives. A button the client
handles by itself (`[url]`, `[copy]`, `[suggest]`) never reaches the engine and
leaves the dialog open.

Under `after: wait` the client shows its "Waiting for Server" screen until the
engine answers: with the next dialog, or by closing the screen
`close-delay-ticks` after the button's actions have run. A dialog with a
client-only button is shown as `after: close` instead. A drawn dialog set to
wait in Studio is answered the same way.

## Building

JDK 21. Dialog Engine compiles part of [RP Engine](https://github.com/resourcepack-ai/rp-engine)
in (the code that runs drawn dialogs), so clone it beside this repository:

```bash
git clone https://github.com/resourcepack-ai/rp-engine
git clone https://github.com/resourcepack-ai/dialog-engine
cd dialog-engine
./gradlew build
```

The plugin is `build/libs/dialog-engine-<version>.jar`. Other dependencies
come from Maven Central, SpigotMC's repository and
[repo.resourcepack.ai](https://repo.resourcepack.ai) (`ai.resourcepack:common`,
a small shared library for the update check and usage count).

## Support

Questions, bugs and ideas: [the ResourcePack AI Discord](https://resourcepack.ai/discord).

## Licence

[GPL-3.0](LICENSE).
