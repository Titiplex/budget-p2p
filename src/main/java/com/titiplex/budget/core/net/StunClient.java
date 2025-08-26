package com.titiplex.budget.core.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

public class StunClient {
    private static final int STUN_BINDING_REQUEST = 0x0001;
    private static final int XOR_MAPPED_ADDRESS = 0x0020;
    private static final int MAGIC_COOKIE = 0x2112A442;

    // Liste de STUN publics; pas de stockage, juste echo
    private static final String[] STUN_HOSTS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302"
    };

    public Optional<InetSocketAddress> queryPublicAddress(int localPort, Duration timeout) {
        for (String h : STUN_HOSTS) {
            String[] hp = h.split(":");
            try {
                InetSocketAddress stun = new InetSocketAddress(hp[0], Integer.parseInt(hp[1]));
                Optional<InetSocketAddress> r = ask(stun, localPort, timeout);
                if (r.isPresent()) return r;
            } catch (Exception ignore) {
            }
        }
        return Optional.empty();
    }

    private Optional<InetSocketAddress> ask(InetSocketAddress stun, int localPort, Duration timeout) throws Exception {
        try (DatagramChannel ch = DatagramChannel.open()) {
            ch.bind(new InetSocketAddress(InetAddress.getLocalHost(), localPort));
            ch.configureBlocking(false);
            byte[] txid = new byte[12];
            new SecureRandom().nextBytes(txid);
            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) STUN_BINDING_REQUEST);
            req.putShort((short) 0);
            req.putInt(MAGIC_COOKIE);
            req.put(txid);
            req.flip();
            ch.send(req, stun);

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            ByteBuffer buf = ByteBuffer.allocate(1024);
            SocketAddress src;
            while (System.currentTimeMillis() < deadline) {
                buf.clear();
                src = ch.receive(buf);
                if (src == null) {
                    Thread.sleep(10);
                    continue;
                }
                buf.flip();
                if (buf.remaining() < 20) continue;

                buf.getShort(); // type
                int len = buf.getShort() & 0xFFFF;
                int cookie = buf.getInt();
                byte[] rxid = new byte[12];
                buf.get(rxid);
                if (cookie != MAGIC_COOKIE) continue;

                int read = 0;
                while (read < len && buf.remaining() >= 4) {
                    int at = buf.getShort() & 0xFFFF;
                    int alen = buf.getShort() & 0xFFFF;
                    if (at == XOR_MAPPED_ADDRESS) {
                        if (alen < 4 || buf.remaining() < alen) break;
                        int pos0 = buf.position();
                        buf.get(); // reserved
                        int family = buf.get() & 0xFF;
                        int xport = buf.getShort() & 0xFFFF;
                        xport ^= (MAGIC_COOKIE >>> 16);
                        if (family == 0x01 && alen >= 8) { // IPv4
                            int xip = buf.getInt() ^ MAGIC_COOKIE;
                            byte[] ip = new byte[]{
                                    (byte) ((xip >>> 24) & 0xFF),
                                    (byte) ((xip >>> 16) & 0xFF),
                                    (byte) ((xip >>> 8) & 0xFF),
                                    (byte) (xip & 0xFF)
                            };
                            return Optional.of(new InetSocketAddress(InetAddress.getByAddress(ip), xport));
                        } else {
                            // skip value
                            buf.position(pos0 + alen);
                        }
                    } else {
                        // skip value (4-byte align)
                        int skip = (alen + 3) & ~3;
                        buf.position(buf.position() + skip);
                    }
                    read += 4 + ((alen + 3) & ~3);
                }
            }
            return Optional.empty();
        }
    }
}
