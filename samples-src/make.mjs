// The drawn sample dialogs: Dialog-style documents, drawn by Studio into the
// folders Dialog Engine keeps a drawn dialog in, and shipped in the jar.
//
//   node samples-src/make.mjs [--studio https://studio.resourcepack.ai]
//
// Writes src/main/resources/drawn/samples/<name>/ for each: dialog.yml (the
// document, the file a server owner edits), .built (its hash, so the plugin
// does not ask Studio to draw it again), dialogs.json and pack/ (Studio's
// drawing, from the same /api/rpengine/build a hand edit is redrawn through),
// and drawn/samples/files.txt, every file, which is how the plugin finds them
// in its jar. Run it after changing a document here, and bump the version.
import { createHash } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
// The `yaml` package: installed here (npm install yaml), or else Studio's copy
// when a Studio checkout sits beside this directory.
const YAML = (() => {
  for (const from of [import.meta.url, join(here, "../../studio/package.json")]) {
    try {
      return createRequire(from)("yaml");
    } catch {}
  }
  throw new Error("make.mjs needs the yaml package: npm install yaml");
})();
const args = process.argv.slice(2);
const studio = (args.includes("--studio") ? args[args.indexOf("--studio") + 1] : "https://studio.resourcepack.ai").replace(/\/$/, "");
const root = join(here, "../src/main/resources/drawn/samples");

// ------------------------------------------------------------------ punish

// A staff panel about one player: who, the punishment being
// chosen (three player settings, so the panel remembers them), and a button
// per punishment. The buttons only say what they would do: a sample must not
// ban anybody. Each one's comment in dialog.yml shows the real command.
const REASONS = ["Hacking", "Spam", "Toxicity", "Griefing", "Advertising", "Other"];
const LENGTHS = ["1h", "1d", "7d", "Perm"];
const choice = (bind, options) => `{${bind}?${options.map((o) => `${o.toLowerCase()}:${o}`).join("|")}|${options[0]}}`;
const REASON = choice("pun_reason", REASONS);
const LENGTH = choice("pun_length", LENGTHS);
const SILENT = "{pun_silent?on:Yes|No}";
const would = (what) =>
  `[message] <gray>Sample: this would ${what} <white>{target}</white> for <white>${REASON}</white>` +
  `${what === "warn" || what === "kick" ? "" : `, <white>${LENGTH}</white>`}.` +
  " Edit dialog.yml to run your punishment plugin's command.";

const punish = {
  title: "Punish {target}",
  style: "dialog",
  contentWidth: 320,
  afterAction: "close",
  canCloseWithEscape: true,
  hideFooter: true,
  buttons: [],
  document: {
    theme: { face: "#c6c6c6", accent: "#d8402f" },
    column: [
      {
        row: [
          {
            section: [
              {
                row: [
                  { icon: "head:{target}", size: 24, frame: true },
                  { column: [{ space: 2 }, { text: "{target}", style: "heading" }, { text: "Sample panel", style: "muted" }], gap: 2 },
                ],
                gap: 5,
                cross: "center",
              },
              { pair: ["Reason", REASON], style: "gold", w: "grow" },
              { pair: ["Length", LENGTH], style: "body", w: "grow" },
              { pair: ["Silent", SILENT], style: "body", w: "grow" },
            ],
            gap: 4,
            h: "grow",
            w: 112,
          },
          {
            section: [
              { setting: "Reason", icon: "ui:warning", control: { dropdown: REASONS[0], options: REASONS, bind: "pun_reason", w: 86 } },
              { setting: "Length", icon: "ui:hourglass", control: { segmented: LENGTHS, selected: 0, bind: "pun_length", compact: true, look: "raised" } },
              { setting: "Silent", icon: "ui:eye-off", control: { switch: false, bind: "pun_silent" } },
            ],
            title: "Punishment",
            h: "grow",
            w: "grow",
          },
        ],
        gap: 4,
        cross: "start",
        w: "grow",
      },
      {
        row: [
          { button: "Pick player", look: "light", icon: "ui:users", align: "left", w: 112, onClick: { action: "run_command", value: "[open] punish_pick" } },
          { button: "Warn", look: "light", icon: "ui:warning", w: "grow", onClick: { action: "run_command", value: would("warn") } },
          { button: "Mute", look: "light", icon: "ui:mute", w: "grow", onClick: { action: "run_command", value: would("mute") } },
        ],
        gap: 4,
        w: "grow",
      },
      {
        row: [
          { button: "Close", look: "light", icon: "ui:exit", align: "left", w: 112, onClick: { action: "close", value: "" } },
          { button: "Kick", look: "light", icon: "ui:exit", w: "grow", onClick: { action: "run_command", value: would("kick") } },
          { button: "Ban", look: "light", icon: "ui:hammer", selected: true, w: "grow", onClick: { action: "run_command", value: would("ban") } },
        ],
        gap: 4,
        w: "grow",
      },
    ],
    gap: 3,
  },
};

