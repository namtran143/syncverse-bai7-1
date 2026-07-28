package com.ptit.syncverse.common;

public final class Protocol {
    public static final String HELLO = "HELLO";
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String FILE_CHANGE = "FILE_CHANGE";
    public static final String RECONNECT = "RECONNECT";
    public static final String DELTA_REQUEST = "DELTA_REQUEST";

    private Protocol() {
    }
}
