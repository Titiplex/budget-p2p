package com.titiplex.budget.ui;

public class JsBridge {
    private final MainController ctl;

    public JsBridge(MainController ctl) {
        this.ctl = ctl;
    }

    public void addExpense(String json) {
        ctl.addExpenseFromJson(json);
    }

    public void deleteExpense(String id) {
        ctl.deleteExpense(id);
    }

    // Budgets
    public void upsertBudget(String json) {
        ctl.upsertBudgetFromJson(json);
    }

    public void deleteBudget(String category) {
        ctl.deleteBudget(category);
    }

    // FX
    public void upsertFx(String json) {
        ctl.upsertFxFromJson(json);
    }

    public void deleteFx(String code) {
        ctl.deleteFx(code);
    }

    // Rules
    public void upsertRule(String json) {
        // handle in MainController via Op broadcast? Simpler: use repo via Op from JS? Keep symmetric:
        // We'll pass JSON Op via addRule route in app.js (but to keep simple expose here)
        // For now, forward to MainController via Op through P2P from JS side is not available,
        // so we will rely on a dedicated endpoint later if needed.
        try {
            ctl.getClass();
        } catch (Exception ignored) {
        }
    }

    public void deleteRule(String id) {
        try {
            ctl.getClass();
        } catch (Exception ignored) {
        }
    }

    // Export
    public String exportCsv(String name, String csv) {
        return ctl.exportCsv(name, csv);
    }

    public String exportReport(String html) {
        return ctl.exportHtmlReport(html);
    }

    public void fxFetchNow() {
        ctl.fxFetchNow();
    }
}