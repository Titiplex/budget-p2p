// ===== Etat =====
let EXPENSES = [];
let BUDGETS = [];
let FX = []; // { code, perBase } per EUR
let RULES = [];
const FX_BASE = "EUR";

// devise d'affichage (persistée localement)
let DISPLAY_CCY = localStorage.getItem("display_ccy") || "EUR";

// Espace pour les instances de graphiques
window.__charts = window.__charts || {};

function destroyChart(id) {
    const c = window.__charts[id];
    if (c && typeof c.destroy === 'function') {
        try {
            c.destroy();
        } catch (_) {
        }
    }
    window.__charts[id] = null;
}

// ===== Bridge (côté Java) → callbacks =====
window.onExpenses = (json) => {
    EXPENSES = JSON.parse(json);
    renderExpensesTable();
    renderBudgetsTable();
    refreshAnalytics();
    populateDisplayCcyOptions();
};

window.onBudgets = (json) => {
    BUDGETS = JSON.parse(json);
    renderBudgetsTable();
    refreshAnalytics();
    populateDisplayCcyOptions();
};

window.onFx = (json) => {
    FX = JSON.parse(json);
    renderFxTable();
    renderBudgetsTable();
    refreshAnalytics();
    populateDisplayCcyOptions();
};

window.onRules = (json) => {
    RULES = JSON.parse(json);
    renderRulesTable();
};

// ===== Router simple (3 vues) =====
document.querySelectorAll('#topnav button').forEach(btn => {
    btn.addEventListener('click', () => setView(btn.dataset.nav));
});

function setView(name) {
    document.querySelectorAll('#topnav button').forEach(b => b.classList.toggle('active', b.dataset.nav === name));
    document.querySelectorAll('.view').forEach(v => v.classList.toggle('active', v.dataset.view === name));
}

setView('transactions'); // défaut

// ===== Sélecteur de devise d'affichage =====
const displaySel = document.getElementById('display-ccy');
displaySel.addEventListener('change', () => setDisplayCurrency(displaySel.value));

function populateDisplayCcyOptions() {
    const set = new Set([FX_BASE, DISPLAY_CCY]);
    EXPENSES.forEach(e => e.currency && set.add(e.currency.toUpperCase()));
    BUDGETS.forEach(b => b.currency && set.add(b.currency.toUpperCase()));
    FX.forEach(r => r.code && set.add(r.code.toUpperCase()));
    const arr = Array.from(set).sort();
    const sel = document.getElementById('display-ccy');
    const cur = sel.value || DISPLAY_CCY;
    sel.innerHTML = arr.map(c => `<option ${c === cur ? 'selected' : ''}>${c}</option>`).join('');
    setDisplayCurrency(sel.value || DISPLAY_CCY);
}

function setDisplayCurrency(ccy) {
    DISPLAY_CCY = (ccy || 'EUR').toUpperCase();
    localStorage.setItem('display_ccy', DISPLAY_CCY);
    document.getElementById('dash-ccy').textContent = DISPLAY_CCY;
    document.querySelectorAll('#view-dashboard .ccy').forEach(n => n.textContent = DISPLAY_CCY);
    // Re-rendus avec conversion avant calculs
    renderExpensesTable();
    renderBudgetsTable();
    refreshAnalytics();
}

// ===== Utilitaires FX =====
function rateOf(code) {
    if (!code) return 1.0;
    if (code.toUpperCase() === FX_BASE) return 1.0;
    const r = FX.find(x => x.code && x.code.toUpperCase() === code.toUpperCase());
    return r ? parseFloat(r.perBase) : NaN; // per EUR
}

/** Convertit 'amount' de 'from' vers 'to' en utilisant les taux per-EUR. */
function convertToCurrency(amount, from, to) {
    const a = parseFloat(amount);
    if (!isFinite(a)) return 0;
    const F = (from || '').toUpperCase(), T = (to || '').toUpperCase();
    if (F === T || !F || !T) return a;
    const rf = rateOf(F), rt = rateOf(T);
    if (!isFinite(rf) || !isFinite(rt) || rf <= 0 || rt <= 0) {
        // si on n'a pas de taux, on garde la valeur (affichage dégradé)
        return a;
    }
    // montant_to = montant_from * (rf / rt)
    return a * (rf / rt);
}

