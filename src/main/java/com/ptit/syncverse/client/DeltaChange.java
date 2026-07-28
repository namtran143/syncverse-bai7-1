package com.ptit.syncverse.client;

public record DeltaChange(
        long version,
        long timestamp,
        String operation,
        String fileName,
        String sourceClient,
        String contentBase64
) {
}
