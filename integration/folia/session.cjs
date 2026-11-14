// Two players, two Folia regions, and Dialog Engine working on each from the
// other and from the console. Run by run.mjs, which reads what this prints:
//
//   CONSOLE <command>    run as the server console
//   CHECK PASS|FAIL <what>
//
// Anything else is narration, shown with VERBOSE=1. The server's own output
// arrives on stdin.
const mineflayer = require("mineflayer");
const nbt = require("prismarine-nbt");

const port = Number(process.argv[2] || 25779);
/** Folia, or Paper running the same session ({@code --folia <paper jar>}). */
const folia = process.argv[3] !== "paper";
const FAR = 3000;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const consoleCmd = (line) => console.log(`CONSOLE ${line}`);
const check = (what, ok) => console.log(`CHECK ${ok ? "PASS" : "FAIL"} ${what}`);

let serverLog = "";
process.stdin.on("data", (d) => { serverLog += d.toString(); });

function join(name) {
  const bot = mineflayer.createBot({ host: "127.0.0.1", port, username: name, version: "1.21.8", auth: "offline" });
  const say = (...a) => console.log(name, ...a);
  bot.dialogs = [];
  bot.chatLog = [];
  bot._client.on("show_dialog", (p) => {
    const data = p.dialog && (p.dialog.data || p.dialog);
    let simple = null;
    try {
      simple = data && data.type ? nbt.simplify(data) : data;
    } catch {
      simple = data;
    }
    bot.dialogs.push(simple);
    say("dialog:", JSON.stringify(simple).slice(0, 200));
  });
  bot._client.on("add_resource_pack", (p) => {
    console.log(name, "pack:", p.url);
    // Accepted, then loaded: what a client that took it says.
    bot._client.write("resource_pack_receive", { uuid: p.uuid, result: 3 });
    bot._client.write("resource_pack_receive", { uuid: p.uuid, result: 0 });
  });
  bot.on("messagestr", (m) => {
    if (!m.trim()) return;
    bot.chatLog.push(m);
    say("chat:", m.slice(0, 150));
  });
  bot.on("kicked", (r) => say("kicked", JSON.stringify(r).slice(0, 300)));
  bot.on("error", (e) => say("error", e.message));
  bot.say = say;
  return new Promise((resolve) => bot.once("spawn", () => resolve(bot)));
}

/** Waits for a dialog to arrive after [count]; the newest one, or null. */
async function nextDialog(bot, count, ms = 4000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (bot.dialogs.length > count) return bot.dialogs[bot.dialogs.length - 1];
    await wait(100);
  }
  return null;
}

/** Waits for a chat line matching [re] after [from] lines. */
async function heard(bot, re, from, ms = 4000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (bot.chatLog.slice(from).some((m) => re.test(m))) return true;
    await wait(100);
  }
  return false;
}

/** Waits for the server's log to match [re] after [from] characters: what EventProbe says a plugin was told. */
async function logged(re, from, ms = 4000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (re.test(serverLog.slice(from))) return true;
    await wait(100);
  }
  return false;
}

/** Every button in a dialog that Dialog Engine answers, by the words on it. */
function buttons(dialog) {
  const out = {};
  const walk = (node) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) return node.forEach(walk);
    const token = node.action && node.action.additions && node.action.additions.dialogengine_click;
    if (token) out[JSON.stringify(node.label)] = token;
    Object.values(node).forEach(walk);
  };
  walk(dialog);
  return out;
}

function tokenFor(dialog, word) {
  const all = buttons(dialog);
  const key = Object.keys(all).find((k) => k.includes(word));
  return key ? all[key] : null;
}

/** Every /dialogclick a drawn dialog's picture runs. */
function drawnClicks(dialog) {
  const out = [];
  const walk = (node) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) return node.forEach(walk);
    const cmd = node.command;
    if (typeof cmd === "string" && /^\/?dialogclick /.test(cmd)) out.push(`/${cmd.replace(/^\//, "")}`);
    Object.values(node).forEach(walk);
  };
  walk(dialog);
  return out;
}

/**
 * What the client sends for a dynamic/custom button: the id and the
 * additions as NBT, length-prefixed. Written by hand, since this protocol library knows the
 * packet's id (0x41) but not its fields.
 */
function click(bot, token) {
  const proto = bot._client.serializer.proto;
  const id = proto.createPacketBuffer("varint", 0x41);
  const key = proto.createPacketBuffer("string", "dialogengine:click");
  const tag = proto.createPacketBuffer("anonymousNbt", {
    type: "compound", name: "", value: { dialogengine_click: { type: "string", value: token } },
  });
  // The tag is length-prefixed (at most 64 KiB).
  const length = proto.createPacketBuffer("varint", tag.length);
  bot._client.writeRaw(Buffer.concat([id, key, length, tag]));
}

