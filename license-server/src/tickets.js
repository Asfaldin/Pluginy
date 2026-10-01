import { randomUUID } from "node:crypto";
import { readJson, writeJson } from "./jsonStore.js";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Zgłoszenia wsparcia - ten sam wzorzec pliku JSON co licenses.json/customers.json
// (patrz db.js). Klient tworzy zgłoszenie, widzi swoje i odpisuje w aplikacji; zespół
// wsparcia odpowiada w panelu /admin (admin/, role w customers.js). Rozmowa to lista
// wiadomości "customer"/"admin"; przy wiadomości zespołu zapisujemy też, kto odpisał (by).

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

/** Odpowiedź zespołu - dopisuje wiadomość i ustawia status na "answered". `by` = e-mail
    osoby z zespołu (widoczny tylko w panelu, klient widzi "Support"). */
export function addAdminReply(id, body, by = null) {
    const list = loadAll();
    const ticket = list.find((t) => t.id === id);
    if (!ticket) return null;
    const now = new Date().toISOString();
    ticket.messages.push({ from: "admin", body, at: now, ...(by ? { by } : {}) });
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

/** Klient odpisuje we własnym zgłoszeniu - wraca ono do kolejki jako "open" (też zamknięte). */
export function addCustomerReply(id, customerId, body) {
    const list = loadAll();
    const ticket = list.find((t) => t.id === id && t.customerId === customerId);
    if (!ticket) return null;
    const now = new Date().toISOString();
    ticket.messages.push({ from: "customer", body, at: now });
    ticket.status = "open";
    ticket.updatedAt = now;
    saveAll(list);
    return ticket;
}

/** Widok dla klienta - bez informacji, kto z zespołu odpisał. */
export function forCustomer(ticket) {
    return { ...ticket, messages: ticket.messages.map(({ by, ...m }) => (void by, m)) };
}
