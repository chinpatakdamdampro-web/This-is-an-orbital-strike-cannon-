package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays MP3 and OGG files cross-platform.
 *
 * <h3>Desktop path (Windows / Linux / macOS)</h3>
 * <p>Uses {@code javax.sound.sampled} and JLayer for MP3.  This is the original
 * implementation and is completely unchanged.</p>
 *
 * <h3>Android path (ZalithLauncher 1, PojavLauncher, MojoLauncher)</h3>
 * <p>Those launchers bundle a stripped OpenJDK-8-based Android JRE that omits
 * {@code javax.sound.sampled}.  We detect this at first playback and fall back
 * to LWJGL <b>OpenAL</b>, which is always present because Minecraft uses it for
 * all in-game audio.</p>
 *
 * <p>Architecture of the Android path:</p>
 * <ol>
 *   <li>A background <em>decoder</em> thread decodes audio frames (JLayer for MP3,
 *       LWJGL STBVorbis for OGG) into raw 16-bit signed LE PCM and pushes chunks
 *       onto a {@link LinkedBlockingDeque}.</li>
 *   <li>All OpenAL calls run on Minecraft's main thread (which owns the AL
 *       context) via {@code MinecraftClient.getInstance().execute(Runnable)}.</li>
 *   <li>A dedicated <em>streaming thread</em> wakes every {@value AL_POLL_MS} ms,
 *       posts a short AL-maintenance runnable to the MC thread, and goes back to
 *       sleep — avoiding per-tick thread creation.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";

    // ── Shared playback state ─────────────────────────────────────────────────
    private static volatile Thread         playThread;
    private static volatile SourceDataLine currentLine;   // desktop path only
    private static volatile String         currentSong;
    private static volatile boolean        playing  = false;
    private static volatile boolean        looping  = false;
    private static final    AtomicBoolean  loopActive = new AtomicBoolean(false);

    // ── Android / OpenAL ──────────────────────────────────────────────────────
    /** Cached once; {@code true} when javax.sound is absent (Android JRE). */
    private static volatile boolean useOpenAlFallback = false;
    private static volatile boolean javaSoundChecked  = false;

    /** Number of AL buffers kept in-flight for streaming. */
    private static final int AL_BUFFER_COUNT = 4;
    /** PCM chunk size per buffer (bytes).  16 KB ≈ 93 ms at 44100 Hz stereo 16-bit. */
    private static final int AL_CHUNK_BYTES  = 16384;
    /** AL service-loop wakeup interval (ms). */
    private static final int AL_POLL_MS      = 40; // safe margin above one MC tick (50 ms)

    /**
     * Sentinel placed at the <em>end</em> of the PCM queue to signal that the
     * decoder is finished.  We cannot use {@code null} because
     * {@link LinkedBlockingDeque} rejects null elements.
     */
    private static final byte[] POISON_PILL = new byte[0];

    private static volatile int                  alSource    = 0;
    private static volatile int[]                alBuffers   = null;
    private static volatile LinkedBlockingDeque<byte[]> pcmQueue;
    private static volatile int                  alSampleRate = 44100;
    private static volatile int                  alChannels   = 2;

    /** Background thread that drives the AL service loop. */
    private static volatile Thread alServiceThread;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts playing {@code name} (without extension) from the songs folder.
     *
     * @param loop  {@code true} to loop indefinitely until {@link #stop()} is called
     * @return {@code null} on success, or a user-visible error string on failure
     */
    public static String play(final String name, final boolean loop) {
        stop();

        final Path songsDir = getSongsDir();
        Path   file = null;
        String ext  = null;
        for (String e : new String[]{"mp3", "ogg"}) {
            Path candidate = songsDir.resolve(name + "." + e);
            if (Files.exists(candidate)) { file = candidate; ext = e; break; }
        }
        if (file == null) {
            return "§cSong not found: §f" + name + ".ogg §7or §f" + name + ".mp3\n"
                 + "§7Put audio files in: §f" + songsDir + "\n"
                 + "§7Available: §f" + String.join(", ", getSongNames());
        }

        final Path   fFile = file;
        final String fExt  = ext;
        currentSong = name;
        looping     = loop;
        loopActive.set(true);

        ensureJavaSoundChecked();

        playThread = new Thread(() -> {
            try {
                do {
                    playing = true;
                    playOne(fFile, fExt);
                } while (loop && loopActive.get() && !Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[StabShot] Playback error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                playing = false;
                if (!loopActive.get()) currentSong = null;
            }
        }, "StabShot-PlayThread");
        playThread.setDaemon(true);
        playThread.start();
        return null;
    }

    /** Used by {@code YtPlayer} to play a cached audio file. YtPlayer manages its own loop. */
    public static void playFile(final Path file) {
        stop();
        currentSong = file.getFileName().toString();
        looping     = false;
        loopActive.set(true);
        playing     = true;

        ensureJavaSoundChecked();

        playThread = new Thread(() -> {
            try {
                String lower = file.getFileName().toString().toLowerCase();
                String ext   = lower.endsWith(".mp3") ? "mp3" : "ogg";
                playOne(file, ext);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[StabShot] playFile error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                playing = false;
            }
        }, "StabShot-FilePlayThread");
        playThread.setDaemon(true);
        playThread.start();
    }

    public static void stop() {
        loopActive.set(false);
        playing = false;
        looping = false;

        // ── Desktop ───────────────────────────────────────────────────────────
        SourceDataLine line = currentLine;
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
            currentLine = null;
        }

        // ── Android / OpenAL: drain queue then shut down AL ───────────────────
        LinkedBlockingDeque<byte[]> q = pcmQueue;
        if (q != null) {
            q.clear();
            q.offer(POISON_PILL); // wake any blocking take()
        }

        // Stop the AL service loop thread first
        Thread svc = alServiceThread;
        if (svc != null) {
            svc.interrupt();
            alServiceThread = null;
        }

        // AL object teardown must run on the thread that owns the AL context
        final int   srcCopy  = alSource;
        final int[] bufsCopy = alBuffers;
        alSource  = 0;
        alBuffers = null;
        pcmQueue  = null;
        if (srcCopy != 0 || (bufsCopy != null && bufsCopy.length > 0)) {
            runOnMcThread(() -> alCleanup(srcCopy, bufsCopy));
        }

        // ── Decoder thread ────────────────────────────────────────────────────
        Thread t = playThread;
        if (t != null) {
            t.interrupt();
            playThread = null;
        }

        currentSong = null;
    }

    public static boolean isPlaying()      { return playing; }
    public static boolean isLooping()      { return looping; }
    public static String  getCurrentSong() { return currentSong; }

    public static List<String> getSongNames() {
        List<String> names = new ArrayList<>();
        Path dir = getSongsDir();
        if (!Files.exists(dir)) return names;
        File[] files = dir.toFile().listFiles(f -> {
            String n = f.getName().toLowerCase();
            return f.isFile() && (n.endsWith(".mp3") || n.endsWith(".ogg"));
        });
        if (files == null) return names;
        for (File f : files) {
            String n   = f.getName();
            int    dot = n.lastIndexOf('.');
            names.add(dot > 0 ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static Path getSongsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SONGS_FOLDER);
        try { if (!Files.exists(dir)) Files.createDirectories(dir); }
        catch (Exception ignored) {}
        return dir;
    }

    // ── Route to the right playback path ─────────────────────────────────────

    private static void playOne(Path file, String ext) throws Exception {
        if (useOpenAlFallback) {
            if ("mp3".equals(ext)) playMp3OpenAl(file);
            else                   playOggOpenAl(file);
        } else {
            try {
                if ("mp3".equals(ext)) playMp3(file);
                else                   playOgg(file);
            } catch (LineUnavailableException lue) {
                // javax.sound present but no usable audio device — fall back once
                useOpenAlFallback = true;
                System.err.println("[StabShot] javax.sound unavailable (" + lue.getMessage()
                        + "), switching to OpenAL fallback.");
                if ("mp3".equals(ext)) playMp3OpenAl(file);
                else                   playOggOpenAl(file);
            }
        }
    }

    private static void ensureJavaSoundChecked() {
        if (!javaSoundChecked) {
            useOpenAlFallback = !isJavaSoundAvailable();
            javaSoundChecked  = true;
            if (useOpenAlFallback) {
                System.out.println("[StabShot] javax.sound.sampled not available — using OpenAL path.");
            }
        }
    }

    // ── Desktop: javax.sound.sampled (UNCHANGED from original) ───────────────

    private static void playMp3(Path file) throws Exception {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream       bitstream = new Bitstream(fis);
            Decoder         decoder   = new Decoder();
            SourceDataLine  line      = null;

            try {
                while (loopActive.get() && !Thread.currentThread().isInterrupted()) {
                    Header header = bitstream.readFrame();
                    if (header == null) break;

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    if (line == null) {
                        int         sr  = output.getSampleFrequency();
                        int         ch  = output.getChannelCount();
                        AudioFormat fmt = new AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED, sr, 16, ch, ch * 2, sr, false);
                        line = (SourceDataLine) AudioSystem.getLine(
                                new DataLine.Info(SourceDataLine.class, fmt));
                        line.open(fmt);
                        line.start();
                        currentLine = line;
                    }

                    short[] samples = output.getBuffer();
                    int     count   = output.getBufferLength();
                    byte[]  bytes   = new byte[count * 2];
                    for (int i = 0; i < count; i++) {
                        bytes[i * 2]     = (byte)  (samples[i] & 0xFF);
                        bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
                    }
                    line.write(bytes, 0, bytes.length);
                    bitstream.closeFrame();
                }
            } finally {
                if (line != null) {
                    try { line.drain(); line.stop(); line.close(); } catch (Exception ignored) {}
                }
                currentLine = null;
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void playOgg(Path file) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat base    = raw.getFormat();
            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16,
                    base.getChannels(), base.getChannels() * 2,
                    base.getSampleRate(), false);

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(decoded, raw)) {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                        new DataLine.Info(SourceDataLine.class, decoded));
                line.open(decoded);
                line.start();
                currentLine = line;

                byte[] buf = new byte[4096];
                int n;
                while (loopActive.get()
                        && !Thread.currentThread().isInterrupted()
                        && (n = pcm.read(buf, 0, buf.length)) != -1) {
                    line.write(buf, 0, n);
                }
                try { line.drain(); line.stop(); line.close(); } catch (Exception ignored) {}
                currentLine = null;
            }
        }
    }

    // ── Android / OpenAL paths ────────────────────────────────────────────────

    /**
     * Decodes MP3 via JLayer (pure Java) and streams PCM to OpenAL.
     * Runs on the play thread; the AL service loop runs separately.
     */
    private static void playMp3OpenAl(Path file) throws Exception {
        alSampleRate = 44100;
        alChannels   = 2;

        pcmQueue = new LinkedBlockingDeque<>(64);
        final LinkedBlockingDeque<byte[]> queue = pcmQueue;

        setupAlAndStartService(queue);

        // Decode MP3 frames → PCM chunks
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bitstream = new Bitstream(fis);
            Decoder   decoder   = new Decoder();
            boolean   firstFrame = true;

            ByteArrayOutputStream acc = new ByteArrayOutputStream(AL_CHUNK_BYTES * 2);
            try {
                while (loopActive.get() && !Thread.currentThread().isInterrupted()) {
                    Header header = bitstream.readFrame();
                    if (header == null) break;

                    SampleBuffer buf = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (firstFrame) {
                        alSampleRate = buf.getSampleFrequency();
                        alChannels   = buf.getChannelCount();
                        firstFrame   = false;
                    }

                    short[] samples = buf.getBuffer();
                    int     count   = buf.getBufferLength();
                    for (int i = 0; i < count; i++) {
                        acc.write( samples[i] & 0xFF);
                        acc.write((samples[i] >> 8) & 0xFF);
                    }
                    if (acc.size() >= AL_CHUNK_BYTES) {
                        if (!offerChunk(queue, acc.toByteArray())) break;
                        acc.reset();
                    }
                    bitstream.closeFrame();
                }
                if (acc.size() > 0) offerChunk(queue, acc.toByteArray());
            } finally {
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }

        // Signal end-of-stream
        queue.offer(POISON_PILL, 5, TimeUnit.SECONDS);
        waitForAlDone();
    }

    /**
     * Decodes OGG via LWJGL STBVorbis (native) and streams PCM to OpenAL.
     * STBVorbis is part of the LWJGL bundle that ships with every Minecraft build.
     */
    private static void playOggOpenAl(Path file) throws Exception {
        byte[]     fileBytes   = Files.readAllBytes(file);
        ByteBuffer fileBuffer  = ByteBuffer.allocateDirect(fileBytes.length)
                                           .put(fileBytes);
        fileBuffer.flip();

        int[]  error   = {0};
        long   vorbis  = org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory(fileBuffer, error, null);
        if (vorbis == 0L) {
            throw new IOException("STBVorbis could not open: " + file + " (err=" + error[0] + ")");
        }

        try {
            // Read header info
            try (org.lwjgl.stb.STBVorbisInfo info = org.lwjgl.stb.STBVorbisInfo.malloc()) {
                org.lwjgl.stb.STBVorbis.stb_vorbis_get_info(vorbis, info);
                alSampleRate = info.sample_rate();
                alChannels   = info.channels();
            }

            pcmQueue = new LinkedBlockingDeque<>(64);
            final LinkedBlockingDeque<byte[]> queue = pcmQueue;

            setupAlAndStartService(queue);

            // Decode OGG frames
            int         samplesPerChunk = AL_CHUNK_BYTES / (alChannels * 2);
            ShortBuffer shortBuf        = ByteBuffer
                    .allocateDirect(samplesPerChunk * alChannels * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();

            while (loopActive.get() && !Thread.currentThread().isInterrupted()) {
                shortBuf.clear();
                int decoded = org.lwjgl.stb.STBVorbis
                        .stb_vorbis_get_samples_short_interleaved(vorbis, alChannels, shortBuf);
                if (decoded <= 0) break;

                // Convert interleaved shorts → little-endian byte array
                int    byteCount = decoded * alChannels * 2;
                byte[] chunk     = new byte[byteCount];
                for (int i = 0, j = 0; i < decoded * alChannels; i++, j += 2) {
                    short s = shortBuf.get(i);
                    chunk[j]     = (byte)  (s & 0xFF);
                    chunk[j + 1] = (byte) ((s >> 8) & 0xFF);
                }
                if (!offerChunk(queue, chunk)) break;
            }

            queue.offer(POISON_PILL, 5, TimeUnit.SECONDS);
            waitForAlDone();

        } finally {
            org.lwjgl.stb.STBVorbis.stb_vorbis_close(vorbis);
        }
    }

    // ── OpenAL helpers ────────────────────────────────────────────────────────

    /**
     * Initialises the AL source + buffer ring on the MC thread, then starts
     * the background service loop thread.
     */
    private static void setupAlAndStartService(LinkedBlockingDeque<byte[]> queue)
            throws Exception {
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicReference<Exception> err = new AtomicReference<>();

        runOnMcThread(() -> {
            try {
                int[] bufs = new int[AL_BUFFER_COUNT];
                org.lwjgl.openal.AL10.alGenBuffers(bufs);
                int src = org.lwjgl.openal.AL10.alGenSources();
                org.lwjgl.openal.AL10.alSourcef(src, org.lwjgl.openal.AL10.AL_GAIN,   1.0f);
                org.lwjgl.openal.AL10.alSource3f(src, org.lwjgl.openal.AL10.AL_POSITION, 0f, 0f, 0f);
                alBuffers = bufs;
                alSource  = src;
            } catch (Exception e) {
                err.set(e);
            } finally {
                ready.set(true);
            }
        });

        long deadline = System.currentTimeMillis() + 3000;
        while (!ready.get() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        if (err.get()  != null) throw err.get();
        if (alSource   == 0)    throw new IllegalStateException("[StabShot] AL source could not be created.");

        // Start the service loop (runs on a daemon thread, posts AL work to MC thread)
        AtomicBoolean doneSignal = new AtomicBoolean(false);
        alServiceThread = new Thread(() -> alServiceLoop(queue, doneSignal), "StabShot-AlService");
        alServiceThread.setDaemon(true);
        alServiceThread.start();
    }

    /**
     * Runs on a dedicated daemon thread.  Every {@value AL_POLL_MS} ms it posts
     * a short AL-maintenance task to the MC thread so buffers are kept filled
     * without blocking the render loop.
     *
     * <p>Terminates when the queue is exhausted <em>and</em> the AL source
     * finishes playing, or when the thread is interrupted (via {@link #stop()}).</p>
     */
    private static void alServiceLoop(LinkedBlockingDeque<byte[]> queue, AtomicBoolean doneSignal) {
        try {
            while (!Thread.currentThread().isInterrupted() && loopActive.get()) {
                Thread.sleep(AL_POLL_MS);

                // Post AL work to MC thread
                final int srcSnap = alSource;
                if (srcSnap == 0) break; // cleaned up by stop()

                // We use a CountDownLatch so we can wait for the MC-thread task to
                // finish before polling again (avoids over-queuing tasks).
                CountDownLatch latch = new CountDownLatch(1);
                final boolean[] shouldStop = {false};

                runOnMcThread(() -> {
                    try {
                        shouldStop[0] = alServiceTick(queue, srcSnap);
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await(200, TimeUnit.MILLISECONDS); // safety timeout
                if (shouldStop[0]) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneSignal.set(true);
        }
    }

    /**
     * Single AL service tick — called on the Minecraft main thread.
     *
     * @return {@code true} when playback is fully done and the loop should exit
     */
    private static boolean alServiceTick(LinkedBlockingDeque<byte[]> queue, int src) {
        if (src == 0 || src != alSource) return true; // cleaned up

        int alFormat = (alChannels == 2)
                ? org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
                : org.lwjgl.openal.AL10.AL_FORMAT_MONO16;

        // Unqueue processed buffers and refill them
        int processed = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);
        boolean exhausted = false;

        for (int i = 0; i < processed; i++) {
            int bufferId = org.lwjgl.openal.AL10.alSourceUnqueueBuffers(src);

            byte[] chunk = queue.poll(); // non-blocking; return null if empty
            if (chunk == null || chunk == POISON_PILL) {
                exhausted = true;
                break;
            }

            ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length)
                                      .order(ByteOrder.LITTLE_ENDIAN)
                                      .put(chunk);
            bb.flip();
            org.lwjgl.openal.AL10.alBufferData(bufferId, alFormat, bb, alSampleRate);
            org.lwjgl.openal.AL10.alSourceQueueBuffers(src, bufferId);
        }

        // Prime: if nothing is queued yet, fill with initial chunks
        int queued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
        if (queued == 0 && !exhausted) {
            int[] bufs = alBuffers;
            if (bufs != null) {
                for (int bufferId : bufs) {
                    byte[] chunk = queue.poll();
                    if (chunk == null || chunk == POISON_PILL) { exhausted = true; break; }
                    ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length)
                                              .order(ByteOrder.LITTLE_ENDIAN)
                                              .put(chunk);
                    bb.flip();
                    org.lwjgl.openal.AL10.alBufferData(bufferId, alFormat, bb, alSampleRate);
                    org.lwjgl.openal.AL10.alSourceQueueBuffers(src, bufferId);
                }
            }
        }

        // Restart if the source stalled due to buffer underrun
        int state = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
        int nowQueued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
        if (state != org.lwjgl.openal.AL10.AL_PLAYING && nowQueued > 0) {
            org.lwjgl.openal.AL10.alSourcePlay(src);
        }

        // Done when decoder signalled end-of-stream AND source finished playing
        if (exhausted) {
            int remainingQueued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
            int srcState        = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
            return remainingQueued == 0 || srcState == org.lwjgl.openal.AL10.AL_STOPPED;
        }
        return false;
    }

    /** Deletes AL source + buffers. Must be called on the MC (AL context) thread. */
    private static void alCleanup(int src, int[] bufs) {
        try {
            if (src != 0) {
                org.lwjgl.openal.AL10.alSourceStop(src);
                org.lwjgl.openal.AL10.alDeleteSources(src);
            }
        } catch (Exception ignored) {}
        try {
            if (bufs != null && bufs.length > 0) {
                org.lwjgl.openal.AL10.alDeleteBuffers(bufs);
            }
        } catch (Exception ignored) {}
    }

    /** Waits for the AL service loop to indicate that playback is finished. */
    private static void waitForAlDone() throws InterruptedException {
        Thread svc = alServiceThread;
        if (svc != null) {
            svc.join(); // wait for the service thread to exit naturally
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    /**
     * Offers a PCM chunk to the queue.  Blocks (with periodic wakeups) until
     * the queue has room or playback is stopped.
     *
     * @return {@code true} if the chunk was accepted, {@code false} if stopped
     */
    private static boolean offerChunk(LinkedBlockingDeque<byte[]> queue, byte[] chunk)
            throws InterruptedException {
        while (loopActive.get() && !Thread.currentThread().isInterrupted()) {
            if (queue.offer(chunk, 50, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code javax.sound.sampled} is present and has at
     * least one usable mixer (i.e. we are on a desktop JRE, not Android).
     */
    private static boolean isJavaSoundAvailable() {
        try {
            Class.forName("javax.sound.sampled.AudioSystem");
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            return mixers != null && mixers.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Schedules {@code r} on Minecraft's main thread via {@code MinecraftClient.execute()}. */
    private static void runOnMcThread(Runnable r) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.execute(r);
        else r.run(); // fallback: run inline (should not normally happen)
    }
}
