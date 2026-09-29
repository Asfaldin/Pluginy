import { createHash } from "node:crypto";
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Płatne jary pluginów - NIE są wbudowane w instalator PluginManagera (tam są tylko
// darmowe), appka pobiera je stąd przy wysyłce na serwer, i to tylko zalogowany klient
// z aktywną licencją na dany plugin (patrz /api/me/plugins w server.js).
//
// Folder z jarami (PLUGIN_JARS_DIR, domyślnie license-server/plugin-jars) NIE jest
// serwowany statycznie - jedyna droga do pliku to endpoint z autoryzacją. Nazwy plików
// jak z mvn package: mainplugins-<id>-<wersja>.jar (np. mainplugins-crates-1.0-SNAPSHOT.jar).

const __dirname = dirname(fileURLToPath(import.meta.url));
const JARS_DIR = resolve(process.env.PLUGIN_JARS_DIR || join(__dirname, "..", "plugin-jars"));
const DATA_DIR = join(__dirname, "..", "data");
const DOWNLOAD_LOG = join(DATA_DIR, "downloads.jsonl");
const JAR_NAME = /^mainplugins-([a-z0-9]+)-[\w.-]+\.jar$/;

// Suma kontrolna liczona raz na wersję pliku (klucz: nazwa + mtime + rozmiar).
const hashCache = new Map();

function sha1Of(path, stat) {
    const cacheKey = `${path}:${stat.mtimeMs}:${stat.size}`;
    let sum = hashCache.get(cacheKey);
    if (!sum) {
        sum = createHash("sha1").update(readFileSync(path)).digest("hex");
        hashCache.set(cacheKey, sum);
    }
    return sum;
}

/** Wszystkie jary w folderze: [{ id, filename, size, sha1 }]. Przy dwóch wersjach tego samego
    pluginu wygrywa nowszy plik. */
export function listJars() {
    if (!existsSync(JARS_DIR)) return [];
    const byId = new Map();
    for (const filename of readdirSync(JARS_DIR)) {
        const m = JAR_NAME.exec(filename);
        if (!m) continue;
        const path = join(JARS_DIR, filename);
        const stat = statSync(path);
        const prev = byId.get(m[1]);
        if (prev && prev.mtimeMs >= stat.mtimeMs) continue;
        byId.set(m[1], { id: m[1], filename, path, size: stat.size, mtimeMs: stat.mtimeMs, stat });
    }
    return [...byId.values()].map(({ id, filename, path, size, stat }) => ({ id, filename, size, sha1: sha1Of(path, stat), path }));
}

export function findJar(pluginId) {
    if (!/^[a-z0-9]+$/.test(pluginId)) return null; // bez ścieżek typu ../
    return listJars().find((j) => j.id === pluginId) ?? null;
}

// Prosty limit pobrań na konto (w pamięci procesu): chroni przed hurtowym ściąganiem
// przez jedno konto udostępnione wielu osobom. Normalna wysyłka to kilka-kilkanaście
// jarów naraz, więc limit jest z dużym zapasem.
const WINDOW_MS = 60 * 60 * 1000;
const MAX_PER_WINDOW = 120;
const recent = new Map();

export function allowDownload(customerId) {
    const now = Date.now();
    const list = (recent.get(customerId) ?? []).filter((t) => now - t < WINDOW_MS);
    if (list.length >= MAX_PER_WINDOW) {
        recent.set(customerId, list);
        return false;
    }
    list.push(now);
    recent.set(customerId, list);
    return true;
}

/** Dziennik pobrań (JSON Lines) - kto, co, kiedy i skąd; przydatny przy wycieku jara. */
export function logDownload(entry) {
    if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });
    appendFileSync(DOWNLOAD_LOG, JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n", "utf8");
}