// ------------------------------------------------------------------ social

// The Social menu: one dialog of six pages down a sidebar, every control a
// setting kept on the player, so it stays as it was left. Nothing reads them;
// a plugin of yours would, through the API or {set_...} placeholders.
const PAGES = [
  { id: "profile", nav: "Profile", icon: "ui:user" },
  { id: "chat", nav: "Chat", icon: "ui:chat" },
  { id: "friends", nav: "Friends", icon: "ui:users" },
  { id: "audio", nav: "Audio", icon: "ui:music" },
  { id: "access", nav: "Accessibility", icon: "ui:eye" },
  { id: "controls", nav: "Controls", icon: "ui:keyboard" },
];

const seg = (bind, options, selected = 0) => ({ segmented: options, selected, bind: `set_${bind}`, compact: true, look: "raised" });
const sw = (bind, on = true) => ({ switch: on, bind: `set_${bind}`, w: 100 });
const drop = (bind, options, icon, w) => ({ dropdown: options[0], options, bind: `set_${bind}`, ...(icon ? { icon } : {}), w: w ?? 64 });
const slide = (bind, value, min, max, step, unit) => ({ slider: value, min, max, step, ...(unit ? { unit } : {}), bind: `set_${bind}`, w: 100 });
const row = (setting, icon, note, control) => ({ setting, icon, note, control });

const STATUS_DOT = { icon: "ui:dot-green", size: 9, bind: "set_status", states: { online: "ui:dot-green", away: "ui:dot-gold", busy: "ui:dot-red" } };
const STATUS_WORD = "{set_status?away:Away|busy:Busy|online:Online|Online}";
const NAME_COLOUR = "{set_name_colour?gold:#FFAA00|aqua:#55FFFF|pink:#FF55FF|#FFFFFF}";
const statusRow = (control) => ({
  row: [
    { column: [{ space: 4 }, STATUS_DOT], gap: 0, name: "Icon" },
    { column: [{ space: 3 }, { text: "Status", style: "body" }, { text: "What friends see", style: "muted" }], gap: 1, w: "grow", name: "Words" },
    control,
  ],
  gap: 5,
  cross: "start",
  w: "grow",
  name: "Status",
});

