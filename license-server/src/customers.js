import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Konta klientów (kupujących), oddzielone od licencji - jeden klient może z czasem
// mieć wiele licencji (kolejne zakupy/pakiety), stąd osobny plik zamiast trzymania
// hasła bezpośrednio w rekordzie licencji. Hasła hashowane scryptem (wbudowany w
// Node, bez dodatkowej zależności) - NIGDY nie trzymamy hasła jawnego.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "customers.json");

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

function hashPassword(password) {
    const salt = randomBytes(16).toString("hex");
    const hash = scryptSync(password, salt, 64).toString("hex");
    return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
    const [salt, hashHex] = stored.split(":");
    if (!salt || !hashHex) return false;
    const actual = scryptSync(password, salt, 64);
    const expected = Buffer.from(hashHex, "hex");
    if (actual.length !== expected.length) return false;
    return timingSafeEqual(actual, expected);
}

export function findCustomerByEmail(email) {
    return loadAll().find((c) => c.email.toLowerCase() === email.toLowerCase()) ?? null;
}

export function findCustomerById(id) {
    return loadAll().find((c) => c.id === id) ?? null;
}

/** @returns nowo utworzony klient, albo null jeśli e-mail już istnieje. */
export function registerCustomer(email, password) {
    if (findCustomerByEmail(email)) return null;
    const list = loadAll();
    const customer = {
        id: randomUUID(),
        email,
        passwordHash: hashPassword(password),
        createdAt: new Date().toISOString(),
    };
    list.push(customer);
    saveAll(list);
    return toPublic(customer);
}

/** @returns klient (bez hasha) jeśli dane poprawne, inaczej null. */
export function authenticateCustomer(email, password) {
    const customer = findCustomerByEmail(email);
    if (!customer) return null;
    if (!verifyPassword(password, customer.passwordHash)) return null;
    return toPublic(customer);
}

function toPublic(customer) {
    const { passwordHash, ...rest } = customer;
    void passwordHash;
    return rest;
}

/** @returns true jeśli zmiana się powiodła, false jeśli aktualne hasło się nie zgadza albo konto nie istnieje. */
export function changePassword(customerId, currentPassword, newPassword) {
    const list = loadAll();
    const customer = list.find((c) => c.id === customerId);
    if (!customer) return false;
    if (!verifyPassword(currentPassword, customer.passwordHash)) return false;
    customer.passwordHash = hashPassword(newPassword);
    saveAll(list);
    return true;
}

const RESET_TOKEN_TTL_MS = 30 * 60 * 1000;

function hashToken(token) {
    return createHash("sha256").update(token).digest("hex");
}

/** Generuje token resetu hasła (jawny tekst zwracany TYLKO tu, do wysłania mailem) -
    w bazie trzymamy wyłącznie jego hash, tak jak hasła. Zwraca null, gdy e-mail nie
    istnieje - wołający (server.js) i tak zawsze odpowiada tym samym komunikatem, żeby
    formularz nie zdradzał, które adresy mają konto. */
export function createPasswordResetToken(email) {
    const customer = findCustomerByEmail(email);
    if (!customer) return null;
    const token = randomBytes(32).toString("hex");
    const list = loadAll();
    const target = list.find((c) => c.id === customer.id);
    target.resetTokenHash = hashToken(token);
    target.resetTokenExpiresAt = new Date(Date.now() + RESET_TOKEN_TTL_MS).toISOString();
    saveAll(list);
    return token;
}

/** @returns true jeśli kod poprawny i nie wygasł - wtedy od razu ustawia nowe hasło i
    zużywa kod (jednorazowy, jak każdy token resetu). */
export function resetPasswordWithToken(token, newPassword) {
    const hash = hashToken(token);
    const list = loadAll();
    const customer = list.find((c) => c.resetTokenHash === hash);
    if (!customer) return false;
    if (!customer.resetTokenExpiresAt || new Date(customer.resetTokenExpiresAt).getTime() < Date.now()) return false;
    customer.passwordHash = hashPassword(newPassword);
    delete customer.resetTokenHash;
    delete customer.resetTokenExpiresAt;
    saveAll(list);
    return true;
}
