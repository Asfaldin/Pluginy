import { readJson, writeJson } from "./jsonStore.js";
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

// Zapis atomowy z kopią .bak - patrz src/jsonStore.js.
function loadAll() {
    return readJson(DB_FILE, []);
}

function saveAll(list) {
    writeJson(DB_FILE, list);
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
export function createLicense({ key, plugin, note, customerId = null, subscriptionId = null, billingType = "one-time", orderId = null }) {
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
        orderId: orderId == null ? null : String(orderId),
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
    // Plan subskrypcji (plan:plus/pro/network) obejmuje wszystkie pluginy - patrz plans.js.
    if (license.plugin.startsWith("plan:")) return true;
    return license.plugin.split(",").map((s) => s.trim()).includes(pluginId);
}

/** Licencja z danego zamówienia LemonSqueezy - do wykrywania powtórzonych webhooków. */
export function findByOrderId(orderId) {
    const id = String(orderId);
    // Licencje sprzed pola orderId mają numer zamówienia tylko w notatce.
    return loadAll().find((l) => (l.orderId != null ? l.orderId === id : l.note?.startsWith(`LemonSqueezy order ${id} - `))) ?? null;
}

/**
 * Zapisuje, z jakiego IP sprawdzano klucz (ostatnie 20 różnych adresów). Ten sam
 * license.yml skopiowany na drugi serwer daje ten sam serverId, więc tego nie da się
 * rozpoznać po samym kluczu - ale widać to po kilku różnych IP w krótkim czasie.
 * Nie blokujemy automatycznie (dynamiczne IP, przeprowadzki hostingu), tylko oznaczamy
 * licencję polem `suspicious` do sprawdzenia w panelu admina.
 */
export function recordValidation(key, ip) {
    const list = loadAll();
    const license = list.find((l) => l.key === key);
    if (!license || !ip) return null;
    const now = Date.now();
    const seen = (license.seenIps ?? []).filter((e) => e.ip !== ip);
    seen.push({ ip, at: new Date(now).toISOString() });
    license.seenIps = seen.slice(-20);
    const dayAgo = now - 24 * 60 * 60 * 1000;
    const recent = new Set(license.seenIps.filter((e) => Date.parse(e.at) > dayAgo).map((e) => e.ip));
    if (recent.size > 2 && !license.suspicious) {
        license.suspicious = `${recent.size} different IPs within 24 h (${new Date(now).toISOString()})`;
        console.warn(`License ${key} validated from ${recent.size} IPs within 24 h - possible key sharing.`);
    }
    saveAll(list);
    return license;
}
