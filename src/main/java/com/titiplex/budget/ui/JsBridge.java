
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
}
