package com.pvpbot.stabshot.youtube;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Provides NewPipe Extractor with an HTTP implementation backed by
 * Java's built-in {@code java.net.http.HttpClient}.
 * Works on PC and PojavLauncher (Android ships HttpClient via Minecraft's JVM shim).
 */
@Environment(EnvType.CLIENT)
public class YtDownloader extends Downloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    @Override
    public Response execute(Request request) throws ReCaptchaException, java.io.IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .header("User-Agent", USER_AGENT);

        // Copy headers from NewPipe request
        if (request.headers() != null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                for (String value : entry.getValue()) {
                    try { builder.header(entry.getKey(), value); } catch (Exception ignored) {}
                }
            }
        }

        // Method
        String method = request.httpMethod();
        if ("POST".equals(method) && request.dataToSend() != null) {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.dataToSend()));
        } else {
            builder.GET();
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            throw new ReCaptchaException("Rate limited by YouTube", request.url());
        }

        return new Response(
                response.statusCode(),
                response.statusCode() + "",
                response.headers().map(),
                response.body(),
                request.url()
        );
    }
}
