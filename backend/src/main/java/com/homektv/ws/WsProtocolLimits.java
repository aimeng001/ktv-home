package com.homektv.ws;

/** Shared wire and queue limits used by the server-side snapshot protocol. */
public final class WsProtocolLimits {
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final int MAX_QUEUE_ITEMS = 1_000;
    public static final int MAX_CHUNK_ENTRIES = 100;
    public static final int MAX_SYNC_CHUNKS = 64;
    public static final long MAX_SNAPSHOT_BYTES = 16L * MAX_MESSAGE_BYTES;

    private WsProtocolLimits() { }
}
