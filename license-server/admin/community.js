// Panel "Community": zapisy ze strony (lista, eksport CSV, usuwanie na prośbę) i odpowiedzi ankiety
// z podsumowaniem. Ten sam token co panel wsparcia (sessionStorage) - bez niego wracamy do logowania.
const TOKEN_KEY = "rsmc-support-token";
const $ = (id) => document.getElementById(id);
let data = { signups: [], survey: [], visitors: [] };

function token() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

async function api(path, options = {}) {
  const resp = await fetch(path, { ...options, headers: { "Content-Type": "application/json", Authorization: `Bearer ${token()}`, ...(options.headers || {}) } });
  if (resp.status === 401) {
    location.href = "index.html";
    throw new Error("unauthorized");
  }
  const body = await resp.json().catch(() => ({}));
  if (resp.status === 403 && body.mfaRequired) {
    location.href = "index.html"; // sesja bez kodu 2FA - logowanie w panelu wsparcia
    throw new Error("unauthorized");
  }
  if (!resp.ok) throw new Error(body.error || `HTTP ${resp.status}`);
  return body;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

const fmt = (iso) => (iso ? new Date(iso).toLocaleString("pl-PL", { dateStyle: "short", timeStyle: "short" }) : "-");

function csv(rows, cols) {
  const q = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  return [cols.map(q).join(","), ...rows.map((r) => cols.map((c) => q(Array.isArray(r[c]) ? r[c].join("; ") : r[c])).join(","))].join("\r\n");
}

function download(name, text) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob(["﻿" + text], { type: "text/csv;charset=utf-8" }));
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}

// ---- zapisy ----

function renderSignups() {
  const all = data.signups;
  const week = Date.now() - 7 * 864e5;
  $("n-signups").textContent = all.length;
  $("s-total").textContent = all.length;
  $("s-confirmed").textContent = all.filter((s) => s.confirmed).length;
  $("s-week").textContent = all.filter((s) => Date.parse(s.createdAt) > week).length;
  const q = $("s-search").value.trim().toLowerCase();
  const only = $("s-only").checked;
  const rows = all
    .filter((s) => (!q || s.email.includes(q)) && (!only || s.confirmed))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const tb = $("s-rows");
  tb.replaceChildren();
  if (!rows.length) {
    const tr = el("tr");
    const td = el("td", "muted", all.length ? "Nothing matches." : "No sign-ups yet.");
    td.colSpan = 6;
    tr.append(td);
    tb.append(tr);
    return;
  }
  for (const s of rows) {
    const tr = el("tr");
    const status = el("td");
    status.append(el("span", s.confirmed ? "badge ok" : "badge wait", s.confirmed ? "Confirmed" : "Waiting"));
    const del = el("button", "ghost small", "Remove");
    del.title = "Remove from the list (e.g. when someone asks to be deleted)";
    del.onclick = async () => {
      if (!confirm(`Remove ${s.email} from the sign-up list? This can't be undone.`)) return;
      try {
        await api("/api/admin/community/signups/remove", { method: "POST", body: JSON.stringify({ email: s.email }) });
        await load();
      } catch (e) {
        showError(e.message);
      }
    };
    const act = el("td");
    act.append(del);
    tr.append(el("td", "mono", s.email), status, el("td", null, fmt(s.createdAt)), el("td", null, fmt(s.confirmedAt)), el("td", "muted", s.source || "-"), act);
    tb.append(tr);
  }
}

// ---- odwiedziny (anonimowe liczniki dzienne z src/analytics.js) ----

let range = 7;
const EVENT_LABELS = { signup: "Signed up", survey_open: "Opened the survey", survey_done: "Finished the survey", discord: "Clicked Discord" };

function sumMaps(days, key) {
  const out = {};
  for (const d of days) for (const [k, v] of Object.entries(d[key] || {})) out[k] = (out[k] || 0) + v;
  return out;
}

function tableCard(title, map, labels = {}) {
  const card = el("div", "cm-card");
  card.append(el("h4", null, title));
  const rows = Object.entries(map).sort((a, b) => b[1] - a[1]);
  const total = rows.reduce((a, [, v]) => a + v, 0);
  if (!rows.length) card.append(el("p", "muted small", "Nothing yet."));
  for (const [k, n] of rows.slice(0, 12)) {
    const row = el("div", "cm-barrow");
    const bar = el("span", "cm-fill");
    bar.style.width = (total ? Math.round((n / total) * 100) : 0) + "%";
    const track = el("span", "cm-track");
    track.append(bar);
    row.append(el("span", "cm-label", labels[k] ?? k), track, el("span", "cm-num", String(n)));
    card.append(row);
  }
  return card;
}

