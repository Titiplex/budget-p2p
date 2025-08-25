package com.titiplex.budget.core.p2p;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.crypto.CryptoBox;
import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.model.Op;
import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

@Service
public class JGroupsP2PService extends ReceiverAdapter implements P2PService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionState ss;
    private JChannel ch;
    private String passphrase;
    private Consumer<Op> onOp;

    public JGroupsP2PService(SessionState ss) {
        this.ss = ss;
    }

    @Override
    public void start(String groupName, String passphrase, List<String> seeds, int port, Consumer<Op> onOp) {
        try {
            this.passphrase = passphrase;
            this.onOp = onOp;

            // Try UPnP mapping (optional, ignore failures)
            tryUpnp(port);

            if (seeds != null && !seeds.isEmpty()) {
                StringJoiner sj = getStringJoiner(seeds, port);
                String xml = ("<config>" +
                        "<TCP bind_port=\"" + port + "\" />" +
                        "<TCPPING initial_hosts=\"" + sj + "\" port_range=\"10\" />" +
                        "<MERGE3/><FD_SOCK/><FD_ALL/><VERIFY_SUSPECT/><BARRIER/>" +
                        "<NAKACK2/><UNICAST3/><STABLE/><GMS/><MFC/><FRAG2/>" +
                        "</config>");
                ch = new JChannel(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            } else {
                // LAN multicast default
                ch = new JChannel(); // default UDP
            }
            ch.setReceiver(this);
            ch.connect(groupName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static StringJoiner getStringJoiner(List<String> seeds, int port) {
        StringJoiner sj = new StringJoiner(",");
        for (String s : seeds) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                if (!trimmed.contains("[")) {
                    // host or host:port -> ensure port present
                    String host = trimmed;
                    int p = port;
                    if (trimmed.contains(":")) {
                        String[] sp = trimmed.split(":");
                        host = sp[0];
                        p = Integer.parseInt(sp[1]);
                    }
                    sj.add(host + "[" + p + "]");
                } else sj.add(trimmed);
            }
        }
        return sj;
    }

    private void tryUpnp(int port) {
        try {
            GatewayDiscover discover = new GatewayDiscover();
            discover.discover();
            GatewayDevice d = discover.getValidGateway();
            if (d != null) {
                d.addPortMapping(port, port, InetAddress.getLocalHost().getHostAddress(), "TCP", "budget-p2p");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void receive(Message msg) {
        try {
            byte[] dec = CryptoBox.decrypt(passphrase, msg.getBuffer());
            String json = new String(dec, StandardCharsets.UTF_8);
            Envelope env = mapper.readValue(json, Envelope.class);
            // Verify signature
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(env.pubKeyB64)));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(env.payloadJson.getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(env.sigB64))) return; // drop
            Op op = mapper.readValue(env.payloadJson, Op.class);
            onOp.accept(op);
        } catch (Exception e) {
            System.err.println("Failed to receive op: " + e.getMessage());
        }
    }

    @Override
    public void broadcast(Op op) {
        try {
            String payload = mapper.writeValueAsString(op);
            // Sign
            Signature sig = Signature.getInstance("Ed25519");
            PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(ss.ed25519Private));
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
            ch.send(m);
        } catch (Exception e) {
            System.err.println("Failed to broadcast op: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (ch != null) ch.close();
    }

    public static class Envelope {
        public String senderId;
        public String senderName;
        public String pubKeyB64;
        public String payloadJson;
        public String sigB64;
    }
}