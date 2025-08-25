package com.titiplex.budget.core.crypto;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionState {
    public String displayName;
    public String userId;
    public byte[] ed25519Private; // raw 32 or 48 bytes depending on impl
    public byte[] ed25519Public;
    public String groupId;
    public String groupPass;
    public List<String> seeds; // host:port strings
    public int port;
}