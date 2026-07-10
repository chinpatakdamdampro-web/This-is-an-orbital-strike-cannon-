package com.pvpbot.stabshot.youtube;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.InfoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * YouTube search using NewPipe Extractor.
 *
 * <h3>Why we call {@code NewPipe.init(new YtDownloader())} on every search</h3>
 * <p>NewPipe caches the YouTube "player JS" (used to decipher obfuscated stream
 * URLs) in a static singleton. After a few hours that cached JS becomes stale
 * and NewPipe throws an exception resembling "The page needs to be reloaded".
 * Re-initialising with a fresh {@link YtDownloader} clears that stale state and
 * forces a new fetch of the player JS on the next extraction.</p>
 *
 * <p>The {@link YtDownloader} itself now uses {@link java.net.HttpURLConnection}
 * (not {@code java.net.http.HttpClient}) so it works on the Android JREs bundled
 * by ZalithLauncher 1, PojavLauncher, and MojoLauncher, which are based on
 * OpenJDK 8 and do not include the Java 11 HTTP client API.</p>
 */
@Environment(EnvType.CLIENT)
public class YtSearch {

    public record YtResult(
            String videoId,
            String title,
            String uploader,
            long   durationSeconds,
            String url
    ) {
        public String durationString() {
            if (durationSeconds < 0) return "?:??";
            long h = durationSeconds / 3600;
            long m = (durationSeconds % 3600) / 60;
            long s = durationSeconds % 60;
            if (h > 0) return h + ":" + String.format("%02d", m) + ":" + String.format("%02d", s);
            return m + ":" + String.format("%02d", s);
        }
    }

    /**
     * Searches YouTube and returns up to {@code maxResults} results.
     * Must be called off the main thread.
     *
     * @param query      the search query string
     * @param maxResults maximum number of results to return (1–15 recommended)
     * @return a (possibly empty) list of results; never {@code null}
     * @throws Exception if the network request or extraction fails
     */
    public static List<YtResult> search(String query, int maxResults) throws Exception {
        // Always create a fresh downloader — stale state causes extraction errors.
        NewPipe.init(new YtDownloader());

        SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(
                YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                        query,
                        List.of(YoutubeSearchQueryHandlerFactory.VIDEOS),
                        ""
                )
        );
        extractor.fetchPage();

        List<YtResult> results = new ArrayList<>();
        for (InfoItem item : extractor.getInitialPage().getItems()) {
            if (!(item instanceof StreamInfoItem stream)) continue;
            String url     = stream.getUrl();
            String videoId = extractVideoId(url);
            results.add(new YtResult(
                    videoId,
                    stream.getName(),
                    stream.getUploaderName(),
                    stream.getDuration(),
                    url
            ));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    /** Extracts the YouTube video ID from a standard or short URL. */
    public static String extractVideoId(String url) {
        if (url == null) return "";
        if (url.contains("v="))        return url.split("v=")[1].split("&")[0];
        if (url.contains("youtu.be/")) return url.split("youtu.be/")[1].split("\\?")[0];
        return url;
    }
}
