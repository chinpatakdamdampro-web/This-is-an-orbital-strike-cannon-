package com.pvpbot.stabshot.youtube;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Commands:
 *   /ts yt play <query>     – search YouTube, show paginated results (no loop)
 *   /ts yt loop <query>     – same but loops (separate command, no brigadier ambiguity)
 *   /ts yt next             – next page of results
 *   /ts yt prev             – previous page of results
 *   /ts yt pick <1-5>       – pick a result from current page
 *   /ts yt stop             – stop playback
 *   /ts yt status           – show what's playing
 *   /ts yt download <query> – download to songs folder
 *   /ts yt cache size       – show cache size
 *   /ts yt cache clear      – clear cache
 */
@Environment(EnvType.CLIENT)
public class YtCommand {

    private static final int PAGE_SIZE = 5;

    private static final List<YtSearch.YtResult> allResults = new CopyOnWriteArrayList<>();
    private static int     currentPage = 0;
    private static boolean lastLoop    = false;
    private static String  lastQuery   = "";

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("ts")
            .then(ClientCommandManager.literal("yt")

                // /ts yt play <query>
                .then(ClientCommandManager.literal("play")
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> execSearch(ctx, false))))

                // /ts yt loop <query>  — separate command avoids brigadier ambiguity
                .then(ClientCommandManager.literal("loop")
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> execSearch(ctx, true))))

                // /ts yt next
                .then(ClientCommandManager.literal("next")
                    .executes(YtCommand::execNext))

                // /ts yt prev
                .then(ClientCommandManager.literal("prev")
                    .executes(YtCommand::execPrev))

                // /ts yt pick <1-5>
                .then(ClientCommandManager.literal("pick")
                    .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, PAGE_SIZE))
                        .executes(YtCommand::execPick)))

                // /ts yt stop
                .then(ClientCommandManager.literal("stop")
                    .executes(YtCommand::execStop))

                // /ts yt status
                .then(ClientCommandManager.literal("status")
                    .executes(YtCommand::execStatus))

                // /ts yt download <query>
                .then(ClientCommandManager.literal("download")
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(YtCommand::execDownload)))

                // /ts yt cache size | clear
                .then(ClientCommandManager.literal("cache")
                    .then(ClientCommandManager.literal("size")
                        .executes(YtCommand::execCacheSize))
                    .then(ClientCommandManager.literal("clear")
                        .executes(YtCommand::execCacheClear)))
            )
        );
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private static int execSearch(CommandContext<FabricClientCommandSource> ctx, boolean loop) {
        FabricClientCommandSource src = ctx.getSource();
        String query = StringArgumentType.getString(ctx, "query");
        lastLoop  = loop;
        lastQuery = query;

        feedback(src, "§8§m                              ");
        feedback(src, "§6§l  ▶ YT §r§e Searching: §f" + query);
        feedback(src, "§8§m                              ");

        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(query, 15);
                allResults.clear();
                allResults.addAll(results);
                currentPage = 0;

                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c  No results found for: §f" + query));
                    return;
                }
                mc(() -> showPage(src, currentPage));
            } catch (Exception e) {
                mc(() -> feedback(src, "§c[YT] Search error: " + e.getMessage()));
            }
        }, "YtSearch-Thread").start();

        return 1;
    }

    private static int execNext(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (allResults.isEmpty()) {
            feedback(src, "§c[YT] No search results. Run §f/ts yt play <query> §cfirst.");
            return 0;
        }
        if (currentPage >= getTotalPages() - 1) {
            feedback(src, "§7[YT] Already on the last page.");
            return 0;
        }
        currentPage++;
        showPage(src, currentPage);
        return 1;
    }

    private static int execPrev(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (allResults.isEmpty()) {
            feedback(src, "§c[YT] No search results. Run §f/ts yt play <query> §cfirst.");
            return 0;
        }
        if (currentPage <= 0) {
            feedback(src, "§7[YT] Already on the first page.");
            return 0;
        }
        currentPage--;
        showPage(src, currentPage);
        return 1;
    }

    private static int execPick(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        int number = IntegerArgumentType.getInteger(ctx, "number");

        if (allResults.isEmpty()) {
            feedback(src, "§c[YT] No search results. Run §f/ts yt play <query> §cfirst.");
            return 0;
        }

        int globalIndex = currentPage * PAGE_SIZE + (number - 1);
        if (globalIndex >= allResults.size()) {
            feedback(src, "§c[YT] No result §f#" + number + " §con this page.");
            return 0;
        }

        YtSearch.YtResult result = allResults.get(globalIndex);

        feedback(src, "§8§m                              ");
        feedback(src, "§6§l  ▶ §r§f" + result.title());
        feedback(src, "§7  by §e" + result.uploader()
                + "  §8[§7" + result.durationString() + "§8]"
                + (lastLoop ? "  §b⟳ looping" : ""));
        feedback(src, "§8§m                              ");

        YtPlayer.getInstance().play(result, lastLoop);
        return 1;
    }

    private static int execStop(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String title = YtPlayer.getInstance().getCurrentTitle();
        YtPlayer.getInstance().stop();
        feedback(src, title != null ? "§7[YT] ■ Stopped: §f" + title : "§7[YT] Nothing was playing.");
        return 1;
    }

    private static int execStatus(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String title = YtPlayer.getInstance().getCurrentTitle();
        if (title == null || !YtPlayer.getInstance().isPlaying()) {
            feedback(src, "§7[YT] Nothing playing.");
        } else {
            feedback(src, "§a[YT] ▶ §f" + title);
        }
        return 1;
    }

    private static int execDownload(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String query = StringArgumentType.getString(ctx, "query");
        feedback(src, "§e[YT] Searching for: §f" + query + "§e...");
        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(query, 1);
                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c[YT] No results found for: §f" + query));
                    return;
                }
                YtPlayer.getInstance().download(results.get(0));
            } catch (Exception e) {
                mc(() -> feedback(src, "§c[YT] Search error: " + e.getMessage()));
            }
        }, "YtDownloadSearch-Thread").start();
        return 1;
    }

    private static int execCacheSize(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§7[YT] Cache: §f"
                + YtPlayer.getInstance().getCacheSizeString() + " §7/ 100 MB");
        return 1;
    }

    private static int execCacheClear(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            YtPlayer.getInstance().clearCache();
            feedback(src, "§a[YT] Cache cleared.");
        } catch (Exception e) {
            feedback(src, "§c[YT] Could not clear cache: " + e.getMessage());
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // Page display
    // -----------------------------------------------------------------------

    private static void showPage(FabricClientCommandSource src, int page) {
        int totalPages = getTotalPages();
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, allResults.size());

        feedback(src, "§8§m                              ");
        feedback(src, "§6§l  ▶ YT §r§7 Results for §f\"" + lastQuery + "§f\""
                + "  §8[§7Page " + (page + 1) + "/" + totalPages + "§8]");
        feedback(src, "§8§m                              ");

        for (int i = start; i < end; i++) {
            YtSearch.YtResult r   = allResults.get(i);
            int               num = (i - start) + 1;
            String            cmd = "/ts yt pick " + num;

            String title = r.title().length() > 40
                    ? r.title().substring(0, 38) + "…"
                    : r.title();

            MutableText line = Text.literal("")
                .append(clickable(Text.literal("§a [" + num + "] "), cmd))
                .append(clickable(Text.literal("§f" + title), cmd))
                .append(clickable(Text.literal(" §8[§7" + r.durationString() + "§8]"), cmd));
            src.sendFeedback(line);
            src.sendFeedback(clickable(Text.literal("§8     ↳ §e" + r.uploader()), cmd));
        }

        feedback(src, "§8§m                              ");

        MutableText nav = Text.literal("§7  ");
        if (page > 0) {
            nav.append(clickable(Text.literal("§b[◀ Prev]"), "/ts yt prev"));
            nav.append(Text.literal(" "));
        }
        nav.append(Text.literal("§7Pick: §f/ts yt pick §71-" + (end - start)));
        if (page < totalPages - 1) {
            nav.append(Text.literal(" "));
            nav.append(clickable(Text.literal("§b[Next ▶]"), "/ts yt next"));
        }
        src.sendFeedback(nav);
        feedback(src, "§8§m                              ");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int getTotalPages() {
        return Math.max(1, (int) Math.ceil((double) allResults.size() / PAGE_SIZE));
    }

    private static MutableText clickable(MutableText text, String command) {
        return text.setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }

    private static void mc(Runnable r) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.execute(r);
    }
}
