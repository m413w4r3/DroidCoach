import { createHash, createPrivateKey, createPublicKey, timingSafeEqual } from "node:crypto";
import { importPublicKey, importSecretKey, generateKeyPair, type KeyPair } from "@getpaseo/relay/e2ee";
import { readPrivateJson, writePrivateJson } from "./secure-files.js";

export interface ServerIdentity {
  schema_version: 1;
  public_key: string;
  secret_key: string;
  fingerprint: string;
}

const PKCS8_X25519_PREFIX = Buffer.from("302e020100300506032b656e04220420", "hex");
const SPKI_PUBLIC_KEY_BYTES = 32;

export async function loadOrCreateIdentity(path: string): Promise<{ identity: ServerIdentity; keyPair: KeyPair }> {
  const stored = await readPrivateJson<Partial<ServerIdentity>>(path);
  if (stored === null) {
    const generated = generateKeyPair();
    const identity = serialize(generated);
    await writePrivateJson(path, identity);
    return { identity, keyPair: generated };
  }
  if (stored.schema_version !== 1 || typeof stored.public_key !== "string" || typeof stored.secret_key !== "string") {
    throw new Error("invalid identity file");
  }
  let publicKey: Uint8Array;
  let secretKey: Uint8Array;
  try {
    publicKey = importPublicKey(stored.public_key);
    secretKey = importSecretKey(stored.secret_key);
  } catch {
    throw new Error("invalid identity file");
  }
  const derivedPublic = derivePublicKey(secretKey);
  if (derivedPublic.byteLength !== publicKey.byteLength || !timingSafeEqual(derivedPublic, publicKey)) {
    throw new Error("identity key pair mismatch");
  }
  const identity = serialize({ publicKey, secretKey });
  if (stored.fingerprint !== identity.fingerprint) throw new Error("identity fingerprint mismatch");
  return { identity, keyPair: { publicKey, secretKey } };
}

function derivePublicKey(secretKey: Uint8Array): Buffer {
  const privateKey = createPrivateKey({
    key: Buffer.concat([PKCS8_X25519_PREFIX, Buffer.from(secretKey)]),
    format: "der",
    type: "pkcs8",
  });
  const publicDer = createPublicKey(privateKey).export({ format: "der", type: "spki" });
  const bytes = Buffer.from(publicDer).subarray(-SPKI_PUBLIC_KEY_BYTES);
  if (bytes.length !== SPKI_PUBLIC_KEY_BYTES) throw new Error("invalid X25519 identity");
  return bytes;
}

function serialize(keyPair: KeyPair): ServerIdentity {
  const publicKey = Buffer.from(keyPair.publicKey);
  return {
    schema_version: 1,
    public_key: publicKey.toString("base64"),
    secret_key: Buffer.from(keyPair.secretKey).toString("base64"),
    fingerprint: createHash("sha256").update(publicKey).digest("hex"),
  };
}
