package com.pvpbot.stabshot.youtube;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

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
    public Response execute(Request request) throws ReCaptchaException, IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .header("User-Agent", USER_AGENT);

        if (request.headers() != null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                for (String value : entry.getValue()) {
                    try { builder.header(entry.getKey(), value); } catch (Exception ignored) {}
                }
            }
        }

        String method = request.httpMethod();
        if ("POST".equals(method) && request.dataToSend() != null) {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.dataToSend()));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

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
