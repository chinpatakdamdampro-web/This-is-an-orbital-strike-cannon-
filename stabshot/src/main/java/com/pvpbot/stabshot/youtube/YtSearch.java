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

@Environment(EnvType.CLIENT)
public class YtSearch {

    public record YtResult(
            String videoId,
            String title,
            String uploader,
            long   durationSeconds, // -1 if unknown
            String url
    ) {
        public String durationString() {
            if (durationSeconds < 0) return "?:??";
            long m = durationSeconds / 60;
            long s = durationSeconds % 60;
            return m + ":" + String.format("%02d", s);
        }

        @Override
        public String toString() {
            return title + " — " + uploader + " (" + durationString() + ")";
        }
    }

    /**
     * Searches YouTube for {@code query} and returns up to {@code maxResults} results.
     * Runs on whatever thread it's called from — always call off the main thread.
     */
    public static List<YtResult> search(String query, int maxResults) throws Exception {
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

    private static String extractVideoId(String url) {
        // https://www.youtube.com/watch?v=XXXXXXXXXXX
        if (url.contains("v=")) return url.split("v=")[1].split("&")[0];
        // https://youtu.be/XXXXXXXXXXX
        if (url.contains("youtu.be/")) return url.split("youtu.be/")[1].split("\\?")[0];
        return url;
    }
}
