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

/**
 * Cross-platform audio player for MP3 and OGG files.
 *
 * TWO BACKENDS — chosen automatically at first playback:
 *
 *  1. javax.sound (desktop — Windows/Linux/macOS)
 *     Uses SourceDataLine + JLayer for MP3, AudioSystem for OGG.
 *     Only used when a real, working audio line can be opened AND
 *     we are NOT on a known Android/mobile launcher.
 *
 *  2. OpenAL streaming (Android — ZalithLauncher, PojavLauncher, MojoLauncher)
 *     Decodes audio to PCM on a background thread, feeds it to OpenAL
 *     buffers on a dedicated AL thread that owns its own AL context share.
 *     Does NOT use mc.execute() — AL calls stay on the AL thread.
 *
 * WHY we can't trust getMixerInfo() on Android:
 *   Android JREs (including the ones in ZalithLauncher 1) stub out
 *   javax.sound.sampled.  getMixerInfo() returns fake mixers that open
 *   without error but silently discard all written audio.  This caused
 *   songs to "play" (no exception) but produce no sound and cut off
 *   randomly when the stub's internal buffer filled up.
 *   We detect this by: checking known Android JRE vendor strings, then
 *   attempting a real test-write through a tiny SourceDataLine.
 */
@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";

    // ── Playback state ────────────────────────────────────────────────────────
    private static volatile Thread         playThread;
    private static volatile SourceDataLine currentLine;
    private static volatile String         currentSong;
    private static volatile boolean        playing   = false;
    private static volatile boolean        looping   = false;
    private static final    AtomicBoolean  loopActive = new AtomicBoolean(false);

    // ── Backend selection ─────────────────────────────────────────────────────
    private static volatile boolean useOpenAlFallback = false;
    private static volatile boolean backendChecked    = false;

    // ── OpenAL streaming ──────────────────────────────────────────────────────
    private static final int     AL_BUFFER_COUNT = 8;   // more buffers = less starvation risk
    private static final int     AL_CHUNK_BYTES  = 32768; // 32 KB per buffer
    private static final byte[]  POISON_PILL     = new byte[0];

    private static volatile int                        alSource     = 0;
    private static volatile int[]                      alBuffers    = null;
    private static volatile LinkedBlockingDeque<byte[]> pcmQueue;
    private static volatile int                        alSampleRate = 44100;
    private static volatile int                        alChannels   = 2;
    private static volatile Thread                     alThread;    // owns the AL context

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
        ensureBackendChecked();

        playThread = new Thread(() -> {
            try {
                do {
                    playing = true;
                    playOne(fFile, fExt);
                } while (loop && loopActive.get());
            } catch (Exception e) {
                if (loopActive.get()) {
                    System.err.println("[StabShot] Playback error: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                playing = false;
                if (!loopActive.get()) currentSong = null;
            }
        }, "StabShot-PlayThread");
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
        ensureBackendChecked();

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

        // Desktop
        SourceDataLine line = currentLine;
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
            currentLine = null;
        }

        // OpenAL
        LinkedBlockingDeque<byte[]> q = pcmQueue;
        if (q != null) { q.clear(); q.offer(POISON_PILL); }

        Thread al = alThread;
        if (al != null) { al.interrupt(); alThread = null; }

        alSource  = 0;
        alBuffers = null;
        pcmQueue  = null;

        Thread t = playThread;
        if (t != null) { t.interrupt(); playThread = null; }

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
            String n = f.getName(); int dot = n.lastIndexOf('.');
            names.add(dot > 0 ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static Path getSongsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SONGS_FOLDER);
        try { if (!Files.exists(dir)) Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    // ── Backend selection ─────────────────────────────────────────────────────

    private static void ensureBackendChecked() {
        if (backendChecked) return;
        useOpenAlFallback = !isRealJavaSoundAvailable();
        backendChecked    = true;
        System.out.println("[StabShot] Audio backend: " + (useOpenAlFallback ? "OpenAL" : "javax.sound"));
    }

    /**
     * Returns true ONLY if:
     *   1. We are not on a known Android/mobile JRE, AND
     *   2. We can actually open and write to a SourceDataLine without error.
     *
     * Android JREs stub javax.sound — getMixerInfo() lies, returns fake mixers
     * that accept writes silently but produce no audio.
     */
    private static boolean isRealJavaSoundAvailable() {
        // Fast-fail on known Android/mobile JRE vendor strings
        String vendor = System.getProperty("java.vendor", "").toLowerCase();
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        if (vendor.contains("android") || vmName.contains("android")
                || vendor.contains("mobile") || vmName.contains("mobile")) {
            return false;
        }
        // Also check for the Caciocavallo/AWT-Less JRE used by some Android launchers
        String awtToolkit = System.getProperty("awt.toolkit", "").toLowerCase();
        if (awtToolkit.contains("caciocavallo") || awtToolkit.contains("mobile")) {
            return false;
        }

        // Try to actually open a real line and write 1 frame of silence.
        // If this throws or produces 0 bytes written, the backend is fake.
        try {
            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) return false;
            SourceDataLine testLine = (SourceDataLine) AudioSystem.getLine(info);
            testLine.open(fmt, 4096);
            testLine.start();
            byte[] silence = new byte[4]; // 1 stereo frame of silence
            int written = testLine.write(silence, 0, silence.length);
            testLine.stop();
            testLine.close();
            return written > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void playOne(Path file, String ext) throws Exception {
        if (useOpenAlFallback) {
            if ("mp3".equals(ext)) playMp3OpenAl(file);
            else                   playOggOpenAl(file);
        } else {
            try {
                if ("mp3".equals(ext)) playMp3(file);
                else                   playOgg(file);
            } catch (LineUnavailableException lue) {
                // Line opened fine in test but failed for real — switch permanently
                useOpenAlFallback = true;
                System.err.println("[StabShot] javax.sound line unavailable at runtime, switching to OpenAL: " + lue.getMessage());
                if ("mp3".equals(ext)) playMp3OpenAl(file);
                else                   playOggOpenAl(file);
            }
        }
    }

    // ── Desktop: javax.sound ──────────────────────────────────────────────────

    private static void playMp3(Path file) throws Exception {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream      bitstream = new Bitstream(fis);
            Decoder        decoder   = new Decoder();
            SourceDataLine line      = null;
            try {
                while (loopActive.get()) {
                    Header header;
                    try { header = bitstream.readFrame(); } catch (BitstreamException e) { break; }
                    if (header == null) break;

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (line == null) {
                        int sr = output.getSampleFrequency(), ch = output.getChannelCount();
                        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sr, 16, ch, ch * 2, sr, false);
                        line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
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
                    if (loopActive.get()) line.write(bytes, 0, bytes.length);
                    bitstream.closeFrame();
                }
                if (line != null && loopActive.get()) line.drain();
            } finally {
                if (line != null) { try { line.stop(); line.close(); } catch (Exception ignored) {} }
                currentLine = null;
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void playOgg(Path file) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat base = raw.getFormat();
            AudioFormat decoded = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16, base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(decoded, raw)) {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, decoded));
                line.open(decoded); line.start(); currentLine = line;
                byte[] buf = new byte[4096]; int n;
                while (loopActive.get() && (n = pcm.read(buf, 0, buf.length)) != -1) {
                    if (loopActive.get()) line.write(buf, 0, n);
                }
                if (loopActive.get()) line.drain();
                try { line.stop(); line.close(); } catch (Exception ignored) {}
                currentLine = null;
            }
        }
    }

    // ── OpenAL streaming ──────────────────────────────────────────────────────
    //
    // Architecture:
    //   playMp3OpenAl / playOggOpenAl   — decoder thread (this thread)
    //     decodes audio → PCM chunks → pcmQueue
    //   alThread                        — AL thread
    //     creates its own AL context, owns all AL object lifetimes,
    //     pumps buffers from pcmQueue into OpenAL continuously.
    //
    // We do NOT bounce AL calls through mc.execute().  Doing so on ZalithLauncher
    // caused silent playback because the MC execute queue is not drained on every
    // tick there, starving the AL buffers.  Instead we create a dedicated thread
    // that calls alcMakeContextCurrent to share Minecraft's AL device, then
    // manages all AL state itself.

    private static void playMp3OpenAl(Path file) throws Exception {
        alSampleRate = 44100;
        alChannels   = 2;
        pcmQueue     = new LinkedBlockingDeque<>(128);
        final LinkedBlockingDeque<byte[]> queue = pcmQueue;

        startAlThread(queue);

        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bitstream  = new Bitstream(fis);
            Decoder   decoder    = new Decoder();
            boolean   firstFrame = true;
            ByteArrayOutputStream acc = new ByteArrayOutputStream(AL_CHUNK_BYTES * 2);
            try {
                while (loopActive.get()) {
                    Header header;
                    try { header = bitstream.readFrame(); } catch (BitstreamException e) { break; }
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
        joinAlThread();
    }

    private static void playOggOpenAl(Path file) throws Exception {
        byte[]     bytes  = Files.readAllBytes(file);
        ByteBuffer fileBuf = ByteBuffer.allocateDirect(bytes.length).put(bytes);
        fileBuf.flip();

        int[] error  = {0};
        long  vorbis = org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory(fileBuf, error, null);
        if (vorbis == 0L) throw new IOException("STBVorbis failed on: " + file + " err=" + error[0]);

        try {
            try (org.lwjgl.stb.STBVorbisInfo info = org.lwjgl.stb.STBVorbisInfo.malloc()) {
                org.lwjgl.stb.STBVorbis.stb_vorbis_get_info(vorbis, info);
                alSampleRate = info.sample_rate();
                alChannels   = info.channels();
            }

            pcmQueue = new LinkedBlockingDeque<>(128);
            final LinkedBlockingDeque<byte[]> queue = pcmQueue;
            startAlThread(queue);

            int         samplesPerChunk = AL_CHUNK_BYTES / (alChannels * 2);
            ShortBuffer shortBuf = ByteBuffer
                    .allocateDirect(samplesPerChunk * alChannels * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();

            while (loopActive.get()) {
                shortBuf.clear();
                int decoded = org.lwjgl.stb.STBVorbis
                        .stb_vorbis_get_samples_short_interleaved(vorbis, alChannels, shortBuf);
                if (decoded <= 0) break;
                int byteCount = decoded * alChannels * 2;
                byte[] chunk  = new byte[byteCount];
                for (int i = 0, j = 0; i < decoded * alChannels; i++, j += 2) {
                    short s = shortBuf.get(i);
                    chunk[j]     = (byte)  (s & 0xFF);
                    chunk[j + 1] = (byte) ((s >> 8) & 0xFF);
                }
                if (!offerChunk(queue, chunk)) break;
            }
            queue.offer(POISON_PILL, 5, TimeUnit.SECONDS);
            joinAlThread();
        } finally {
            org.lwjgl.stb.STBVorbis.stb_vorbis_close(vorbis);
        }
    }

    /**
     * Starts the AL thread.  The thread:
     *   1. Acquires Minecraft's AL device handle via alcGetContextsDevice on
     *      the current context, then creates a NEW context that shares it.
     *   2. Makes that context current on this thread.
     *   3. Allocates AL source + buffers.
     *   4. Pumps PCM from the queue into AL until the POISON_PILL arrives.
     *   5. Drains remaining queued AL buffers until the source stops.
     *   6. Cleans up AL objects and releases the context.
     */
    private static void startAlThread(LinkedBlockingDeque<byte[]> queue) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);

        alThread = new Thread(() -> {
            long device  = 0L;
            long context = 0L;
            int  src     = 0;
            int[] bufs   = null;

            try {
                // Share Minecraft's AL device by getting it from the current context
                long mcContext = org.lwjgl.openal.ALC10.alcGetCurrentContext();
                device  = org.lwjgl.openal.ALC10.alcGetContextsDevice(mcContext);
                context = org.lwjgl.openal.ALC10.alcCreateContext(device, (java.nio.IntBuffer) null);
                org.lwjgl.openal.ALC10.alcMakeContextCurrent(context);

                bufs = new int[AL_BUFFER_COUNT];
                org.lwjgl.openal.AL10.alGenBuffers(bufs);
                src = org.lwjgl.openal.AL10.alGenSources();
                org.lwjgl.openal.AL10.alSourcef(src, org.lwjgl.openal.AL10.AL_GAIN, 1.0f);
                org.lwjgl.openal.AL10.alSource3f(src, org.lwjgl.openal.AL10.AL_POSITION, 0f, 0f, 0f);

                alSource  = src;
                alBuffers = bufs;
                ready.countDown(); // signal that AL is set up

                int alFormat = (alChannels == 2)
                        ? org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
                        : org.lwjgl.openal.AL10.AL_FORMAT_MONO16;

                boolean decoderDone = false;

                // Prime: fill all buffers before starting playback
                for (int b : bufs) {
                    byte[] chunk = queue.poll(2, TimeUnit.SECONDS);
                    if (chunk == null || chunk == POISON_PILL) { decoderDone = true; break; }
                    ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length).order(ByteOrder.LITTLE_ENDIAN).put(chunk);
                    bb.flip();
                    org.lwjgl.openal.AL10.alBufferData(b, alFormat, bb, alSampleRate);
                    org.lwjgl.openal.AL10.alSourceQueueBuffers(src, b);
                }
                org.lwjgl.openal.AL10.alSourcePlay(src);

                // Streaming loop
                while (!Thread.currentThread().isInterrupted() && loopActive.get()) {
                    // Unqueue processed buffers and refill them
                    int processed = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);
                    for (int i = 0; i < processed; i++) {
                        int bufferId = org.lwjgl.openal.AL10.alSourceUnqueueBuffers(src);
                        if (decoderDone) continue; // no more data, just drain
                        byte[] chunk = decoderDone ? null : queue.poll(100, TimeUnit.MILLISECONDS);
                        if (chunk == null || chunk == POISON_PILL) { decoderDone = true; continue; }
                        ByteBuffer bb = ByteBuffer.allocateDirect(chunk.length).order(ByteOrder.LITTLE_ENDIAN).put(chunk);
                        bb.flip();
                        org.lwjgl.openal.AL10.alBufferData(bufferId, alFormat, bb, alSampleRate);
                        org.lwjgl.openal.AL10.alSourceQueueBuffers(src, bufferId);
                    }

                    // Restart source if it stalled due to buffer underrun
                    int state     = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
                    int nowQueued = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
                    if (state != org.lwjgl.openal.AL10.AL_PLAYING && nowQueued > 0) {
                        org.lwjgl.openal.AL10.alSourcePlay(src);
                    }

                    // Exit when decoder is done and all queued buffers have played out
                    if (decoderDone) {
                        int remaining = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
                        int srcState  = org.lwjgl.openal.AL10.alGetSourcei(src, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
                        if (remaining == 0 || srcState == org.lwjgl.openal.AL10.AL_STOPPED) break;
                    }

                    Thread.sleep(20);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[StabShot] AL thread error: " + e.getMessage());
                e.printStackTrace();
                ready.countDown(); // unblock caller even on error
            } finally {
                // Clean up AL objects on this thread (context is current here)
                try {
                    if (src  != 0) { org.lwjgl.openal.AL10.alSourceStop(src); org.lwjgl.openal.AL10.alDeleteSources(src); }
                } catch (Exception ignored) {}
                try {
                    if (bufs != null) org.lwjgl.openal.AL10.alDeleteBuffers(bufs);
                } catch (Exception ignored) {}
                // Restore MC's context as current on this thread before we exit,
                // then destroy ours.
                try {
                    long mcContext = org.lwjgl.openal.ALC10.alcGetCurrentContext();
                    // Our context is current — switch back to none, then destroy
                    org.lwjgl.openal.ALC10.alcMakeContextCurrent(0L);
                    if (context != 0L) org.lwjgl.openal.ALC10.alcDestroyContext(context);
                } catch (Exception ignored) {}
                alSource  = 0;
                alBuffers = null;
            }
        }, "StabShot-AlThread");
        alThread.setDaemon(true);
        alThread.start();

        // Wait up to 3 s for AL setup before the decoder starts feeding data
        ready.await(3, TimeUnit.SECONDS);
    }

    private static void joinAlThread() throws InterruptedException {
        Thread al = alThread;
        if (al != null) {
            al.join();
            alThread = null;
        }
    }

    private static boolean offerChunk(LinkedBlockingDeque<byte[]> queue, byte[] chunk)
            throws InterruptedException {
        while (loopActive.get()) {
            if (queue.offer(chunk, 50, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }
}
