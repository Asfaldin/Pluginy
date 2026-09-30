import { randomUUID } from "node:crypto";
import { readJson, writeJson } from "./jsonStore.js";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Zgłoszenia wsparcia - ten sam wzorzec pliku JSON co licenses.json/customers.json
// (patrz db.js). Klient tworzy zgłoszenie i widzi swoje własne (requireCustomer w
// server.js); odpowiadanie na razie przez curl z x-admin-key, tak samo jak wystawianie
// licencji - jeden operator, bez potrzeby osobnego panelu admina na razie.

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, "..", "data");
const DB_FILE = join(DATA_DIR, "tickets.json");

export const CATEGORIES = ["Pomoc techniczna", "Zakup / licencja", "Błąd w aplikacji", "Inne"];
export const PRIORITIES = ["Niski", "Średni", "Wysoki"];

// Zapis atomowy z kopią .bak - patrz src/jsonStore.js.
function loadAll() {
    return readJson(DB_FILE, []);
}

function saveAll(list) {
    writeJson(DB_FILE, list);
}

function sortByUpdatedDesc(list) {
    return [...list].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));
}

export function createTicket({ customerId, subject, category, priority, serverProfile, message }) {
    const list = loadAll();
    const now = new Date().toISOString();
    const ticket = {
        id: randomUUID(),
        customerId,
        subject,
        category,
        priority,
        serverProfile: serverProfile || null,
        status: "open",
        messages: [{ from: "customer", body: message, at: now }],
        createdAt: now,
        updatedAt: now,
    };
    list.push(ticket);
    saveAll(list);
    return ticket;
}

export function listByCustomer(customerId) {
    return sortByUpdatedDesc(loadAll().filter((t) => t.customerId === customerId));
}

export function listAll() {
    return sortByUpdatedDesc(loadAll());
}

export function findById(id) {
    return loadAll().find((t) => t.id === id) ?? null;
}

/** Odpowiedź admina (curl, patrz README) - dopisuje wiadomość i ustawia status na "answered". */
export function addAdminReply(id, body) {
    const list = loadAll();
    const ticket = list.find((t) => t.id === id);
    if (!ticket) return null;
    const now = new Date().toISOString();
    ticket.messages.push({ from: "admin", body, at: now });
    ticket.status = "answered";
    ticket.updatedAt = now;
    saveAll(list);
    return ticket;
}

export function setStatus(id, status) {
    const list = loadAll();
    const ticket = list.find((t) => t.id === id);
    if (!ticket) return null;
    ticket.status = status;
    ticket.updatedAt = new Date().toISOString();
    saveAll(list);
    return ticket;
}