function renderVisitors() {
  const all = data.visitors || [];
  const days = all.slice(-range);
  const sum = (k) => days.reduce((a, d) => a + (d[k] || 0), 0);
  const visitors = sum("visitors");
  const events = sumMaps(days, "events");
  $("v-visitors").textContent = visitors;
  $("v-views").textContent = sum("views");
  $("v-signups").textContent = events.signup || 0;
  $("v-conv").textContent = visitors ? `${(((events.signup || 0) / visitors) * 100).toFixed(1)}%` : "-";

  // wykres: odwiedzający dziennie (przy "Today" pokazujemy ostatnie 7 dni dla kontekstu)
  const chartDays = all.slice(-Math.max(range, 7));
  const max = Math.max(1, ...chartDays.map((d) => d.visitors));
  const chart = $("v-chart");
  chart.replaceChildren();
  for (const d of chartDays) {
    const col = el("div", "cm-col");
    col.title = `${d.day}: ${d.visitors} visitors, ${d.views} views`;
    const bar = el("span", "cm-colbar");
    bar.style.height = Math.round((d.visitors / max) * 100) + "%";
    col.append(el("span", "cm-colnum", d.visitors ? String(d.visitors) : ""), bar, el("span", "cm-colday", chartDays.length > 31 ? "" : d.day.slice(8)));
    chart.append(col);
  }

  const t = $("v-tables");
  t.replaceChildren(
    tableCard("Where they came from", sumMaps(days, "refs"), { "(direct)": "Direct / typed in" }),
    tableCard("What they did", events, EVENT_LABELS),
    tableCard("Campaign links (utm_source)", sumMaps(days, "sources")),
    tableCard("Devices", sumMaps(days, "devices"), { mobile: "Phone", tablet: "Tablet", desktop: "Computer" }),
    tableCard("Pages", sumMaps(days, "pages"), { "/": "Home", "/privacy.html": "Privacy policy" }),
  );
}

document.querySelectorAll("[data-range]").forEach((b) =>
  b.addEventListener("click", () => {
    range = Number(b.dataset.range);
    document.querySelectorAll("[data-range]").forEach((x) => x.classList.toggle("on", x === b));
    renderVisitors();
  })
);

// ---- ankieta ----

const LABELS = {
  runs: ["Runs a server", { yes: "Yes", stopped: "Had one, stopped", planning: "Planning", no: "No" }],
  players: ["Players per day", {}],
  experience: ["Experience", { "<6m": "< 6 months", "6-24m": "6-24 months", "2-5y": "2-5 years", "5y+": "5+ years" }],
  configTime: ["Config time (last server)", {}],
  pains: ["Biggest troubles", { compat: "Plugins working together", yaml: "YAML", textures: "Textures", mobs: "Mobs / custom content", builds: "Builds / spawn", deploy: "Deploying" }],
  gaveUp: ["Gave up an idea", { yes: "Yes", no: "No" }],
  fit: ["Solves their problem (1-5)", {}],
  useful: ["Most useful", { templates: "Templates", ai_textures: "AI textures", ai_mobs: "AI mobs", ai_builds: "AI builds", one_click: "One-click upload" }],
  spend: ["Spends per month", { 0: "Nothing", "<20": "< $5", "20-100": "$5-25", "100-300": "$25-75", "300+": "$75+" }],
  billing: ["Billing preference", { "one-time": "Pay once", subscription: "Subscription", either: "No preference" }],
  source: ["Heard about us", {}],
  demo: ["Wants the demo", { yes: "Yes", no: "No" }],
};
const TEXTS = [["gaveUpWhat", "Gave up on"], ["missing", "Missing to buy"], ["sourceOther", "Source (other)"], ["contact", "Contact"]];
const PRICES = [["priceTooCheap", "Too cheap"], ["priceBargain", "Bargain"], ["priceExpensive", "Expensive"], ["priceTooExpensive", "Too expensive"]];

