package com.ptit.syncverse.server;

import com.ptit.syncverse.common.HttpForm;
import com.ptit.syncverse.common.JsonLite;
import com.ptit.syncverse.common.Protocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ServerApp {
    private static final int DEFAULT_PORT = 8080;

    private ServerApp() {
    }

    public static void main(String[] args) {
        String serverName = args.length > 0 ? args[0] : "AlphaServer";
        int port = Integer.parseInt(System.getenv().getOrDefault("SYNCVERSE_PORT", String.valueOf(DEFAULT_PORT)));
        try {
            SyncStore store = new SyncStore(Path.of("server-data"));
            SessionRegistry sessions = new SessionRegistry();
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool());

            server.createContext("/api/register", exchange -> handle(exchange, () -> register(exchange, serverName, store, sessions)));
            server.createContext("/api/heartbeat", exchange -> handle(exchange, () -> heartbeat(exchange, sessions)));
            server.createContext("/api/change", exchange -> handle(exchange, () -> change(exchange, store, sessions)));
            server.createContext("/api/reconnect", exchange -> handle(exchange, () -> reconnect(exchange, store, sessions)));
            server.createContext("/api/delta", exchange -> handle(exchange, () -> delta(exchange, store, sessions)));
            server.createContext("/health", exchange -> handle(exchange, () -> sendJson(exchange, 200, JsonLite.object(Map.of(
                    "status", "UP",
                    "serverName", serverName,
                    "version", store.currentVersion()
            )))));

            server.start();
            System.out.println("SyncVerse server started");
            System.out.println("Name: " + serverName);
            System.out.println("Port: " + port);
            System.out.println("Data: " + Path.of("server-data").toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("Server failed: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private static void register(HttpExchange exchange, String serverName, SyncStore store, SessionRegistry sessions) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, String> form = bodyForm(exchange);
        String clientName = requireValue(form, "clientName");
        SessionRegistry.Session session = sessions.register(clientName);
        sendJson(exchange, 200, JsonLite.object(Map.of(
                "type", Protocol.HELLO,
                "serverName", serverName,
                "clientId", session.token(),
                "serverVersion", store.currentVersion()
        )));
        System.out.println("HELLO " + clientName);
    }

    private static void heartbeat(HttpExchange exchange, SessionRegistry sessions) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, String> form = bodyForm(exchange);
        String clientId = requireValue(form, "clientId");
        sessions.heartbeat(clientId);
        sendJson(exchange, 200, JsonLite.object(Map.of("type", Protocol.HEARTBEAT, "status", "OK")));
    }

    private static void change(HttpExchange exchange, SyncStore store, SessionRegistry sessions) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, String> form = bodyForm(exchange);
        SessionRegistry.Session session = sessions.require(requireValue(form, "clientId"));
        String operation = requireValue(form, "operation");
        String fileName = requireValue(form, "fileName");
        String contentBase64 = form.getOrDefault("contentBase64", "");
        if (!operation.equals("DELETE")) {
            byte[] content = Base64.getDecoder().decode(contentBase64);
            if (content.length > 1024 * 1024) {
                throw new IllegalArgumentException("File size must not exceed 1 MB");
            }
        }
        ChangeRecord record = store.append(operation, fileName, contentBase64, session.clientName());
        sendJson(exchange, 200, JsonLite.object(Map.of(
                "type", Protocol.FILE_CHANGE,
                "version", record.version(),
                "status", "ACCEPTED"
        )));
        System.out.println("FILE_CHANGE v" + record.version() + " " + operation + " " + fileName + " from " + session.clientName());
    }

    private static void reconnect(HttpExchange exchange, SyncStore store, SessionRegistry sessions) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, String> form = bodyForm(exchange);
        SessionRegistry.Session session = sessions.require(requireValue(form, "clientId"));
        long lastVersion = Long.parseLong(form.getOrDefault("lastVersion", "0"));
        sendJson(exchange, 200, JsonLite.object(Map.of(
                "type", Protocol.RECONNECT,
                "clientName", session.clientName(),
                "lastVersion", lastVersion,
                "serverVersion", store.currentVersion()
        )));
        System.out.println("RECONNECT " + session.clientName() + " from v" + lastVersion);
    }

    private static void delta(HttpExchange exchange, SyncStore store, SessionRegistry sessions) throws Exception {
        requireMethod(exchange, "GET");
        Map<String, String> query = HttpForm.parse(exchange.getRequestURI().getRawQuery());
        sessions.require(requireValue(query, "clientId"));
        long since = Long.parseLong(query.getOrDefault("since", "0"));
        List<ChangeRecord> changes = store.changesAfter(since);
        String packed = packChanges(changes);
        sendJson(exchange, 200, JsonLite.object(Map.of(
                "type", Protocol.DELTA_REQUEST,
                "serverVersion", store.currentVersion(),
                "changeCount", changes.size(),
                "changesBase64", Base64.getEncoder().encodeToString(packed.getBytes(StandardCharsets.UTF_8))
        )));
    }

    private static String packChanges(List<ChangeRecord> changes) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder packed = new StringBuilder();
        for (ChangeRecord change : changes) {
            if (!packed.isEmpty()) {
                packed.append('\n');
            }
            packed.append(change.version()).append('\t')
                    .append(change.timestamp()).append('\t')
                    .append(encoder.encodeToString(change.operation().getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(encoder.encodeToString(change.fileName().getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(encoder.encodeToString(change.sourceClient().getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(change.contentBase64());
        }
        return packed.toString();
    }

    private static void handle(HttpExchange exchange, ThrowingAction action) throws IOException {
        try {
            action.run();
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, JsonLite.object(Map.of("status", 400, "error", "BAD_REQUEST", "message", exception.getMessage())));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, JsonLite.object(Map.of("status", 500, "error", "SERVER_ERROR", "message", exception.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> bodyForm(HttpExchange exchange) throws IOException {
        return HttpForm.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String requireValue(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Method must be " + method);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
