import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { loadEnvironmentFile } from "../scripts/lib/config.mjs";

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) =>
    rm(directory, { recursive: true, force: true })));
});

describe("restricted relay environment files", () => {
  it("loads the documented V4 settings from a private environment file", async () => {
    const directory = await makeTemporaryDirectory();
    const tokenPath = join(directory, "android-token");
    const envFile = await writeEnvironmentFile(directory, {
      enabled: "1",
      tokenPath,
    });

    const loaded = await loadEnvironmentFile(envFile);

    expect(loaded.fileMode).toBe(0o600);
    expect(loaded.values).toMatchObject({
      ATLAS_V4_ENABLED: "1",
      ATLAS_V4_ANDROID_TOKEN_FILE: tokenPath,
    });
  });

  it("rejects environment keys outside the explicit allowlist", async () => {
    const directory = await makeTemporaryDirectory();
    const envFile = join(directory, "relay.env");
    await writePrivateFile(envFile, "ATLAS_V4_UNRECOGNIZED=1\n");

    await expect(loadEnvironmentFile(envFile)).rejects.toThrow("unsupported environment key");
  });

  it("accepts a mode 0600 V4 environment file through check-config", async () => {
    const directory = await makeTemporaryDirectory();
    const tokenPath = join(directory, "android-token");
    await writePrivateFile(tokenPath, "synthetic-android-token\n");
    const envFile = await writeEnvironmentFile(directory, {
      enabled: "1",
      tokenPath,
    });

    const result = runScript(directory, "check-config.mjs", "--env-file", envFile);

    expect(result.status).toBe(0);
    expect(result.stdout).toContain("PASS V4 legacy development bearer file path is configured (value hidden)");
    expect(`${result.stdout}${result.stderr}`).not.toContain(tokenPath);
  });

  it("requires and validates a private Android token file in check-secret", async () => {
    const directory = await makeTemporaryDirectory();
    const tokenPath = join(directory, "android-token");
    const envFile = await writeEnvironmentFile(directory, {
      enabled: "1",
      tokenPath,
    });

    const missing = runScript(directory, "check-secret.mjs", "--env-file", envFile);
    expect(missing.status).toBe(1);
    expect(missing.stdout).toContain("ATLAS_V4_ANDROID_TOKEN_FILE: unavailable or permissions invalid");

    await writePrivateFile(tokenPath, "synthetic-android-token\n");
    const privateFile = runScript(directory, "check-secret.mjs", "--env-file", envFile);
    expect(privateFile.status).toBe(0);
    expect(privateFile.stdout).toContain("ATLAS_V4_ANDROID_TOKEN_FILE: private file present");

    await chmod(tokenPath, 0o640);
    const nonPrivateFile = runScript(directory, "check-secret.mjs", "--env-file", envFile);
    expect(nonPrivateFile.status).toBe(1);
    expect(nonPrivateFile.stdout).toContain("ATLAS_V4_ANDROID_TOKEN_FILE: unavailable or permissions invalid");
    expect(`${privateFile.stdout}${privateFile.stderr}${nonPrivateFile.stdout}${nonPrivateFile.stderr}`).not.toContain(tokenPath);
  });

  it("accepts only 0 or 1 for ATLAS_V4_ENABLED", async () => {
    const directory = await makeTemporaryDirectory();
    const disabledFile = await writeEnvironmentFile(directory, { enabled: "0" });
    const disabled = runScript(directory, "check-config.mjs", "--env-file", disabledFile);
    expect(disabled.status).toBe(0);
    expect(disabled.stdout).toContain("PASS V4 operations are disabled unless ATLAS_V4_ENABLED=1");

    const tokenPath = join(directory, "android-token");
    await writePrivateFile(tokenPath, "synthetic-android-token\n");
    const enabledFile = await writeEnvironmentFile(directory, { enabled: "1", tokenPath });
    const enabled = runScript(directory, "check-config.mjs", "--env-file", enabledFile);
    expect(enabled.status).toBe(0);

    const invalidFile = await writeEnvironmentFile(directory, { enabled: "true" });
    const invalid = runScript(directory, "check-config.mjs", "--env-file", invalidFile);
    expect(invalid.status).toBe(1);
    expect(invalid.stderr).toContain("ATLAS_V4_ENABLED must be 0 or 1");
  });

  it("accepts assertion secret environment values only in tests and validates production secret files", async () => {
    const directory = await makeTemporaryDirectory();
    const secret = Buffer.from("synthetic-relay-assertion-secret-for-config-tests").toString("base64url");
    const envOnlyPath = join(directory, "env-only.env");
    await writePrivateFile(envOnlyPath, [
      "ATLAS_RELAY_URL=wss://relay.example.test",
      "ATLAS_BACKEND_URL=http://127.0.0.1:8100",
      `ATLAS_RELAY_STATE_DIR=${join(directory, "state")}`,
      "ATLAS_V4_ENABLED=1",
      "ATLAS_V4_AUTH_MODE=dual",
      `ATLAS_CORE_PRINCIPAL_MAP_FILE=${join(directory, "principal-map.json")}`,
      "ATLAS_V4_RELAY_ASSERTION_KEY_ID=synthetic-test-key",
      `ATLAS_V4_RELAY_ASSERTION_SECRET=${secret}`,
      "",
    ].join("\n"));

    const productionEnvironment = runScript(directory, "check-config.mjs", "--env-file", envOnlyPath, {
      NODE_ENV: "production",
    });
    expect(productionEnvironment.status).toBe(1);
    expect(productionEnvironment.stderr).toContain("accepted only when NODE_ENV=test");
    expect(`${productionEnvironment.stdout}${productionEnvironment.stderr}`).not.toContain(secret);

    const testEnvironment = runScript(directory, "check-config.mjs", "--env-file", envOnlyPath, {
      NODE_ENV: "test",
    });
    expect(testEnvironment.status).toBe(0);
    expect(`${testEnvironment.stdout}${testEnvironment.stderr}`).not.toContain(secret);

    const credentialPath = join(directory, "core-credential");
    const principalMapPath = join(directory, "principal-map.json");
    const assertionPath = join(directory, "relay-assertion-key");
    await writePrivateFile(credentialPath, "synthetic-core-credential-for-map\n");
    await writePrivateFile(principalMapPath, JSON.stringify({
      "relay-device-synthetic": { principal_id: "principal-synthetic", credential_file: credentialPath },
    }));
    await writePrivateFile(assertionPath, `${secret}\n`);
    const fileEnvironmentPath = join(directory, "file-only.env");
    await writePrivateFile(fileEnvironmentPath, [
      "ATLAS_RELAY_URL=wss://relay.example.test",
      "ATLAS_BACKEND_URL=http://127.0.0.1:8100",
      `ATLAS_RELAY_STATE_DIR=${join(directory, "state")}`,
      "ATLAS_V4_ENABLED=1",
      "ATLAS_V4_AUTH_MODE=dual",
      `ATLAS_CORE_PRINCIPAL_MAP_FILE=${principalMapPath}`,
      "ATLAS_V4_RELAY_ASSERTION_KEY_ID=synthetic-test-key",
      `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE=${assertionPath}`,
      "",
    ].join("\n"));

    const privateFile = runScript(directory, "check-secret.mjs", "--env-file", fileEnvironmentPath, {
      NODE_ENV: "production",
    });
    expect(privateFile.status).toBe(0);
    expect(privateFile.stdout).toContain("ATLAS_V4_RELAY_ASSERTION_SECRET: private key configured");
    expect(`${privateFile.stdout}${privateFile.stderr}`).not.toContain(secret);

    const privateConfig = runScript(directory, "check-config.mjs", "--env-file", fileEnvironmentPath, {
      NODE_ENV: "production",
    });
    expect(privateConfig.status).toBe(0);
    expect(`${privateConfig.stdout}${privateConfig.stderr}`).not.toContain(secret);

    await chmod(assertionPath, 0o640);
    const nonPrivateConfig = runScript(directory, "check-config.mjs", "--env-file", fileEnvironmentPath, {
      NODE_ENV: "production",
    });
    expect(nonPrivateConfig.status).toBe(1);
    expect(nonPrivateConfig.stderr).toContain("owner-controlled mode-0600 file outside the repository");
    await chmod(assertionPath, 0o600);

    const bothSourcesPath = join(directory, "both-sources.env");
    await writePrivateFile(bothSourcesPath, [
      "ATLAS_V4_ENABLED=1",
      "ATLAS_V4_AUTH_MODE=dual",
      `ATLAS_CORE_PRINCIPAL_MAP_FILE=${principalMapPath}`,
      "ATLAS_V4_RELAY_ASSERTION_KEY_ID=synthetic-test-key",
      `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE=${assertionPath}`,
      `ATLAS_V4_RELAY_ASSERTION_SECRET=${secret}`,
      "",
    ].join("\n"));
    const bothSources = runScript(directory, "check-secret.mjs", "--env-file", bothSourcesPath, {
      NODE_ENV: "test",
    });
    expect(bothSources.status).toBe(1);
    expect(bothSources.stdout).toContain("ATLAS_V4_RELAY_ASSERTION_SECRET: unavailable or permissions invalid");
    expect(`${bothSources.stdout}${bothSources.stderr}`).not.toContain(secret);
  });
});

