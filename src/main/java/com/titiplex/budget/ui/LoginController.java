package com.titiplex.budget.ui;

import com.titiplex.budget.core.config.ConfigService;
import com.titiplex.budget.core.crypto.IdentityService;
import com.titiplex.budget.core.crypto.SessionState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class LoginController {

    private final ApplicationContext ctx;
    private final IdentityService idSvc;
    private final SessionState ss;
    private final ConfigService config;

    @Value("${app.net.port:7800}")
    private int defaultPort;

    public LoginController(ApplicationContext ctx, IdentityService idSvc, SessionState ss, ConfigService config) {
        this.ctx = ctx;
        this.idSvc = idSvc;
        this.ss = ss;
        this.config = config;
    }

    @FXML
    private TextField nameField;
    @FXML
    private TextField groupIdField;
    @FXML
    private PasswordField passField;
    @FXML
    private TextField seedsField;
    @FXML
    private TextField portField;

    @FXML
    public void initialize() {
        portField.setText(String.valueOf(defaultPort));

        // Auto-login si une config valide existe
        Platform.runLater(() -> {
            boolean reset = Boolean.getBoolean("app.resetLogin"); // optionnel: -Dapp.resetLogin=true
            if (!reset && config.tryRestore(ss)) {
                // On a déjà displayName/userId via IdentityService; s'il n'existe pas, on le crée ici
                idSvc.ensureIdentity(ss, ss.displayName != null ? ss.displayName : "Me");
                goMain();
            }
        });
    }

    @FXML
    public void onJoin() {
        try {
            String name = nameField.getText().trim();
            String gid = groupIdField.getText().trim();
            String pass = passField.getText();
            String seeds = seedsField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());

            if (name.isEmpty() || gid.isEmpty() || pass.isEmpty()) {
                return;
            }

            idSvc.ensureIdentity(ss, name);
            ss.groupId = gid;
            ss.groupPass = pass;
            ss.port = port;
            if (!seeds.isEmpty()) {
                ss.seeds = Arrays.stream(seeds.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toList());
            }

            // Sauvegarde locale -> auto-login au prochain lancement
            config.saveLastSession(ss);

            goMain();
        } catch (Exception e) {
            System.err.println("Failed to join group: " + e.getMessage());
        }
    }

    private void goMain() {
        try {
            Stage stage = (Stage) portField.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            loader.setControllerFactory(ctx::getBean);
            Parent root = loader.load();
            stage.setTitle("Budget P2P - " + ss.groupId);
            stage.setScene(new Scene(root, 1100, 720));
            stage.show();
        } catch (Exception e) {
            System.err.println("Failed to load main: " + e.getMessage());
        }
    }
}
