package com.titiplex.budget.core.p2p;

import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.net.StunClient;
import com.titiplex.budget.core.net.UPnPService;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

public class WanDiscoveryService {
    private final SessionState ss;
    private final UPnPService upnp = new UPnPService();
    private final StunClient stun = new StunClient();
    private InetSocketAddress publicSock;

    public WanDiscoveryService(SessionState ss) {
        this.ss = ss;
    }

    /**
     * Appel au démarrage de l'app (une fois)
     */
    public void start(int port) {
        // 1) UPnP si possible (TCP & UDP)
        upnp.tryMap(port, "TCP");
        upnp.tryMap(port, "UDP");

        // 2) STUN pour connaître l'endpoint public
        Optional<InetSocketAddress> ext = stun.queryPublicAddress(port, Duration.ofSeconds(2));
        ext.ifPresent(sock -> publicSock = sock);
    }

    public boolean hasPublic() {
        return publicSock != null;
    }

    public InetSocketAddress publicSocket() {
        return publicSock;
    }

    /**
     * Génére un code d'invitation signé (pas d'IP à saisir côté joiner)
     */
    public String generateInvite() {
        if (publicSock == null) throw new IllegalStateException("No public address (UPnP/STUN failed)");
        return InviteCodec.create(ss.groupId, ss.groupPass, publicSock);
    }

    /**
     * Parse et retourne (gid, host, port) après vérif HMAC/expiration
     */
    public InviteCodec.Parsed parse(String code) {
        return InviteCodec.parseAndVerify(code, ss.groupPass);
    }
}