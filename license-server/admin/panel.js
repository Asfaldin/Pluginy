// Panel wsparcia RSMC. Bez bibliotek i bez innerHTML - wszystko, co pochodzi od klientów
// (temat, treść, e-mail), trafia do strony wyłącznie jako tekst (textContent).
// Token sesji w sessionStorage: znika po zamknięciu karty.
"use strict";

const TOKEN_KEY = "rsmc-support-token";
const REFRESH_MS = 30000;

const $ = (id) => document.getElementById(id);
const state = { tickets: [], filter: "open", query: "", selectedId: null, me: null };

const CATEGORY = { "Pomoc techniczna": "Technical help", "Zakup / licencja": "Purchase / license", "Błąd w aplikacji": "App bug", Inne: "Other" };
const PRIORITY = { Niski: "Low", Średni: "Medium", Wysoki: "High" };
const PRIORITY_CLASS = { Niski: "p-low", Średni: "p-medium", Wysoki: "p-high" };
const STATUS = { open: "Open", answered: "Answered", closed: "Closed" };

/** Element DOM z tekstem (nigdy HTML). */
function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text != null) e.textContent = text;
  return e;
}

function token() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(t) {
  try {
    if (t) sessionStorage.setItem(TOKEN_KEY, t);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* prywatne okno - zostajemy zalogowani do odświeżenia strony */
  }
}

async function api(path, options = {}) {
  const resp = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(token() ? { Authorization: `Bearer ${token()}` } : {}), ...(options.headers || {}) },
  });
  if (resp.status === 401) {
    showLogin("Your session has expired - sign in again.");
    throw new Error("unauthorized");
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(data.error || `HTTP ${resp.status}`);
  return data;
}

function relTime(iso) {
  const min = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  return d < 30 ? `${d} d ago` : new Date(iso).toLocaleDateString();
}

const fullTime = (iso) => new Date(iso).toLocaleString();

// --- Logowanie ---

function showLogin(message) {
  setToken(null);
  state.me = null;
  $("app").hidden = true;
  $("setup2fa").hidden = true;
  $("login").hidden = false;
  $("login-code-row").hidden = true;
  $("login-code").value = "";
  const err = $("login-error");
  err.hidden = !message;
  err.textContent = message || "";
}

async function start() {
  if (!token()) return showLogin();
  try {
    state.me = await api("/api/staff/me");
  } catch (e) {
    if (e.message !== "unauthorized") showLogin(e.message);
    return;
  }
  // Sesja bez kodu 2FA: bez włączonej weryfikacji -> ekran włączania, z włączoną -> zaloguj się jeszcze raz z kodem.
  if (!state.me.mfa) {
    if (state.me.totp) return showLogin("Sign in again with the code from your authenticator app.");
    return showSetup2fa();
  }
  $("setup2fa").hidden = true;
  $("login").hidden = true;
  $("app").hidden = false;
  $("me").textContent = `${state.me.email} · ${state.me.role}`;
  await refresh();
}

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("login-error");
  err.hidden = true;
  try {
    const resp = await fetch("/api/staff/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: $("login-email").value, password: $("login-password").value, code: $("login-code").value || undefined }),
    });
    const data = await resp.json().catch(() => ({}));
    if (data.totpRequired) {
      // hasło dobre - teraz kod z aplikacji
      $("login-code-row").hidden = false;
      $("login-code").value = "";
      $("login-code").focus();
    }
    if (!resp.ok) throw new Error(data.error || `HTTP ${resp.status}`);
    setToken(data.token);
    $("login-password").value = "";
    $("login-code").value = "";
    await start();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
});

async function showSetup2fa() {
  $("login").hidden = true;
  $("app").hidden = true;
  $("setup2fa").hidden = false;
  $("setup-error").hidden = true;
  try {
    const s = await api("/api/staff/2fa/setup", { method: "POST" });
    $("setup-qr").src = s.qr;
    $("setup-secret").textContent = s.secret.match(/.{1,4}/g).join(" ");
    $("setup-code").focus();
  } catch (e) {
    if (e.message !== "unauthorized") showLogin(e.message);
  }
}

$("setup-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("setup-error");
  err.hidden = true;
  try {
    await api("/api/staff/2fa/enable", { method: "POST", body: JSON.stringify({ code: $("setup-code").value }) });
    $("setup-code").value = "";
    $("setup-qr").removeAttribute("src");
    $("setup-secret").textContent = "";
    await start();
  } catch (ex) {
    if (ex.message === "unauthorized") return;
    err.textContent = ex.message;
    err.hidden = false;
  }
});

$("logout").addEventListener("click", async () => {
  try {
    await api("/api/auth/logout", { method: "POST" });
  } catch {
    /* i tak wylogowujemy lokalnie */
  }
  showLogin();
});

// --- Lista ---

async function refresh() {
  try {
    state.tickets = await api("/api/staff/tickets");
    $("refreshed").textContent = `Updated ${new Date().toLocaleTimeString()}`;
    renderList();
    renderDetail();
  } catch (e) {
    if (e.message !== "unauthorized") $("refreshed").textContent = `Refresh failed: ${e.message}`;
  }
}

function matches(t) {
  if (state.filter !== "all" && t.status !== state.filter) return false;
  const q = state.query.trim().toLowerCase();
  if (!q) return true;
  return (
    t.subject.toLowerCase().includes(q) ||
    (t.customer?.email || "").toLowerCase().includes(q) ||
    t.messages.some((m) => m.body.toLowerCase().includes(q))
  );
}