const CONTENT = {
  profile: [
    { title: "Identity", icon: "ui:user", rows: [
      row("Name colour", "ui:palette", "Your name in chat", drop("name_colour", ["Default", "Gold", "Aqua", "Pink"], null, 64)),
      row("Rank tag", "ui:crown", "Show your rank", sw("rank_tag")),
    ] },
    { title: "Status", icon: "ui:dot-green", rows: [
      statusRow(seg("status", ["Online", "Away", "Busy"])),
      row("Last seen", "ui:clock", "When you were last on", sw("last_seen", false)),
    ] },
  ],
  chat: [
    { title: "Messages", icon: "ui:chat", rows: [
      row("Show chat from", "ui:users", "Whose words you see", seg("chat", ["All", "Friends", "None"])),
      row("Emoji", "ui:sparkle", "Turn :codes: into pictures", sw("emoji")),
    ] },
    { title: "Filters", icon: "ui:filter", rows: [
      row("Word filter", "ui:shield", "Hide rude words", drop("filter", ["Mild", "Strict", "Off"], null, 64)),
      row("Timestamps", "ui:clock", "When it was sent", sw("timestamps", false)),
    ] },
  ],
  friends: [
    { title: "Friends", icon: "ui:users", rows: [
      row("Requests", "ui:person-add", "Who can add you", seg("requests", ["Anyone", "Nobody"])),
      row("Join alerts", "ui:bell", "When a friend joins", sw("join_alerts")),
    ] },
    { title: "Parties", icon: "ui:party", rows: [
      row("Party invites", "ui:mail", "Who can invite you", drop("invites", ["Anyone", "Friends", "Nobody"], null, 64)),
      row("Auto-accept", "ui:check", "Join friends at once", sw("auto_accept", false)),
    ] },
  ],
  audio: [
    { title: "Volume", icon: "ui:sound", rows: [
      row("Music", "ui:music", "Lobby music", slide("music", 40, 0, 100, 10, "%")),
      row("Mention sound", "ui:bell", "When you're named", drop("mention", ["Bell", "Pling", "Chime", "None"], "ui:bell", 64)),
    ] },
    { title: "Effects", icon: "ui:star", rows: [
      row("Footsteps", "ui:paw", "Other players' steps", seg("footsteps", ["All", "Near", "None"])),
      row("Victory jingle", "ui:trophy", "A tune when you win", sw("jingle")),
    ] },
  ],
  access: [
    { title: "Reading", icon: "ui:book", rows: [
      row("Text size", "ui:info", "Server messages", { segmented: ["Small", "Normal", "Large"], selected: 1, bind: "set_text_size", look: "cycle" }),
      row("High contrast", "ui:eye", "Stronger menu colours", sw("contrast", false)),
    ] },
    { title: "Comfort", icon: "ui:heart", rows: [
      row("Reduce flashing", "ui:bolt", "Calmer effects", sw("flashing", false)),
      row("Subtitles", "ui:chat", "Captions for sounds", sw("subtitles", false)),
    ] },
  ],
  controls: [
    { title: "Movement", icon: "ui:compass", rows: [
      row("Sneak", "ui:arrow-down", "Hold, or press once", seg("sneak", ["Hold", "Toggle"])),
      row("Double-tap sprint", "ui:arrow-right", "Tap forward twice", sw("sprint")),
    ] },
    { title: "Abilities", icon: "ui:sword", rows: [
      row("Ability key", "ui:keyboard", "Your class ability", drop("ability_key", ["F", "R", "G"], null, 40)),
      row("Aim assist", "ui:target", "Snap to targets", slide("aim", 50, 0, 100, 25, "%")),
    ] },
  ],
};

const sidebar = {
  column: [
    {
      row: [
        { icon: "head:{player}", size: 24 },
        {
          column: [
            { space: 2 },
            { text: "{player}", style: "heading", color: NAME_COLOUR },
            { space: 2 },
            { row: [STATUS_DOT, { column: [{ space: 1 }, { text: STATUS_WORD, style: "muted" }], gap: 0 }], gap: 3, cross: "start" },
          ],
          gap: 0,
        },
      ],
      surface: "well",
      pad: 4,
      gap: 4,
      cross: "center",
      w: "grow",
    },
    { space: 2 },
    // "Go to page" on each: the build lights the one for the page it is on.
    ...PAGES.map((p) => ({ nav: p.nav, icon: p.icon, onClick: { action: "page", value: p.id } })),
  ],
  surface: "sheet",
  pad: 2,
  w: 104,
  gap: 1,
  name: "Sidebar",
};

const socialPage = (p) => ({
  theme: { face: "#c6c6c6", accent: "#3670f8" },
  row: [
    sidebar,
    {
      column: [
        ...CONTENT[p.id].map((s) => ({ section: s.rows, title: s.title, icon: s.icon })),
        {
          row: [
            { space: "grow" },
            { button: "Done", look: "light", icon: "ui:check", onClick: { action: "close", value: "" } },
          ],
          gap: 4,
          w: "grow",
        },
      ],
      w: "grow",
      gap: 6,
      perPage: true,
      name: "Page",
    },
  ],
  gap: 6,
  cross: "start",
});

