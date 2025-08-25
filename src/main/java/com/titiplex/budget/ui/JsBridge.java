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
        ctl.upsertRuleFromJson(json);
    }

    public void deleteRule(String id) {
        ctl.deleteRule(id);
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

    public void upsertRecurring(String json) {
        ctl.upsertRecurringFromJson(json);
    }

    public void deleteRecurring(String id) {
        ctl.deleteRecurring(id);
    }
}