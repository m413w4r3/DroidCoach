import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ARTIFACT = join(PACKAGE_ROOT, "vendor", "relay-0.13.1.tgz");
const CHECKSUM = join(PACKAGE_ROOT, "vendor", "relay-0.13.1.tgz.sha256");
const EXPECTED_SHA256 = "cc1102a64892dc6f9de5bacbc813b472758a6675be26ef65534d5bf7b5d70452";

try {
  const [artifact, sidecar, packageText] = await Promise.all([
    readFile(ARTIFACT),
    readFile(CHECKSUM, "utf8"),
    readFile(join(PACKAGE_ROOT, "package.json"), "utf8"),
  ]);
  const digest = createHash("sha256").update(artifact).digest("hex");
  const packageJson = JSON.parse(packageText);
  const sidecarDigest = sidecar.trim().split(/\s+/)[0];
  if (digest !== EXPECTED_SHA256 || sidecarDigest !== EXPECTED_SHA256) throw new Error("vendored relay artifact hash mismatch");
  if (packageJson.dependencies?.["@getpaseo/relay"] !== "file:vendor/relay-0.13.1.tgz") throw new Error("relay dependency does not use the pinned artifact");
  process.stdout.write(`PASS vendored @getpaseo/relay 0.13.1 SHA-256 ${digest}\n`);
} catch {
  process.stderr.write("FAIL vendored relay artifact verification\n");
  process.exitCode = 1;
}
