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

## Commands

| Command | |
|---|---|
| `/de list` | Every dialog, by namespace, with its command |
| `/de open <id> [player] [key=value ...]` | Open one on somebody, with values |
| `/de info <id>` | What a dialog is made of |
| `/de reload` | Read everything again, and say what is wrong |

`/dialogengine` and `/dialogs` are the same command. Permission:
`dialogengine.admin`.

## Samples

On the first start the plugin writes ten sample dialogs into
`plugins/DialogEngine/dialogs/samples/`, one file each: a welcome notice,
server rules, a shop, kits, warps, a player list, a profile, a report form and
a settings page, plus a menu of them all. Open the menu with
`/dialog-samples`. Only ops (`dialogengine.samples`) can open them. Set
`samples: false` in config.yml and they are never written.

## Writing a dialog

In the browser: `/de edit <namespace:id>` opens it in Studio's dialog editor
(no account) and Send to server writes it back under its key, leaving the rest
of the file alone. `edit.url` in config.yml says where the editor is.


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
  after: wait                   # wait | close | none, see config.yml
  body:
    - "<gray>You have <white>%vault_eco_balance%</white> coins."   # text
    - item: diamond             # an item, with words beside it
      name: "<aqua>Diamond"
      description: "<gray>64 for 100 coins"
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
colour or a click into somebody else's screen.

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
| `[url] link` / `[copy] text` / `[suggest] /cmd` | As a button's only action these are the client's own (open the link, copy, fill the chat box) |
| `[myplugin:tag] ...` | Whatever another plugin registered |

### Placeholders

`{name}` anywhere: titles, text, labels, tooltips, actions, conditions.

- What the dialog was opened with (`/de open shop Steve coins=40`, an API
  call, or a command's `args:`), and everything typed into its inputs.
- `{player}` `{player_uuid}` `{player_display}` `{player_world}` `{player_x}`
  `{player_y}` `{player_z}` `{player_health}` `{player_level}`
  `{player_gamemode}` `{online}` `{max_players}` `{dialog}`.
- Whatever a plugin registered, and every PlaceholderAPI `%placeholder%`.
- **A choice**: `{mode?on:Enabled|off:Disabled|Unknown}` prints the words for
  the value `mode` has, and the last entry when none matches. `$value` in the
  words prints the value itself.

A name nothing knows is left as written.

A dialog can be **about somebody**: `args: [target]` makes `/punish Steve`
open it with `{target}` = Steve. The last argument takes the rest of the line.
`if-missing: other_dialog` opens another one when an argument is left out.
`defaults:` gives values the dialog starts with.

### Conditions

`if:` on a body entry, input or button, and the `[if]` action.

```
{muted}                            true unless empty, false, 0, no, off or unknown
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
type in chat presses a button, and a click on an old screen acts on what that
screen showed. Buttons stop working `click-lifetime-minutes` after the dialog
was drawn.

Under `after: wait` (the default) the client shows a waiting screen until the
engine answers, so one dialog replaces another with no flash of the world in
between.
