package com.pvpbot.stabshot.youtube;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pvpbot.stabshot.youtube.YtSearch;
import com.pvpbot.stabshot.youtube.YtPlayer;
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
 *   /ts yt play <query>       – search YouTube, show top 5 results to pick from
 *   /ts yt play loop <query>  – same but loops
 *   /ts yt pick <number>      – pick a result from last search
 *   /ts yt stop               – stop playback
 *   /ts yt status             – show what's playing
 *   /ts yt download <query>   – download to songs folder as MP3
 *   /ts yt cache size         – show cache size
 *   /ts yt cache clear        – clear cache
 */
@Environment(EnvType.CLIENT)
public class YtCommand {

    // Holds the last search results so /ts yt pick can reference them
    private static final List<YtSearch.YtResult> lastResults = new CopyOnWriteArrayList<>();
    private static boolean lastSearchLoop = false;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("ts")
            .then(ClientCommandManager.literal("yt")

                // /ts yt play [loop] <query>
                .then(ClientCommandManager.literal("play")
                    .then(ClientCommandManager.literal("loop")
                        .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                            .executes(ctx -> execSearch(ctx, true))))
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> execSearch(ctx, false))))

                // /ts yt pick <1-5>
                .then(ClientCommandManager.literal("pick")
                    .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, 5))
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
        lastSearchLoop = loop;

        feedback(src, "§e[YT] Searching: §f" + query + "§e...");

        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(query, 5);
                lastResults.clear();
                lastResults.addAll(results);

                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c[YT] No results found for: " + query));
                    return;
                }

                mc(() -> {
                    feedback(src, "§6§l[YT] Results for \"" + query + "\"§r§7 — click to play:");
                    for (int i = 0; i < results.size(); i++) {
                        YtSearch.YtResult r = results.get(i);
                        String cmd = "/ts yt pick " + (i + 1);
                        MutableText line = Text.literal("")
                            .append(Text.literal("§a[" + (i + 1) + "] ")
                                .setStyle(Style.EMPTY
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))))
                            .append(Text.literal("§f" + r.title())
                                .setStyle(Style.EMPTY
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))))
                            .append(Text.literal(" §7— " + r.uploader() + " §8(" + r.durationString() + ")")
                                .setStyle(Style.EMPTY
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))));
                        src.sendFeedback(line);
                    }
                    feedback(src, "§7Click a result or type §f/ts yt pick <1-" + results.size() + ">");
                });
            } catch (Exception e) {
                mc(() -> feedback(src, "§c[YT] Search error: " + e.getMessage()));
            }
        }, "YtSearch-Thread").start();

        return 1;
    }

    private static int execPick(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        int number = IntegerArgumentType.getInteger(ctx, "number");

        if (lastResults.isEmpty()) {
            feedback(src, "§c[YT] No search results to pick from. Run §f/ts yt play <query> §cfirst.");
            return 0;
        }
        if (number > lastResults.size()) {
            feedback(src, "§c[YT] Only " + lastResults.size() + " results available.");
            return 0;
        }

        YtSearch.YtResult result = lastResults.get(number - 1);
        feedback(src, "§a[YT] Playing: §f" + result.title() + " §7— " + result.uploader()
                + (lastSearchLoop ? " §7(looping)" : ""));

        YtPlayer.getInstance().play(result, lastSearchLoop);
        return 1;
    }

    private static int execStop(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String title = YtPlayer.getInstance().getCurrentTitle();
        YtPlayer.getInstance().stop();
        if (title != null) {
            feedback(src, "§7[YT] Stopped: §f" + title);
        } else {
            feedback(src, "§7[YT] Nothing was playing.");
        }
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

        feedback(src, "§e[YT] Searching for download: §f" + query + "§e...");

        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(query, 1);
                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c[YT] No results found for: " + query));
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
        feedback(ctx.getSource(), "§7[YT] Cache size: §f" + YtPlayer.getInstance().getCacheSizeString()
                + " §7/ 100 MB");
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
    // Helpers
    // -----------------------------------------------------------------------

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }

    private static void mc(Runnable r) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.execute(r);
    }
}
