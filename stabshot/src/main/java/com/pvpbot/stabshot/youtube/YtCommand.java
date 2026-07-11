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
 *   /ts yt play [loop] <query>          – search, show paginated picker, play on pick
 *   /ts yt next / prev                  – page through play results
 *   /ts yt pick <1-5>                   – pick a result to play
 *   /ts yt stop / status
 *   /ts yt download <query>             – search, show paginated picker for download
 *   /ts yt download <query> as <name>   – same but save with custom filename
 *   /ts yt dnext / dprev                – page through download results
 *   /ts yt dpick <1-5>                  – pick a result to download
 *   /ts yt save <name>                  – save currently playing YT track to songs folder
 *   /ts yt cache size / clear
 */
@Environment(EnvType.CLIENT)
public class YtCommand {

    private static final int PAGE_SIZE = 5;

    // ── Play state ────────────────────────────────────────────────────────────
    private static final List<YtSearch.YtResult> playResults = new CopyOnWriteArrayList<>();
    private static int     playPage   = 0;
    private static boolean lastLoop   = false;
    private static String  lastPlayQuery = "";

    // ── Download state ────────────────────────────────────────────────────────
    private static final List<YtSearch.YtResult> dlResults = new CopyOnWriteArrayList<>();
    private static int    dlPage        = 0;
    private static String lastDlQuery   = "";
    private static String lastDlName    = null; // custom filename, null = use title

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

                // /ts yt next / prev (play results)
                .then(ClientCommandManager.literal("next").executes(YtCommand::execNext))
                .then(ClientCommandManager.literal("prev").executes(YtCommand::execPrev))

