import { loadEnvironmentFile, inspectConfiguration, runtimeEnvironment, tokenPresent } from "./lib/config.mjs";

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
  for (const check of result.checks) process.stdout.write(`PASS ${check}\n`);
  process.stdout.write(`INFO FastAPI service token: ${tokenPresent(environment) ? "present" : "absent"} (value hidden)\n`);
  for (const error of result.errors) process.stderr.write(`FAIL ${error}\n`);
  if (result.errors.length) process.exitCode = 1;
} catch (error) {
  process.stderr.write(`FAIL configuration check: ${safeMessage(error)}\n`);
  process.exitCode = 1;
}

function safeMessage(error) {
  return error instanceof Error ? error.message.replace(/ATLAS_SERVICE_TOKEN=[^\s]*/g, "ATLAS_SERVICE_TOKEN=<hidden>") : "invalid configuration";
}