const social = {
  title: "Social",
  style: "dialog",
  contentWidth: 360,
  afterAction: "none",
  canCloseWithEscape: true,
  hideFooter: true,
  buttons: [],
  document: socialPage(PAGES[0]),
  pages: PAGES.map((p, i) => ({ id: p.id, name: p.nav, ...(i > 0 ? { document: socialPage(p) } : {}) })),
};

// ------------------------------------------------------------------ build

const SAMPLES = [
  {
    name: "punish",
    spec: punish,
    command: "sample-punish",
    header: [
      "samples:punish - a staff panel about one player, drawn with a resource pack.",
      "/sample-punish <player> opens it, or Punish menu in /dialog-samples.",
      "",
      "Reason, Length and Silent are settings kept on the player who clicks",
      "them; an action can print them, {pun_reason} {pun_length} {pun_silent}.",
      "The buttons only say what they would do. To make Ban ban, change its",
      "value to your punishment plugin's command, e.g.",
      "  value: \"[console] ban {target} {pun_reason}; [message] <red>Banned {target}\"",
    ],
  },
  {
    name: "social",
    spec: social,
    command: "sample-social",
    header: [
      "samples:social - a settings menu of six pages, drawn with a resource pack.",
      "/sample-social opens it, or Social menu in /dialog-samples.",
      "",
      "Every control is a setting kept on the player (set_status, set_music...),",
      "so it stays as it was left. Nothing reads them: a plugin of yours can,",
      "and any action or text can print one, e.g. {set_status}.",
    ],
  },
];

const DRAWN_HEADER = [
  "A drawn dialog: Studio's dialog editor document, as YAML.",
  "Edit it with /de edit, or by hand here and run /de reload: a changed",
  "file is redrawn by Studio (resourcepack.ai), which needs the internet.",
];

rmSync(root, { recursive: true, force: true });
const listed = [];
for (const sample of SAMPLES) {
  const spec = { ...sample.spec, command: sample.command, commandPermission: "dialogengine.samples" };
  const body = {
    client: "DialogEngine/samples",
    kind: "dialog",
    label: `samples:${sample.name}`,
    minecraftVersion: "1.21.11",
    dialog: { path: `drawn/samples/${sample.name}`, key: sample.name, spec },
  };
  const res = await fetch(`${studio}/api/rpengine/build`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "User-Agent": "DialogEngine/samples" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${sample.name}: ${res.status} ${text.slice(0, 400)}`);
  const pull = JSON.parse(text);
  const dir = join(root, sample.name);
  mkdirSync(dir, { recursive: true });
  const yml = [...sample.header, "", ...DRAWN_HEADER].map((l) => (l ? `# ${l}` : "#")).join("\n") + "\n"
    + YAML.stringify(pull.dialog.spec ?? spec, { lineWidth: 0 });
  writeFileSync(join(dir, "dialog.yml"), yml);
  writeFileSync(join(dir, ".built"), createHash("sha256").update(yml.replace(/\r\n/g, "\n"), "utf8").digest("hex"));
  writeFileSync(join(dir, "dialogs.json"), JSON.stringify(pull.dialog.built));
  listed.push(`${sample.name}/dialog.yml`, `${sample.name}/.built`, `${sample.name}/dialogs.json`);
  for (const f of pull.files ?? []) {
    if (!/^pack\/[A-Za-z0-9._/-]+$/.test(f.path) || f.path.includes("..")) throw new Error(`refused ${f.path}`);
    const target = join(dir, f.path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, f.encoding === "base64" ? Buffer.from(f.content, "base64") : f.content);
    listed.push(`${sample.name}/${f.path}`);
  }
  console.log(`${sample.name}: ${pull.dialog.built.length} page(s), ${(pull.files ?? []).length} pack files`);
}
writeFileSync(join(root, "files.txt"), listed.join("\n") + "\n");
