package com.titiplex.budget.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.config.ConfigService;
import com.titiplex.budget.core.crypto.IdentityService;
import com.titiplex.budget.core.crypto.SessionState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class LoginController {

    private final ApplicationContext ctx;
    private final IdentityService idSvc;
    private final SessionState ss;
    private final ConfigService config;
    @FXML
    private TextField inviteField;
    @FXML
    private Label inviteStatus;
    private final ObjectMapper mapper = new ObjectMapper();


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
                System.out.println("[login] auto-restore ok: gid=" + ss.groupId + ", seeds=" + ss.seeds.size());
                // On a déjà displayName/userId via IdentityService; s'il n'existe pas, on le crée ici
                idSvc.ensureIdentity(ss, ss.displayName != null ? ss.displayName : "Me");
                goMain();
            } else {
                System.out.println("[login] pas d'auto-restore (reset=" + reset + ")");
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

    @FXML
    public void onPasteInvite() {
        String s = Clipboard.getSystemClipboard().getString();
        if (s != null && !s.isBlank()) {
            inviteField.setText(s.trim());
            inviteStatus.setText("Lien collé depuis le presse-papiers.");
        } else {
            inviteStatus.setText("Presse-papiers vide.");
        }
    }

    @FXML
    public void onJoinWithLink() {
        String link = (inviteField.getText() == null) ? "" : inviteField.getText().trim();
        if (link.isEmpty()) {
            inviteStatus.setText("Collez un lien d’invitation.");
            return;
        }

        // 1) essaie le format JSON: budgetp2p://join#<b64url(Json)>
        if (tryJoinWithJsonInvite(link)) return;

        // 2) sinon, essaie ton format HMAC: BUDP2P1.<payload>.<sig>
        if (tryJoinWithBUDP2P1(link)) return;

        inviteStatus.setText("Format de lien inconnu.");
    }

    @SuppressWarnings("unchecked")
    private boolean tryJoinWithJsonInvite(String link) {
        try {
            String token = link;
            int pos = token.indexOf('#');
            if (token.startsWith("budgetp2p://") && pos >= 0) token = token.substring(pos + 1);
            else return false; // pas le bon schéma

            String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            Map<String, Object> p = mapper.readValue(json, Map.class);
            String gid = (String) p.get("gid");
            String gp = (String) p.get("gp"); // mot de passe du compte
            List<String> seeds = (List<String>) p.getOrDefault("seeds", List.of());

            if (gid == null || gp == null) {
                inviteStatus.setText("Lien invalide (gid/gp manquant).");
                return true; // c’était bien un budgetp2p://, mais invalide
            }

            // Remplit l’UI pour que l’utilisateur voie ce qu’il va rejoindre
            groupIdField.setText(gid);
            passField.setText(gp);

            // Prépare la session
            String name = (nameField.getText() == null || nameField.getText().isBlank()) ? "Me" : nameField.getText().trim();
            idSvc.ensureIdentity(ss, name);
            ss.groupId = gid;
            ss.groupPass = gp;
            ss.port = Integer.parseInt(portField.getText().trim());
            ss.seeds = normalizeSeeds(seeds);

            config.saveLastSession(ss);
            inviteStatus.setText("Lien accepté. Connexion…");
            goMain();
            return true;
        } catch (IllegalArgumentException iae) {
            inviteStatus.setText("Lien JSON invalide : " + iae.getMessage());
            return true; // on a reconnu le schéma mais c’était invalide
        } catch (Exception e) {
            return false; // pas ce format
        }
    }

    private boolean tryJoinWithBUDP2P1(String code) {
        try {
            // Ton InviteCodec HMAC exige le mot de passe saisi dans le champ
            String pass = passField.getText();
            if (pass == null || pass.isBlank()) {
                inviteStatus.setText("Pour un code BUDP2P1, saisis d’abord le mot de passe du compte.");
                return true; // c’était le bon format mais prérequis manquant
            }
            var parsed = com.titiplex.budget.core.p2p.InviteCodec.parseAndVerify(code, pass);
            // Remplit l’UI pour info
            groupIdField.setText(parsed.gid());
            // Prépare la session
            String name = (nameField.getText() == null || nameField.getText().isBlank()) ? "Me" : nameField.getText().trim();
            idSvc.ensureIdentity(ss, name);
            ss.groupId = parsed.gid();
            ss.groupPass = pass;
            ss.port = Integer.parseInt(portField.getText().trim());
            ss.seeds = new ArrayList<>();
            ss.seeds.add(parsed.host() + "[" + parsed.port() + "]");

            config.saveLastSession(ss);
            inviteStatus.setText("Code validé. Connexion…");
            goMain();
            return true;
        } catch (IllegalArgumentException iae) {
            inviteStatus.setText("Code BUDP2P1 refusé : " + iae.getMessage());
            return true; // on a reconnu le format mais signature/exp invalide
        } catch (Exception e) {
            return false; // pas ce format
        }
    }

    private List<String> normalizeSeeds(List<String> seeds) {
        List<String> out = new ArrayList<>();
        if (seeds == null) return out;
        for (String s : seeds) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim();
            if (t.contains("[")) {
                out.add(t);
                continue;
            }
            String host = t, port = "7800";
            if (t.contains(":")) {
                String[] sp = t.split(":");
                host = sp[0];
                port = sp[1];
            }
            out.add(host + "[" + port + "]");
        }
        return out;
    }

}