async function makeTemporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "atlas-relay-env-test-"));
  temporaryDirectories.push(directory);
  return directory;
}

async function writeEnvironmentFile(
  directory: string,
  options: { enabled: "0" | "1" | "true"; tokenPath?: string },
): Promise<string> {
  const lines = [
    "ATLAS_RELAY_URL=wss://relay.example.test",
    "ATLAS_BACKEND_URL=http://127.0.0.1:8100",
    `ATLAS_RELAY_STATE_DIR=${join(directory, "state")}`,
    `ATLAS_V4_ENABLED=${options.enabled}`,
  ];
  if (options.tokenPath) lines.push(`ATLAS_V4_ANDROID_TOKEN_FILE=${options.tokenPath}`);

  const envFile = join(directory, "relay.env");
  await writePrivateFile(envFile, `${lines.join("\n")}\n`);
  return envFile;
}

async function writePrivateFile(path: string, content: string): Promise<void> {
  await writeFile(path, content, { mode: 0o600 });
  await chmod(path, 0o600);
}

function runScript(directory: string, script: string, ...input: Array<string | Record<string, string>>) {
  const extraEnvironment = input.length > 0 && typeof input.at(-1) === "object"
    ? input.pop() as Record<string, string>
    : {};
  const args = input as string[];
  const result = spawnSync(process.execPath, [join(PACKAGE_ROOT, "scripts", script), ...args], {
    cwd: PACKAGE_ROOT,
    encoding: "utf8",
    env: {
      HOME: directory,
      PATH: process.env.PATH ?? "/usr/bin:/bin",
      ...extraEnvironment,
    },
    timeout: 5_000,
  });

  if (result.error) throw result.error;
  return {
    status: result.status,
    stdout: result.stdout,
    stderr: result.stderr,
  };
}