function renderSurvey() {
  const all = data.survey;
  $("n-survey").textContent = all.length;
  $("v-count").textContent = `${all.length} responses`;
  const sum = $("v-summary");
  sum.replaceChildren();
  if (!all.length) {
    sum.append(el("p", "muted", "No survey answers yet."));
    $("v-list").replaceChildren();
    return;
  }
  // rozkład odpowiedzi na każde pytanie z listą
  for (const [key, [title, names]] of Object.entries(LABELS)) {
    const counts = {};
    let answered = 0;
    for (const r of all) {
      const v = r[key];
      const vals = Array.isArray(v) ? v : v != null ? [v] : [];
      if (vals.length) answered++;
      for (const x of vals) counts[x] = (counts[x] || 0) + 1;
    }
    const card = el("div", "cm-card");
    card.append(el("h4", null, `${title}`), el("span", "muted small", `${answered} answered`));
    for (const [v, n] of Object.entries(counts).sort((a, b) => b[1] - a[1])) {
      const row = el("div", "cm-barrow");
      const pct = answered ? Math.round((n / answered) * 100) : 0;
      const bar = el("span", "cm-fill");
      bar.style.width = pct + "%";
      row.append(el("span", "cm-label", names[v] ?? v), el("span", "cm-track"), el("span", "cm-num", `${n} · ${pct}%`));
      row.children[1].append(bar);
      card.append(row);
    }
    sum.append(card);
  }
  // progi ceny (Van Westendorp): mediany
  const pc = el("div", "cm-card");
  pc.append(el("h4", null, "Price thresholds ($/month, median)"));
  for (const [k, l] of PRICES) {
    const v = all.map((r) => r[k]).filter((x) => typeof x === "number").sort((a, b) => a - b);
    const med = v.length ? (v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2) : null;
    const row = el("div", "cm-barrow");
    row.append(el("span", "cm-label", l), el("span", "cm-num", med == null ? "-" : `$${med} (${v.length})`));
    pc.append(row);
  }
  sum.append(pc);

  const list = $("v-list");
  list.replaceChildren();
  for (const r of [...all].sort((a, b) => b.at.localeCompare(a.at))) {
    const card = el("details", "cm-answer");
    const s = el("summary");
    s.append(el("b", null, fmt(r.at)), el("span", "muted", ` · ${LABELS.runs[1][r.runs] ?? "-"} · fit ${r.fit ?? "-"}/5${r.contact ? " · " + r.contact : ""}`));
    card.append(s);
    const dl = el("dl");
    for (const [key, [title, names]] of Object.entries(LABELS)) {
      const v = r[key];
      if (v == null || (Array.isArray(v) && !v.length)) continue;
      dl.append(el("dt", null, title), el("dd", null, Array.isArray(v) ? v.map((x) => names[x] ?? x).join(", ") : names[v] ?? v));
    }
    for (const [k, l] of [...PRICES, ...TEXTS]) if (r[k] != null && r[k] !== "") dl.append(el("dt", null, l), el("dd", null, String(r[k])));
    card.append(dl);
    list.append(card);
  }
}

// ---- wspólne ----

function showError(msg) {
  $("error").textContent = msg;
  $("error").hidden = !msg;
}

async function load() {
  try {
    data = await api("/api/admin/community");
    showError("");
  } catch (e) {
    if (e.message !== "unauthorized") showError(e.message);
  }
  renderVisitors();
  renderSignups();
  renderSurvey();
}

document.querySelectorAll(".cm-tabs button").forEach((b) =>
  b.addEventListener("click", () => {
    document.querySelectorAll(".cm-tabs button").forEach((x) => x.classList.toggle("on", x === b));
    for (const id of ["visitors", "signups", "survey"]) $(id).hidden = b.dataset.tab !== id;
  })
);
$("s-search").addEventListener("input", renderSignups);
$("s-only").addEventListener("change", renderSignups);
$("refresh").addEventListener("click", load);
$("s-csv").addEventListener("click", () => download(`rsmc-signups-${new Date().toISOString().slice(0, 10)}.csv`, csv(data.signups, ["email", "confirmed", "createdAt", "confirmedAt", "source"])));
$("v-csv").addEventListener("click", () =>
  download(`rsmc-survey-${new Date().toISOString().slice(0, 10)}.csv`, csv(data.survey, ["at", ...Object.keys(LABELS), ...PRICES.map((p) => p[0]), ...TEXTS.map((t) => t[0])]))
);

if (!token()) location.href = "index.html";
else load();
