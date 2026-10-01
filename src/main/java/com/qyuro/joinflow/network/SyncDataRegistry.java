package com.qyuro.joinflow.network;

import com.qyuro.joinflow.config.ServerConfig;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Small registry of opt-in static data. Mutable Minecraft objects are never accepted or retained. */
public final class SyncDataRegistry {
    private static final int MAX_ENTRIES = 4096;
    private static final Map<String, StaticData> DATA = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_BYTES = new AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger TOTAL_ENTRIES = new java.util.concurrent.atomic.AtomicInteger();

    private SyncDataRegistry() {
    }

    public static boolean register(String owner, String key, int version, byte[] payload, boolean cacheable) {
        if (payload.length > ServerConfig.maxStaticSyncBytes() || DATA.containsKey(key)) return false;
        StaticData value = new StaticData(owner, key, version, payload, cacheable, sha256(payload));
        int entries = TOTAL_ENTRIES.incrementAndGet();
        if (entries > MAX_ENTRIES) {
            TOTAL_ENTRIES.decrementAndGet();
            return false;
        }
        long total = TOTAL_BYTES.addAndGet(payload.length);
        if (total > ServerConfig.maxStaticSyncBytes()) {
            TOTAL_BYTES.addAndGet(-payload.length);
            TOTAL_ENTRIES.decrementAndGet();
            return false;
        }
        if (DATA.putIfAbsent(key, value) != null) {
            TOTAL_BYTES.addAndGet(-payload.length);
            TOTAL_ENTRIES.decrementAndGet();
            return false;
        }
        return true;
    }

    public static boolean unregister(String owner, String key) {
        StaticData current = DATA.get(key);
        if (current == null || !current.owner().equals(owner)) return false;
        if (DATA.remove(key, current)) {
            TOTAL_BYTES.addAndGet(-current.sizeBytes());
            return true;
        }
        return false;
    }

    public static StaticData get(String key) { return DATA.get(key); }
    public static int size() { return DATA.size(); }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public static final class StaticData {
        private final String owner;
        private final String key;
        private final int version;
        private final byte[] payload;
        private final boolean cacheable;
        private final String hash;

        private StaticData(String owner, String key, int version, byte[] payload, boolean cacheable, String hash) {
            this.owner = owner;
            this.key = key;
            this.version = version;
            this.payload = payload.clone();
            this.cacheable = cacheable;
            this.hash = hash;
        }

        public String owner() { return owner; }
        public String key() { return key; }
        public int version() { return version; }
        public int sizeBytes() { return payload.length; }
        public boolean cacheable() { return cacheable; }
        public String hash() { return hash; }
        public byte[] copyPayload() { return payload.clone(); }
    }
}
