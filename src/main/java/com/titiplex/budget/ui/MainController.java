package com.titiplex.budget.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.config.ConfigService;
import com.titiplex.budget.core.crdt.HLC;
import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.fx.FxAutoService;
import com.titiplex.budget.core.model.*;
import com.titiplex.budget.core.p2p.P2PService;
import com.titiplex.budget.core.recurring.RecurringService;
import com.titiplex.budget.core.store.Repository;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class MainController {

    private final Repository repo;
    private final P2PService p2p;
    private final SessionState ss;
    private final ConfigService config;
    private final FxAutoService fxAuto;
    private final RecurringService recurring;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HLC.Clock clock;

    public MainController(
            Repository repo,
            P2PService p2p,
            SessionState ss,
            ConfigService config,
            FxAutoService fxAuto,
            RecurringService recurring) {
        this.repo = repo;
        this.p2p = p2p;
        this.ss = ss;
        this.config = config;
        this.fxAuto = fxAuto;
        this.recurring = recurring;
        this.clock = new HLC.Clock(ss.userId == null ? UUID.randomUUID().toString() : ss.userId);
    }

    @FXML
    private WebView webview;

    @FXML
    public void initialize() {
        // Start P2P
        p2p.start(ss.groupId, ss.groupPass, ss.seeds, ss.port, this::onRemoteOp);
        recurring.start();

        config.saveLastSession(ss);

        // UI
        WebEngine engine = webview.getEngine();
        engine.load(Objects.requireNonNull(getClass().getResource("/web/index.html")).toExternalForm());

        engine.getLoadWorker().stateProperty().addListener((_, _, st) -> {
            if (st.toString().equals("SUCCEEDED")) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("bridge", new JsBridge(this));
                pushAll();
                pushBudgets();
                pushFx();
                pushRules();
                fxAuto.startScheduler();     // planifie (si app.fx.auto=true)
                fxAuto.fetchNow();           // tente une MAJ immédiate au lancement
            }
        });
    }

    void onRemoteOp(Op op) {
        try {
            switch (op.type()) {
                case ADD -> {
                    Expense e = mapper.convertValue(op.payload(), Expense.class);
                    repo.upsertExpense(e);
                    pushAll();
                }
                case DELETE -> {
                    Expense e = mapper.convertValue(op.payload(), Expense.class);
                    repo.tombstone(e.id(), e.ver(), e.author());
                    pushAll();
                }
                case BUDGET_UPSERT -> {
                    CategoryBudget b = mapper.convertValue(op.payload(), CategoryBudget.class);
                    repo.upsertBudget(b);
                    pushBudgets();
                }
                case BUDGET_DELETE -> {
                    CategoryBudget b = mapper.convertValue(op.payload(), CategoryBudget.class);
                    repo.tombstoneBudget(b.category(), b.ver(), b.author());
                    pushBudgets();
                }
                case FX_UPSERT -> {
                    FxRate r = mapper.convertValue(op.payload(), FxRate.class);
                    repo.upsertFx(r);
                    pushFx();
                }
                case FX_DELETE -> {
                    FxRate r = mapper.convertValue(op.payload(), FxRate.class);
                    repo.tombstoneFx(r.code(), r.ver(), r.author());
                    pushFx();
                }
                case RULE_UPSERT -> {
                    Rule r = mapper.convertValue(op.payload(), Rule.class);
                    repo.upsertRule(r);
                    pushRules();
                }
                case RULE_DELETE -> {
                    Rule r = mapper.convertValue(op.payload(), Rule.class);
                    repo.tombstoneRule(r.id(), r.ver(), r.author());
                    pushRules();
                }
                case RECUR_UPSERT -> {
                    RecurringRule r = mapper.convertValue(op.payload(), RecurringRule.class);
                    repo.upsertRecurring(r);
                    pushRecurring();
                }
                case RECUR_DELETE -> {
                    RecurringRule r = mapper.convertValue(op.payload(), RecurringRule.class);
                    repo.tombstoneRecurring(r.id(), r.ver(), r.author());
                    pushRecurring();
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to process op: " + e.getMessage());
        }
    }

    public void pushAll() {
        List<Expense> all = repo.listActive();
        try {
            String json = mapper.writeValueAsString(all);
            Platform.runLater(() -> {
                try {
                    webview.getEngine()
                            .executeScript("window.onExpenses(" + mapper.writeValueAsString(json) + ");");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to push expenses: " + e.getMessage());
        }
    }

    public void deleteExpense(String id) {
        String ver = clock.tick();
        Expense e = repo.findById(id);
        if (e != null) {
            Expense tomb = new Expense(e.id(), e.who(), e.category(), e.amount(), e.currency(), e.note(),
                    e.ts(), true, ver, ss.userId);
            repo.tombstone(id, ver, ss.userId);
            p2p.broadcast(new Op(Op.Type.DELETE, tomb));
            pushAll();
        }
    }

    public void pushBudgets() {
        try {
            var all = repo.listBudgetsActive();
            String json = mapper.writeValueAsString(all);
            Platform.runLater(() ->
            {
                try {
                    webview.getEngine().executeScript("window.onBudgets(" + mapper.writeValueAsString(json) + ");");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to push budgets: " + e.getMessage());
        }
    }

    // Called by JS to upsert a budget
    public void upsertBudgetFromJson(String json) {
        try {
            CategoryBudget in = mapper.readValue(json, CategoryBudget.class);
            String ver = clock.tick();
            CategoryBudget b = new CategoryBudget(
                    (in.id() == null || in.id().isEmpty()) ? java.util.UUID.randomUUID().toString() : in.id(),
                    in.category(),
                    new BigDecimal(in.monthlyLimit().toPlainString()),
                    in.currency(),
                    false,
                    ver,
                    ss.userId,
                    in.rolloverMode(),
                    in.rolloverCap()
            );
            repo.upsertBudget(b);
            p2p.broadcast(new Op(Op.Type.BUDGET_UPSERT, b));
            pushBudgets();
        } catch (Exception ex) {
            System.err.println("Failed to upsert budget: " + ex.getMessage());
        }
    }

    public void deleteBudget(String category) {
        try {
            String ver = clock.tick();
            CategoryBudget tomb = new CategoryBudget(
                    java.util.UUID.randomUUID().toString(),
                    category,
                    BigDecimal.ZERO,
                    "",
                    true,
                    ver,
                    ss.userId,
                    "NONE",
                    BigDecimal.ZERO
            );
            repo.tombstoneBudget(category, ver, ss.userId);
            p2p.broadcast(new Op(Op.Type.BUDGET_DELETE, tomb));
            pushBudgets();
        } catch (Exception e) {
            System.err.println("Failed to delete budget: " + e.getMessage());
        }
    }

    public void pushFx() {
        try {
            var all = repo.listFxActive();
            String json = mapper.writeValueAsString(all);
            Platform.runLater(() -> {
                try {
                    webview.getEngine().executeScript("window.onFx(" + mapper.writeValueAsString(json) + ");");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to push fx rates: " + e.getMessage());
        }
    }

    public void pushRules() {
        try {
            var all = repo.listRulesActive();
            String json = mapper.writeValueAsString(all);
            Platform.runLater(() -> {
                try {
                    webview.getEngine().executeScript("window.onRules(" + mapper.writeValueAsString(json) + ");");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to push rules: " + e.getMessage());
        }
    }

    // ---- Expense entry with rule auto-categorization ----
    public void addExpenseFromJson(String json) {
        try {
            Expense in = mapper.readValue(json, Expense.class);
            String id = (in.id() == null || in.id().isEmpty()) ? UUID.randomUUID().toString() : in.id();
            String cat = in.category();
            if (cat == null || cat.isBlank()) {
                // simple server-side rule application
                for (Rule r : repo.listRulesActive()) {
                    if (matchRule(r, in)) {
                        cat = r.category();
                        break;
                    }
                }
            }
            String ver = clock.tick();
            Expense e = new Expense(
                    id,
                    in.who(),
                    cat,
                    new BigDecimal(in.amount().toPlainString()),
                    in.currency(),
                    in.note(),
                    Instant.now().toEpochMilli(),
                    false,
                    ver,
                    ss.userId
            );
            repo.upsertExpense(e);
            p2p.broadcast(new Op(Op.Type.ADD, e));
            pushAll();
        } catch (Exception ex) {
            System.err.println("Failed to add expense: " + ex.getMessage());
        }
    }

    private boolean matchRule(Rule r, Expense e) {
        if (r == null || !r.active()) return false;
        String hay = (e.note() == null ? "" : e.note()) + " " + (e.who() == null ? "" : e.who());
        try {
            return switch (r.kind()) {
                case "SUBSTRING" -> hay.toLowerCase().contains(r.pattern().toLowerCase());
                case "REGEX" -> hay.matches(r.pattern());
                default -> false;
            };
        } catch (Exception ex) {
            return false;
        }
    }

    // ---- FX CRUD ----
    public void upsertFxFromJson(String json) {
        try {
            FxRate in = mapper.readValue(json, FxRate.class);
            String ver = clock.tick();
            FxRate r = new FxRate(in.code(), new BigDecimal(in.perBase().toPlainString()), false, ver, ss.userId);
            repo.upsertFx(r);
            p2p.broadcast(new Op(Op.Type.FX_UPSERT, r));
            pushFx();
        } catch (Exception e) {
            System.err.println("Failed to upsert fx: " + e.getMessage());
        }
    }

    public void deleteFx(String code) {
        try {
            String ver = clock.tick();
            FxRate tomb = new FxRate(code, BigDecimal.ZERO, true, ver, ss.userId);
            repo.tombstoneFx(code, ver, ss.userId);
            p2p.broadcast(new Op(Op.Type.FX_DELETE, tomb));
            pushFx();
        } catch (Exception e) {
            System.err.println("Failed to delete fx: " + e.getMessage());
        }
    }

    // ---- Exports ----
    public String exportCsv(String name, String csv) {
        try {
            String dir = System.getProperty("user.home") + "/.budget-p2p/reports";
            new File(dir).mkdirs();
            String path = dir + "/" + name;
            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(csv.getBytes(StandardCharsets.UTF_8));
            }
            return path;
        } catch (Exception e) {
            return "";
        }
    }

    public String exportHtmlReport(String html) {
        try {
            String dir = System.getProperty("user.home") + "/.budget-p2p/reports";
            new File(dir).mkdirs();
            String path = dir + "/report-" + System.currentTimeMillis() + ".html";
            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(html.getBytes(StandardCharsets.UTF_8));
            }
            return path;
        } catch (Exception e) {
            return "";
        }
    }

    public void fxFetchNow() {
        fxAuto.fetchNow();
    }

    // ---- Rules CRUD (appelées par JS via JsBridge) ----
    public void upsertRuleFromJson(String json) {
        try {
            Rule in = mapper.readValue(json, Rule.class);
            String ver = clock.tick();
            Rule r = new Rule(
                    (in.id() == null || in.id().isEmpty()) ? java.util.UUID.randomUUID().toString() : in.id(),
                    in.name(),
                    in.kind(),
                    in.pattern(),
                    in.category(),
                    true,   // active
                    false,  // deleted
                    ver,
                    ss.userId
            );
            repo.upsertRule(r);
            p2p.broadcast(new Op(Op.Type.RULE_UPSERT, r));
            pushRules();
        } catch (Exception e) {
            System.err.println("Failed to upsert rule: " + e.getMessage());
        }
    }

    public void deleteRule(String id) {
        try {
            String ver = clock.tick();
            // on envoie un tombstone minimal (id + deleted=true + ver/author)
            Rule tomb = new Rule(id, "", "", "", "", false, true, ver, ss.userId);
            repo.tombstoneRule(id, ver, ss.userId);
            p2p.broadcast(new Op(Op.Type.RULE_DELETE, tomb));
            pushRules();
        } catch (Exception e) {
            System.err.println("Failed to delete rule: " + e.getMessage());
        }
    }

    public void pushRecurring() {
        try {
            var all = repo.listRecurringActive();
            String json = mapper.writeValueAsString(all);
            javafx.application.Platform.runLater(() ->
            {
                try {
                    webview.getEngine().executeScript("window.onRecurring(" + mapper.writeValueAsString(json) + ");");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to push recurring: " + e.getMessage());
        }
    }

    public void upsertRecurringFromJson(String json) {
        try {
            RecurringRule in = mapper.readValue(json, RecurringRule.class);
            String ver = clock.tick();
            RecurringRule r = new RecurringRule(
                    (in.id() == null || in.id().isEmpty()) ? java.util.UUID.randomUUID().toString() : in.id(),
                    in.name(), in.period(), in.day(), in.weekday(), in.month(),
                    in.amount(), in.currency(), in.category(), in.note(),
                    true, false, ver, ss.userId
            );
            repo.upsertRecurring(r);
            p2p.broadcast(new Op(Op.Type.RECUR_UPSERT, r));
            pushRecurring();
        } catch (Exception e) {
            System.err.println("Failed to upsert recurring: " + e.getMessage());
        }
    }

    public void deleteRecurring(String id) {
        try {
            String ver = clock.tick();
            RecurringRule tomb = new RecurringRule(id, "", "", 0, 0, 0, java.math.BigDecimal.ZERO, "", "", "", false, true, ver, ss.userId);
            repo.tombstoneRecurring(id, ver, ss.userId);
            p2p.broadcast(new Op(Op.Type.RECUR_DELETE, tomb));
            pushRecurring();
        } catch (Exception e) {
            System.err.println("Failed to delete recurring: " + e.getMessage());
        }
    }

}