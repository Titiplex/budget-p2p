
package com.titiplex.budget.core.store;

import com.titiplex.budget.core.model.Expense;

import java.util.List;

public interface Repository {
    void init();

    void saveExpense(Expense e);

    void deleteExpense(String id);

    Expense findById(String id);

    List<Expense> listExpenses();
}
