package shit.zen.modules.impl.misc.music;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

public class AudioPlayer {
    public enum State { STOPPED, PLAYING, PAUSED, LOADING }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private volatile float volume = 0.8f;
    private volatile SongInfo currentSong;
    private volatile String currentUrl;
    private volatile SourceDataLine currentLine;
    private volatile Thread playbackThread;
    private volatile boolean paused;

    // time-based progress tracking
    private volatile long playStartMs;
    private volatile long pauseStartMs;
    private volatile long totalPausedMs;

    // seek support
    private volatile long seekTargetMs = -1;
    private volatile long bytesConsumedFromStream = 0;

    public void play(SongInfo song, String url) {
        stop();
        this.currentSong = song;
        this.currentUrl = url;
        this.state.set(State.LOADING);
        this.paused = false;
        this.totalPausedMs = 0;
        this.playStartMs = System.currentTimeMillis();
        this.seekTargetMs = -1;
        playbackThread = new Thread(() -> playInternal(url), "MusicPlayer-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void pause() {
        if (state.get() == State.PLAYING && currentLine != null) {
            paused = true;
            pauseStartMs = System.currentTimeMillis();
            currentLine.stop();
            state.set(State.PAUSED);
        }
    }

    public void resume() {
        if (state.get() == State.PAUSED && currentLine != null) {
            paused = false;
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            currentLine.start();
            state.set(State.PLAYING);
        }
    }

    public void stop() {
        state.set(State.STOPPED);
        paused = false;
        seekTargetMs = -1;
        currentSong = null;
        currentUrl = null;
        if (currentLine != null) {
            try { currentLine.drain(); } catch (Exception ignored) {}
            try { currentLine.close(); } catch (Exception ignored) {}
            currentLine = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void togglePause() {
        if (state.get() == State.PLAYING) {
            pause();
        } else if (state.get() == State.PAUSED) {
            resume();
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0f, Math.min(1f, vol));
        applyVolume();
    }

    public void seekToMs(long targetMs) {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return;
        State s = state.get();
        if (s != State.PLAYING && s != State.PAUSED) return;
        targetMs = Math.max(0, Math.min(targetMs, song.duration));
        seekTargetMs = targetMs;
    }

    private void applySeek(long targetMs, AudioFormat format, SourceDataLine line) {
        int bytesPerFrame = format.getFrameSize();
        int sampleRate = (int) format.getSampleRate();
        if (bytesPerFrame <= 0 || sampleRate <= 0) return;
        long bytesToSkip = (long) (targetMs / 1000.0 * sampleRate * bytesPerFrame);
        // Drain what's already written so the line plays from the new position
        line.drain();
        line.flush();
        // We can't skip in the current stream, so signal restart
        // playInternal will handle it via seekTargetMs
    }

    public void prevSongFallback() {
        SongInfo song = currentSong;
        if (song == null) return;
        seekToMs(0);
    }

    public void nextSongFallback() {
        // No-op without queue context
    }

    public float getVolume() { return volume; }
    public State getState() { return state.get(); }
    public SongInfo getCurrentSong() { return currentSong; }

    public float getProgress() {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return 0f;
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0f;
        long now = System.currentTimeMillis();
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        long elapsed = now - playStartMs - paused;
        return Math.max(0f, Math.min(1f, (float) elapsed / song.duration));
    }

    public long getCurrentPositionMs() {
        if (currentSong == null) return 0;
        long now = System.currentTimeMillis();
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0;
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        return Math.max(0, now - playStartMs - paused);
    }

    private void playInternal(String url) {
        SourceDataLine localLine = null;
        try {
            System.out.println("[MusicPlayer] Starting playback: " + url);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                    .build();

            HttpResponse<java.io.InputStream> resp = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            BufferedInputStream bis = new BufferedInputStream(resp.body());
            AudioInputStream rawStream = AudioSystem.getAudioInputStream(bis);
            AudioFormat baseFormat = rawStream.getFormat();

            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );

            AudioInputStream ais = AudioSystem.getAudioInputStream(decoded, rawStream);

            // compute duration from audio stream
            if (currentSong != null) {
                long frames = rawStream.getFrameLength();
                if (frames > 0) {
                    currentSong.duration = (long)(frames / baseFormat.getSampleRate() * 1000);
                } else {
                    long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (contentLength > 0) {
                        int bytesPerSec = (int)(baseFormat.getSampleRate() * baseFormat.getFrameSize());
                        if (bytesPerSec > 0) {
                            currentSong.duration = (contentLength * 1000L) / bytesPerSec;
                        }
                    }
                }
                System.out.println("[MusicPlayer] Duration: " + currentSong.duration + "ms");
            }

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded);
            localLine = (SourceDataLine) AudioSystem.getLine(info);
            localLine.open(decoded);
            currentLine = localLine;
            applyVolume();
            localLine.start();

            playStartMs = System.currentTimeMillis();
            totalPausedMs = 0;
            state.set(State.PLAYING);
            bytesConsumedFromStream = 0;

            int bytesPerFrame = decoded.getFrameSize();
            int sampleRate = (int) decoded.getSampleRate();

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = ais.read(buffer, 0, buffer.length)) != -1) {
                bytesConsumedFromStream += bytesRead;
                if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;

                // Handle seek
                long seek = seekTargetMs;
                if (seek >= 0) {
                    seekTargetMs = -1;
                    long absoluteTargetBytes = (long) (seek / 1000.0 * sampleRate * bytesPerFrame);
                    long relativeSkip = absoluteTargetBytes - bytesConsumedFromStream;

                    if (relativeSkip > 0) {
                        // Forward seek: skip bytes in current stream
                        long skipped = 0;
                        byte[] skipBuf = new byte[8192];
                        while (skipped < relativeSkip) {
                            if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;
                            long toRead = Math.min(skipBuf.length, relativeSkip - skipped);
                            int n = ais.read(skipBuf, 0, (int) toRead);
                            if (n < 0) break;
                            skipped += n;
                            bytesConsumedFromStream += n;
                        }
                    } else if (relativeSkip < 0) {
                        // Backward seek: can't rewind HTTP stream, restart from URL
                        seekTargetMs = seek; // preserve for restart
                        localLine.flush();
                        break;
                    }

                    localLine.flush();
                    playStartMs = System.currentTimeMillis() - seek;
                    totalPausedMs = 0;
                    if (paused) {
                        pauseStartMs = System.currentTimeMillis();
                    }
                    continue;
                }

                if (paused) {
                    Thread.sleep(50);
                    continue;
                }
                localLine.write(buffer, 0, bytesRead);
            }

            ais.close();
            rawStream.close();

            // Check for pending backward seek — restart stream from URL
            long pendingSeek = seekTargetMs;
            if (pendingSeek >= 0 && state.get() != State.STOPPED) {
                seekTargetMs = -1;
                System.out.println("[MusicPlayer] Backward seek to " + pendingSeek + "ms, restarting stream");
                playInternal(url); // recursive restart
                return;
            }

            if (state.get() == State.PLAYING) {
                state.set(State.STOPPED);
            }
            System.out.println("[MusicPlayer] Playback finished");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            if (state.get() != State.STOPPED) {
                state.set(State.STOPPED);
            }
        } finally {
            if (localLine != null) {
                try { localLine.drain(); } catch (Exception ignored) {}
                try { localLine.close(); } catch (Exception ignored) {}
                if (currentLine == localLine) currentLine = null;
            }
        }
    }

    private void applyVolume() {
        if (currentLine != null && currentLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) currentLine.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log(Math.max(volume, 0.0001)) / Math.log(10.0) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(dB, gain.getMaximum())));
        }
    }
}
