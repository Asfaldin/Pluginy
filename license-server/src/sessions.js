import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Opaque session tokeny (nie JWT - nie ma potrzeby podpisywać/dekodować niczego
// po stronie klienta, prostsze i równie bezpieczne przy tej skali: token to
// losowy klucz do rekordu trzymanego tutaj, każdy request go weryfikuje
// przeciwko temu plikowi). Wygasają po 30 dniach - appka po prostu każe się
// zalogować ponownie, nie ma "refresh tokenów".

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "sessions.json");
const TTL_MS = 30 * 24 * 60 * 60 * 1000;

function ensureFile() {
    if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });
    if (!existsSync(DB_FILE)) writeFileSync(DB_FILE, "[]", "utf8");
}

function loadAll() {
    ensureFile();
    return JSON.parse(readFileSync(DB_FILE, "utf8"));
}

function saveAll(list) {
    writeFileSync(DB_FILE, JSON.stringify(list, null, 2), "utf8");
}

export function createSession(customerId) {
    const list = loadAll().filter((s) => s.expiresAt > Date.now()); // przy okazji sprzątamy wygasłe
    const token = randomBytes(32).toString("hex");
    list.push({ token, customerId, expiresAt: Date.now() + TTL_MS });
    saveAll(list);
    return token;
}

export function resolveSession(token) {
    if (!token) return null;
    const session = loadAll().find((s) => s.token === token);
    if (!session || session.expiresAt < Date.now()) return null;
    return session.customerId;
}

export function deleteSession(token) {
    const list = loadAll().filter((s) => s.token !== token);
    saveAll(list);
}
