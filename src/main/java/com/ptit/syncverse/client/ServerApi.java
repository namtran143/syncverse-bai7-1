package com.ptit.syncverse.client;

import com.ptit.syncverse.common.HttpForm;
import com.ptit.syncverse.common.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServerApi {
    private final String baseUrl;
    private final HttpClient client;

    public ServerApi(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public Map<String, String> register(String clientName) throws IOException, InterruptedException {
        return post("/api/register", Map.of("clientName", clientName));
    }

    public void heartbeat(String clientId) throws IOException, InterruptedException {
        post("/api/heartbeat", Map.of("clientId", clientId));
    }

    public long sendChange(String clientId, String operation, String fileName, String contentBase64) throws IOException, InterruptedException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("clientId", clientId);
        values.put("operation", operation);
        values.put("fileName", fileName);
        values.put("contentBase64", contentBase64 == null ? "" : contentBase64);
        return Long.parseLong(post("/api/change", values).get("version"));
    }

    public void reconnect(String clientId, long lastVersion) throws IOException, InterruptedException {
        post("/api/reconnect", Map.of("clientId", clientId, "lastVersion", Long.toString(lastVersion)));
    }

    public Map<String, String> delta(String clientId, long since) throws IOException, InterruptedException {
        String query = HttpForm.encode(Map.of("clientId", clientId, "since", Long.toString(since)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/delta?" + query))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        return send(request);
    }

    private Map<String, String> post(String path, Map<String, String> values) throws IOException, InterruptedException {
        String body = HttpForm.encode(values);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private Map<String, String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Server returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return JsonLite.parseFlatObject(response.body());
    }
}
