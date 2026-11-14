#!/usr/bin/env node
// Runs Dialog Engine on a real Folia server with two players in two regions.
//
//   npm install                       once, for mineflayer
//   node run.mjs                      build, boot Folia, play, check
//   node run.mjs --no-build           use the jar already in build/libs
//   node run.mjs --folia <jar>        a Folia jar of your own
//   node run.mjs --jar <jar>          a Dialog Engine jar other than this checkout's
//   node run.mjs --keep               leave build/folia behind
//
// The Folia jar: --folia <path>, else DIALOGENGINE_TEST_FOLIA, else downloaded
// once into integration/.cache. Java: $JAVA_HOME/bin/java when it is set, so a
// JDK 21 can be chosen without touching PATH.

import { spawn, spawnSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..", "..");
const WORK = path.join(ROOT, "build", "folia");
const CACHE = path.join(HERE, "..", ".cache");
const PORT = 25779;

/** The Minecraft this is pinned to: the newest Folia mineflayer speaks well. */
const MINECRAFT = "1.21.8";

/**
 * The dialogs the session opens, in dialogs/folia/ (so their ids are
 * folia:<name>). Each button is one thing that has to change thread on Folia.
 */
const DIALOGS = {
  "folia/menu.yml": [
    "menu:",
    "  title: Folia",
    "  command: foliamenu",
    "  after: wait",
    "  body:",
    "    - \"Hello {player}\"",
    "  buttons:",
    "    - label: Console",
    "      actions:",
    "        - \"[console] say console-ran-for {player}\"",
    "        - \"[message] after-console\"",
    "    - label: Wait",
    "      actions:",
    "        - \"[delay] 10\"",
    "        - \"[message] after-delay\"",
    "    - label: Next",
    "      actions:",
    "        - \"[open] folia:next\"",
    "    - label: Bad",
    "      actions:",
    "        - \"[console] no-such-command-here\"",
    "        - \"[message] must-not-run\"",
    "next:",
    "  title: Next",
    "  body:",
    "    - second",
    "",
  ].join("\n"),
  // The events a plugin hears (EventProbe logs them): every way a dialog closes, and a click named by [event].
  "folia/events.yml": [
    "events:",
    "  title: Events",
    "  command: foliaevents",
    "  body:",
    "    - events",
    "  buttons:",
    "    - label: Named",
    "      actions:",
    "        - \"[event] picked_{player}\"",
    "        - \"[message] named-ran\"",
    "    - label: Shut",
    "      id: shut",
    "      actions:",
    "        - \"[close]\"",
    "    - label: Onward",
    "      actions:",
    "        - \"[open] folia:next\"",
    "  exit:",
    "    label: Leave",
    "",
  ].join("\n"),
};

/**
 * What in the server's log means the plugin went wrong. Any stack trace at
 * all fails the run, since a Folia thread violation is an exception wherever
 * it is caught.
 */
const FAULTS = [
  /Exception/,
  /Error occurred/,
  /\[DialogEngine\].*(failed|Could not show)/,
];

const args = process.argv.slice(2);
const keep = args.includes("--keep");
const noBuild = args.includes("--no-build");
const foliaArg = valueOf("--folia");
const jarArg = valueOf("--jar");

function valueOf(flag) {
  const at = args.indexOf(flag);
  return at === -1 ? null : args[at + 1];
}

function say(line) {
  process.stdout.write(`${line}\n`);
}

function java() {
  return process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
    : "java";
}

/** The spigot-api jar Gradle already has, which the probe compiles against. */
async function spigotApi() {
  const base = path.join(process.env.USERPROFILE || process.env.HOME || "", ".gradle", "caches", "modules-2",
    "files-2.1", "org.spigotmc", "spigot-api", "1.21.8-R0.1-SNAPSHOT");
  for (const hash of await fs.readdir(base).catch(() => [])) {
    for (const file of await fs.readdir(path.join(base, hash))) {
      if (/^spigot-api-.*\.jar$/.test(file) && !/sources|javadoc/.test(file)) return path.join(base, hash, file);
    }
  }
  throw new Error(`No spigot-api 1.21.8 in ${base}: build Dialog Engine once with Gradle first.`);
}

/** Compiles probe/EventProbe.java into plugins/EventProbe.jar, against the Dialog Engine jar under test. */
async function probe(engineJar) {
  const bin = (name) => process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? `${name}.exe` : name)
    : name;
  const classes = path.join(WORK, "probe-classes");
  await fs.mkdir(classes, { recursive: true });
  const cp = [await spigotApi(), engineJar].join(path.delimiter);
  const javac = spawnSync(bin("javac"), ["-d", classes, "-cp", cp, path.join(HERE, "probe", "EventProbe.java")], { stdio: "inherit" });
  if (javac.status !== 0) throw new Error("The event probe did not compile.");
  await fs.copyFile(path.join(HERE, "probe", "plugin.yml"), path.join(classes, "plugin.yml"));
  const jar = spawnSync(bin("jar"), ["cf", path.join(WORK, "plugins", "EventProbe.jar"), "-C", classes, "."], { stdio: "inherit" });
  if (jar.status !== 0) throw new Error("The event probe did not package.");
}