(async () => {
  const near = await join("NearBot");
  const far = await join("FarBot");
  consoleCmd("op NearBot");
  consoleCmd("op FarBot");
  consoleCmd(`tp FarBot ${FAR} -60 0`);
  await wait(4000);
  check(`FarBot is in another region (x=${Math.round(far.entity.position.x)})`, far.entity.position.x > FAR - 10);

  // ---- opened from elsewhere --------------------------------------------
  let n = far.dialogs.length;
  consoleCmd("de open folia:menu FarBot");
  check("the console's /de open reaches a far player", !!await nextDialog(far, n));

  n = far.dialogs.length;
  near.chat("/de open folia:menu FarBot");
  check("one player's /de open reaches a player in another region", !!await nextDialog(far, n));

  n = far.dialogs.length;
  far.chat("/foliamenu");
  check("a dialog's own command opens it", !!await nextDialog(far, n));

  n = far.dialogs.length;
  far.chat("/foliamenu");
  const menu = await nextDialog(far, n);

  // ---- clicks ------------------------------------------------------------
  let c = far.chatLog.length;
  const console_ = tokenFor(menu, "Console");
  check("the dialog's buttons carry click tokens", !!console_);
  if (console_) click(far, console_);
  check("a [console] action runs (on the global region)", await heard(far, /console-ran-for FarBot/, c));
  check("the actions after a [console] action run", await heard(far, /after-console/, c));

  n = far.dialogs.length;
  far.chat("/foliamenu");
  let shown = await nextDialog(far, n);
  c = far.chatLog.length;
  if (shown) click(far, tokenFor(shown, "Wait"));
  check("the actions after a [delay] run", await heard(far, /after-delay/, c));

  n = far.dialogs.length;
  far.chat("/foliamenu");
  shown = await nextDialog(far, n);
  c = far.chatLog.length;
  if (shown) click(far, tokenFor(shown, "Bad"));
  check("a failed [console] action tells the player", await heard(far, /didn't work/, c));
  await wait(1000);
  check("and stops the actions after it", !far.chatLog.slice(c).some((m) => /must-not-run/.test(m)));

  n = far.dialogs.length;
  far.chat("/foliamenu");
  shown = await nextDialog(far, n);
  n = far.dialogs.length;
  if (shown) click(far, tokenFor(shown, "Next"));
  const next = await nextDialog(far, n);
  check("an [open] action shows the next dialog", !!next && JSON.stringify(next).includes("second"));

  // ---- the events a plugin hears (EventProbe logs them) -----------------
  const events = async () => {
    const k = far.dialogs.length;
    far.chat("/foliaevents");
    return nextDialog(far, k);
  };
  let at = serverLog.length;
  let ev = await events();
  check("DialogCloseEvent: another dialog in its place is REPLACED, naming it",
    await logged(/EVENT close dialog=folia:next reason=REPLACED button=null next=folia:events player=FarBot .*on=owner/, at));

  at = serverLog.length;
  c = far.chatLog.length;
  if (ev) click(far, tokenFor(ev, "Named"));
  check("DialogClickEvent: a button is named by its [event], placeholders filled",
    await logged(/EVENT click dialog=folia:events button=picked_FarBot drawn=false .*on=owner/, at));
  check("and its other actions still run", await heard(far, /named-ran/, c));
  check("DialogCloseEvent: a seamless click nothing answers is BUTTON, with the button",
    await logged(/EVENT close dialog=folia:events reason=BUTTON button=picked_FarBot next=null .*on=owner/, at));

  at = serverLog.length;
  ev = await events();
  if (ev) click(far, tokenFor(ev, "Leave"));
  check("DialogCloseEvent: the exit button, which Escape presses, is EXIT",
    await logged(/EVENT close dialog=folia:events reason=EXIT button=exit .*on=owner/, at));

  at = serverLog.length;
  ev = await events();
  if (ev) click(far, tokenFor(ev, "Shut"));
  check("DialogCloseEvent: [close] is BUTTON, with the button's id:",
    await logged(/EVENT close dialog=folia:events reason=BUTTON button=shut .*on=owner/, at));

  at = serverLog.length;
  ev = await events();
  if (ev) click(far, tokenFor(ev, "Onward"));
  check("DialogCloseEvent: a button's [open] is REPLACED",
    await logged(/EVENT close dialog=folia:events reason=REPLACED button=null next=folia:next .*on=owner/, at));
  await wait(500);
  check("and only once", (serverLog.slice(at).match(/EVENT close dialog=folia:events /g) || []).length === 1);

  // A plugin's chest takes the screen.
  at = serverLog.length;
  ev = await events();
  consoleCmd("probechest FarBot");
  check("DialogCloseEvent: a container opened over it is SCREEN",
    await logged(/EVENT chest FarBot[\s\S]*EVENT close dialog=folia:events reason=SCREEN .*on=owner/, at, 8000));
  far.closeWindow(far.currentWindow || { id: 1 });
  await wait(500);

  // Another world's loading screen takes it. Folia tells plugins nothing about a move to another world (no
  // PlayerChangedWorldEvent, no PlayerTeleportEvent, from /tp or a plugin's teleportAsync), so these are Paper's.
  if (!folia) {
    at = serverLog.length;
    ev = await events();
    consoleCmd("probetp FarBot world_the_end 0 70 0");
    check("DialogCloseEvent: a plugin's teleport to another world is SCREEN",
      await logged(/EVENT close dialog=folia:events reason=SCREEN .*on=owner/, at, 8000));
    consoleCmd(`probetp FarBot world ${FAR} -60 0`);
    await wait(3000);
    at = serverLog.length;
    ev = await events();
    consoleCmd("execute in minecraft:the_end run tp FarBot 0 70 0");
    check("DialogCloseEvent: /tp to another world is SCREEN",
      await logged(/EVENT close dialog=folia:events reason=SCREEN .*on=owner/, at, 8000));
    consoleCmd(`execute in minecraft:overworld run tp FarBot ${FAR} -60 0`);
    await wait(3000);
  }

  // ---- a drawn dialog ---------------------------------------------------
  n = far.dialogs.length;
  far.chat("/sample-social");
  // First a notice while the pack loads, then the dialog itself.
  let drawn = null;
  for (const until = Date.now() + 30000; !drawn && Date.now() < until; await wait(200)) {
    drawn = far.dialogs.slice(n).find((d) => drawnClicks(d).length > 0) || null;
  }
  check("a drawn dialog opens far away", !!drawn);
  const turns = drawn ? drawnClicks(drawn).filter((cmd) => / page /.test(cmd) && !/ close$/.test(cmd)) : [];
  if (turns.length) {
    n = far.dialogs.length;
    far.chat(turns[0]);
    check("a drawn dialog's page turn shows the page", !!await nextDialog(far, n));
  } else {
    check("a drawn dialog has a page turn to click", false);
  }
  const setting = drawn ? drawnClicks(drawn).find((cmd) => / var /.test(cmd)) : null;
  if (setting) {
    at = serverLog.length;
    n = far.dialogs.length;
    far.chat(setting);
    check("a drawn dialog's setting is kept and the page drawn again", !!await nextDialog(far, n));
    check("DialogSettingChangeEvent: the setting, its old value and its new",
      await logged(/EVENT setting dialog=samples:social name=\w+ old=\S* new=\S+ on=owner/, at));
    check("a page turn or a setting is not a close", !/EVENT close dialog=samples:social/.test(serverLog.slice(at)));
  }
  const shut = drawn ? drawnClicks(drawn).find((cmd) => / page close$/.test(cmd)) : null;
  at = serverLog.length;
  if (shut) far.chat(shut);
  check("DialogCloseEvent: a drawn dialog's Close (and Escape) is EXIT",
    !!shut && await logged(/EVENT close dialog=samples:social reason=EXIT button=exit .*on=owner/, at));

  // A drawn piece's actions fire DialogClickEvent too, with the player's settings.
  n = far.dialogs.length;
  far.chat("/sample-punish NearBot");
  let punish = null;
  for (const until = Date.now() + 30000; !punish && Date.now() < until; await wait(200)) {
    punish = far.dialogs.slice(n).find((d) => drawnClicks(d).some((cmd) => / act /.test(cmd))) || null;
  }
  // A setting first, so the click has one to report (the samples were drawn before Studio sent defaults).
  const pick = punish ? drawnClicks(punish).find((cmd) => / var pun_\w+ /.test(cmd)) : null;
  const picked = pick ? pick.match(/ var (pun_\w+) (\S+)/) : null;
  if (pick) {
    n = far.dialogs.length;
    far.chat(pick);
    punish = await nextDialog(far, n) || punish;
  }
  const act = punish ? drawnClicks(punish).find((cmd) => / act /.test(cmd)) : null;
  at = serverLog.length;
  if (act) far.chat(act);
  check(`DialogClickEvent: a drawn dialog's piece, with its settings (${picked ? picked[1] : "none"})`,
    !!act && !!picked && await logged(new RegExp(`EVENT click dialog=samples:punish button=act_\\d+ drawn=true inputs=\\{\\} `
      + `settings=\\{[^}]*${picked[1]}=${picked[2]}[^}]*\\} on=owner`), at));

  // ---- reloads from a player and from the console -----------------------
  c = near.chatLog.length;
  near.chat("/de reload");
  check("/de reload typed by a player runs", await heard(near, /Loaded/, c, 8000));
  const before = serverLog.length;
  consoleCmd("de reload");
  await wait(3000);
  check("/de reload from the console runs", /Loaded/.test(serverLog.slice(before)));

  n = far.dialogs.length;
  consoleCmd("de open folia:menu FarBot");
  check("dialogs still open after the reloads", !!await nextDialog(far, n));

  at = serverLog.length;
  n = near.dialogs.length;
  consoleCmd("de open folia:events NearBot");
  await nextDialog(near, n);
  near.quit();
  check("DialogCloseEvent: leaving the server is QUIT",
    await logged(/EVENT close dialog=folia:events reason=QUIT .*player=NearBot .*on=owner/, at));
  far.quit();
  await wait(1500);
  process.exit(0);
})().catch((e) => {
  console.log(`CHECK FAIL the session threw: ${e.message}`);
  process.exit(1);
});

setTimeout(() => {
  console.log("CHECK FAIL the session timed out");
  process.exit(2);
}, 240_000);