function renderList() {
  for (const s of ["open", "answered", "closed"]) {
    const n = state.tickets.filter((t) => t.status === s).length;
    $(`count-${s}`).textContent = n ? String(n) : "";
  }
  const box = $("tickets");
  box.replaceChildren();
  const visible = state.tickets.filter(matches);
  if (visible.length === 0) {
    box.append(el("p", "muted small pad", "No tickets here."));
    return;
  }
  for (const t of visible) {
    const item = el("button", `ticket-item${t.id === state.selectedId ? " selected" : ""}${t.status === "open" ? " unread" : ""}`);
    item.type = "button";
    const top = el("div", "ti-top");
    top.append(el("span", "ti-subject", t.subject), el("span", "ti-time", relTime(t.updatedAt)));
    const bottom = el("div", "ti-bottom");
    bottom.append(
      el("span", "ti-email", t.customer?.email || "deleted account"),
      el("span", `badge ${PRIORITY_CLASS[t.priority] || ""}`, PRIORITY[t.priority] || t.priority)
    );
    const last = t.messages[t.messages.length - 1];
    item.append(top, bottom, el("div", "ti-preview", `${last.from === "admin" ? "You: " : ""}${last.body}`));
    item.addEventListener("click", () => {
      state.selectedId = t.id;
      renderList();
      renderDetail();
    });
    box.append(item);
  }
}

// --- Szczegóły ---

function renderDetail() {
  const t = state.tickets.find((x) => x.id === state.selectedId);
  $("empty").hidden = !!t;
  $("ticket").hidden = !t;
  if (!t) return;

  $("t-subject").textContent = t.subject;
  const meta = $("t-meta");
  meta.replaceChildren(
    el("span", "badge", CATEGORY[t.category] || t.category),
    el("span", `badge ${PRIORITY_CLASS[t.priority] || ""}`, `${PRIORITY[t.priority] || t.priority} priority`),
    ...(t.serverProfile ? [el("span", "badge", `Server: ${t.serverProfile}`)] : []),
    el("span", "muted small", `Opened ${fullTime(t.createdAt)}`)
  );
  const status = $("t-status");
  status.textContent = STATUS[t.status];
  status.className = `badge s-${t.status}`;
  $("t-toggle").textContent = t.status === "closed" ? "Reopen" : "Close ticket";

  const thread = $("t-thread");
  const atBottom = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 40;
  thread.replaceChildren();
  for (const m of t.messages) {
    const msg = el("div", `msg ${m.from === "admin" ? "msg-staff" : "msg-customer"}`);
    const head = el("div", "msg-head");
    head.append(el("strong", null, m.from === "admin" ? `Support${m.by ? ` (${m.by})` : ""}` : t.customer?.email || "Customer"), el("span", "muted small", fullTime(m.at)));
    msg.append(head, el("div", "msg-body", m.body));
    thread.append(msg);
  }
  if (atBottom || thread.dataset.ticket !== t.id) thread.scrollTop = thread.scrollHeight;
  thread.dataset.ticket = t.id;

  $("c-email").textContent = t.customer?.email || "Account deleted";
  $("c-since").textContent = t.customer ? `Customer since ${new Date(t.customer.createdAt).toLocaleDateString()}` : "";
  const lic = $("c-licenses");
  lic.replaceChildren();
  const list = t.customer?.licenses || [];
  if (list.length === 0) lic.append(el("p", "muted small", "No licenses."));
  for (const l of list) {
    const row = el("div", "lic");
    row.append(
      el("span", "lic-name", l.plugin === "*" ? "Ultimate (all)" : l.plugin.split(",").join(", ")),
      el("span", `badge ${l.status === "active" ? "s-answered" : "s-closed"}`, l.status),
      el("span", "muted small", l.boundAt ? "activated on a server" : "not activated yet")
    );
    lic.append(row);
  }
}

$("t-toggle").addEventListener("click", async () => {
  const t = state.tickets.find((x) => x.id === state.selectedId);
  if (!t) return;
  try {
    await api(`/api/staff/tickets/${encodeURIComponent(t.id)}/status`, {
      method: "POST",
      body: JSON.stringify({ status: t.status === "closed" ? "open" : "closed" }),
    });
    await refresh();
  } catch (e) {
    showReplyError(e.message);
  }
});

function showReplyError(message) {
  const err = $("reply-error");
  err.textContent = message || "";
  err.hidden = !message;
}

async function sendReply(close) {
  const t = state.tickets.find((x) => x.id === state.selectedId);
  const text = $("reply-text").value.trim();
  if (!t || !text) return;
  showReplyError(null);
  try {
    await api(`/api/staff/tickets/${encodeURIComponent(t.id)}/reply`, { method: "POST", body: JSON.stringify({ message: text, close }) });
    $("reply-text").value = "";
    await refresh();
  } catch (e) {
    showReplyError(e.message);
  }
}

$("reply-form").addEventListener("submit", (e) => {
  e.preventDefault();
  void sendReply(false);
});
$("reply-close").addEventListener("click", () => void sendReply(true));
$("reply-text").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    void sendReply(false);
  }
});

// --- Filtry, wyszukiwanie, odświeżanie ---

for (const b of document.querySelectorAll(".tabs button")) {
  b.addEventListener("click", () => {
    state.filter = b.dataset.filter;
    for (const o of document.querySelectorAll(".tabs button")) o.classList.toggle("on", o === b);
    renderList();
  });
}
$("search").addEventListener("input", (e) => {
  state.query = e.target.value;
  renderList();
});

setInterval(() => {
  if (!$("app").hidden && document.visibilityState === "visible") void refresh();
}, REFRESH_MS);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && !$("app").hidden) void refresh();
});

void start();
