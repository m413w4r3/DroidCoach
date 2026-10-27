import { createHash } from "node:crypto";
import { copyFile, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const UPSTREAM_SHA = "902b0c7a1637cc7a6c2ee268dca8161965a35def";
const EXPECTED_SHA256 = "cc1102a64892dc6f9de5bacbc813b472758a6675be26ef65534d5bf7b5d70452";
const EXPECTED_INTEGRITY = "sha512-6bA7X2zugIPcp8jttqlx11QG64xLohXdNsfyCx+2oPErNs9W+OkKTALapRCO7NNgKbtNu4e4OWQkfvV7kZBtwg==";

const args = process.argv.slice(2);
const upstreamIndex = args.indexOf("--upstream");
const upstreamPath = upstreamIndex >= 0 ? args[upstreamIndex + 1] : undefined;
const writeArtifact = args.includes("--write");
if (!upstreamPath || args.some((arg, index) => arg === "--upstream" && (!args[index + 1] || args[index + 1].startsWith("--"))) ||
    args.some((arg) => !["--upstream", upstreamPath, "--write"].includes(arg))) {
  process.stderr.write("usage: node scripts/build-vendored-relay.mjs --upstream PASEO_CHECKOUT [--write]\n");
  process.exit(2);
}

const head = runText("git", ["-C", upstreamPath, "rev-parse", "HEAD"]).trim();
if (head !== UPSTREAM_SHA) fail("upstream checkout is not at the pinned commit");
const temp = await mkdtemp(join(tmpdir(), "atlas-paseo-relay-build-"));
try {
  const archive = runBuffer("git", ["-C", upstreamPath, "archive", "--format=tar", UPSTREAM_SHA, "packages/relay", "LICENSE"]);
  runBuffer("tar", ["-xf", "-", "-C", temp], archive);
  const relayDir = join(temp, "packages", "relay");
  const sourcePackage = JSON.parse(await readFile(join(relayDir, "package.json"), "utf8"));
  if (sourcePackage.name !== "@getpaseo/relay" || sourcePackage.version !== "0.13.1") fail("pinned upstream package metadata mismatch");
  const lockText = runText("git", ["-C", upstreamPath, "show", `${UPSTREAM_SHA}:package-lock.json`]);
  const lockPackages = JSON.parse(lockText).packages;
  const resolved = (name) => {
    const entry = lockPackages[`node_modules/${name}`];
    if (!entry?.version) fail(`pinned upstream lockfile does not resolve ${name}`);
    return entry.version;
  };
  const runtimeDependencies = Object.fromEntries(Object.keys(sourcePackage.dependencies).map((name) => [name, resolved(name)]));
  const buildManifest = {
    name: sourcePackage.name,
    version: sourcePackage.version,
    type: sourcePackage.type,
    dependencies: runtimeDependencies,
    devDependencies: {
      "@types/node": resolved("@types/node"),
      "@types/ws": resolved("@types/ws"),
      typescript: resolved("typescript"),
    },
  };
  await writeFile(join(relayDir, "package.json"), `${JSON.stringify(buildManifest, null, 2)}\n`);
  runText("npm", ["install", "--ignore-scripts", "--no-save", "--package-lock=false", "--audit=false", "--fund=false", "--silent"], { cwd: relayDir });
  runText(process.execPath, [join(relayDir, "node_modules", "typescript", "bin", "tsc"), "-p", "tsconfig.json", "--incremental", "false"], { cwd: relayDir });
  await writeFile(join(relayDir, "package.json"), `${JSON.stringify(sourcePackage, null, 2)}\n`);
  const packResult = runText("npm", ["pack", "--ignore-scripts", "--json", "--pack-destination", temp], { cwd: relayDir });
  const packMetadata = JSON.parse(packResult)[0];
  if (packMetadata.name !== "@getpaseo/relay" || packMetadata.version !== "0.13.1" || packMetadata.integrity !== EXPECTED_INTEGRITY) {
    fail("rebuilt package does not match published @getpaseo/relay@0.13.1 integrity");
  }
  const generated = join(temp, packMetadata.filename);
  const digest = createHash("sha256").update(await readFile(generated)).digest("hex");
  if (digest !== EXPECTED_SHA256) fail("rebuilt artifact SHA-256 does not match the pinned artifact");
  if (writeArtifact) {
    await copyFile(generated, join(PACKAGE_ROOT, "vendor", "relay-0.13.1.tgz"));
    await writeFile(join(PACKAGE_ROOT, "vendor", "relay-0.13.1.tgz.sha256"), `${EXPECTED_SHA256}  relay-0.13.1.tgz\n`);
  }
  process.stdout.write(`PASS upstream ${UPSTREAM_SHA}; published integrity verified; SHA-256 ${digest}${writeArtifact ? "; artifact refreshed" : "; artifact unchanged"}\n`);
} finally {
  await rm(temp, { recursive: true, force: true });
}

function runText(command, commandArgs, options = {}) {
  const result = spawnSync(command, commandArgs, { encoding: "utf8", maxBuffer: 16 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) fail(`${command} failed${result.error ? ` (${result.error.code ?? "spawn error"})` : ""}`);
  return result.stdout;
}

function runBuffer(command, commandArgs, input) {
  const result = spawnSync(command, commandArgs, { input, maxBuffer: 16 * 1024 * 1024 });
  if (result.error || result.status !== 0) fail(`${command} failed`);
  return result.stdout;
}

function fail(message) {
  throw new Error(message);
}
