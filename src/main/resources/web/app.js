window.onExpenses = (json) => {
    const data = JSON.parse(json);
    const tbody = document.querySelector('#tbl tbody');
    tbody.innerHTML = '';
    for (const e of data) {
        const tr = document.createElement('tr');
        const date = new Date(e.ts).toLocaleString();
        tr.innerHTML = `
      <td>${date}</td>
      <td>${escapeHtml(e.who || '')}</td>
      <td>${escapeHtml(e.category || '')}</td>
      <td>${e.amount}</td>
      <td>${escapeHtml(e.currency || '')}</td>
      <td>${escapeHtml(e.note || '')}</td>
      <td><button class="rowdel" data-id="${e.id}">Supprimer</button></td>`;
        tbody.appendChild(tr);
    }
    tbody.querySelectorAll('button.rowdel').forEach(b => {
        b.addEventListener('click', () => window.bridge.deleteExpense(b.dataset.id));
    });
};

document.getElementById('add').addEventListener('click', () => {
    const who = document.getElementById('who').value.trim();
    const category = document.getElementById('category').value.trim();
    const amount = document.getElementById('amount').value.trim();
    const currency = document.getElementById('currency').value.trim();
    const note = document.getElementById('note').value.trim();
    if (!who || !category || !amount) return alert('Champs requis manquants.');
    const e = {id: '', who, category, amount, currency, note, ts: Date.now(), deleted: false};
    window.bridge.addExpense(JSON.stringify(e));
    document.getElementById('note').value = '';
    document.getElementById('amount').value = '';
});

function escapeHtml(s) {
    return s.replace(/[&<>"']/g, m => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '\"': '&quot;', "'": '\"&#39;\"'}[m]));
}
