package com.titiplex.budget.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.crypto.LocalSecrets;
import com.titiplex.budget.core.crypto.SessionState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConfigService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalSecrets lockbox;
    private final Path cfgPath;

    public ConfigService(LocalSecrets lockbox, @Value("${app.data.dir}") String dataDir) {
        this.lockbox = lockbox;
        this.cfgPath = Paths.get(dataDir).resolve("config.json");
    }

    /**
     * Sauvegarde la dernière session localement (mdp chiffré).
     */
    public void saveLastSession(SessionState ss) {
        try {
            Map<String, Object> last = new HashMap<>();
            last.put("displayName", ss.displayName);
            last.put("groupId", ss.groupId);
            last.put("port", ss.port);
            last.put("seeds", ss.seeds);
            last.put("encGroupPass", lockbox.seal(ss.groupPass));

            Map<String, Object> root = new HashMap<>();
            root.put("lastSession", last);

            Files.createDirectories(cfgPath.getParent());
            Files.writeString(cfgPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tente de restaurer la session ; renvoie true si OK et remplit SessionState.
     */
    @SuppressWarnings("unchecked")
    public boolean tryRestore(SessionState ss) {
        try {
            if (!Files.exists(cfgPath)) return false;
            Map<String, Object> root = mapper.readValue(Files.readAllBytes(cfgPath), Map.class);
            Object ls = root.get("lastSession");
            if (!(ls instanceof Map)) return false;
            Map<String, Object> last = (Map<String, Object>) ls;

            ss.displayName = (String) last.get("displayName");
            ss.groupId = (String) last.get("groupId");

            Object port = last.get("port");
            ss.port = (port instanceof Number) ? ((Number) port).intValue() : 7800;

            List<String> seeds = (List<String>) last.get("seeds");
            ss.seeds = (seeds != null) ? seeds : new java.util.ArrayList<>();

            String enc = (String) last.get("encGroupPass");
            ss.groupPass = (enc != null && !enc.isBlank()) ? lockbox.open(enc) : null;

            boolean ok = ss.displayName != null && ss.groupId != null && ss.groupPass != null;
            if (!ok) {
                System.out.println("[config] tryRestore incomplet: "
                        + "name=" + ss.displayName + ", gid=" + ss.groupId + ", pass?=" + (ss.groupPass != null)
                        + ", seeds=" + ss.seeds.size());
            }
            return ok;
        } catch (Exception e) {
            System.out.println("[config] tryRestore exception: " + e.getMessage());
            return false;
        }
    }

    public void clear() {
        try {
            if (Files.exists(cfgPath)) Files.delete(cfgPath);
        } catch (Exception ignored) {
        }
    }
}