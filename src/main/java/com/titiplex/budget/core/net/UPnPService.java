package com.titiplex.budget.core.net;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;

import java.net.InetAddress;
import java.util.Optional;

public class UPnPService {
    private GatewayDevice dev;

    public boolean tryMap(int internalPort, String proto) {
        try {
            if (dev == null) {
                GatewayDiscover discover = new GatewayDiscover();
                discover.discover();
                dev = discover.getValidGateway();
                if (dev == null) return false;
            }
            String localHost = InetAddress.getLocalHost().getHostAddress();
            int externalPort = internalPort; // même port ext
            // Supprime l’ancienne règle si elle existe
            try {
                dev.deletePortMapping(externalPort, proto);
            } catch (Exception ignore) {
            }
            // Ajoute
            return dev.addPortMapping(externalPort, internalPort, localHost, proto, "budgetp2p-" + proto);
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<String> getExternalIPAddress() {
        try {
            if (dev == null) return Optional.empty();
            return Optional.ofNullable(dev.getExternalIPAddress());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}