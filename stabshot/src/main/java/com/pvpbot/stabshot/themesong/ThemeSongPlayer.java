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

@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";

    // ── Shared playback state ─────────────────────────────────────────────────
    private static volatile Thread        playThread;
    private static volatile SourceDataLine currentLine;
    private static volatile String        currentSong;
    private static volatile boolean       playing  = false;
    private static volatile boolean       looping  = false;
    // loopActive gates the decode loops AND the do-while restart in play().
    // It is only set to false by stop() or when the user has not requested looping.
    private static final AtomicBoolean    loopActive = new AtomicBoolean(false);

    // ── Android / OpenAL ──────────────────────────────────────────────────────
    private static volatile boolean useOpenAlFallback = false;
    private static volatile boolean javaSoundChecked  = false;

    private static final int AL_BUFFER_COUNT = 4;
    private static final int AL_CHUNK_BYTES  = 16384;
    private static final int AL_POLL_MS      = 40;
    private static final byte[] POISON_PILL  = new byte[0];

    private static volatile int                        alSource    = 0;
    private static volatile int[]                      alBuffers   = null;
    private static volatile LinkedBlockingDeque<byte[]> pcmQueue;
    private static volatile int                        alSampleRate = 44100;
    private static volatile int                        alChannels   = 2;
    private static volatile Thread                     alServiceThread;

    // ── Public API ────────────────────────────────────────────────────────────

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
                    // After a natural end-of-file, if we are looping we restart.
                    // We don't check isInterrupted() here because a GC pause or
                    // brief scheduler delay should NOT stop the loop — only an
                    // explicit stop() call (which sets loopActive=false) should.
                } while (loop && loopActive.get());
            } catch (Exception e) {
                // Only log if it wasn't a deliberate stop
                if (loopActive.get()) {
                    System.err.println("[StabShot] Playback error: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                playing = false;
                if (!loopActive.get()) currentSong = null;
            }
        }, "StabShot-PlayThread");
        // NOT a daemon thread — daemon threads can be killed mid-song by the JVM
        // when it's under GC/scheduling pressure, which caused the cut-off bug.
        playThread.setDaemon(false);
        playThread.start();
        return null;
    }

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
            } catch (Exception e) {
                if (loopActive.get()) {
                    System.err.println("[StabShot] playFile error: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                playing = false;
            }
        }, "StabShot-FilePlayThread");
        playThread.setDaemon(false);
        playThread.start();
    }

    public static void stop() {
        loopActive.set(false);
        playing = false;
        looping = false;

        // ── Desktop ──────────────────────────────────────────────────────────
        SourceDataLine line = currentLine;
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
            currentLine = null;
        }

        // ── Android / OpenAL ─────────────────────────────────────────────────
        LinkedBlockingDeque<byte[]> q = pcmQueue;
        if (q != null) {
            q.clear();
            q.offer(POISON_PILL);
        }

        Thread svc = alServiceThread;
        if (svc != null) {
            svc.interrupt();
            alServiceThread = null;
        }

        final int   srcCopy  = alSource;
        final int[] bufsCopy = alBuffers;
        alSource  = 0;
        alBuffers = null;
        pcmQueue  = null;
        if (srcCopy != 0 || (bufsCopy != null && bufsCopy.length > 0)) {
            runOnMcThread(() -> alCleanup(srcCopy, bufsCopy));
        }

        // Interrupt the play thread so it unblocks from line.write() or queue.offer()
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

    // ── Routing ───────────────────────────────────────────────────────────────

    private static void playOne(Path file, String ext) throws Exception {
        if (useOpenAlFallback) {
            if ("mp3".equals(ext)) playMp3OpenAl(file);
            else                   playOggOpenAl(file);
        } else {
            try {
                if ("mp3".equals(ext)) playMp3(file);
                else                   playOgg(file);
            } catch (LineUnavailableException lue) {
                useOpenAlFallback = true;
                System.err.println("[StabShot] javax.sound unavailable, switching to OpenAL: " + lue.getMessage());
                if ("mp3".equals(ext)) playMp3OpenAl(file);
                else                   playOggOpenAl(file);
            }
        }
    }

    private static void ensureJavaSoundChecked() {
        if (!javaSoundChecked) {
            useOpenAlFallback = !isJavaSoundAvailable();
            javaSoundChecked  = true;
            if (useOpenAlFallback)
                System.out.println("[StabShot] javax.sound.sampled unavailable — using OpenAL.");
        }
    }

    // ── Desktop: javax.sound.sampled ──────────────────────────────────────────

    private static void playMp3(Path file) throws Exception {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream      bitstream = new Bitstream(fis);
            Decoder        decoder   = new Decoder();
            SourceDataLine line      = null;

            try {
                while (loopActive.get()) {
                    Header header;
                    try {
                        header = bitstream.readFrame();
                    } catch (BitstreamException e) {
                        // End of stream or corrupt frame — treat as end of file
                        break;
                    }
                    if (header == null) break;

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    if (line == null) {
                        int sr = output.getSampleFrequency();
                        int ch = output.getChannelCount();
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

                    // Write to audio line. If stop() was called, loopActive is now false
                    // and the line is already closed, so this will throw — that's fine,
                    // the finally block will clean up.
                    if (loopActive.get()) {
                        line.write(bytes, 0, bytes.length);
                    }

                    bitstream.closeFrame();
                }

                // Drain ensures the hardware buffer is fully played before we return.
                // This is what prevents the last ~0.5s from being cut off on loop restart.
                if (line != null && loopActive.get()) {
                    line.drain();
                }
            } finally {
                if (line != null) {
                    try { line.stop(); line.close(); } catch (Exception ignored) {}
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
                while (loopActive.get() && (n = pcm.read(buf, 0, buf.length)) != -1) {
                    if (loopActive.get()) line.write(buf, 0, n);
                }
                if (loopActive.get()) line.drain();
                try { line.stop(); line.close(); } catch (Exception ignored) {}
                currentLine = null;
            }
        }
    }

    // ── Android / OpenAL ──────────────────────────────────────────────────────

    private static void playMp3OpenAl(Path file) throws Exception {
        alSampleRate = 44100;
        alChannels   = 2;

        pcmQueue = new LinkedBlockingDeque<>(64);
        final LinkedBlockingDeque<byte[]> queue = pcmQueue;
        setupAlAndStartService(queue);

        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bitstream  = new Bitstream(fis);
            Decoder   decoder    = new Decoder();
            boolean   firstFrame = true;
            ByteArrayOutputStream acc = new ByteArrayOutputStream(AL_CHUNK_BYTES * 2);

            try {
                while (loopActive.get()) {
                    Header header;
                    try { header = bitstream.readFrame(); }
                    catch (BitstreamException e) { break; }
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

        queue.offer(POISON_PILL, 5, TimeUnit.SECONDS);
        waitForAlDone();
    }

    private static void playOggOpenAl(Path file) throws Exception {
        byte[]     fileBytes  = Files.readAllBytes(file);
        ByteBuffer fileBuffer = ByteBuffer.allocateDirect(fileBytes.length).put(fileBytes);
        fileBuffer.flip();

        int[] error  = {0};
        long  vorbis = org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory(fileBuffer, error, null);
        if (vorbis == 0L)
            throw new IOException("STBVorbis could not open: " + file + " (err=" + error[0] + ")");

        try {
            try (org.lwjgl.stb.STBVorbisInfo info = org.lwjgl.stb.STBVorbisInfo.malloc()) {
                org.lwjgl.stb.STBVorbis.stb_vorbis_get_info(vorbis, info);
                alSampleRate = info.sample_rate();
                alChannels   = info.channels();
            }

            pcmQueue = new LinkedBlockingDeque<>(64);
            final LinkedBlockingDeque<byte[]> queue = pcmQueue;
            setupAlAndStartService(queue);

            int         samplesPerChunk = AL_CHUNK_BYTES / (alChannels * 2);
            ShortBuffer shortBuf        = ByteBuffer
                    .allocateDirect(samplesPerChunk * alChannels * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();

            while (loopActive.get()) {
                shortBuf.clear();
                int decoded = org.lwjgl.stb.STBVorbis
                        .stb_vorbis_get_samples_short_interleaved(vorbis, alChannels, shortBuf);
                if (decoded <= 0) break;

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

    private static void setupAlAndStartService(LinkedBlockingDeque<byte[]> queue)
            throws Exception {
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicReference<Exception> err = new AtomicReference<>();

        runOnMcThread(() -> {
            try {
                int[] bufs = new int[AL_BUFFER_COUNT];
                org.lwjgl.openal.AL10.alGenBuffers(bufs);
                int src = org.lwjgl.openal.AL10.alGenSources();
                org.lwjgl.openal.AL10.alSourcef(src,  org.lwjgl.openal.AL10.AL_GAIN,     1.0f);
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
        if (err.get() != null) throw err.get();
        if (alSource  == 0)   throw new IllegalStateException("[StabShot] AL source could not be created.");

        AtomicBoolean doneSignal = new AtomicBoolean(false);
        alServiceThread = new Thread(() -> alServiceLoop(queue, doneSignal), "StabShot-AlService");
        alServiceThread.setDaemon(true);
        alServiceThread.start();
    }

    private static void alServiceLoop(LinkedBlockingDeque<byte[]> queue, AtomicBoolean doneSignal) {
        try {
            while (!Thread.currentThread().isInterrupted() && loopActive.get()) {
                Thread.sleep(AL_POLL_MS);

                final int srcSnap = alSource;
                if (srcSnap == 0) break;

                CountDownLatch latch = new CountDownLatch(1);
                final boolean[] shouldStop = {false};

                runOnMcThread(() -> {
                    try {
                        shouldStop[0] = alServiceTick(queue, srcSnap);
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await(200, TimeUnit.MILLISECONDS);
                if (shouldStop[0]) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneSignal.set(true);
        }
    }

    private static boolean alServiceTick(LinkedBlockingDeque<byte[]> queue, int src) {
        if (src == 0 || src != alSource) return true;

        int alFormat = (alChannels == 2)
                ? org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
                : org.lwjgl.openal.AL10.AL_FORMAT_MONO16;

        int     processed = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);
        boolean exhausted = false;

        for (int i = 0; i < processed; i++) {
            int    bufferId = org.lwjgl.openal.AL10.alSourceUnqueueBuffers(src);
            byte[] chunk    = queue.poll();
            if (chunk == null || chunk == POISON_PILL) { exhausted = true; break; }
            ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length)
                    .order(ByteOrder.LITTLE_ENDIAN).put(chunk);
            bb.flip();
            org.lwjgl.openal.AL10.alBufferData(bufferId, alFormat, bb, alSampleRate);
            org.lwjgl.openal.AL10.alSourceQueueBuffers(src, bufferId);
        }

        int queued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
        if (queued == 0 && !exhausted) {
            int[] bufs = alBuffers;
            if (bufs != null) {
                for (int bufferId : bufs) {
                    byte[] chunk = queue.poll();
                    if (chunk == null || chunk == POISON_PILL) { exhausted = true; break; }
                    ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length)
                            .order(ByteOrder.LITTLE_ENDIAN).put(chunk);
                    bb.flip();
                    org.lwjgl.openal.AL10.alBufferData(bufferId, alFormat, bb, alSampleRate);
                    org.lwjgl.openal.AL10.alSourceQueueBuffers(src, bufferId);
                }
            }
        }

        int state     = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
        int nowQueued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
        if (state != org.lwjgl.openal.AL10.AL_PLAYING && nowQueued > 0) {
            org.lwjgl.openal.AL10.alSourcePlay(src);
        }

        if (exhausted) {
            int remaining = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
            int srcState  = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
            return remaining == 0 || srcState == org.lwjgl.openal.AL10.AL_STOPPED;
        }
        return false;
    }

    private static void alCleanup(int src, int[] bufs) {
        try { if (src != 0) { org.lwjgl.openal.AL10.alSourceStop(src); org.lwjgl.openal.AL10.alDeleteSources(src); } } catch (Exception ignored) {}
        try { if (bufs != null && bufs.length > 0) org.lwjgl.openal.AL10.alDeleteBuffers(bufs); } catch (Exception ignored) {}
    }

    private static void waitForAlDone() throws InterruptedException {
        Thread svc = alServiceThread;
        if (svc != null) svc.join();
    }

    private static boolean offerChunk(LinkedBlockingDeque<byte[]> queue, byte[] chunk)
            throws InterruptedException {
        while (loopActive.get()) {
            if (queue.offer(chunk, 50, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

    private static boolean isJavaSoundAvailable() {
        try {
            Class.forName("javax.sound.sampled.AudioSystem");
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            return mixers != null && mixers.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void runOnMcThread(Runnable r) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.execute(r);
        else r.run();
    }
}