// ===== Règles (client) =====
document.getElementById('r-save').addEventListener('click', () => {
    const name = document.getElementById('r-name').value.trim();
    const kind = document.getElementById('r-kind').value.trim();
    const pattern = document.getElementById('r-pattern').value.trim();
    const category = document.getElementById('r-category').value.trim();
    if (!name || !pattern || !category) return alert('Champs requis.');

    const payload = {id: '', name, kind, pattern, category, active: true, deleted: false, ver: '', author: ''};

    // 🔗 branchement vers Java (création/MAJ → P2P)
    if (!window.bridge || !window.bridge.upsertRule) {
        return alert("Bridge.upsertRule non exposé côté Java — vois étapes 2 & 3.");
    }
    window.bridge.upsertRule(JSON.stringify(payload));

    // reset soft
    document.getElementById('r-pattern').value = '';
});


function renderRulesTable() {
    const tbody = document.querySelector('#tbl-rules tbody');
    if (!tbody) return;
    tbody.innerHTML = '';
    for (const r of RULES) {
        const tr = document.createElement('tr');
        tr.innerHTML = `
      <td>${escapeHtml(r.name)}</td>
      <td>${escapeHtml(r.kind)}</td>
      <td>${escapeHtml(r.pattern)}</td>
      <td>${escapeHtml(r.category)}</td>
      <td><button class="rowdel" data-id="${r.id}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => {
            if (!window.bridge || !window.bridge.deleteRule) return alert("Bridge.deleteRule non exposé côté Java.");
            window.bridge.deleteRule(b.dataset.id);
        });
    });
}


function applyRules(e) {
    const hay = ((e.note || '') + ' ' + (e.who || '')).toLowerCase();
    for (const r of RULES) {
        if (!r || r.deleted || r.active === false) continue;
        try {
            if (r.kind === 'SUBSTRING' && hay.includes(r.pattern.toLowerCase())) return r.category;
            if (r.kind === 'REGEX' && new RegExp(r.pattern).test(hay)) return r.category;
        } catch (_) {
        }
    }
    return null;
}

// ===== UI Dépenses =====
function renderExpensesTable() {
    const tbody = document.querySelector('#tbl tbody');
    tbody.innerHTML = '';
    for (const e of EXPENSES) {
        let cat = e.category || '';
        if (!cat) {
            const c = applyRules(e);
            if (c) cat = c + ' (suggéré)';
        }
        const converted = convertToCurrency(e.amount, e.currency, DISPLAY_CCY);
        const tr = document.createElement('tr');
        const date = new Date(e.ts).toLocaleString();
        tr.innerHTML = `
      <td>${date}</td>
      <td>${escapeHtml(e.who || '')}</td>
      <td>${escapeHtml(cat)}</td>
      <td>${converted.toFixed(2)} ${DISPLAY_CCY}</td>
      <td>${parseFloat(e.amount).toFixed(2)} ${escapeHtml(e.currency || '')}</td>
      <td>${escapeHtml(e.note || '')}</td>
      <td><button class="rowdel" data-id="${e.id}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => window.bridge.deleteExpense(b.dataset.id));
    });
}

document.getElementById('add').addEventListener('click', () => {
    const who = document.getElementById('who').value.trim();
    let category = document.getElementById('category').value.trim();
    const amount = document.getElementById('amount').value.trim();
    const currency = document.getElementById('currency').value.trim();
    const note = document.getElementById('note').value.trim();
    if (!who || !amount) return alert('Champs requis manquants.');
    if (!category) {
        const c = applyRules({who, note});
        if (c) category = c;
    }
    const e = {id: '', who, category, amount, currency, note, ts: Date.now(), deleted: false, ver: '', author: ''};
    window.bridge.addExpense(JSON.stringify(e));
    document.getElementById('note').value = '';
    document.getElementById('amount').value = '';
});

