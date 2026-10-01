package com.qyuro.joinflow.server;

import com.qyuro.joinflow.config.ServerConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded registry of active JoinFlow post-login sessions, not a replacement for Forge's login queue. */
public final class JoinQueue {
    private final Map<UUID, JoinSession> sessions = new LinkedHashMap<>();

    public JoinSession start(UUID playerId, long nowNanos) {
        JoinSession existing = sessions.get(playerId);
        if (existing != null) return existing;
        if (sessions.size() >= ServerConfig.maxQueueSize()) return null;
        JoinSession session = new JoinSession(playerId, nowNanos);
        sessions.put(playerId, session);
        return session;
    }

    public JoinSession get(UUID playerId) { return sessions.get(playerId); }

    public JoinSession remove(UUID playerId) { return sessions.remove(playerId); }

    public void complete(UUID playerId) {
        JoinSession session = sessions.remove(playerId);
        if (session != null) session.markComplete();
    }

    public int size() { return sessions.size(); }

    public int queuedSessionCount() {
        int count = 0;
        for (JoinSession session : sessions.values()) {
            if (session.tasksPending() > 0) count++;
        }
        return count;
    }

    public Collection<JoinSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public Map<UUID, JoinSession> snapshot() {
        return Map.copyOf(sessions);
    }

    public void cancelAll() {
        for (JoinSession session : sessions.values()) session.cancel();
        sessions.clear();
    }
}