function build() {
  say("Building Dialog Engine…");
  const wrapper = path.join(ROOT, process.platform === "win32" ? "gradlew.bat" : "gradlew");
  const built = spawnSync(wrapper, ["shadowJar", "-q"], {
    cwd: ROOT, stdio: "inherit", shell: process.platform === "win32",
  });
  if (built.status !== 0) {
    throw new Error("Gradle build failed.");
  }
}

async function exists(at) {
  try {
    await fs.access(at);
    return true;
  } catch {
    return false;
  }
}

async function folia() {
  if (foliaArg) return path.resolve(foliaArg);
  if (process.env.DIALOGENGINE_TEST_FOLIA) return path.resolve(process.env.DIALOGENGINE_TEST_FOLIA);
  await fs.mkdir(CACHE, { recursive: true });
  const latest = await fetch(
    `https://fill.papermc.io/v3/projects/folia/versions/${MINECRAFT}/builds/latest`,
    { headers: { "User-Agent": "dialog-engine-folia-session/1.0" } },
  ).then((r) => r.json());
  const download = latest?.downloads?.["server:default"];
  if (!download) {
    throw new Error(`No Folia build for ${MINECRAFT}. Pass --folia <path> instead.`);
  }
  const at = path.join(CACHE, download.name);
  if (await exists(at)) return at;
  say(`Downloading ${download.name}…`);
  const jar = await fetch(download.url, { headers: { "User-Agent": "dialog-engine-folia-session/1.0" } });
  await fs.writeFile(at, Buffer.from(await jar.arrayBuffer()));
  return at;
}

async function version() {
  const gradle = await fs.readFile(path.join(ROOT, "build.gradle"), "utf8");
  return gradle.match(/^version = '([^']+)'/m)[1];
}

async function prepare(jar) {
  await fs.rm(WORK, { recursive: true, force: true });
  await fs.mkdir(path.join(WORK, "plugins"), { recursive: true });
  await fs.copyFile(jar, path.join(WORK, "folia.jar"));
  const engineJar = jarArg ? path.resolve(jarArg) : path.join(ROOT, "build", "libs", `dialog-engine-${await version()}.jar`);
  await fs.copyFile(engineJar, path.join(WORK, "plugins", "DialogEngine.jar"));
  await probe(engineJar);
  await fs.writeFile(path.join(WORK, "eula.txt"), "eula=true\n");
  await fs.writeFile(path.join(WORK, "server.properties"), [
    `server-port=${PORT}`,
    "online-mode=false",
    "max-players=4",
    "view-distance=4",
    "simulation-distance=4",
    "spawn-protection=0",
    "difficulty=peaceful",
    "level-type=minecraft\\:flat",
    "allow-nether=false",
    "generate-structures=false",
    "",
  ].join("\n"));
  for (const [file, body] of Object.entries(DIALOGS)) {
    const at = path.join(WORK, "plugins", "DialogEngine", "dialogs", file);
    await fs.mkdir(path.dirname(at), { recursive: true });
    await fs.writeFile(at, body);
  }
}

