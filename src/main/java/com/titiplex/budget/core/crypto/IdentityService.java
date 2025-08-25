package com.titiplex.budget.core.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityService {
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.data.dir}")
    private String dataDir;

    public void ensureIdentity(SessionState ss, String displayName) {
        try {
            Path dir = Path.of(dataDir, "identity");
            Files.createDirectories(dir);
            Path idFile = dir.resolve("me.json");
            if (Files.exists(idFile)) {
                Map m = mapper.readValue(Files.readAllBytes(idFile), Map.class);
                ss.displayName = (String) m.getOrDefault("displayName", displayName);
                ss.userId = (String) m.get("userId");
                ss.ed25519Public = Base64.getDecoder().decode((String) m.get("pub"));
                ss.ed25519Private = Base64.getDecoder().decode((String) m.get("priv"));
            } else {
                // Generate Ed25519
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
                kpg.initialize(255, new SecureRandom());
                KeyPair kp = kpg.generateKeyPair();
                ss.userId = UUID.randomUUID().toString();
                ss.displayName = displayName;
                ss.ed25519Public = kp.getPublic().getEncoded();
                ss.ed25519Private = kp.getPrivate().getEncoded();
                Map<String, Object> out = new HashMap<>();
                out.put("userId", ss.userId);
                out.put("displayName", ss.displayName);
                out.put("pub", Base64.getEncoder().encodeToString(ss.ed25519Public));
                out.put("priv", Base64.getEncoder().encodeToString(ss.ed25519Private));
                Files.writeString(idFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}