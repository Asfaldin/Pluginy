import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { readJson, writeJson } from "./jsonStore.js";

// Społeczność ze strony rsmc-network.pl: zapisy na wczesny dostęp (z potwierdzeniem mailem,
// RODO - bez potwierdzenia adres nie jest używany) i tablica pomysłów / zgłoszeń z głosowaniem.
// Dane w data/waitlist.json i data/ideas.json (poza repo, jak reszta baz).

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const WAITLIST_FILE = join(DATA_DIR, "waitlist.json");
const IDEAS_FILE = join(DATA_DIR, "ideas.json");

const sha = (s) => createHash("sha256").update(s).digest("hex");
export const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,24}$/;
export const IDEA_TYPES = ["idea", "bug", "feedback"];
export const IDEA_STATUSES = ["open", "planned", "in_progress", "done", "hidden"];

// ---- zapisy ----

const DAY = 24 * 3600 * 1000;

/** Lista zapisów bez niepotwierdzonych starszych niż 90 dni (tak mówi polityka prywatności). */
function loadWaitlist() {
    const db = readJson(WAITLIST_FILE, { entries: [] });
    const before = db.entries.length;
    db.entries = db.entries.filter((e) => e.confirmed || Date.now() - Date.parse(e.tokenAt ?? e.createdAt) < 90 * DAY);
    if (db.entries.length !== before) writeJson(WAITLIST_FILE, db);
    return db;
}

/**
 * Zapis albo ponowny zapis (nowy link potwierdzający). Zwraca token do maila albo null, gdy adres
 * jest już potwierdzony (odpowiedź dla użytkownika i tak ta sama - nie zdradzamy, kto jest na liście).
 */
export function addToWaitlist(email, source) {
    const db = loadWaitlist();
    const norm = email.trim().toLowerCase();
    let entry = db.entries.find((e) => e.email === norm);
    if (entry?.confirmed) return null;
    const token = randomBytes(24).toString("base64url");
    if (!entry) {
        entry = { email: norm, confirmed: false, createdAt: new Date().toISOString(), source: String(source ?? "site").slice(0, 40) };
        db.entries.push(entry);
    }
    entry.tokenHash = sha(token);
    entry.tokenAt = new Date().toISOString();
    writeJson(WAITLIST_FILE, db);
    return token;
}

/** Potwierdzenie z linku w mailu (ważny 7 dni). true = potwierdzony. */
export function confirmWaitlist(token) {
    if (!token || typeof token !== "string" || token.length > 100) return false;
    const db = loadWaitlist();
    const h = sha(token);
    const entry = db.entries.find((e) => e.tokenHash === h);
    if (!entry) return false;
    if (Date.now() - Date.parse(entry.tokenAt) > 7 * 24 * 3600 * 1000) return false;
    entry.confirmed = true;
    entry.confirmedAt = new Date().toISOString();
    delete entry.tokenHash;
    writeJson(WAITLIST_FILE, db);
    return true;
}

export function waitlistStats() {
    const db = loadWaitlist();
    return { total: db.entries.length, confirmed: db.entries.filter((e) => e.confirmed).length };
}

/** Dla admina: wszystkie zapisy (bez tokenów). */
export function waitlistAll() {
    return loadWaitlist().entries.map((e) => ({ email: e.email, confirmed: !!e.confirmed, createdAt: e.createdAt, confirmedAt: e.confirmedAt ?? null, source: e.source ?? null }));
}

/** Usunięcie adresu z listy (prośba o usunięcie / wypisanie się). */
/** Podpis linku "wypisz mnie" - HMAC adresu z sekretem serwera: bez niego nie da się wypisać cudzego adresu. */
export function unsubscribeSig(email, secret) {
    return createHmac("sha256", secret).update(`unsub|${String(email).trim().toLowerCase()}`).digest("base64url").slice(0, 32);
}

export function checkUnsubscribeSig(email, sig, secret) {
    const want = Buffer.from(unsubscribeSig(email, secret));
    const got = Buffer.from(String(sig ?? ""));
    return got.length === want.length && timingSafeEqual(got, want);
}

export function waitlistRemove(email) {
    const db = loadWaitlist();
    const norm = String(email).trim().toLowerCase();
    const before = db.entries.length;
    db.entries = db.entries.filter((e) => e.email !== norm);
    if (db.entries.length === before) return false;
    writeJson(WAITLIST_FILE, db);
    return true;
}

/** Dla zespołu: potwierdzone adresy (do zaproszeń). */
export function waitlistConfirmed() {
    return loadWaitlist().entries.filter((e) => e.confirmed).map((e) => ({ email: e.email, confirmedAt: e.confirmedAt, source: e.source }));
}

// ---- tablica pomysłów ----

function loadIdeas() {
    return readJson(IDEAS_FILE, { ideas: [] });
}

/** Publiczny widok: bez ukrytych, bez identyfikatorów głosujących; mine = czy ten głosujący już dał głos. */
export function listIdeas(voter) {
    return loadIdeas()
        .ideas.filter((i) => i.status !== "hidden")
        .map((i) => ({ id: i.id, type: i.type, title: i.title, body: i.body, author: i.author, status: i.status, createdAt: i.createdAt, votes: i.voters.length, mine: i.voters.includes(voter), reply: i.reply ?? null }));
}

