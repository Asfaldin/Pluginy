import { createHash, randomBytes } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { readJson, writeJson } from "./jsonStore.js";

// Anonimowe statystyki odwiedzin strony rsmc-network.pl - bez ciasteczek i bez zewnętrznych usług.
// Na dysku są WYŁĄCZNIE liczby na dzień (wejścia, podstrony, domeny, z których ktoś przyszedł, typ urządzenia,
// kliknięcia). Unikalnych odwiedzających liczymy skrótem IP + przeglądarki z losową solą, która żyje tylko
// w pamięci i zmienia się o północy (UTC) - po zmianie dnia nie da się już nikogo rozpoznać ani powiązać dni.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DB_FILE = join(__dirname, "..", "data", "analytics.json");
const KEEP_DAYS = 730;
const EVENTS = new Set(["signup", "survey_open", "survey_done", "discord", "download",
    // lejek aplikacji (desktop-app/src/lib/track.ts)
    "app_first_open", "app_open", "welcome_next", "terms_accepted", "tour_done", "tour_skipped", "server_added", "plugin_installed", "config_sent", "register_error"]);
const BOT = /bot|crawl|spider|slurp|preview|headless|lighthouse|monitor|curl|wget|python|scan|http-client|go-http|java\//i;

let db = null;
let dirty = false;
let salt = { day: "", value: null };
const seen = new Set();

const today = () => new Date().toISOString().slice(0, 10);
const load = () => (db ??= readJson(DB_FILE, { days: {} }));
const clean = (v, n) => (typeof v === "string" ? v.toLowerCase().replace(/[^a-z0-9.\-_/]/g, "").slice(0, n) : "");

/** +1 pod kluczem; przy zbyt wielu różnych kluczach (śmieci od botów) reszta ląduje w "other". */
function inc(obj, key, max = 60) {
    if (!key) return;
    if (!(key in obj) && Object.keys(obj).length >= max) key = "other";
    obj[key] = (obj[key] ?? 0) + 1;
}

function bucket(day) {
    return (load().days[day] ??= { views: 0, visitors: 0, pages: {}, refs: {}, sources: {}, devices: {}, events: {} });
}

/** Wejście albo kliknięcie wysłane przez stats.js na stronie. @returns co policzono (dla powiadomień) albo undefined. */
export function recordHit(body, ip, ua) {
    if (!body || typeof body !== "object" || !ua || BOT.test(ua)) return;
    const day = today();
    if (salt.day !== day) {
        salt = { day, value: randomBytes(32) };
        seen.clear();
    }
    const b = bucket(day);
    if (body.t === "view") {
        b.views++;
        const id = createHash("sha256").update(salt.value).update(`${ip}|${ua}`).digest("base64");
        const newVisitor = !seen.has(id) && seen.size < 200_000;
        if (newVisitor) {
            seen.add(id);
            b.visitors++;
        }
        const ref = clean(body.r, 60).replace(/^www\./, "");
        inc(b.pages, clean(body.p, 60) || "/");
        inc(b.refs, ref || "(direct)");
        inc(b.sources, clean(body.s, 40));
        const w = Number(body.w) || 0;
        inc(b.devices, w > 0 && w < 768 ? "mobile" : w > 0 && w < 1100 ? "tablet" : "desktop");
        dirty = true;
        return { newVisitor, ref };
    } else if (body.t === "event" && EVENTS.has(body.e)) {
        inc(b.events, body.e);
        dirty = true;
        return { event: body.e };
    }
}

/** Zdarzenie z serwera (np. "account" - ktoś założył konto w aplikacji): sama liczba na dzień, bez danych osoby. */
export function recordEvent(e) {
    inc(bucket(today()).events, e);
    dirty = true;
}

/** Zapis na dysk najwyżej raz na minutę (nie przy każdym wejściu). */
export function flushAnalytics() {
    if (!dirty || !db) return;
    const cutoff = new Date(Date.now() - KEEP_DAYS * 864e5).toISOString().slice(0, 10);
    for (const d of Object.keys(db.days)) if (d < cutoff) delete db.days[d];
    writeJson(DB_FILE, db);
    dirty = false;
}
setInterval(flushAnalytics, 60_000).unref();
process.once("beforeExit", flushAnalytics);
for (const sig of ["SIGTERM", "SIGINT"]) {
    process.once(sig, () => {
        flushAnalytics();
        process.exit(0);
    });
}

/** Dzisiejsze liczby (UTC) - wejścia, odwiedzający i zdarzenia. */
export function todayStats() {
    const d = load().days[today()];
    return { views: d?.views ?? 0, visitors: d?.visitors ?? 0, events: { ...(d?.events ?? {}) } };
}

/** Sumy od początku zbierania: odwiedzający i każde zdarzenie (pobrania, konta, zapisy...). */
export function allTimeTotals() {
    const out = { visitors: 0, events: {} };
    for (const d of Object.values(load().days)) {
        out.visitors += d.visitors ?? 0;
        for (const [k, n] of Object.entries(d.events ?? {})) out.events[k] = (out.events[k] ?? 0) + n;
    }
    return out;
}

/** Ostatnie `days` dni (od najstarszego), puste dni jako zera - dla panelu. */
export function visitorStats(days = 90) {
    flushAnalytics();
    const out = [];
    for (let i = days - 1; i >= 0; i--) {
        const d = new Date(Date.now() - i * 864e5).toISOString().slice(0, 10);
        out.push({ day: d, ...(load().days[d] ?? { views: 0, visitors: 0, pages: {}, refs: {}, sources: {}, devices: {}, events: {} }) });
    }
    return out;
}
