import { createPrivateKey, createPublicKey, sign } from "node:crypto";

// Podpisane tokeny licencji (Ed25519). Serwer licencji podpisuje kluczem prywatnym
// (LICENSE_SIGNING_KEY w .env - NIGDY w repo), a pluginy (mainplugins-license:
// LicenseToken.java) mają wbudowany klucz publiczny i odrzucają wszystko, czego ten
// serwer nie podpisał. Dzięki temu nie da się podstawić własnego "serwera licencji"
// (api.url w license.yml) ani podrobić licencji w podmienionym Core.
//
// Nowa para kluczy: node deploy/gen-signing-key.mjs (klucz publiczny trafia do
// LicenseToken.PUBLIC_KEY; zmiana pary unieważnia tokeny starych wersji pluginów).

const TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000;

let privateKey = null;
let publicKeyB64 = null;

/** Ładuje klucz z env (PEM zakodowany base64 w jednej linii). Bez niego serwer nie startuje. */
export function loadSigningKey() {
    const raw = process.env.LICENSE_SIGNING_KEY;
    if (!raw) return false;
    privateKey = createPrivateKey(Buffer.from(raw, "base64").toString("utf8"));
    if (privateKey.asymmetricKeyType !== "ed25519") throw new Error("LICENSE_SIGNING_KEY must be an Ed25519 private key");
    publicKeyB64 = createPublicKey(privateKey).export({ type: "spki", format: "der" }).toString("base64");
    return true;
}

export function signingPublicKey() {
    return publicKeyB64;
}

/**
 * Token ważnej licencji: base64url(JSON) + podpis base64url. Pola w JSON-ie (kolejność
 * nieistotna - podpisujemy dokładne bajty tokenu, nie pola):
 *  v, plugin, serverId, nonce (od pluginu - chroni przed odtworzeniem starej odpowiedzi),
 *  iat, exp (ms od epoki; po exp plugin bez świeżego tokenu się wyłącza).
 */
export function issueToken({ plugin, serverId, nonce }) {
    const now = Date.now();
    const payload = { v: 1, plugin, serverId, nonce: nonce ?? "", iat: now, exp: now + TOKEN_TTL_MS };
    const token = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
    const signature = sign(null, Buffer.from(token, "ascii"), privateKey).toString("base64url");
    return { token, signature, exp: payload.exp };
}
