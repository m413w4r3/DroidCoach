import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { findRepositoryRoot, isOutsideRepositoryPath, isPrivateV4RelayAssertion, loadEnvironmentFile } from "../scripts/lib/config.mjs";

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

  it("validates assertion files in standalone layouts and detects fake checkout roots", async () => {
    const directory = await makeTemporaryDirectory();
    await writeFile(join(directory, ".git"), "unrelated dotfiles marker\n", { mode: 0o600 });
    const standaloneModuleDirectory = join(directory, ".local", "lib", "atlas-relay", "scripts", "lib");
    await mkdir(standaloneModuleDirectory, { recursive: true });
    const secret = Buffer.from("synthetic-standalone-assertion-secret").toString("base64url");
    const assertionPath = join(directory, ".local", "state", "atlas-relay", "assertion-key");
    await mkdir(dirname(assertionPath), { recursive: true });
    await writePrivateFile(assertionPath, `${secret}\n`);

    const standaloneRoot = await findRepositoryRoot(standaloneModuleDirectory);
    expect(standaloneRoot).toBeUndefined();
    expect(isOutsideRepositoryPath(assertionPath, standaloneRoot)).toBe(true);
    const fileEnvironment = {
      ATLAS_V4_RELAY_ASSERTION_KEY_ID: "synthetic-test-key",
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: assertionPath,
      NODE_ENV: "production",
    };
    expect(await isPrivateV4RelayAssertion(fileEnvironment)).toBe(true);

    await chmod(assertionPath, 0o640);
    expect(await isPrivateV4RelayAssertion(fileEnvironment)).toBe(false);
    await chmod(assertionPath, 0o600);
    expect(await isPrivateV4RelayAssertion({
      ...fileEnvironment,
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: "relative/assertion-key",
    })).toBe(false);

    const invalidPath = join(directory, "invalid-assertion-key");
    await writePrivateFile(invalidPath, "not-base64url\n");
    expect(await isPrivateV4RelayAssertion({
      ...fileEnvironment,
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: invalidPath,
    })).toBe(false);

    const linkedPath = join(directory, "linked-assertion-key");
    await symlink(assertionPath, linkedPath);
    expect(await isPrivateV4RelayAssertion({
      ...fileEnvironment,
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: linkedPath,
    })).toBe(false);

    const owner = process.getuid();
    const ownerSpy = vi.spyOn(process, "getuid").mockReturnValue(owner + 1);
    try {
      expect(await isPrivateV4RelayAssertion(fileEnvironment)).toBe(false);
    } finally {
      ownerSpy.mockRestore();
    }
  });

  it("recognizes ATLAS checkouts, ignores unrelated Git parents, and fences the real checkout", async () => {
    const directory = await makeTemporaryDirectory();
    const checkoutRoot = join(directory, "checkout");
    const checkoutModuleDirectory = join(checkoutRoot, "integrations", "relay", "pc", "scripts", "lib");
    await mkdir(checkoutModuleDirectory, { recursive: true });
    await writeFile(join(checkoutRoot, ".git"), "synthetic git marker\n", { mode: 0o600 });
    await writeFile(join(checkoutRoot, "AGENTS.md"), "synthetic marker\n", { mode: 0o600 });
    const checkoutSecretPath = join(checkoutRoot, "state", "assertion-key");
    const externalSecretPath = join(directory, "external", "state", "assertion-key");
    await mkdir(dirname(checkoutSecretPath), { recursive: true });
    await mkdir(dirname(externalSecretPath), { recursive: true });
    const discoveredRoot = await findRepositoryRoot(checkoutModuleDirectory);
    expect(discoveredRoot).toBe(checkoutRoot);
    expect(isOutsideRepositoryPath(checkoutSecretPath, discoveredRoot)).toBe(false);
    expect(isOutsideRepositoryPath(externalSecretPath, discoveredRoot)).toBe(true);
    const externalSecret = Buffer.from("synthetic-atlas-external-assertion").toString("base64url");
    await writePrivateFile(externalSecretPath, `${externalSecret}\n`);
    expect(await isPrivateV4RelayAssertion({
      ATLAS_V4_RELAY_ASSERTION_KEY_ID: "synthetic-test-key",
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: externalSecretPath,
      NODE_ENV: "production",
    })).toBe(true);

    const actualCheckoutRoot = await findRepositoryRoot(PACKAGE_ROOT);
    expect(actualCheckoutRoot).toBeDefined();
    const actualSecretDirectory = await mkdtemp(join(PACKAGE_ROOT, ".relay-secret-test-"));
    temporaryDirectories.push(actualSecretDirectory);
    const actualSecretPath = join(actualSecretDirectory, "assertion-key");
    const actualSecret = Buffer.from("synthetic-real-checkout-assertion-secret").toString("base64url");
    await writePrivateFile(actualSecretPath, `${actualSecret}\n`);
    expect(await isPrivateV4RelayAssertion({
      ATLAS_V4_RELAY_ASSERTION_KEY_ID: "synthetic-test-key",
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: actualSecretPath,
      NODE_ENV: "production",
    })).toBe(false);
  });

  it("uses the outermost ATLAS boundary when checkouts are nested", async () => {
    const directory = await makeTemporaryDirectory();
    const outerRoot = join(directory, "outer-checkout");
    const nestedRoot = join(outerRoot, "projects", "nested-checkout");
    const moduleDirectory = join(nestedRoot, "integrations", "relay", "pc", "scripts", "lib");
    await mkdir(join(outerRoot, "integrations", "relay", "pc"), { recursive: true });
    await mkdir(moduleDirectory, { recursive: true });
    await writeFile(join(outerRoot, ".git"), "synthetic outer git marker\n", { mode: 0o600 });
    await writeFile(join(nestedRoot, "AGENTS.md"), "synthetic nested marker\n", { mode: 0o600 });

    const outerSecretPath = join(outerRoot, "state", "assertion-key");
    const externalSecretPath = join(directory, "outside", "state", "assertion-key");
    await mkdir(dirname(outerSecretPath), { recursive: true });
    await mkdir(dirname(externalSecretPath), { recursive: true });
    const secret = Buffer.from("synthetic-nested-checkout-assertion-secret").toString("base64url");
    await writePrivateFile(outerSecretPath, `${secret}\n`);
    await writePrivateFile(externalSecretPath, `${secret}\n`);

    const discoveredRoot = await findRepositoryRoot(moduleDirectory);
    expect(discoveredRoot).toBe(outerRoot);
    expect(isOutsideRepositoryPath(outerSecretPath, discoveredRoot)).toBe(false);
    expect(isOutsideRepositoryPath(externalSecretPath, discoveredRoot)).toBe(true);
    expect(await isPrivateV4RelayAssertion({
      ATLAS_V4_RELAY_ASSERTION_KEY_ID: "synthetic-test-key",
      ATLAS_V4_RELAY_ASSERTION_SECRET_FILE: externalSecretPath,
      NODE_ENV: "production",
    })).toBe(true);
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
