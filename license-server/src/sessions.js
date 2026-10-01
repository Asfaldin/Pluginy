import { createHash, randomBytes } from "node:crypto";
import { readJson, writeJson } from "./jsonStore.js";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Opaque session tokeny (nie JWT - nie ma potrzeby podpisywać/dekodować niczego
// po stronie klienta, prostsze i równie bezpieczne przy tej skali: token to
// losowy klucz do rekordu trzymanego tutaj, każdy request go weryfikuje
// przeciwko temu plikowi). Wygasają po 30 dniach - appka po prostu każe się
// zalogować ponownie, nie ma "refresh tokenów".
//
// W pliku trzymamy tylko SKRÓT tokenu (SHA-256) - wyciek sessions.json (np. z kopii
// zapasowej) nie pozwala przejąć niczyjego konta. Stare wpisy z jawnym tokenem (sprzed
// tej zmiany) są jeszcze rozpoznawane i wygasną same.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "sessions.json");
const TTL_MS = 30 * 24 * 60 * 60 * 1000;

const hashToken = (token) => createHash("sha256").update(String(token)).digest("hex");
const matches = (s, token) => s.tokenHash === hashToken(token) || (s.token != null && s.token === token);

// Zapis atomowy z kopią .bak - patrz src/jsonStore.js.
function loadAll() {
    return readJson(DB_FILE, []);
}

function saveAll(list) {
    writeJson(DB_FILE, list);
}

/** @param mfa true = logowanie potwierdzone kodem 2FA (wymagane przez panel zespołu). */
export function createSession(customerId, mfa = false) {
    const list = loadAll().filter((s) => s.expiresAt > Date.now()); // przy okazji sprzątamy wygasłe
    const token = randomBytes(32).toString("hex");
    list.push({ tokenHash: hashToken(token), customerId, expiresAt: Date.now() + TTL_MS, ...(mfa ? { mfa: true } : {}) });
    saveAll(list);
    return token;
}

export function resolveSession(token) {
    return resolveSessionInfo(token)?.customerId ?? null;
}

/** Jak resolveSession, ale z flagą mfa. */
export function resolveSessionInfo(token) {
    if (!token) return null;
    const session = loadAll().find((s) => matches(s, token));
    if (!session || session.expiresAt < Date.now()) return null;
    return { customerId: session.customerId, mfa: !!session.mfa };
}

/** Oznacza bieżącą sesję jako potwierdzoną 2FA (zaraz po włączeniu weryfikacji). */
export function markSessionMfa(token) {
    const list = loadAll();
    const s = list.find((x) => matches(x, token));
    if (!s) return false;
    s.mfa = true;
    saveAll(list);
    return true;
}

export function deleteSession(token) {
    const list = loadAll().filter((s) => !matches(s, token));
    saveAll(list);
}

/** Wylogowuje konto wszędzie (po zmianie/resecie hasła), opcjonalnie poza bieżącą sesją. */
export function deleteSessionsForCustomer(customerId, exceptToken = null) {
    const list = loadAll().filter((s) => s.customerId !== customerId || (exceptToken != null && matches(s, exceptToken)));
    saveAll(list);
}
