package com.titiplex.budget.core.p2p;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

public class InviteCodec {
    // format : BUDP2P1.<base64url(payload)>.<base64url(sig)>
    // payload = gid|host|port|expEpochSec
    public static String create(String gid, String groupPass, InetSocketAddress pub) {
        long exp = Instant.now().plusSeconds(3600).getEpochSecond(); // 1h
        String payload = gid + "|" + pub.getHostString() + "|" + pub.getPort() + "|" + exp;
        byte[] sig = hmac(groupPass, payload);
        return "BUDP2P1." + b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(sig);
    }

    public record Parsed(String gid, String host, int port) {
    }

    public static Parsed parseAndVerify(String code, String groupPass) {
        if (code == null) throw new IllegalArgumentException("empty invite");
        String[] parts = code.trim().split("\\.");
        if (parts.length != 3 || !parts[0].equals("BUDP2P1"))
            throw new IllegalArgumentException("bad format");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        if (!MessageDigest.isEqual(sig, hmac(groupPass, payload)))
            throw new IllegalArgumentException("bad signature");
        String[] fields = payload.split("\\|");
        if (fields.length != 4) throw new IllegalArgumentException("bad payload");
        long exp = Long.parseLong(fields[3]);
        if (Instant.now().getEpochSecond() > exp) throw new IllegalArgumentException("invite expired");
        return new Parsed(fields[0], fields[1], Integer.parseInt(fields[2]));
    }

    private static byte[] hmac(String pass, String msg) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(pass.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}