import { homedir } from "node:os";
import { join } from "node:path";
import { loadEnvironmentFile, runtimeEnvironment } from "./lib/config.mjs";
import { backupState } from "./state-backup.mjs";

try {
  const [envFile, backupDir] = process.argv.slice(2);
  if (!envFile || !backupDir || process.argv.length !== 4) throw new Error("usage: backup-state.mjs ENV_FILE BACKUP_DIRECTORY");
  const loaded = await loadEnvironmentFile(envFile);
  const environment = runtimeEnvironment(loaded.values);
  const stateDir = environment.ATLAS_RELAY_STATE_DIR ?? join(homedir(), ".local", "state", "atlas-relay");
  await backupState(stateDir, backupDir);
  process.stdout.write("PASS identity and registry backup created with mode 0700/0600\n");
} catch (error) {
  process.stderr.write(`FAIL state backup: ${error instanceof Error ? error.message : "invalid backup request"}\n`);
  process.exitCode = 1;
}
