package com.pvpbot.stabshot.youtube;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * NewPipe {@link Downloader} implementation backed by {@link HttpURLConnection}.
 *
 * <p>We deliberately avoid {@code java.net.http.HttpClient} here. That class
 * was added in Java 11 and is <em>not</em> present in the stripped Android JRE
 * bundles shipped by ZalithLauncher 1, PojavLauncher, and MojoLauncher — all
 * of which use an OpenJDK-8-based Android port. {@link HttpURLConnection} has
 * been part of the Java standard library since Java 1.1 and is universally
 * available, including on every Android JRE.</p>
 *
 * <p>A new {@link HttpURLConnection} is created for every request (no shared
 * singleton pool). This avoids stale-connection and context-reuse bugs that
 * appeared with the old shared {@code HttpClient} singleton, which was a
 * secondary contributor to the "searches hang forever" symptom.</p>
 */
@Environment(EnvType.CLIENT)
public class YtDownloader extends Downloader {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    @Override
    public Response execute(Request request) throws ReCaptchaException, IOException {
        HttpURLConnection conn = openConnection(request.url(), request.httpMethod(),
                request.headers(), request.dataToSend());

        int statusCode;
        try {
            statusCode = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }

        if (statusCode == 429) {
            conn.disconnect();
            throw new ReCaptchaException("Rate limited by YouTube", request.url());
        }

        // Collect response headers
        Map<String, List<String>> headers = new LinkedHashMap<>(conn.getHeaderFields());

        // Read response body
        String body;
        try (InputStream is = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            body = is == null ? "" : readStream(is);
        } finally {
            conn.disconnect();
        }

        return new Response(statusCode, String.valueOf(statusCode), headers, body, request.url());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Opens an {@link HttpURLConnection} configured with our standard headers,
     * timeouts, and (for POST requests) the request body.
     */
    static HttpURLConnection openConnection(String urlStr, String method,
                                            Map<String, List<String>> extraHeaders,
                                            byte[] body) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (extraHeaders != null) {
            for (Map.Entry<String, List<String>> entry : extraHeaders.entrySet()) {
                String key = entry.getKey();
                // HttpURLConnection rejects null keys (from HttpURLConnection.getHeaderFields())
                if (key == null) continue;
                for (String value : entry.getValue()) {
                    try { conn.setRequestProperty(key, value); } catch (Exception ignored) {}
                }
            }
        }

        String httpMethod = (method != null && !method.isEmpty()) ? method : "GET";
        conn.setRequestMethod(httpMethod);

        if ("POST".equals(httpMethod) && body != null && body.length > 0) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        }

        return conn;
    }

    /** Reads an {@link InputStream} fully and returns its contents as a UTF-8 string. */
    static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
