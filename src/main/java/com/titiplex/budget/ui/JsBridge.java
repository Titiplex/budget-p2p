package com.titiplex.budget.ui;

import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.p2p.JGroupsP2PService;
import com.titiplex.budget.core.p2p.P2PService;

public class JsBridge {
    private final MainController ctl;
    private final SessionState ss;
    private final P2PService p2p;

    public JsBridge(MainController ctl, SessionState ss, P2PService p2p) {
        this.ctl = ctl;
        this.ss = ss;
        this.p2p = p2p;
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

    public void upsertGoal(String json) {
        ctl.upsertGoalFromJson(json);
    }

    public void deleteGoal(String id) {
        ctl.deleteGoal(id);
    }

    public String generateInviteCode() {
        return ctl.generateInviteCode();
    }

    public String joinFromInviteCode(String code) {
        return ctl.joinFromInviteCode(code);
    }

    public String netInfo() {
        try {
            var map = new java.util.HashMap<String, Object>();
            boolean connected = (p2p instanceof JGroupsP2PService jp2p) && jp2p.isConnected();
            map.put("connected", connected);
            map.put("mode", (ss.seeds != null && !ss.seeds.isEmpty()) ? "WAN" : "LAN");
            map.put("seeds", ss.seeds == null ? 0 : ss.seeds.size());
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{\"connected\":false,\"mode\":\"LAN\",\"seeds\":0}";
        }
    }
}