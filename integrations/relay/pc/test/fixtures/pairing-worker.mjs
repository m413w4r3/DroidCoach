import { appendFile } from "node:fs/promises";
import { DeviceRegistry } from "../../dist/device-registry.js";

const [registryPath, tokenFile, label, attemptPath] = process.argv.slice(2);
await new DeviceRegistry(registryPath, {
  timeoutMs: 10_000,
  retryMs: 5,
  onAttempt: async () => appendFile(attemptPath, "attempt\n"),
})
  .pairToTokenFile(label, tokenFile, ["sync.status"]);
