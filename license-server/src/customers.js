import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { readJson, writeJson } from "./jsonStore.js";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Konta klientów (kupujących), oddzielone od licencji - jeden klient może z czasem
// mieć wiele licencji (kolejne zakupy/pakiety), stąd osobny plik zamiast trzymania
// hasła bezpośrednio w rekordzie licencji. Hasła hashowane scryptem (wbudowany w
// Node, bez dodatkowej zależności) - NIGDY nie trzymamy hasła jawnego.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "customers.json");

// Zapis atomowy z kopią .bak - patrz src/jsonStore.js.
function loadAll() {
    return readJson(DB_FILE, []);
}

function saveAll(list) {
    writeJson(DB_FILE, list);
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

/** Ile kont jest w sumie (statystyki na Discordzie - sama liczba). */
export function customerCount() {
    return loadAll().length;
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

/** Tylko pola, które klient może zobaczyć (bez skrótów hasła i tokenu resetu). */
export function toPublic(customer) {
    return { id: customer.id, email: customer.email, createdAt: customer.createdAt, role: customer.role ?? "customer" };
}

// --- Role zespołu wsparcia ---
// "admin" - pełny dostęp do panelu wsparcia; "support" - to samo (na przyszłość, gdyby
// trzeba było rozdzielić uprawnienia). Rolę nadaje się WYŁĄCZNIE skryptem na serwerze
// (src/set-role.mjs), nigdy przez API - nikt nie może nadać jej sam sobie.
export const STAFF_ROLES = new Set(["admin", "support"]);

export function isStaff(customerId) {
    const c = findCustomerById(customerId);
    return !!c && STAFF_ROLES.has(c.role);
}

/** @returns zaktualizowany klient albo null, gdy konto nie istnieje. */
export function setRole(email, role) {
    const list = loadAll();
    const customer = list.find((c) => c.email.toLowerCase() === email.toLowerCase());
    if (!customer) return null;
    if (role === "customer") delete customer.role;
    else customer.role = role;
    saveAll(list);
    return toPublic(customer);
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

/** @returns id konta, jeśli kod poprawny i nie wygasł (wtedy od razu ustawia nowe hasło
    i zużywa kod - jednorazowy), albo false. */
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
    return customer.id;
}

// --- Weryfikacja dwuetapowa (tylko konta zespołu, patrz src/totp.js) ---
// Sekret trzymany w customers.json (plik 600, poza repo). Wyłączenie/reset tylko skryptem
// na serwerze (src/reset-2fa.mjs) - ktoś z samym hasłem nie wyłączy 2FA przez API.

export function totpEnabled(customerId) {
    return !!findCustomerById(customerId)?.totp?.enabled;
}

/** Zapisuje sekret do potwierdzenia pierwszym kodem. Nie nadpisuje już włączonego 2FA. */
export function setPendingTotp(customerId, secret) {
    const list = loadAll();
    const c = list.find((x) => x.id === customerId);
    if (!c || c.totp?.enabled) return false;
    c.totp = { secret, enabled: false, lastStep: -1 };
    saveAll(list);
    return true;
}

/**
 * Sprawdza kod i zapamiętuje użyte okno (kod nie przejdzie drugi raz).
 * @param enable true = pierwsze potwierdzenie przy włączaniu 2FA.
 */
export function checkTotp(customerId, code, verify, enable = false) {
    const list = loadAll();
    const c = list.find((x) => x.id === customerId);
    if (!c?.totp?.secret || c.totp.enabled === enable) return false;
    const step = verify(c.totp.secret, code, c.totp.lastStep ?? -1);
    if (step == null) return false;
    c.totp.lastStep = step;
    if (enable) c.totp.enabled = true;
    saveAll(list);
    return true;
}

export function resetTotp(email) {
    const list = loadAll();
    const c = list.find((x) => x.email.toLowerCase() === String(email).toLowerCase());
    if (!c) return false;
    delete c.totp;
    saveAll(list);
    return true;
}

/** Zapamiętuje skrót IP logowania. @returns true, gdy to IP pojawia się pierwszy raz (alert). */
export function noteLoginIp(customerId, ipHash) {
    const list = loadAll();
    const c = list.find((x) => x.id === customerId);
    if (!c) return false;
    const known = c.loginIps ?? [];
    const isNew = !known.includes(ipHash);
    c.loginIps = [ipHash, ...known.filter((h) => h !== ipHash)].slice(0, 20);
    saveAll(list);
    return isNew;
}
