// Espace de stockage isolé pour les instances
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

// ---- State ----
let EXPENSES = [];
let BUDGETS = [];
let FX = []; // { code, perBase }
let RULES = [];

const FX_BASE = "EUR";

// ---- Bridges (called by Java) ----
window.onExpenses = (json) => {
    EXPENSES = JSON.parse(json);
    renderExpensesTable();
    refreshAnalytics();
    renderBudgetsTable();
};

window.onBudgets = (json) => {
    BUDGETS = JSON.parse(json);
    renderBudgetsTable();
    refreshAnalytics();
};

window.onFx = (json) => {
    FX = JSON.parse(json);
    renderFxTable();
    refreshAnalytics();
};

window.onRules = (json) => {
    RULES = JSON.parse(json);
    renderRulesTable();
};

// ---- UI Expenses ----
function renderExpensesTable() {
    const tbody = document.querySelector('#tbl tbody');
    tbody.innerHTML = '';
    for (const e of EXPENSES) {
        // auto-categorization hint client-side (non-blocking)
        let cat = e.category || '';
        if (!cat) {
            const c = applyRules(e);
            if (c) cat = c + ' (suggéré)';
        }
        const tr = document.createElement('tr');
        const date = new Date(e.ts).toLocaleString();
        tr.innerHTML = `
      <td>${date}</td>
      <td>${escapeHtml(e.who || '')}</td>
      <td>${escapeHtml(cat)}</td>
      <td>${e.amount}</td>
      <td>${escapeHtml(e.currency || '')}</td>
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

// ---- Budgets ----
document.getElementById('b-save').addEventListener('click', () => {
    const category = document.getElementById('b-cat').value.trim();
    const monthlyLimit = document.getElementById('b-limit').value.trim();
    const currency = document.getElementById('b-cur').value.trim();
    if (!category || !monthlyLimit) return alert('Catégorie et plafond requis.');
    const b = {id: '', category, monthlyLimit, currency, deleted: false, ver: '', author: ''};
    window.bridge.upsertBudget(JSON.stringify(b));
    document.getElementById('b-limit').value = '';
});

function renderBudgetsTable() {
    const tbody = document.querySelector('#tbl-budgets tbody');
    if (!tbody) return;
    tbody.innerHTML = '';
    const spentByCat = groupByCategory(EXPENSES.filter(isSameMonth));
    for (const b of BUDGETS) {
        const spentRaw = spentByCat.get(b.category) || 0;
        // convert expenses to budget currency
        const spent = convertToCurrency(spentRaw, guessExpenseCurrency(b.category), b.currency);
        const left = parseFloat(b.monthlyLimit) - spent;
        const cls = left < 0 ? 'warn' : 'ok';
        const tr = document.createElement('tr');
        tr.innerHTML = `
      <td>${escapeHtml(b.category)}</td>
      <td>${b.monthlyLimit}</td>
      <td>${escapeHtml(b.currency || '')}</td>
      <td>${spent.toFixed(2)}</td>
      <td class="${cls}">${left.toFixed(2)}</td>
      <td><button class="rowdel" data-cat="${b.category}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => window.bridge.deleteBudget(b.dataset.cat));
    });
}

function guessExpenseCurrency(category) {
    // naive: pick most frequent currency for this category in current month
    const by = new Map();
    for (const e of EXPENSES.filter(isSameMonth)) {
        if (e.category === category) {
            by.set(e.currency, (by.get(e.currency) || 0) + 1);
        }
    }
    let best = null, cnt = -1;
    for (const [k, v] of by) if (v > cnt) {
        best = k;
        cnt = v;
    }
    return best || FX_BASE;
}

