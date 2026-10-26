import { join } from "node:path";
import { ensurePrivateDirectory } from "./secure-files.js";
import { DeviceRegistry } from "./device-registry.js";
import { loadOrCreateIdentity } from "./identity.js";
import { configFromEnvironment, createConnector } from "./connector.js";
import { CAPABILITIES, type Capability } from "./protocol.js";

async function main(args: string[]): Promise<void> {
  const command = args[0] ?? "start";
  const config = configFromEnvironment();
  await ensurePrivateDirectory(config.stateDir);
  const identityPath = join(config.stateDir, "identity.json");
  const registry = new DeviceRegistry(join(config.stateDir, "devices.json"));

  if (command === "show-identity") {
    const { identity } = await loadOrCreateIdentity(identityPath);
    process.stdout.write(`${JSON.stringify({ public_key: identity.public_key, fingerprint: identity.fingerprint }, null, 2)}\n`);
    return;
  }
  if (command === "pair-device") {
    const options = parseOptions(args.slice(1));
    const label = options.label;
    if (!label) throw new Error("pair-device requires --label");
    const capabilities = options.capabilities
      ? options.capabilities.split(",").filter(Boolean) as Capability[]
      : [...CAPABILITIES];
    const paired = await registry.pair(label, capabilities);
    process.stdout.write(`${JSON.stringify({ device_id: paired.device.device_id, label: paired.device.label, capabilities: paired.device.capabilities, token: paired.token }, null, 2)}\n`);
    return;
  }
  if (command === "revoke-device") {
    const deviceId = args[1];
    if (!deviceId) throw new Error("revoke-device requires a device UUID");
    const device = await registry.revoke(deviceId);
    process.stdout.write(`${JSON.stringify({ device_id: device.device_id, revoked_at: device.revoked_at })}\n`);
    return;
  }
  if (command === "start") {
    const runtime = await createConnector(config);
    await runtime.start();
    process.stdout.write("ATLAS relay connector started; metrics bound to 127.0.0.1\n");
    const stop = () => void runtime.stop().finally(() => process.exit(0));
    process.once("SIGINT", stop);
    process.once("SIGTERM", stop);
    return;
  }
  if (command === "help" || command === "--help" || command === "-h") {
    process.stdout.write("Usage: atlas-relay-connector [start|show-identity|pair-device --label LABEL [--capabilities a,b]|revoke-device UUID]\n");
    return;
  }
  throw new Error("unknown command");
}

function parseOptions(args: string[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (let index = 0; index < args.length; index += 1) {
    const key = args[index];
    if (!key?.startsWith("--")) throw new Error("invalid command option");
    const value = args[index + 1];
    if (!value || value.startsWith("--")) throw new Error("missing command option value");
    result[key.slice(2)] = value;
    index += 1;
  }
  return result;
}

main(process.argv.slice(2)).catch(() => {
  process.stderr.write("ATLAS relay connector command failed\n");
  process.exitCode = 1;
});