// ===== Budgets (calculs en devise d'affichage) =====
document.getElementById('b-save').addEventListener('click', () => {
    const category = document.getElementById('b-cat').value.trim();
    const monthlyLimit = document.getElementById('b-limit').value.trim();
    const currency = document.getElementById('b-cur').value.trim();
    if (!category || !monthlyLimit) return alert('Catégorie et plafond requis.');
    const rolloverMode = document.getElementById('b-roll').value.trim();
    const rolloverCap = document.getElementById('b-cap').value.trim() || '0';
    const b = {
        id: '',
        category,
        monthlyLimit,
        currency,
        rolloverMode,
        rolloverCap,
        deleted: false,
        ver: '',
        author: ''
    };
    window.bridge.upsertBudget(JSON.stringify(b));
    document.getElementById('b-limit').value = '';
});

function renderBudgetsTable() {
    const tbody = document.querySelector('#tbl-budgets tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    const spentByCatDisp = groupByCategoryIn(DISPLAY_CCY, EXPENSES.filter(isSameMonth));

    for (const b of BUDGETS) {
        // calc prev month spent/planned in DISPLAY_CCY
        const prev = prevMonthStats(DISPLAY_CCY);
        const carried = computeRollover(b, prev); // valeur en DISPLAY_CCY
        const plannedDisp = convertToCurrency(b.monthlyLimit, b.currency, DISPLAY_CCY) + carried;
        const spentDisp = spentByCatDisp.get(b.category) || 0;
        const leftDisp = plannedDisp - spentDisp;
        const cls = leftDisp < 0 ? 'warn' : 'ok';
        const tr = document.createElement('tr');
        tr.innerHTML = `
      <td>${escapeHtml(b.category)}</td>
      <td>${parseFloat(b.monthlyLimit).toFixed(2)} ${escapeHtml(b.currency || '')}</td>
      <td>${plannedDisp.toFixed(2)} ${DISPLAY_CCY}</td>
      <td>${spentDisp.toFixed(2)} ${DISPLAY_CCY}</td>
      <td class="${cls}">${leftDisp.toFixed(2)} ${DISPLAY_CCY}</td>
      <td><button class="rowdel" data-cat="${b.category}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => window.bridge.deleteBudget(b.dataset.cat));
    });
}

function prevMonthStats(targetCcy) {
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const key = k => k.getFullYear() + '-' + String(k.getMonth() + 1).padStart(2, '0');
    const prevKey = key(prev), curKey = key(new Date(now.getFullYear(), now.getMonth(), 1));
    const m = new Map(); // cat -> {planned, spent}
    // planned : budgets du mois précédent = on reprend mêmes limites/plans (simplif: on suppose identiques)
    for (const b of BUDGETS) {
        const planned = convertToCurrency(b.monthlyLimit, b.currency, targetCcy);
        m.set(b.category, {planned, spent: 0});
    }
    // spent
    for (const e of EXPENSES) {
        const d = new Date(e.ts);
        const k = key(new Date(d.getFullYear(), d.getMonth(), 1));
        if (k === prevKey) {
            const x = convertToCurrency(e.amount, e.currency, targetCcy);
            const slot = m.get(e.category) || {planned: 0, spent: 0};
            slot.spent += isNaN(x) ? 0 : x;
            m.set(e.category, slot);
        }
    }
    return m; // Map cat -> {planned, spent}
}

function computeRollover(b, prevMap) {
    const slot = prevMap.get(b.category);
    if (!slot) return 0;
    const diff = slot.planned - slot.spent; // >0 surplus, <0 déficit
    let out;
    switch ((b.rolloverMode || 'NONE').toUpperCase()) {
        case 'SURPLUS':
            out = Math.max(0, diff);
            break;
        case 'DEFICIT':
            out = Math.min(0, diff);
            break;
        case 'BOTH':
            out = diff;
            break;
        default:
            out = 0;
    }
    const cap = parseFloat(b.rolloverCap || '0');
    if (cap > 0) {
        if (out > 0) out = Math.min(out, cap);
        else out = Math.max(out, -cap);
    }
    return out;
}

// Agrégations converties AVANT calculs
function groupByCategoryIn(targetCcy, arr) {
    const m = new Map();
    for (const e of arr) {
        const x = convertToCurrency(e.amount, e.currency, targetCcy);
        m.set(e.category, (m.get(e.category) || 0) + (isNaN(x) ? 0 : x));
    }
    return m;
}

function isSameMonth(e) {
    const now = new Date();
    const d = new Date(e.ts);
    return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
}

function keyOfMonth(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

function monthlyBuckets(n = 6) {
    const now = new Date();
    const list = [];
    for (let i = n - 1; i >= 0; i--) list.push(keyOfMonth(new Date(now.getFullYear(), now.getMonth() - i, 1)));
    return list;
}

function sumByMonthIn(targetCcy, expenses) {
    const map = new Map(); // key: YYYY-MM
    for (const e of expenses) {
        const k = keyOfMonth(new Date(e.ts));
        const x = convertToCurrency(e.amount, e.currency, targetCcy);
        map.set(k, (map.get(k) || 0) + (isNaN(x) ? 0 : x));
    }
    return map;
}

// ===== FX =====
document.getElementById('fx-save').addEventListener('click', () => {
    const code = document.getElementById('fx-code').value.trim().toUpperCase();
    const perBase = document.getElementById('fx-rate').value.trim();
    if (!code || !perBase) return alert('Devise et taux requis.');
    const r = {code, perBase, deleted: false, ver: '', author: ''};
    window.bridge.upsertFx(JSON.stringify(r));
    document.getElementById('fx-rate').value = '';
});
document.getElementById('fx-refresh').addEventListener('click', () => {
    if (window.bridge.fxFetchNow) window.bridge.fxFetchNow();
});

function renderFxTable() {
    const tbody = document.querySelector('#tbl-fx tbody');
    if (!tbody) return;
    tbody.innerHTML = '';
    for (const r of FX) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${escapeHtml(r.code)}</td><td>${r.perBase}</td>
      <td><button class="rowdel" data-code="${r.code}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => window.bridge.deleteFx(b.dataset.code));
    });
}

// ===== Graphiques (en devise d'affichage) =====
function refreshAnalytics() {
    // Catégories (mois courant)
    const cats = Array.from(groupByCategoryIn(DISPLAY_CCY, EXPENSES.filter(isSameMonth)).entries());
    drawPie('chartCat', 'chartCat-fallback', cats);

    // 6 derniers mois
    const labels = monthlyBuckets(6);
    const m = sumByMonthIn(DISPLAY_CCY, EXPENSES);
    const series = labels.map(k => m.get(k) || 0);
    drawLine('chartMonthly', 'chartMonthly-fallback', labels, series);

    // Budget vs réalisé (mois)
    const spentByCat = groupByCategoryIn(DISPLAY_CCY, EXPENSES.filter(isSameMonth));
    const labelsB = BUDGETS.map(b => b.category);
    const planned = BUDGETS.map(b => convertToCurrency(b.monthlyLimit, b.currency, DISPLAY_CCY));
    const actual = BUDGETS.map(b => spentByCat.get(b.category) || 0);
    drawBars('chartBudget', 'chartBudget-fallback', labelsB, planned, actual);
}

function drawPie(canvasId, fallbackId, entries) {
    const c = document.getElementById(canvasId);
    const fb = document.getElementById(fallbackId);
    if (window.Chart && c) {
        const data = {labels: entries.map(e => e[0]), datasets: [{data: entries.map(e => e[1])}]};
        destroyChart(canvasId);
        window.__charts[canvasId] = new Chart(c.getContext('2d'), {
            type: 'doughnut',
            data,
            options: {responsive: true, plugins: {legend: {display: true}}}
        });
        fb.innerHTML = '';
    } else {
        destroyChart(canvasId);
        fb.innerHTML = '';
        for (const [k, v] of entries) {
            const row = document.createElement('div');
            row.innerHTML = `<div class="row"><span>${escapeHtml(k)}</span><span>${v.toFixed(2)} ${DISPLAY_CCY}</span></div>
                       <div class="bar" style="width:${Math.min(100, v)}%"></div>`;
            fb.appendChild(row);
        }
    }
}

function drawLine(canvasId, fallbackId, labels, series) {
    const c = document.getElementById(canvasId);
    const fb = document.getElementById(fallbackId);
    if (window.Chart && c) {
        const data = {labels, datasets: [{label: 'Total mensuel', data: series}]};
        destroyChart(canvasId);
        window.__charts[canvasId] = new Chart(c.getContext('2d'), {
            type: 'line',
            data,
            options: {responsive: true, plugins: {legend: {display: false}}}
        });
        fb.innerHTML = '';
    } else {
        destroyChart(canvasId);
        fb.innerHTML = '';
        labels.forEach((lab, i) => {
            const v = series[i] || 0;
            const row = document.createElement('div');
            row.innerHTML = `<div class="row"><span>${lab}</span><span>${v.toFixed(2)} ${DISPLAY_CCY}</span></div>
                       <div class="bar" style="width:${Math.min(100, v)}%"></div>`;
            fb.appendChild(row);
        });
    }
}

function drawBars(canvasId, fallbackId, labels, planned, actual) {
    const c = document.getElementById(canvasId);
    const fb = document.getElementById(fallbackId);
    if (window.Chart && c) {
        const data = {labels, datasets: [{label: 'Plafond', data: planned}, {label: 'Réalisé', data: actual}]};
        destroyChart(canvasId);
        window.__charts[canvasId] = new Chart(c.getContext('2d'), {type: 'bar', data, options: {responsive: true}});
        fb.innerHTML = '';
    } else {
        destroyChart(canvasId);
        fb.innerHTML = '';
        labels.forEach((lab, i) => {
            const p = planned[i] || 0, a = actual[i] || 0;
            const row = document.createElement('div');
            row.innerHTML = `<div class="row"><span>${escapeHtml(lab)}</span><span>${a.toFixed(2)} / ${p.toFixed(2)} ${DISPLAY_CCY}</span></div>
                       <div class="bar" style="width:${Math.min(100, a)}%"></div>`;
            fb.appendChild(row);
        });
    }
}

// ===== Exports =====
document.getElementById('exportCsv').addEventListener('click', () => {
    const lines = [["ts", "who", "category", "amount_display", "display_ccy", "amount_original", "currency", "note"]];
    for (const e of EXPENSES) {
        const disp = convertToCurrency(e.amount, e.currency, DISPLAY_CCY);
        lines.push([e.ts, e.who || "", e.category || "", disp.toFixed(2), DISPLAY_CCY, e.amount, e.currency || "", (e.note || "").replace(/\n/g, " ")]);
    }
    const csv = lines.map(r => r.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(",")).join("\n");
    const name = "expenses-" + new Date().toISOString().slice(0, 10) + ".csv";
    const path = window.bridge.exportCsv(name, csv);
    alert(path ? "CSV exporté: " + path : "Échec export CSV");
});
document.getElementById('exportHtml').addEventListener('click', () => {
    const img1 = toDataUrl('chartCat'), img2 = toDataUrl('chartMonthly'), img3 = toDataUrl('chartBudget');
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>Rapport Budget</title>
  <style>body{font-family:system-ui,Segoe UI,Roboto,Arial;margin:20px;color:#222} h1{margin:0 0 8px} h2{margin:16px 0 6px} table{border-collapse:collapse;width:100%} th,td{border:1px solid #ddd;padding:6px}</style>
  </head><body>
  <h1>Rapport Budget – ${new Date().toLocaleString()} (affichage ${DISPLAY_CCY})</h1>
  <h2>Graphiques</h2>
  <img src="${img1}" style="max-width:32%" alt=""/> <img src="${img2}" style="max-width:32%" alt=""/> <img src="${img3}" style="max-width:32%" alt=""/>
  <h2>Dépenses (mois courant)</h2>
  ${htmlTableExpenses()}
  </body></html>`;
    const path = window.bridge.exportReport(html);
    alert(path ? "Rapport HTML exporté: " + path : "Échec export HTML");
});

function toDataUrl(id) {
    const c = document.getElementById(id);
    try {
        return c.toDataURL("image/png");
    } catch (e) {
        return "";
    }
}

function htmlTableExpenses() {
    const rows = EXPENSES.filter(isSameMonth).map(e => {
        const disp = convertToCurrency(e.amount, e.currency, DISPLAY_CCY);
        return `<tr><td>${new Date(e.ts).toLocaleString()}</td>
      <td>${escapeHtml(e.who || '')}</td><td>${escapeHtml(e.category || '')}</td>
      <td>${disp.toFixed(2)} ${DISPLAY_CCY}</td>
      <td>${parseFloat(e.amount).toFixed(2)} ${escapeHtml(e.currency || '')}</td>
      <td>${escapeHtml(e.note || '')}</td></tr>`;
    }).join("");
    return `<table><thead><tr><th>Date</th><th>Qui</th><th>Cat.</th><th>Montant (disp.)</th><th>Original</th><th>Note</th></tr></thead><tbody>${rows}</tbody></table>`;
}

// ===== Divers =====
function escapeHtml(s) {
    return (s || '').replace(/[&<>"']/g, m => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": "&#39;"
    }[m]));
}

