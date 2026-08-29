import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Prosta baza plikowa (JSON) - w sam raz na skalę "jeden operator, dziesiątki/
// setki licencji". Zapisy są synchroniczne, więc bez wyścigów przy współbieżnych
// requestach (Node i tak obsługuje je jeden po drugim między await-ami).
// Gdy skala urośnie, wymiana na prawdziwą bazę (Postgres/SQLite) to tylko
// przepisanie tego jednego pliku - reszta serwera korzysta wyłącznie z funkcji
// eksportowanych stąd.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "licenses.json");

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

export function listLicenses() {
    return loadAll();
}

export function findByKey(key) {
    return loadAll().find((l) => l.key === key) ?? null;
}

/**
 * `plugin` może być: pojedynczy id ("tools"), lista rozdzielona przecinkami dla
 * pakietu ("crates,market,spawners") albo "*" (wszystko, patrz licenseGrants).
 * `customerId`/`subscriptionId`/`billingType` opcjonalne - licencje wystawione
 * ręcznie z panelu admina (curl/zakładka Licencje) ich nie mają.
 */
export function createLicense({ key, plugin, note, customerId = null, subscriptionId = null, billingType = "one-time" }) {
    const list = loadAll();
    const license = {
        key,
        plugin,
        note: note ?? "",
        serverId: null,
        status: "active",
        customerId,
        subscriptionId,
        billingType,
        createdAt: new Date().toISOString(),
        boundAt: null,
    };
    list.push(license);
    saveAll(list);
    return license;
}

export function revokeLicense(key) {
    const list = loadAll();
    const license = list.find((l) => l.key === key);
    if (!license) return null;
    license.status = "revoked";
    saveAll(list);
    return license;
}

export function bindServer(key, serverId) {
    const list = loadAll();
    const license = list.find((l) => l.key === key);
    if (!license) return null;
    license.serverId = serverId;
    license.boundAt = new Date().toISOString();
    saveAll(list);
    return license;
}

export function findByCustomer(customerId) {
    return loadAll().filter((l) => l.customerId === customerId);
}

/** Do obsługi webhooków cyklu życia subskrypcji (renew/cancel) - znajduje licencję po ID subskrypcji LemonSqueezy. */
export function findBySubscriptionId(subscriptionId) {
    return loadAll().find((l) => l.subscriptionId === String(subscriptionId)) ?? null;
}

export function setStatus(key, status) {
    const list = loadAll();
    const license = list.find((l) => l.key === key);
    if (!license) return null;
    license.status = status;
    saveAll(list);
    return license;
}

/** Czy licencja (już zweryfikowana jako active/przypisana do właściwego serwera) obejmuje ten plugin. */
export function licenseGrants(license, pluginId) {
    if (license.plugin === "*") return true;
    return license.plugin.split(",").map((s) => s.trim()).includes(pluginId);
}
