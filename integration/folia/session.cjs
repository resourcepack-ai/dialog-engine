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
    n = far.dialogs.length;
    far.chat(setting);
    check("a drawn dialog's setting is kept and the page drawn again", !!await nextDialog(far, n));
  }

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

  near.quit();
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
