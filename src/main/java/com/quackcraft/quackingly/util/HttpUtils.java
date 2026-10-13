package com.quackcraft.quackingly.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper over java.net.http.HttpClient.
 *
 * Why not OkHttp / Apache? Because the JVM's built-in client is good enough and
 * we want zero additional runtime dependencies (keeps the .jar small, helps Pojav/Mojo).
 */
public final class HttpUtils {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpUtils() {}

    public static HttpResponse<String> postJson(String url, String bearer, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearer != null && !bearer.isBlank()) b.header("Authorization", bearer);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> postJsonWithHeaders(String url, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (headers != null) headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<byte[]> postBytesWithHeaders(String url, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers != null) headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public static HttpResponse<String> get(String url, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (bearer != null && !bearer.isBlank()) b.header("Authorization", bearer);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpClient client() { return CLIENT; }
}