                // /ts yt pick <1-5>
                .then(ClientCommandManager.literal("pick")
                    .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, PAGE_SIZE))
                        .executes(YtCommand::execPick)))

                // /ts yt stop / status
                .then(ClientCommandManager.literal("stop")  .executes(YtCommand::execStop))
                .then(ClientCommandManager.literal("status").executes(YtCommand::execStatus))

                // /ts yt download <query>
                // /ts yt download <query> as <name>
                // We parse the trailing " as <name>" ourselves from the greedy arg.
                .then(ClientCommandManager.literal("download")
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(YtCommand::execDownloadSearch)))

                // /ts yt dnext / dprev (download results)
                .then(ClientCommandManager.literal("dnext").executes(YtCommand::execDNext))
                .then(ClientCommandManager.literal("dprev").executes(YtCommand::execDPrev))

                // /ts yt dpick <1-5>
                .then(ClientCommandManager.literal("dpick")
                    .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, PAGE_SIZE))
                        .executes(YtCommand::execDPick)))

                // /ts yt save <name>
                .then(ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(YtCommand::execSave)))

                // /ts yt cache size | clear
                .then(ClientCommandManager.literal("cache")
                    .then(ClientCommandManager.literal("size") .executes(YtCommand::execCacheSize))
                    .then(ClientCommandManager.literal("clear").executes(YtCommand::execCacheClear)))
            )
        );
    }

    // ── Play handlers ─────────────────────────────────────────────────────────

    private static int execSearch(CommandContext<FabricClientCommandSource> ctx, boolean loop) {
        FabricClientCommandSource src = ctx.getSource();
        String query = StringArgumentType.getString(ctx, "query");
        lastLoop      = loop;
        lastPlayQuery = query;

        feedback(src, "§8§m                              ");
        feedback(src, "§6§l  ▶ YT §r§e Searching: §f" + query);
        feedback(src, "§8§m                              ");

        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(query, 15);
                playResults.clear();
                playResults.addAll(results);
                playPage = 0;
                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c  No results found for: §f" + query));
                    return;
                }
                mc(() -> showPlayPage(src, playPage));
            } catch (Exception e) {
                mc(() -> feedback(src, "§c[YT] Search error: " + e.getMessage()));
            }
        }, "YtSearch-Thread").start();
        return 1;
    }

    private static int execNext(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (playResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt play <query> §cfirst."); return 0; }
        if (playPage >= totalPages(playResults) - 1) { feedback(src, "§7[YT] Already on last page."); return 0; }
        showPlayPage(src, ++playPage);
        return 1;
    }

    private static int execPrev(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (playResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt play <query> §cfirst."); return 0; }
        if (playPage <= 0) { feedback(src, "§7[YT] Already on first page."); return 0; }
        showPlayPage(src, --playPage);
        return 1;
    }

    private static int execPick(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (playResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt play <query> §cfirst."); return 0; }

        int idx = playPage * PAGE_SIZE + (number - 1);
        if (idx >= playResults.size()) { feedback(src, "§c[YT] No result #" + number + " on this page."); return 0; }

        YtSearch.YtResult result = playResults.get(idx);
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
        feedback(src, (title != null && YtPlayer.getInstance().isPlaying())
                ? "§a[YT] ▶ §f" + title : "§7[YT] Nothing playing.");
        return 1;
    }

    // ── Download handlers ─────────────────────────────────────────────────────

    /**
     * /ts yt download <query>
     * /ts yt download <query> as <name>
     *
     * Shows a paginated result list with [Download] buttons, exactly like the
     * play picker. The optional " as <name>" suffix sets the save filename.
     */
    private static int execDownloadSearch(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String raw = StringArgumentType.getString(ctx, "query");

        // Split "query as name" on the LAST " as " so names can contain spaces
        String query      = raw;
        String customName = null;
        int splitIdx = raw.toLowerCase().lastIndexOf(" as ");
        if (splitIdx > 0) {
            query      = raw.substring(0, splitIdx).trim();
            customName = raw.substring(splitIdx + 4).trim();
            if (customName.isEmpty()) customName = null;
        }

        lastDlQuery = query;
        lastDlName  = customName;

        feedback(src, "§8§m                              ");
        feedback(src, "§6§l  ⬇ YT §r§e Searching: §f" + query
                + (customName != null ? " §e→ will save as §f" + customName : ""));
        feedback(src, "§8§m                              ");

        final String fQuery = query;
        new Thread(() -> {
            try {
                List<YtSearch.YtResult> results = YtSearch.search(fQuery, 15);
                dlResults.clear();
                dlResults.addAll(results);
                dlPage = 0;
                if (results.isEmpty()) {
                    mc(() -> feedback(src, "§c  No results found for: §f" + fQuery));
                    return;
                }
                mc(() -> showDlPage(src, dlPage));
            } catch (Exception e) {
                mc(() -> feedback(src, "§c[YT] Search error: " + e.getMessage()));
            }
        }, "YtDlSearch-Thread").start();
        return 1;
    }

    private static int execDNext(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (dlResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt download <query> §cfirst."); return 0; }
        if (dlPage >= totalPages(dlResults) - 1) { feedback(src, "§7[YT] Already on last page."); return 0; }
        showDlPage(src, ++dlPage);
        return 1;
    }

    private static int execDPrev(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (dlResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt download <query> §cfirst."); return 0; }
        if (dlPage <= 0) { feedback(src, "§7[YT] Already on first page."); return 0; }
        showDlPage(src, --dlPage);
        return 1;
    }

    private static int execDPick(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (dlResults.isEmpty()) { feedback(src, "§c[YT] No results. Run §f/ts yt download <query> §cfirst."); return 0; }

        int idx = dlPage * PAGE_SIZE + (number - 1);
        if (idx >= dlResults.size()) { feedback(src, "§c[YT] No result #" + number + " on this page."); return 0; }

        YtSearch.YtResult result = dlResults.get(idx);
        YtPlayer.getInstance().download(result, lastDlName);
        return 1;
    }

    private static int execSave(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty()) { feedback(src, "§c[YT] Provide a name: §f/ts yt save <name>"); return 0; }
        String err = YtPlayer.getInstance().saveCurrentToSongs(name);
        if (err != null) { feedback(src, "§c[YT] " + err); return 0; }
        feedback(src, "§a[YT] Saved as §f" + name + " §a— play with §f/ts play " + name);
        return 1;
    }

    private static int execCacheSize(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§7[YT] Cache: §f" + YtPlayer.getInstance().getCacheSizeString() + " §7/ 100 MB");
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

    // ── Page display ──────────────────────────────────────────────────────────

    private static void showPlayPage(FabricClientCommandSource src, int page) {
        showPage(src, page, playResults, lastPlayQuery,
                "§6§l  ▶ YT",
                n -> "/ts yt pick " + n,
                "/ts yt prev", "/ts yt next");
    }

    private static void showDlPage(FabricClientCommandSource src, int page) {
        String nameHint = lastDlName != null ? " §7(→ §f" + lastDlName + "§7)" : "";
        showPage(src, page, dlResults, lastDlQuery + nameHint,
                "§6§l  ⬇ YT",
                n -> "/ts yt dpick " + n,
                "/ts yt dprev", "/ts yt dnext");
    }

    private static void showPage(FabricClientCommandSource src, int page,
                                 List<YtSearch.YtResult> results, String query,
                                 String header,
                                 java.util.function.IntFunction<String> pickCmd,
                                 String prevCmd, String nextCmd) {
        int total = totalPages(results);
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, results.size());

        feedback(src, "§8§m                              ");
        feedback(src, header + " §r§7 \"§f" + query + "§7\"  §8[§7Page " + (page + 1) + "/" + total + "§8]");
        feedback(src, "§8§m                              ");

        for (int i = start; i < end; i++) {
            YtSearch.YtResult r   = results.get(i);
            int               num = (i - start) + 1;
            String            cmd = pickCmd.apply(num);
            String title = r.title().length() > 40 ? r.title().substring(0, 38) + "…" : r.title();

            MutableText line = Text.literal("")
                    .append(clickable(Text.literal("§a [" + num + "] "), cmd))
                    .append(clickable(Text.literal("§f" + title), cmd))
                    .append(clickable(Text.literal(" §8[§7" + r.durationString() + "§8]"), cmd));
            src.sendFeedback(line);
            src.sendFeedback(clickable(Text.literal("§8     ↳ §e" + r.uploader()), cmd));
        }

        feedback(src, "§8§m                              ");
        MutableText nav = Text.literal("§7  ");
        if (page > 0)         { nav.append(clickable(Text.literal("§b[◀ Prev]"), prevCmd)); nav.append(Text.literal(" ")); }
        String sampleCmd = pickCmd.apply(1);
        String baseCmd   = sampleCmd.substring(0, sampleCmd.lastIndexOf(' '));
        nav.append(Text.literal("§7Pick: §f" + baseCmd + " §71-" + (end - start)));
        if (page < total - 1) { nav.append(Text.literal(" ")); nav.append(clickable(Text.literal("§b[Next ▶]"), nextCmd)); }
        src.sendFeedback(nav);
        feedback(src, "§8§m                              ");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int totalPages(List<?> list) {
        return Math.max(1, (int) Math.ceil((double) list.size() / PAGE_SIZE));
    }

    private static MutableText clickable(MutableText text, String command) {
        return text.setStyle(Style.EMPTY.withClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }

    private static void mc(Runnable r) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.execute(r);
    }
}
