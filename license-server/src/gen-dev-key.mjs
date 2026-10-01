// Para kluczy do TESTÓW lokalnych (nigdy na produkcjê):
//   node src/gen-dev-key.mjs
// Liniê LICENSE_SIGNING_KEY wklej do swojego license-server/.env, a klucz publiczny podaj przy budowaniu jarów:
//   ./mvnw -q -pl <modu³> -am package -Dlicense.publicKey=<klucz publiczny>
import { generateKeyPairSync } from "node:crypto";

const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
console.log(`LICENSE_SIGNING_KEY=${Buffer.from(pem).toString("base64")}`);
console.log(`Public key (-Dlicense.publicKey): ${publicKey.export({ type: "spki", format: "der" }).toString("base64")}`);
