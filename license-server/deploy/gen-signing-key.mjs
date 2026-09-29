// Generuje parę kluczy Ed25519 do podpisywania tokenów licencji.
//   node deploy/gen-signing-key.mjs
// Wypisuje:
//  - LICENSE_SIGNING_KEY=...  -> do .env na VPS (/opt/license-server/.env), NIKOMU nie pokazywać,
//  - klucz publiczny          -> do mainplugins-license/.../LicenseToken.java (PUBLIC_KEY).
// Zmiana pary kluczy wymaga nowych jarów pluginów (stare nie przyjmą nowych podpisów).
import { generateKeyPairSync } from "node:crypto";

const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
console.log("LICENSE_SIGNING_KEY=" + Buffer.from(pem, "utf8").toString("base64"));
console.log("");
console.log("Public key for LicenseToken.PUBLIC_KEY:");
console.log(publicKey.export({ type: "spki", format: "der" }).toString("base64"));