// ---- FX ----
document.getElementById('fx-save').addEventListener('click', () => {
    const code = document.getElementById('fx-code').value.trim().toUpperCase();
    const perBase = document.getElementById('fx-rate').value.trim();
    if (!code || !perBase) return alert('Devise et taux requis.');
    const r = {code, perBase, deleted: false, ver: '', author: ''};
    window.bridge.upsertFx(JSON.stringify(r));
    document.getElementById('fx-rate').value = '';
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

function rateOf(code) {
    if (!code) return 1.0;
    const r = FX.find(x => x.code.toUpperCase() === code.toUpperCase());
    return r ? parseFloat(r.perBase) : (code.toUpperCase() === FX_BASE ? 1.0 : NaN);
}

function convertToCurrency(amount, from, to) {
    if (!from || !to || from === to) return parseFloat(amount) || 0;
    const rf = rateOf(from), rt = rateOf(to);
    if (!isFinite(rf) || !isFinite(rt) || rf <= 0 || rt <= 0) return parseFloat(amount) || 0;
    // amount_from * (rf / rt)
    return (parseFloat(amount) || 0) * (rf / rt);
}

// ---- Rules ----
document.getElementById('r-save').addEventListener('click', () => {
    const name = document.getElementById('r-name').value.trim();
    const kind = document.getElementById('r-kind').value.trim();
    const pattern = document.getElementById('r-pattern').value.trim();
    const category = document.getElementById('r-category').value.trim();
    if (!name || !pattern || !category) return alert('Champs requis.');
    // rules are synced via P2P with Op (we'll send through CSV-like channel later). For now add locally by making a fake CSV export path?
    const payload = {id: '', name, kind, pattern, category, active: true, deleted: false, ver: '', author: ''};
    // Send as generic op through bridge: not directly supported; future hook
    alert('Pour synchroniser les règles entre pairs, ajoute le wiring côté Java (Op RULE_UPSERT/RULE_DELETE). En attendant, elles sont prises en compte côté client uniquement.');
    // Client-side only update (temporary)
    const idx = RULES.findIndex(r => r.name === name);
    if (idx >= 0) RULES[idx] = payload; else RULES.push(payload);
    renderRulesTable();
});

function renderRulesTable() {
    const tbody = document.querySelector('#tbl-rules tbody');
    if (!tbody) return;
    tbody.innerHTML = '';
    for (const r of RULES) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${escapeHtml(r.name)}</td><td>${escapeHtml(r.kind)}</td>
      <td>${escapeHtml(r.pattern)}</td><td>${escapeHtml(r.category)}</td>
      <td></td>`;
        tbody.appendChild(tr);
    }
}

function applyRules(e) {
    const hay = ((e.note || '') + ' ' + (e.who || '')).toLowerCase();
    for (const r of RULES) {
        if (!r.active || r.deleted) continue;
        try {
            if (r.kind === 'SUBSTRING') {
                if (hay.includes(r.pattern.toLowerCase())) return r.category;
            } else if (r.kind === 'REGEX') {
                if (new RegExp(r.pattern).test(hay)) return r.category;
            }
        } catch (_) {
        }
    }
    return null;
}

// ---- Analytics & Graphs ----
function isSameMonth(e) {
    const now = new Date();
    const d = new Date(e.ts);
    return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
}

function keyOfMonth(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

function groupByCategory(arr) {
    const m = new Map();
    for (const e of arr) {
        const x = parseFloat(e.amount);
        const v = m.get(e.category) || 0;
        m.set(e.category, v + (isNaN(x) ? 0 : x));
    }
    return m;
}

function monthlyBuckets(n = 6) {
    const now = new Date();
    const list = [];
    for (let i = n - 1; i >= 0; i--) {
        const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
        list.push(keyOfMonth(d));
    }
    return list;
}

function sumByMonth(expenses) {
    const map = new Map(); // key: YYYY-MM
    for (const e of expenses) {
        const d = new Date(e.ts);
        const k = keyOfMonth(d);
        const x = parseFloat(e.amount);
        map.set(k, (map.get(k) || 0) + (isNaN(x) ? 0 : x));
    }
    return map;
}

let catChart = null, monthlyChart = null, budgetChart = null;

function refreshAnalytics() {
    // Catégories (mois courant)
    const cats = Array.from(groupByCategory(EXPENSES.filter(isSameMonth)).entries());
    drawPie('chartCat', 'chartCat-fallback', cats);

    // 6 derniers mois
    const labels = monthlyBuckets(6);
    const m = sumByMonth(EXPENSES);
    const series = labels.map(k => m.get(k) || 0);
    drawLine('chartMonthly', 'chartMonthly-fallback', labels, series);

    // Budget vs réalisé (mois)
    const spentByCat = groupByCategory(EXPENSES.filter(isSameMonth));
    const labelsB = BUDGETS.map(b => b.category);
    const planned = BUDGETS.map(b => parseFloat(b.monthlyLimit));
    const actual = BUDGETS.map(b => {
        const raw = spentByCat.get(b.category) || 0;
        return convertToCurrency(raw, guessExpenseCurrency(b.category), b.currency);
    });
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
            row.innerHTML = `<div class="row"><span>${escapeHtml(k)}</span><span>${v.toFixed(2)}</span></div>
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
            row.innerHTML = `<div class="row"><span>${lab}</span><span>${v.toFixed(2)}</span></div>
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
            row.innerHTML = `<div class="row"><span>${escapeHtml(lab)}</span><span>${a.toFixed(2)} / ${p.toFixed(2)}</span></div>
                       <div class="bar" style="width:${Math.min(100, a)}%"></div>`;
            fb.appendChild(row);
        });
    }
}

// ---- Exports ----
document.getElementById('exportCsv').addEventListener('click', () => {
    const lines = [["ts", "who", "category", "amount", "currency", "note"]];
    for (const e of EXPENSES) {
        lines.push([e.ts, e.who || "", e.category || "", e.amount, e.currency || "", (e.note || "").replace(/\n/g, " ")]);
    }
    const csv = lines.map(r => r.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(",")).join("\n");
    const name = "expenses-" + new Date().toISOString().slice(0, 10) + ".csv";
    const path = window.bridge.exportCsv(name, csv);
    alert(path ? "CSV exporté: " + path : "Échec export CSV");
});

document.getElementById('exportHtml').addEventListener('click', () => {
    const img1 = toDataUrl('chartCat');
    const img2 = toDataUrl('chartMonthly');
    const img3 = toDataUrl('chartBudget');
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>Rapport Budget</title>
  <style>body{font-family:system-ui,Segoe UI,Roboto,Arial;margin:20px;color:#222} h1{margin:0 0 8px} h2{margin:16px 0 6px} table{border-collapse:collapse;width:100%} th,td{border:1px solid #ddd;padding:6px}</style>
  </head><body>
  <h1>Rapport Budget – ${new Date().toLocaleString()}</h1>
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
    const rows = EXPENSES.filter(isSameMonth).map(e => `<tr>
    <td>${new Date(e.ts).toLocaleString()}</td><td>${escapeHtml(e.who || '')}</td><td>${escapeHtml(e.category || '')}</td>
    <td>${e.amount}</td><td>${escapeHtml(e.currency || '')}</td><td>${escapeHtml(e.note || '')}</td></tr>`).join("");
    return `<table><thead><tr><th>Date</th><th>Qui</th><th>Cat.</th><th>Montant</th><th>Devise</th><th>Note</th></tr></thead><tbody>${rows}</tbody></table>`;
}

// ---- Utils ----
function escapeHtml(s) {
    return (s || '').replace(/[&<>"']/g, m => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": "&#39;"
    }[m]));
}

document.getElementById('fx-refresh').addEventListener('click', () => {
    window.bridge.fxFetchNow();
});