window.onRecurring = (json) => {
    RECUR = JSON.parse(json);
    renderRecTable();
};
let RECUR = [];

document.getElementById('rec-save').addEventListener('click', () => {
    const payload = {
        id: '', name: val('rec-name'), period: val('rec-period'),
        day: parseInt(val('rec-day') || '0', 10),
        weekday: parseInt(val('rec-weekday') || '0', 10),
        month: parseInt(val('rec-month') || '0', 10),
        amount: val('rec-amount'), currency: val('rec-ccy'),
        category: val('rec-cat'), note: val('rec-note'),
        active: true, deleted: false, ver: '', author: ''
    };
    if (!payload.name || !payload.period || !payload.amount) return alert('Nom/période/montant requis.');
    window.bridge.upsertRecurring(JSON.stringify(payload));
});

function renderRecTable() {
    const tbody = qs('#tbl-rec tbody');
    tbody.innerHTML = '';
    for (const r of RECUR) {
        const rule = r.period === 'MONTHLY' ? `jour=${r.day}` :
            r.period === 'WEEKLY' ? `weekday=${r.weekday}` :
                `jour=${r.day} mois=${r.month}`;
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(r.name)}</td><td>${esc(r.period)}</td><td>${esc(rule)}</td>
                    <td>${parseFloat(r.amount).toFixed(2)}</td><td>${esc(r.currency)}</td>
                    <td>${esc(r.category)}</td>
                    <td><button class="rowdel" data-id="${r.id}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => b.addEventListener('click', () => window.bridge.deleteRecurring(b.dataset.id)));
}

