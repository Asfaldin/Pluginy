// Powiadomienia o ruchu na stronie na prywatny kanał Discorda zespołu (SITE_DISCORD_WEBHOOK w .env).
// Do Discorda NIE trafiają żadne dane osobowe: bez e-maili, bez kontaktów i tekstów z ankiety - tylko
// liczby, domeny, z których ktoś przyszedł, i odpowiedzi wyboru z ankiety. Szczegóły są w panelu admina.
// Odwiedziny zbieramy w paczki (co 10 minut), żeby kanał nie zamienił się w spam.

const PANEL = `${process.env.PUBLIC_API_URL || "https://api.rsmc-network.pl"}/admin/community.html`;
const BATCH_MS = 10 * 60 * 1000;

async function post(content) {
    const url = process.env.SITE_DISCORD_WEBHOOK;
    if (!url) return;
    try {
        const r = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ content: content.slice(0, 1900), allowed_mentions: { parse: [] }, flags: 4 }), // 4 = bez podglądu linków
        });
        if (!r.ok) console.error(`Site Discord webhook returned HTTP ${r.status}`);
    } catch (e) {
        console.error(`Site Discord webhook failed: ${e.message}`);
    }
}

// ---- odwiedziny w paczkach ----
let pending = { visitors: 0, views: 0, refs: {}, events: {} };
const EVENT_LABELS = { survey_open: "opened the survey", discord: "clicked Discord" };

export function noteVisit({ newVisitor, ref, event }) {
    if (event) {
        if (EVENT_LABELS[event]) pending.events[event] = (pending.events[event] ?? 0) + 1;
        return;
    }
    pending.views++;
    if (newVisitor) {
        pending.visitors++;
        const key = ref || "direct";
        pending.refs[key] = (pending.refs[key] ?? 0) + 1;
    }
}

function flushVisits() {
    const p = pending;
    if (!p.visitors && !Object.keys(p.events).length) return;
    pending = { visitors: 0, views: 0, refs: {}, events: {} };
    const from = Object.entries(p.refs).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} (${n})`).join(", ");
    const lines = [];
    if (p.visitors) lines.push(`👀 **${p.visitors} new visitor${p.visitors === 1 ? "" : "s"}** in the last 10 min${from ? ` · from ${from}` : ""}`);
    const ev = Object.entries(p.events).map(([k, n]) => `${n} ${EVENT_LABELS[k]}`).join(", ");
    if (ev) lines.push(`↳ ${ev}`);
    post(lines.join("\n"));
}
setInterval(flushVisits, BATCH_MS).unref();

// ---- zdarzenia od razu ----

export function notifySignup({ confirmed, total, confirmedTotal, source }) {
    post(
        confirmed
            ? `✅ **Sign-up confirmed** · ${confirmedTotal} confirmed so far`
            : `📩 **New sign-up** (waiting for email confirmation)${source && source !== "site" ? ` · source: ${source}` : ""} · ${total} on the list\n<${PANEL}>`,
    );
}

const RUNS = { yes: "runs a server", stopped: "had a server, stopped", planning: "planning a server", no: "no server" };

export function notifySurvey(a, total) {
    const bits = [
        a.runs && RUNS[a.runs],
        a.players && `${a.players} players/day`,
        a.fit && `fit ${a.fit}/5`,
        a.billing && `prefers ${a.billing}`,
        a.pains?.length && `pains: ${a.pains.join(", ")}`,
        a.useful?.length && `wants: ${a.useful.join(", ")}`,
    ].filter(Boolean);
    post(`📝 **New survey answer** (#${total})${bits.length ? `\n${bits.join(" · ")}` : ""}${a.missing || a.gaveUpWhat ? "\n💬 left a written comment" : ""}\n<${PANEL}>`);
}
