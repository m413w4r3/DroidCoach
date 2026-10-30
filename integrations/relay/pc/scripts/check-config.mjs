import { loadEnvironmentFile, inspectConfiguration, runtimeEnvironment, tokenPresent } from "./lib/config.mjs";
import { isAbsolute } from "node:path";

try {
  let fileValues = {};
  const args = process.argv.slice(2);
  if (args.length) {
    if (args.length !== 2 || args[0] !== "--env-file") throw new Error("usage: check-config.mjs [--env-file PATH]");
    const filePath = args[1];
    if (!filePath) throw new Error("environment file path is required");
    fileValues = (await loadEnvironmentFile(filePath)).values;
  }
  const environment = runtimeEnvironment(fileValues);
  const result = inspectConfiguration(environment);
  const checks = [...result.checks];
  const errors = [...result.errors];
  const v4Enabled = environment.ATLAS_V4_ENABLED;
  const androidTokenFile = environment.ATLAS_V4_ANDROID_TOKEN_FILE;
  if (v4Enabled !== undefined && v4Enabled !== "0" && v4Enabled !== "1") {
    errors.push("ATLAS_V4_ENABLED must be 0 or 1");
  }
  if (androidTokenFile !== undefined && !isAbsolute(androidTokenFile)) {
    errors.push("ATLAS_V4_ANDROID_TOKEN_FILE must be an absolute path");
  }
  if (v4Enabled === "1") {
    if (!androidTokenFile) errors.push("ATLAS_V4_ANDROID_TOKEN_FILE is required when V4 is enabled");
    else checks.push("V4 Android bearer file path is configured (value hidden)");
  } else {
    checks.push("V4 operations are disabled unless ATLAS_V4_ENABLED=1");
  }
  for (const check of checks) process.stdout.write(`PASS ${check}\n`);
  process.stdout.write(`INFO FastAPI service token: ${tokenPresent(environment) ? "present" : "absent"} (value hidden)\n`);
  for (const error of errors) process.stderr.write(`FAIL ${error}\n`);
  if (errors.length) process.exitCode = 1;
} catch (error) {
  process.stderr.write(`FAIL configuration check: ${safeMessage(error)}\n`);
  process.exitCode = 1;
}

function safeMessage(error) {
  return error instanceof Error ? error.message.replace(/ATLAS_SERVICE_TOKEN=[^\s]*/g, "ATLAS_SERVICE_TOKEN=<hidden>") : "invalid configuration";
}
