package com.ptit.syncverse.server;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByClientName = new ConcurrentHashMap<>();

    public Session register(String clientName) {
        String oldToken = tokenByClientName.remove(clientName);
        if (oldToken != null) {
            byToken.remove(oldToken);
        }
        Session session = new Session(clientName, UUID.randomUUID().toString(), Instant.now().toEpochMilli());
        byToken.put(session.token(), session);
        tokenByClientName.put(clientName, session.token());
        return session;
    }

    public Session require(String token) {
        Session session = byToken.get(token);
        if (session == null) {
            throw new IllegalArgumentException("Unknown or expired client session");
        }
        return session;
    }

    public void heartbeat(String token) {
        Session session = require(token);
        session.lastHeartbeat = Instant.now().toEpochMilli();
    }

    public static final class Session {
        private final String clientName;
        private final String token;
        private volatile long lastHeartbeat;

        private Session(String clientName, String token, long lastHeartbeat) {
            this.clientName = clientName;
            this.token = token;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String clientName() {
            return clientName;
        }

        public String token() {
            return token;
        }

        public long lastHeartbeat() {
            return lastHeartbeat;
        }
    }
}
