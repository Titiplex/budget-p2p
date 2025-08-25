package com.titiplex.budget.core.store;

import com.titiplex.budget.core.model.CategoryBudget;
import com.titiplex.budget.core.model.Expense;
import com.titiplex.budget.core.model.FxRate;
import com.titiplex.budget.core.model.Rule;

import java.util.List;

public interface Repository {
    // Expenses
    void init();

    void upsertExpense(Expense e);

    void tombstone(String id, String ver, String author);

    Expense findById(String id);

    List<Expense> listActive();

    // Budgets
    void upsertBudget(CategoryBudget b);

    void tombstoneBudget(String category, String ver, String author);

    List<CategoryBudget> listBudgetsActive();

    // FX
    void upsertFx(FxRate r);

    void tombstoneFx(String code, String ver, String author);

    List<FxRate> listFxActive();

    // Rules
    void upsertRule(Rule r);

    void tombstoneRule(String id, String ver, String author);

    List<Rule> listRulesActive();
}