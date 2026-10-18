# Dialog Engine: a guide for AI agents

You are writing dialogs for **Dialog Engine**, a Spigot and Paper plugin that turns YAML
files into Minecraft's own dialog screens (1.21.6+). This file is everything
you need. The person you are working for will drop what you write into their
server and run `/de reload`.

## How to work

1. **Ask what the screen is for** if you were not told: who opens it, how
   (a command? another dialog?), and what each button should DO on their
   server. Use their real commands; do not invent plugin commands they may not
   have. If unsure, write the action as a `[message]` saying what it would do.
2. **Write one `.yml` file** in `plugins/DialogEngine/dialogs/<namespace>/`.
   The folder name is the namespace (use the server's name or the feature's,
   lower case). Every top-level key in the file is one dialog.
3. **Give it a `command:`** so it can be opened by typing it, unless it is only
   ever opened from another dialog with `[open]`.
4. **Tell the person to run `/de reload`.** It reports every mistake with the
   file and the line. `/de info <namespace:id>` shows what a dialog is made
   of, `/de open <namespace:id>` opens it.

## Rules that save a round trip

- Text is MiniMessage (`<gold>`, `<bold>`, `<#3670f8>`), never `&` codes.
- Quote any string that starts with `<`, `[`, `{`, `%`, `*` or contains `: `.
- Actions run in order. A line with no `[tag]` is a command the PLAYER runs,
  with the player's permissions; use `[console]` for anything staff-only, and
  guard the button with `permission:`.
- End a button that leaves the screen with `[close]`, or it stays up.
- What a player types is available as `{key}` in every action after it.
  Check it (`required`, `pattern`, `min-length`) before using it in a command.
  A command takes it only as a name, number or id; for free text write
  `{key|raw}` (the last argument of a plugin's command, never vanilla's `say`
  or `msg`), and inside a quoted JSON or SNBT string `{key|json}` or `{key|snbt}`.
- Before a command takes something (money, items), check the player has it
  with `[if]`, e.g. `[if] %vault_eco_balance% >= 100`. A command that does not
  exist or throws stops the actions after it, but one that ran and refused
  (not enough money) counts as done, so the rest would still run.
- Keep labels short: buttons are 150 pixels wide unless `width:` says so.
- Two to four buttons per row (`columns:`) reads best; use a list with
  `for-each` instead of writing out many near-identical buttons.
- Prefer one dialog per job, linked with `[open]` and `[back]`, over one huge
  screen.

## Samples shipped with the plugin

`plugins/DialogEngine/dialogs/samples/` holds working dialogs, one file each: a
welcome notice, server rules with a confirmation, a shop with a dropdown and a
slider, kits with item pictures and a permission button, warps from a list in
the YAML, a paged list of online players, a profile built from placeholders
and conditions, a report form with arguments and checks, and a settings page
with every input type. Read the nearest one before writing your own. (They are
missing if the server owner set `samples: false`.)

## The format

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
  quick-action: true            # the Quick Actions key (G) opens it (optional)
```

`quick-action: true` puts a dialog on the Quick Actions key (G). Each player's
copy is drawn as they join and is not redrawn, so do not use it for a dialog
whose values change while they play: use `quick-action: launcher`, which has G
open a button that opens the dialog fresh. A change takes a server restart.

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
