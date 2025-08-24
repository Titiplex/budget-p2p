
package com.titiplex.budget.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.model.Expense;
import com.titiplex.budget.core.model.Op;
import com.titiplex.budget.core.p2p.P2PService;
import com.titiplex.budget.core.store.Repository;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class MainController {

    private final Repository repo;
    private final P2PService p2p;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.group.name:default-budget}")
    private String groupName;

    @Value("${app.group.passphrase:change-me}")
    private String passphrase;

    public MainController(Repository repo, P2PService p2p) {
        this.repo = repo;
        this.p2p = p2p;
    }

    @FXML
    private WebView webview;

    @FXML
    public void initialize() {
        p2p.start(groupName, passphrase, this::onRemoteOp);

        WebEngine engine = webview.getEngine();
        engine.load(Objects.requireNonNull(getClass().getResource("/web/index.html")).toExternalForm());

        engine.getLoadWorker().stateProperty().addListener((obs, old, st) -> {
            if (st.toString().equals("SUCCEEDED")) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("bridge", new JsBridge(this));
                pushAll();
            }
        });
    }

    void onRemoteOp(Op op) {
        switch (op.type()) {
            case ADD -> {
                repo.saveExpense((Expense) op.payload());
                pushAll();
            }
            case DELETE -> {
                Expense e = (Expense) op.payload();
                repo.deleteExpense(e.id());
                pushAll();
            }
        }
    }

    public void pushAll() {
        List<Expense> all = repo.listExpenses();
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
            System.err.println("Failed to push expenses to webview" + e.getMessage());
        }
    }

    public void addExpenseFromJson(String json) {
        try {
            Expense e = mapper.readValue(json, Expense.class);
            if (e.id() == null || e.id().isEmpty()) {
                e = new Expense(UUID.randomUUID().toString(), e.who(), e.category(), e.amount(),
                        e.currency(), e.note(), Instant.now().toEpochMilli(), false);
            }
            repo.saveExpense(e);
            p2p.broadcast(new Op(Op.Type.ADD, e));
            pushAll();
        } catch (Exception ex) {
            System.err.println("Failed to add expense from webview: " + ex.getMessage());
        }
    }

    public void deleteExpense(String id) {
        Expense e = repo.findById(id);
        if (e != null) {
            repo.deleteExpense(id);
            p2p.broadcast(new Op(Op.Type.DELETE, e));
            pushAll();
        }
    }
}
