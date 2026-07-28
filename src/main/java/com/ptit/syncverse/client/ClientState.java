package com.ptit.syncverse.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientState {
    private final Path stateFile;
    private final Properties properties = new Properties();

    public ClientState(String clientName) throws IOException {
        Path directory = Path.of("client-state").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        this.stateFile = directory.resolve(clientName + ".properties");
        if (Files.exists(stateFile)) {
            try (InputStream input = Files.newInputStream(stateFile)) {
                properties.load(input);
            }
        }
    }

    public synchronized long lastVersion() {
        return Long.parseLong(properties.getProperty("lastVersion", "0"));
    }

    public synchronized void setLastVersion(long version) throws IOException {
        properties.setProperty("lastVersion", Long.toString(version));
        save();
    }

    public synchronized String hash(String fileName) {
        return properties.getProperty("hash." + fileName);
    }

    public synchronized void setHash(String fileName, String hash) throws IOException {
        properties.setProperty("hash." + fileName, hash);
        save();
    }

    public synchronized void removeHash(String fileName) throws IOException {
        properties.remove("hash." + fileName);
        save();
    }

    private void save() throws IOException {
        try (OutputStream output = Files.newOutputStream(stateFile)) {
            properties.store(output, "SyncVerse client state");
        }
    }
}
