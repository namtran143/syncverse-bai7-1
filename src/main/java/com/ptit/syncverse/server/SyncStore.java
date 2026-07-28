package com.ptit.syncverse.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SyncStore {
    private final Path dataDirectory;
    private final Path journalFile;
    private final AtomicLong version = new AtomicLong();
    private final List<ChangeRecord> journal = new ArrayList<>();
    private final Map<String, ChangeRecord> latestByFile = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SyncStore(Path dataDirectory) throws IOException {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.journalFile = this.dataDirectory.resolve("journal.tsv");
        Files.createDirectories(this.dataDirectory);
        load();
    }

    public long currentVersion() {
        return version.get();
    }

    public ChangeRecord append(String operation, String fileName, String contentBase64, String sourceClient) throws IOException {
        validateFileName(fileName);
        long nextVersion = version.incrementAndGet();
        ChangeRecord record = new ChangeRecord(
                nextVersion,
                operation,
                fileName,
                contentBase64 == null ? "" : contentBase64,
                sourceClient,
                System.currentTimeMillis()
        );
        lock.writeLock().lock();
        try {
            journal.add(record);
            latestByFile.put(fileName, record);
            Files.writeString(
                    journalFile,
                    encode(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } finally {
            lock.writeLock().unlock();
        }
        return record;
    }

    public List<ChangeRecord> changesAfter(long sinceVersion) {
        lock.readLock().lock();
        try {
            return journal.stream()
                    .filter(change -> change.version() > sinceVersion)
                    .sorted(Comparator.comparingLong(ChangeRecord::version))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void load() throws IOException {
        if (!Files.exists(journalFile)) {
            return;
        }
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            ChangeRecord record = decode(line);
            journal.add(record);
            latestByFile.put(record.fileName(), record);
            version.updateAndGet(current -> Math.max(current, record.version()));
        }
    }

    private static String encode(ChangeRecord record) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return record.version() + "\t"
                + record.timestamp() + "\t"
                + encoder.encodeToString(record.operation().getBytes(StandardCharsets.UTF_8)) + "\t"
                + encoder.encodeToString(record.fileName().getBytes(StandardCharsets.UTF_8)) + "\t"
                + encoder.encodeToString(record.sourceClient().getBytes(StandardCharsets.UTF_8)) + "\t"
                + record.contentBase64();
    }

    private static ChangeRecord decode(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid journal line");
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new ChangeRecord(
                Long.parseLong(parts[0]),
                new String(decoder.decode(parts[2]), StandardCharsets.UTF_8),
                new String(decoder.decode(parts[3]), StandardCharsets.UTF_8),
                parts[5],
                new String(decoder.decode(parts[4]), StandardCharsets.UTF_8),
                Long.parseLong(parts[1])
        );
    }

    private static void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Only flat-directory file names are allowed");
        }
    }
}
