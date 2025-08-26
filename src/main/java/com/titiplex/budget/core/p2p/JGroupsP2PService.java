package com.titiplex.budget.core.p2p;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.config.ConfigService;
import com.titiplex.budget.core.crypto.CryptoBox;
import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.model.Op;
import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class JGroupsP2PService extends ReceiverAdapter implements P2PService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionState ss;
    private final ConfigService config;

    private final AtomicReference<JChannel> chRef = new AtomicReference<>();
    private String clusterName;        // ex: "budget-<groupId>"
    private String passphrase;         // groupPass
    private Consumer<Op> onOp;

    public JGroupsP2PService(SessionState ss, ConfigService config) {
        this.ss = ss;
        this.config = config;
    }

    // ---------- Public API ----------

    @Override
    public synchronized void start(String groupName, String passphrase, List<String> seeds, int port, Consumer<Op> onOp) {
        startInternal(groupName, passphrase, seeds, port, null, onOp);
    }

    /**
     * Overload si tu veux passer une IP publique explicite (ex: STUN) pour aider JGroups (external_addr).
     */
    public synchronized void start(String groupName, String passphrase, List<String> seeds, int port, String externalAddr, Consumer<Op> onOp) {
        startInternal(groupName, passphrase, seeds, port, externalAddr, onOp);
    }

    @Override
    public synchronized void stop() {
        JChannel ch = chRef.getAndSet(null);
        if (ch != null) ch.close();
    }

    @Override
    public synchronized void broadcast(Op op) {
        try {
            String payload = mapper.writeValueAsString(op);
            sendEncryptedEnvelope(payload);
        } catch (Exception e) {
            System.err.println("Failed to broadcast op: " + e.getMessage());
        }
    }

    /**
     * Ajoute un seed (host,port) et bascule en pile TCP si nécessaire, puis reconnecte.
     */
    public synchronized void addSeedAndReconnect(String host, int port, List<String> existing) {
        try {
            String normalized = host + "[" + port + "]";
            if (existing != null && !existing.contains(normalized)) {
                existing.add(normalized);
                config.saveLastSession(ss); // persiste
            }
            reconnectTcp(existing != null ? existing : List.of(normalized), port, null);
        } catch (Exception e) {
            throw new RuntimeException("reconnect failed: " + e.getMessage(), e);
        }
    }

    // ---------- Receiver ----------

    @Override
    public void receive(Message msg) {
        try {
            byte[] arr = msg.getBuffer();
            String str = new String(arr, java.nio.charset.StandardCharsets.UTF_8);
            if (str.startsWith("{\"t\":\"ANN\"")) {
                int i = str.indexOf("\"s\":\"");
                int j = str.indexOf("\"", i + 5);
                if (i > 0 && j > i) {
                    String seed = str.substring(i + 5, j);       // ex: "93.12.34.56[5000]"
                    if (!ss.seeds.contains(seed)) {
                        ss.seeds.add(seed);
                        config.saveLastSession(ss);
                    }
                }
                return; // ne pas traiter comme Op applicatif
            }
            byte[] dec = CryptoBox.decrypt(passphrase, msg.getBuffer());
            String json = new String(dec, StandardCharsets.UTF_8);
            Envelope env = mapper.readValue(json, Envelope.class);

            // Vérif signature
            PublicKey pub = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(env.pubKeyB64)));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(env.payloadJson.getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(env.sigB64))) return; // drop silently

            // Route selon payload
            JsonNode node = mapper.readTree(env.payloadJson);
            if (node.has("t") && "ANN".equals(node.get("t").asText())) {
                // Gossip seed appris
                if (node.has("s")) {
                    String seed = node.get("s").asText();
                    if (seed != null && !seed.isBlank()) {
                        if (!ss.seeds.contains(seed)) {
                            ss.seeds.add(seed);
                            config.saveLastSession(ss); // persiste l’apprentissage
                        }
                    }
                }
                return;
            }

            // Sinon, c’est une Op applicative
            Op op = mapper.readValue(env.payloadJson, Op.class);
            if (onOp != null) onOp.accept(op);

        } catch (Exception e) {
            System.err.println("Failed to receive: " + e.getMessage());
        }
    }

    @Override
    public void viewAccepted(View view) {
        // tu peux logger si besoin
        // System.out.println("P2P view: " + view);
    }

    // ---------- Impl interne ----------

    private void startInternal(String groupName, String passphrase, List<String> seeds, int port, String externalAddr, Consumer<Op> onOp) {
        try {
            this.clusterName = groupName;
            this.passphrase = passphrase;
            this.onOp = onOp;

            // UPnP best-effort (TCP + UDP)
            tryUpnp(port);

            // Choix de la pile
            final boolean wan = seeds != null && !seeds.isEmpty();
            final String cfg = wan ? tcpStack(normalizeSeeds(seeds, port), port, externalAddr)
                    : udpStack(port);

            JChannel ch = new JChannel(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            ch.setReceiver(this);
            ch.connect(groupName);
            chRef.set(ch);
        } catch (Exception e) {
            throw new RuntimeException("P2P start failed: " + e.getMessage(), e);
        }
    }

    private void reconnectTcp(List<String> seeds, int bindPort, String externalAddr) throws Exception {
        JChannel old = chRef.getAndSet(null);
        if (old != null) old.close();

        String cfg = tcpStack(normalizeSeeds(seeds, bindPort), bindPort, externalAddr);
        JChannel ch = new JChannel(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        ch.setReceiver(this);
        ch.connect(clusterName);
        chRef.set(ch);
    }

    private void sendEncryptedEnvelope(String payload) throws Exception {
        // Sign
        Signature sig = Signature.getInstance("Ed25519");
        PrivateKey priv = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(ss.ed25519Private));
        sig.initSign(priv);
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        String sigB64 = Base64.getEncoder().encodeToString(sig.sign());
        String pubB64 = Base64.getEncoder().encodeToString(ss.ed25519Public);

        Envelope env = new Envelope();
        env.senderId = ss.userId;
        env.senderName = ss.displayName;
        env.pubKeyB64 = pubB64;
        env.payloadJson = payload;
        env.sigB64 = sigB64;

        String json = mapper.writeValueAsString(env);
        byte[] enc = CryptoBox.encrypt(passphrase, json.getBytes(StandardCharsets.UTF_8));
        Message m = new Message(null, enc);
        JChannel ch = chRef.get();
        if (ch != null && ch.isConnected()) ch.send(m);
    }

    // --- Stacks JGroups ---
    private String udpStack(int bindPort) {
        return """
                <config>
                  <org.jgroups.protocols.UDP bind_port="%PORT%" mcast_port="45588" ip_ttl="2"/>
                  <org.jgroups.protocols.PING/>
                  <org.jgroups.protocols.MERGE3 min_interval="10000" max_interval="30000"/>
                  <org.jgroups.protocols.FD_SOCK/>
                  <org.jgroups.protocols.FD_ALL interval="3000" timeout="12000"/>
                  <org.jgroups.protocols.VERIFY_SUSPECT timeout="1500"/>
                  <org.jgroups.protocols.BARRIER/>
                  <org.jgroups.protocols.pbcast.NAKACK2 use_mcast_xmit="true"/>
                  <org.jgroups.protocols.UNICAST3/>
                  <org.jgroups.protocols.pbcast.STABLE/>
                  <org.jgroups.protocols.pbcast.GMS join_timeout="5000" print_local_addr="true"/>
                  <org.jgroups.protocols.UFC/>
                  <org.jgroups.protocols.MFC/>
                  <org.jgroups.protocols.FRAG2/>
                </config>
                """.replace("%PORT%", Integer.toString(bindPort));
    }

    /**
     * seeds au format "host[port],host[port]" ; externalAddr peut aider JGroups derrière certains NAT.
     */
    private String tcpStack(java.util.List<String> seeds, int bindPort, String externalAddr) {
        String initial = String.join(",", seeds); // ex: "1.2.3.4[7800],example.com[7800]"
        return """
                <config>
                  <org.jgroups.protocols.TCP bind_port="%PORT%" %EXTERNAL%/>
                  <org.jgroups.protocols.TCPPING timeout="2000" initial_hosts="%SEEDS%" port_range="2"/>
                  <org.jgroups.protocols.MERGE3 min_interval="10000" max_interval="30000"/>
                  <org.jgroups.protocols.FD_SOCK/>
                  <org.jgroups.protocols.FD_ALL interval="3000" timeout="12000"/>
                  <org.jgroups.protocols.VERIFY_SUSPECT timeout="1500"/>
                  <org.jgroups.protocols.BARRIER/>
                  <org.jgroups.protocols.pbcast.NAKACK2 use_mcast_xmit="false"/>
                  <org.jgroups.protocols.UNICAST3/>
                  <org.jgroups.protocols.pbcast.STABLE/>
                  <org.jgroups.protocols.pbcast.GMS join_timeout="5000" print_local_addr="true"/>
                  <org.jgroups.protocols.UFC/>
                  <org.jgroups.protocols.MFC/>
                  <org.jgroups.protocols.FRAG2/>
                </config>
                """
                .replace("%PORT%", Integer.toString(bindPort))
                .replace("%SEEDS%", initial)
                .replace("%EXTERNAL%", externalAddr == null ? "" : ("external_addr=\"" + externalAddr + "\""));
    }

    private List<String> normalizeSeeds(List<String> seeds, int defaultPort) {
        if (seeds == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : seeds) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (t.contains("[")) {
                out.add(t);
                continue;
            }
            String host = t;
            int p = defaultPort;
            if (t.contains(":")) {
                String[] sp = t.split(":");
                host = sp[0];
                p = Integer.parseInt(sp[1]);
            }
            out.add(host + "[" + p + "]");
        }
        return out;
    }

    private void tryUpnp(int port) {
        try {
            GatewayDiscover discover = new GatewayDiscover();
            discover.discover();
            GatewayDevice d = discover.getValidGateway();
            if (d != null) {
                String lan = InetAddress.getLocalHost().getHostAddress();
                // Nettoyage best-effort
                try {
                    d.deletePortMapping(port, "TCP");
                } catch (Exception ignore) {
                }
                try {
                    d.deletePortMapping(port, "UDP");
                } catch (Exception ignore) {
                }
                // Mappage
                d.addPortMapping(port, port, lan, "TCP", "budget-p2p");
                d.addPortMapping(port, port, lan, "UDP", "budget-p2p");
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- Envelope ----------

    public static class Envelope {
        public String senderId;
        public String senderName;
        public String pubKeyB64;
        public String payloadJson; // soit Op JSON, soit CTRL {"t":"ANN","s":"host[port]"}
        public String sigB64;
    }

    /**
     * Gossip : annonce ton endpoint sous forme seed "host[port]" (chiffré/signé). Appelle ceci après connexion.
     */
    public void announceSelfSeed(String host, int port) {
        try {
            var ch = chRef.get();
            if (ch == null) return;
            String msg = "{\"t\":\"ANN\",\"s\":\"" + host + "[" + port + "]\"}";
            ch.send(null, msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }
    }

    public boolean isConnected() {
        JChannel ch = chRef.get();
        return ch != null && ch.isConnected();
    }
}