package com.ptit.syncverse.client;

import com.ptit.syncverse.common.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientApp {
    private static final long MAX_FILE_SIZE = 1024L * 1024L;
    private static final long POLL_SECONDS = 2;
    private static final long HEARTBEAT_SECONDS = 4;

    private final String clientName;
    private final Path workspace;
    private final ServerApi api;
    private final ClientState state;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, String> suppressNextHash = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile String clientId;

    private ClientApp(String clientName, Path workspace, String serverUrl) throws IOException {
        this.clientName = clientName;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.api = new ServerApi(serverUrl);
        this.state = new ClientState(clientName);
        Files.createDirectories(this.workspace);
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java -jar client.jar <ClientName> <workspace-directory>");
            return;
        }
        String serverUrl = System.getenv().getOrDefault("SYNCVERSE_SERVER_URL", "http://localhost:8080");
        try {
            ClientApp app = new ClientApp(args[0], Path.of(args[1]), serverUrl);
            app.start();
        } catch (Exception exception) {
            System.err.println("Client failed: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private void start() throws Exception {
        connect(false);
        pullDelta();
        pushInitialLocalFiles();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            scheduler.shutdownNow();
        }));

        scheduler.scheduleAtFixedRate(this::safeHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::safePullDelta, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);

        System.out.println("SyncVerse client started");
        System.out.println("Client: " + clientName);
        System.out.println("Workspace: " + workspace);
        System.out.println("Server: " + System.getenv().getOrDefault("SYNCVERSE_SERVER_URL", "http://localhost:8080"));
        watchLoop();
    }

    private void connect(boolean reconnect) throws IOException, InterruptedException {
        Map<String, String> response = api.register(clientName);
        clientId = response.get("clientId");
        if (reconnect || state.lastVersion() > 0) {
            api.reconnect(clientId, state.lastVersion());
        }
    }

    private void watchLoop() throws Exception {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            workspace.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            while (running.get()) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path relative = (Path) event.context();
                    Path file = workspace.resolve(relative);
                    String fileName = relative.getFileName().toString();
                    if (fileName.startsWith(".")) {
                        continue;
                    }
                    try {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            handleLocalDelete(fileName);
                        } else if (Files.isRegularFile(file)) {
                            handleLocalUpsert(fileName, file);
                        }
                    } catch (Exception exception) {
                        System.err.println("Local change failed for " + fileName + ": " + exception.getMessage());
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }
        }
    }

    private void handleLocalUpsert(String fileName, Path file) throws Exception {
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            System.err.println("Ignored file larger than 1 MB: " + fileName);
            return;
        }
        byte[] content = Files.readAllBytes(file);
        String hash = Hashing.sha256(content);
        String suppressed = suppressNextHash.get(fileName);
        if (hash.equals(suppressed)) {
            suppressNextHash.remove(fileName);
            state.setHash(fileName, hash);
            return;
        }
        if (hash.equals(state.hash(fileName))) {
            return;
        }
        long version = api.sendChange(clientId, "UPSERT", fileName, Base64.getEncoder().encodeToString(content));
        state.setHash(fileName, hash);
        state.setLastVersion(Math.max(state.lastVersion(), version));
        System.out.println("PUSH UPSERT " + fileName + " -> v" + version);
    }

    private void handleLocalDelete(String fileName) throws Exception {
        if (state.hash(fileName) == null) {
            return;
        }
        String suppressed = suppressNextHash.get(fileName);
        if ("__DELETE__".equals(suppressed)) {
            suppressNextHash.remove(fileName);
            state.removeHash(fileName);
            return;
        }
        long version = api.sendChange(clientId, "DELETE", fileName, "");
        state.removeHash(fileName);
        state.setLastVersion(Math.max(state.lastVersion(), version));
        System.out.println("PUSH DELETE " + fileName + " -> v" + version);
    }

    private void pushInitialLocalFiles() throws Exception {
        try (var files = Files.list(workspace)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.startsWith(".")) {
                    handleLocalUpsert(fileName, file);
                }
            }
        }
    }

    private synchronized void pullDelta() throws Exception {
        Map<String, String> response = api.delta(clientId, state.lastVersion());
        long serverVersion = Long.parseLong(response.getOrDefault("serverVersion", Long.toString(state.lastVersion())));
        List<DeltaChange> changes = unpack(response.getOrDefault("changesBase64", ""));
        for (DeltaChange change : changes) {
            if (!change.sourceClient().equals(clientName)) {
                applyRemoteChange(change);
            }
            state.setLastVersion(change.version());
        }
        if (changes.isEmpty() && serverVersion > state.lastVersion()) {
            state.setLastVersion(serverVersion);
        }
    }

    private void applyRemoteChange(DeltaChange change) throws Exception {
        Path target = workspace.resolve(change.fileName()).normalize();
        if (!target.getParent().equals(workspace)) {
            throw new IOException("Server returned invalid flat-directory file name");
        }
        if (change.operation().equals("DELETE")) {
            suppressNextHash.put(change.fileName(), "__DELETE__");
            Files.deleteIfExists(target);
            state.removeHash(change.fileName());
            System.out.println("PULL DELETE " + change.fileName() + " <- v" + change.version());
            return;
        }
        byte[] content = Base64.getDecoder().decode(change.contentBase64());
        if (content.length > MAX_FILE_SIZE) {
            throw new IOException("Server delta exceeds 1 MB");
        }
        String hash = Hashing.sha256(content);
        suppressNextHash.put(change.fileName(), hash);
        Files.write(target, content);
        state.setHash(change.fileName(), hash);
        System.out.println("PULL UPSERT " + change.fileName() + " <- v" + change.version());
    }

    private static List<DeltaChange> unpack(String changesBase64) {
        List<DeltaChange> changes = new ArrayList<>();
        if (changesBase64 == null || changesBase64.isBlank()) {
            return changes;
        }
        String packed = new String(Base64.getDecoder().decode(changesBase64), StandardCharsets.UTF_8);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String line : packed.split("\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            changes.add(new DeltaChange(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    new String(decoder.decode(parts[2]), StandardCharsets.UTF_8),
                    new String(decoder.decode(parts[3]), StandardCharsets.UTF_8),
                    new String(decoder.decode(parts[4]), StandardCharsets.UTF_8),
                    parts[5]
            ));
        }
        return changes;
    }

    private void safeHeartbeat() {
        try {
            api.heartbeat(clientId);
        } catch (Exception exception) {
            reconnectAfterFailure("HEARTBEAT", exception);
        }
    }

    private void safePullDelta() {
        try {
            pullDelta();
        } catch (Exception exception) {
            reconnectAfterFailure("DELTA_REQUEST", exception);
        }
    }

    private synchronized void reconnectAfterFailure(String action, Exception cause) {
        System.err.println(action + " failed: " + cause.getMessage());
        try {
            connect(true);
            pullDelta();
            System.out.println("RECONNECT successful at v" + state.lastVersion());
        } catch (Exception reconnectError) {
            System.err.println("RECONNECT failed: " + reconnectError.getMessage());
        }
    }
}
