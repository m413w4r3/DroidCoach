import { loadEnvironmentFile, inspectConfiguration, isPrivateV4RelayAssertion, runtimeEnvironment, tokenPresent } from "./lib/config.mjs";
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
  const authMode = environment.ATLAS_V4_AUTH_MODE ?? "legacy";
  const bindingRequired = environment.ATLAS_REQUIRE_PRINCIPAL_BINDING;
  const principalMapFile = environment.ATLAS_CORE_PRINCIPAL_MAP_FILE;
  const assertionKeyId = environment.ATLAS_V4_RELAY_ASSERTION_KEY_ID;
  const assertionSecretFile = environment.ATLAS_V4_RELAY_ASSERTION_SECRET_FILE;
  const assertionSecret = environment.ATLAS_V4_RELAY_ASSERTION_SECRET;
  const assertionSecretFilePresent = assertionSecretFile !== undefined;
  const assertionSecretEnvPresent = assertionSecret !== undefined;
  if (v4Enabled !== undefined && v4Enabled !== "0" && v4Enabled !== "1") {
    errors.push("ATLAS_V4_ENABLED must be 0 or 1");
  }
  if (!["legacy", "dual", "principal"].includes(authMode)) {
    errors.push("ATLAS_V4_AUTH_MODE must be legacy, dual, or principal");
  }
  if (bindingRequired !== undefined && !["0", "1"].includes(bindingRequired)) {
    errors.push("ATLAS_REQUIRE_PRINCIPAL_BINDING must be 0 or 1");
  }
  if (androidTokenFile !== undefined && !isAbsolute(androidTokenFile)) {
    errors.push("ATLAS_V4_ANDROID_TOKEN_FILE must be an absolute path");
  }
  if (principalMapFile !== undefined && !isAbsolute(principalMapFile)) {
    errors.push("ATLAS_CORE_PRINCIPAL_MAP_FILE must be an absolute path");
  }
  if (assertionSecretFile !== undefined && !isAbsolute(assertionSecretFile)) {
    errors.push("ATLAS_V4_RELAY_ASSERTION_SECRET_FILE must be an absolute path");
  }
  if (v4Enabled === "1") {
    const principalBinding = authMode !== "legacy" || bindingRequired === "1" || Boolean(principalMapFile);
    if (principalBinding) {
      if (!principalMapFile) errors.push("ATLAS_CORE_PRINCIPAL_MAP_FILE is required for principal-bound V4");
      if (!assertionKeyId) errors.push("ATLAS_V4_RELAY_ASSERTION_KEY_ID is required for principal-bound V4");
      if (!assertionSecretFilePresent && !assertionSecretEnvPresent) {
        errors.push("configure exactly one Relay assertion secret source");
      }
      if (assertionSecretFilePresent && !(await isPrivateV4RelayAssertion(environment))) {
        errors.push("ATLAS_V4_RELAY_ASSERTION_SECRET_FILE must be an owner-controlled mode-0600 file outside the repository");
      }
      if (assertionSecret !== undefined && !/^[A-Za-z0-9_-]{43,}$/.test(assertionSecret)) {
        errors.push("ATLAS_V4_RELAY_ASSERTION_SECRET must be base64url with at least 32 bytes");
      }
      if (principalMapFile && assertionKeyId && (assertionSecretFilePresent || assertionSecretEnvPresent)) {
        checks.push("V4 principal map and Relay assertion settings are configured (values hidden)");
      }
    } else if (!androidTokenFile) {
      errors.push("ATLAS_V4_ANDROID_TOKEN_FILE is required for the legacy development fallback");
    } else {
      checks.push("V4 legacy development bearer file path is configured (value hidden)");
    }
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
