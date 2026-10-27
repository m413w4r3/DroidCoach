import { homedir } from "node:os";
import { join } from "node:path";
import { loadEnvironmentFile, runtimeEnvironment } from "./lib/config.mjs";
import { restoreState } from "./state-backup.mjs";

try {
  const [envFile, backupDir, ...flags] = process.argv.slice(2);
  if (!envFile || !backupDir) throw new Error("usage: restore-state.mjs ENV_FILE BACKUP_DIRECTORY --confirm-fingerprint HEX --pre-restore-backup DIRECTORY --service-stopped");
  const options = parseFlags(flags);
  const loaded = await loadEnvironmentFile(envFile);
  const environment = runtimeEnvironment(loaded.values);
  const stateDir = environment.ATLAS_RELAY_STATE_DIR ?? join(homedir(), ".local", "state", "atlas-relay");
  await restoreState({
    stateDir,
    backupDir,
    expectedFingerprint: options.fingerprint,
    preRestoreBackup: options.preRestoreBackup,
    serviceStopped: options.serviceStopped,
  });
  process.stdout.write("PASS state identity and registry restored; pre-restore backup retained\n");
} catch (error) {
  process.stderr.write(`FAIL state restore: ${error instanceof Error ? error.message : "invalid restore request"}\n`);
  process.exitCode = 1;
}

function parseFlags(args) {
  const result = { serviceStopped: false };
  for (let index = 0; index < args.length; index += 1) {
    const flag = args[index];
    if (flag === "--service-stopped") result.serviceStopped = true;
    else if (flag === "--confirm-fingerprint") result.fingerprint = args[++index];
    else if (flag === "--pre-restore-backup") result.preRestoreBackup = args[++index];
    else throw new Error("unsupported restore option");
  }
  return result;
}