/** Boots the server, runs the session against it, stops it, and hands back the log. */
function play() {
  return new Promise((resolve, reject) => {
    const logPath = path.join(WORK, "server.log");
    const log = createWriteStream(logPath);
    const server = spawn(java(), ["-Xms1G", "-Xmx3G", "-jar", "folia.jar", "nogui"], { cwd: WORK });
    let session = null;
    const checks = { passed: 0, failed: 0 };
    let started = false;
    const patience = setTimeout(() => {
      server.kill();
      if (session) session.kill();
      reject(new Error(`No result after 8 minutes; see ${logPath}`));
    }, 8 * 60_000);

    const watch = (chunk) => {
      log.write(chunk);
      if (session) session.stdin.write(chunk);
      if (!started && /Done \(/.test(chunk.toString())) {
        started = true;
        say("Folia is up. Two players join…");
        const kind = /paper/i.test(path.basename(foliaArg || "")) ? "paper" : "folia";
        session = spawn(process.execPath, [path.join(HERE, "session.cjs"), String(PORT), kind], { cwd: HERE });
        readline.createInterface({ input: session.stdout }).on("line", (line) => {
          const console_ = line.match(/^CONSOLE (.*)$/);
          if (console_) {
            server.stdin.write(`${console_[1]}\n`);
            return;
          }
          const check = line.match(/^CHECK (PASS|FAIL) (.*)$/);
          if (check) {
            checks[check[1] === "PASS" ? "passed" : "failed"]++;
            say(`   ${check[1] === "PASS" ? "pass" : "FAIL"}  ${check[2]}`);
            return;
          }
          if (process.env.VERBOSE) say(`   ${line}`);
        });
        session.stderr.on("data", (d) => process.stderr.write(d));
        session.stdin.on("error", () => {});
        session.on("close", (code) => {
          if (code !== 0) checks.failed++;
          session = null;
          server.stdin.write("stop\n");
        });
      }
    };
    server.stdout.on("data", watch);
    server.stderr.on("data", watch);
    server.on("close", async () => {
      clearTimeout(patience);
      log.end();
      resolve({ checks, log: await fs.readFile(logPath, "utf8").catch(() => "") });
    });
  });
}

async function main() {
  if (!noBuild && !jarArg) build();
  await prepare(await folia());
  const { checks, log } = await play();

  // The Bad button's console command is meant to fail; its words are not a fault.
  const faults = log.split(/\r?\n/)
    .filter((line) => FAULTS.some((f) => f.test(line)))
    .filter((line) => !/no-such-command-here/.test(line));
  for (const line of faults.slice(0, 20)) {
    say(`   FAIL  log: ${line.slice(0, 220)}`);
  }
  if (!/Disabling DialogEngine/.test(log)) {
    faults.push("never disabled");
    say("   FAIL  Dialog Engine was never disabled: the server did not stop cleanly");
  }

  const failed = checks.failed + faults.length;
  if (!keep) {
    for (const name of ["world", "world_the_end", "libraries", "versions", "cache", "folia.jar"]) {
      await fs.rm(path.join(WORK, name), { recursive: true, force: true }).catch(() => {});
    }
  }
  say(`\n${failed ? "FAILED" : "OK"}: ${checks.passed} checks passed, ${checks.failed} failed, `
    + `${faults.length} faults in the log (build/folia/server.log)`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  say(`\n${e.message}`);
  process.exit(1);
});