function val(id) {
    return document.getElementById(id).value.trim();
}

function qs(s) {
    return document.querySelector(s);
}

function esc(s) {
    return (s || '').replace(/[&<>"']/g, m => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[m]));
}

window.onGoals = (json) => {
    GOALS = JSON.parse(json);
    renderGoals();
};
let GOALS = [];

document.getElementById('g-save').addEventListener('click', () => {
    const name = v('g-name'), target = v('g-target'), currency = v('g-ccy'), due = v('g-due');
    if (!name || !target || !currency) return alert('Nom/target/devise requis.');
    const dueTs = due ? Date.parse(due + 'T00:00:00') : 0;
    const payload = {id: '', name, target, currency, dueTs, deleted: false, ver: '', author: ''};
    window.bridge.upsertGoal(JSON.stringify(payload));
});

function renderGoals() {
    const tbody = q('#tbl-goals tbody');
    tbody.innerHTML = '';
    for (const g of GOALS) {
        const targetDisp = convertToCurrency(g.target, g.currency, DISPLAY_CCY);
        const saved = sumGoalContribIn(g, DISPLAY_CCY);
        const pct = targetDisp > 0 ? Math.min(100, (saved / targetDisp) * 100) : 0;
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(g.name)}</td>
      <td>${targetDisp.toFixed(2)} ${DISPLAY_CCY}</td>
      <td>${saved.toFixed(2)} ${DISPLAY_CCY} (${pct.toFixed(0)}%)</td>
      <td>${g.dueTs ? new Date(g.dueTs).toLocaleDateString() : '-'}</td>
      <td><button class="rowdel" data-id="${g.id}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => b.addEventListener('click', () => window.bridge.deleteGoal(b.dataset.id)));
}

function sumGoalContribIn(g, ccy) {
    // convention simple: toute dépense dont la note contient #[goal:<goalId>] est une contribution
    const tag = "#[goal:" + g.id + "]";
    let s = 0;
    for (const e of EXPENSES) {
        if ((e.note || '').includes(tag)) s += convertToCurrency(e.amount, e.currency, ccy);
    }
    return s;
}

function v(id) {
    return document.getElementById(id).value.trim();
}

function q(s) {
    return document.querySelector(s);
}