export function addIdea({ type, title, body, author }) {
    const db = loadIdeas();
    const idea = {
        id: randomBytes(6).toString("base64url"),
        type: IDEA_TYPES.includes(type) ? type : "idea",
        title: String(title).trim().slice(0, 120),
        body: String(body ?? "").trim().slice(0, 2000),
        author: String(author ?? "").trim().slice(0, 32) || "Anonymous",
        status: "open",
        createdAt: new Date().toISOString(),
        voters: [],
    };
    db.ideas.push(idea);
    writeJson(IDEAS_FILE, db);
    return idea;
}

/** Głos (drugi raz = cofnięcie). Zwraca nową liczbę głosów albo null (brak pomysłu). */
export function toggleVote(id, voter) {
    const db = loadIdeas();
    const idea = db.ideas.find((i) => i.id === id && i.status !== "hidden");
    if (!idea) return null;
    const at = idea.voters.indexOf(voter);
    if (at >= 0) idea.voters.splice(at, 1);
    else idea.voters.push(voter);
    writeJson(IDEAS_FILE, db);
    return { votes: idea.voters.length, mine: at < 0 };
}

/** Zespół: status (planned, done, hidden...) i odpowiedź widoczna pod pomysłem. */
export function moderateIdea(id, { status, reply }) {
    const db = loadIdeas();
    const idea = db.ideas.find((i) => i.id === id);
    if (!idea) return null;
    if (status && IDEA_STATUSES.includes(status)) idea.status = status;
    if (reply !== undefined) idea.reply = String(reply).trim().slice(0, 1000) || null;
    writeJson(IDEAS_FILE, db);
    return idea;
}

export function allIdeasForStaff() {
    return loadIdeas().ideas.map((i) => ({ ...i, votes: i.voters.length, voters: undefined }));
}

/** Anonimowy identyfikator głosującego: skrót IP + przeglądarki z sekretem serwera (nie do odwrócenia). */
export function voterId(req, secret) {
    return sha(`${secret}|${req.ip}|${req.get("user-agent") ?? ""}`).slice(0, 32);
}

// ---- ankieta (okno na stronie - odpowiedzi widzi tylko zespół) ----

const SURVEY_FILE = join(DATA_DIR, "survey.json");
const clip = (v, n) => (typeof v === "string" ? v.trim().slice(0, n) : "");

/** Pytania: typ i dozwolone wartości. Wszystko spoza listy jest odrzucane. */
export const SURVEY_SCHEMA = {
    runs: ["yes", "stopped", "planning", "no"],
    players: ["0-10", "10-50", "50-200", "200-1000", "1000+"],
    experience: ["<6m", "6-24m", "2-5y", "5y+"],
    configTime: ["<5h", "5-20h", "20-50h", "50h+"],
    pains: { list: ["compat", "yaml", "textures", "mobs", "builds", "deploy"] },
    gaveUp: ["yes", "no"],
    gaveUpWhat: { text: 1000 },
    fit: ["1", "2", "3", "4", "5"],
    useful: { list: ["templates", "ai_textures", "ai_mobs", "ai_builds", "one_click"] },
    missing: { text: 2000 },
    spend: ["0", "<20", "20-100", "100-300", "300+"],
    priceTooCheap: { num: 100000 },
    priceBargain: { num: 100000 },
    priceExpensive: { num: 100000 },
    priceTooExpensive: { num: 100000 },
    billing: ["one-time", "subscription", "either"],
    source: ["discord", "youtube", "tiktok", "friend", "search", "server-list", "other"],
    sourceOther: { text: 200 },
    demo: ["yes", "no"],
    contact: { text: 254 },
};

/** Zapis jednej odpowiedzi - tylko znane pytania, wartości z listy, teksty i liczby przycięte. */
export function addSurvey(answers) {
    const out = { id: randomBytes(6).toString("base64url"), at: new Date().toISOString() };
    let filled = 0;
    for (const [key, rule] of Object.entries(SURVEY_SCHEMA)) {
        const v = answers?.[key];
        let val = null;
        if (Array.isArray(rule)) val = rule.includes(v) ? v : null;
        else if (rule.list) val = Array.isArray(v) ? [...new Set(v.filter((x) => rule.list.includes(x)))] : [];
        else if (rule.text) val = clip(v, rule.text) || null;
        else if (rule.num) {
            const n = Number(v);
            val = v !== "" && v != null && Number.isFinite(n) && n >= 0 ? Math.min(rule.num, Math.round(n * 100) / 100) : null;
        }
        if (val !== null && !(Array.isArray(val) && val.length === 0)) filled++;
        out[key] = val;
    }
    if (filled === 0) return null;
    const db = readJson(SURVEY_FILE, { responses: [] });
    // Odpowiedzi starsze niż 24 miesiące są usuwane (polityka prywatności).
    db.responses = db.responses.filter((r) => Date.now() - Date.parse(r.at) < 730 * DAY);
    db.responses.push(out);
    writeJson(SURVEY_FILE, db);
    return out;
}

export function surveyResponses() {
    return readJson(SURVEY_FILE, { responses: [] }).responses;
}
