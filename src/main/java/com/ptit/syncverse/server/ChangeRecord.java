package com.ptit.syncverse.server;

public record ChangeRecord(
        long version,
        String operation,
        String fileName,
        String contentBase64,
        String sourceClient,
        long timestamp
) {
}
