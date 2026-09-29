import { closeSync, copyFileSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeSync } from "node:fs";
import { dirname } from "node:path";

// Bezpieczny zapis plików-baz JSON (licencje, konta, sesje, zgłoszenia).
// Zapis atomowy: nowa treść idzie do pliku tymczasowego, jest fsync-owana i dopiero
// potem podmienia stary plik (rename jest atomowy w obrębie jednego systemu plików).
// Awaria prądu / restart w trakcie zapisu zostawia albo stary, albo nowy plik - nigdy
// pusty czy ucięty. Poprzednia wersja zostaje jako <plik>.bak i jest używana, gdyby
// główny plik mimo wszystko nie dał się odczytać.

export function readJson(file, fallback) {
    for (const path of [file, `${file}.bak`]) {
        if (!existsSync(path)) continue;
        try {
            return JSON.parse(readFileSync(path, "utf8"));
        } catch (e) {
            console.error(`Cannot parse ${path}: ${e.message}`);
        }
    }
    return fallback;
}

export function writeJson(file, data) {
    const dir = dirname(file);
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
    const tmp = `${file}.tmp`;
    const fd = openSync(tmp, "w", 0o600);
    try {
        writeSync(fd, JSON.stringify(data, null, 2));
        fsyncSync(fd);
    } finally {
        closeSync(fd);
    }
    if (existsSync(file)) copyFileSync(file, `${file}.bak`);
    renameSync(tmp, file);
}
