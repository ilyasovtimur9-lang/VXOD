package com.qyuro.joinflow.network;

import java.util.Arrays;

/** One bounded, versioned piece of an opt-in static-data transfer. */
public final class SyncBatch {
    private final int batchId;
    private final int sequence;
    private final int total;
    private final String key;
    private final int dataVersion;
    private final String contentHash;
    private final int checksum;
    private final boolean cacheable;
    private final byte[] payload;

    public SyncBatch(int batchId, int sequence, int total, String key, int dataVersion,
                     String contentHash, int checksum, boolean cacheable, byte[] payload) {
        this.batchId = batchId;
        this.sequence = sequence;
        this.total = total;
        this.key = key;
        this.dataVersion = dataVersion;
        this.contentHash = contentHash;
        this.checksum = checksum;
        this.cacheable = cacheable;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public int batchId() { return batchId; }
    public int sequence() { return sequence; }
    public int total() { return total; }
    public String key() { return key; }
    public int dataVersion() { return dataVersion; }
    public String contentHash() { return contentHash; }
    public int checksum() { return checksum; }
    public boolean cacheable() { return cacheable; }
    public byte[] copyPayload() { return Arrays.copyOf(payload, payload.length); }
    public int payloadBytes() { return payload.length; }
}
