// SPDX-License-Identifier: Apache-2.0

package me.jxl.kiosk.plugins.dotring;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Lights the Echo Dot 2 LED ring when Kiosk Satellite reports a wake word. The ring is an IS31FL3236
 * whose kernel driver takes all 36 channels (12 RGB LEDs) as one string of 72 hex characters in its
 * "frame" sysfs attribute. All hardware access runs on a plugin-owned thread, so lifecycle callbacks
 * stay fast.
 */
public final class DotRingPlugin implements KioskPlugin {
    static final int CHANNELS = 36;
    private static final int CHANNELS_PER_LED = 3;
    private static final int LED_COUNT = 12;
    private static final int RING_OFFSET = 0;
    private static final int MAX_BRIGHTNESS = 255;
    private static final boolean REVERSE = false;
    private static final String COLOR_ORDER = "RGB";
    private static final String DEFAULT_FRAME_PATH = "/sys/bus/i2c/devices/0-003f/frame";
    private static final long FRAME_MS = 60;
    private static final long WAKE_SPIN_MS = 2400;
    private static final long ERROR_FLASH_MS = 1000;
    private static final double[] BLUE = {0.0, 0.4, 1.0};
    private static final double[] AMBER = {1.0, 0.62, 0.0};
    private static final double[] GREEN = {0.0, 1.0, 0.0};
    private static final double[] RED = {1.0, 0.0, 0.0};

    private final Object lock = new Object();
    private final LedWriter writer = new LedWriter();

    private PluginHost host;
    private ScheduledExecutorService ring;
    private ScheduledFuture<?> frames;

    // Settings, guarded by lock.
    private String framePath;
    private final String writerMode;
    // Runtime state, guarded by lock.
    private boolean active;
    private boolean voiceActive;
    private boolean wakeSpinActive;
    private long wakeSpinStartedAt;
    private boolean muted;
    private int wakewordStateEvents;
    private int voiceStateEvents;
    private boolean voiceStateSupported;
    private String satelliteState;
    private VoiceStage voiceStage = VoiceStage.IDLE;
    private long errorStartedAt;
    private long stageStartedAt;

    private enum VoiceStage { IDLE, LISTENING, THINKING, RESPONDING, ERROR }

    public DotRingPlugin() {
        this(DEFAULT_FRAME_PATH, LedWriter.AUTO);
    }

    DotRingPlugin(String framePath) {
        this(framePath, LedWriter.AUTO);
    }

    DotRingPlugin(String framePath, String writerMode) {
        this.framePath = framePath;
        this.writerMode = writerMode;
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        synchronized (lock) {
            this.host = host;
            ring = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dot-ring");
                thread.setDaemon(true);
                return thread;
            });
        }
        configure(settings);
        host.subscribe("wakeword.detected");
        host.subscribe("voice.interaction");
        host.subscribe("wakeword.state");
        try {
            host.subscribe("voice.state");
            synchronized (lock) {
                voiceStateSupported = true;
            }
        } catch (IllegalArgumentException olderHost) { /* Older KS versions can still show wake-word activity. */ }
        host.log("Echo Dot LED ring started");
        status("Waiting for a wake word.", false);
        host.executeCommand("getWakeWordState", Collections.emptyMap(), (ok, data, error) -> {
            boolean isMuted;
            synchronized (lock) {
                if (!ok || wakewordStateEvents != 0 || !(data instanceof Map)) return;
                muted = Boolean.TRUE.equals(((Map<?, ?>) data).get("muted"));
                if (muted) begin();
                isMuted = muted;
            }
            if (isMuted) status("Muted.", false);
        });
        host.executeCommand("getVoiceState", Collections.emptyMap(), (ok, data, error) -> {
            Map<?, ?> result = ok && data instanceof Map ? (Map<?, ?>) data : null;
            String initialStatus = null;
            boolean enabled = result != null && Boolean.TRUE.equals(result.get("enabled"));
            synchronized (lock) {
                if (host == null || !ok || result == null || voiceStateEvents != 0) return;
                if (!enabled) {
                    voiceActive = false;
                    voiceStage = VoiceStage.IDLE;
                    active = muted;
                    wakeSpinActive = false;
                    initialStatus = muted ? "Muted." : "Voice Satellite is off.";
                }
            }
            if (!enabled) {
                if (!muted) {
                    try { render(null); } catch (IOException | RuntimeException ignored) {}
                }
                if (initialStatus != null) status(initialStatus, false);
                return;
            }
            Object state = result.get("state");
            if (state instanceof String) updateSatelliteState((String) state);
        });
    }

    @Override
    public void configure(Map<String, Object> settings) {
        writer.setMode(writerMode);
        synchronized (lock) {
            voiceStage = VoiceStage.IDLE;
            errorStartedAt = 0;
            stageStartedAt = 0;
            voiceActive = false;
            active = false;
            wakeSpinActive = false;
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("This plugin has no actions: " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("ks.wakeword.detected".equals(event)) {
            trigger();
        } else if ("ks.voice.interaction".equals(event)) {
            boolean on = Boolean.TRUE.equals(payload.get("active"));
            String runtimeStatus = null;
            synchronized (lock) {
                if (muted) return;
                boolean wasActive = voiceActive;
                voiceActive = on;
                if (on) {
                    if (voiceStage == VoiceStage.IDLE) setVoiceStage(VoiceStage.LISTENING);
                    else begin();
                    runtimeStatus = statusForStage(voiceStage);
                } else if (wasActive && voiceStage == VoiceStage.LISTENING && !voiceStateSupported) {
                    // Without voice.state support, end the blue listening state from the interaction event.
                    voiceStage = VoiceStage.IDLE;
                    active = false;
                    runtimeStatus = "Waiting for a wake word.";
                }
            }
            if (runtimeStatus != null) status(runtimeStatus, false);
        } else if ("ks.wakeword.state".equals(event)) {
            boolean isMuted;
            synchronized (lock) {
                wakewordStateEvents++;
                muted = Boolean.TRUE.equals(payload.get("muted"));
                if (muted) {
                    voiceActive = false;
                    begin();
                } else {
                    active = false;
                    voiceActive = false;
                    wakeSpinActive = false;
                    voiceStage = VoiceStage.IDLE;
                }
                isMuted = muted;
            }
            status(isMuted ? "Muted." : "Waiting for a wake word.", false);
        } else if ("ks.voice.state".equals(event)) {
            synchronized (lock) {
                voiceStateEvents++;
            }
            Object state = payload.get("state");
            if (state instanceof String) updateSatelliteState((String) state);
        }
    }

    /** Applies the Voice Satellite state supplied by the KS SDK. */
    private void updateSatelliteState(String state) {
        String runtimeStatus = null;
        boolean runtimeError = false;
        synchronized (lock) {
            if (!"idle".equals(state) && !"listening".equals(state)
                    && !"processing".equals(state) && !"responding".equals(state)) return;
            if (muted) {
                satelliteState = state;
                return;
            } else {
                String previous = satelliteState;
                satelliteState = state;
                if ("listening".equals(state)) {
                    setVoiceStage(VoiceStage.LISTENING);
                    runtimeStatus = "Listening.";
                } else if ("processing".equals(state)) {
                    setVoiceStage(VoiceStage.THINKING);
                    runtimeStatus = "Thinking.";
                } else if ("responding".equals(state)) {
                    setVoiceStage(VoiceStage.RESPONDING);
                    runtimeStatus = "Responding.";
                } else if ("idle".equals(state)) {
                    // A turn that returns to idle from processing without responding likely failed.
                    if ("processing".equals(previous)) {
                        showError();
                        runtimeStatus = "Voice Satellite error.";
                        runtimeError = true;
                    } else {
                        setVoiceStage(VoiceStage.IDLE);
                        voiceActive = false;
                        if (!wakeSpinActive) active = false;
                        runtimeStatus = "Waiting for a wake word.";
                    }
                }
            }
        }
        if (runtimeStatus != null) status(runtimeStatus, runtimeError);
    }

    /** Caller holds lock. */
    private void setVoiceStage(VoiceStage stage) {
        if (muted) return;
        voiceStage = stage;
        stageStartedAt = System.currentTimeMillis();
        if (stage == VoiceStage.IDLE) {
            voiceActive = false;
            if (!wakeSpinActive) active = false;
            return;
        }
        voiceActive = true;
        begin();
    }

    /** Caller holds lock. */
    private void showError() {
        if (muted) return;
        voiceStage = VoiceStage.ERROR;
        errorStartedAt = System.currentTimeMillis();
        wakeSpinActive = false;
        voiceActive = false;
        begin();
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor;
        synchronized (lock) {
            executor = ring;
            ring = null;
            active = false;
            if (frames != null) frames.cancel(false);
            frames = null;
            host = null;
        }
        if (executor != null) {
            // Interrupt the frame worker, wait briefly for it, then darken the ring here.
            executor.shutdownNow();
            try {
                executor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            render(null);
        } catch (IOException | RuntimeException ignored) {
            // best effort, KS may already be tearing down
        }
        writer.close();
    }

    // --- triggering -------------------------------------------------------------------------

    private void trigger() {
        synchronized (lock) {
            if (muted) return;
            voiceStage = VoiceStage.IDLE;
            wakeSpinActive = true;
            wakeSpinStartedAt = System.currentTimeMillis();
            begin();
        }
        status("Wake word detected.", false);
    }

    /** Caller holds lock. */
    private void begin() {
        if (!active) writer.forget(); // another writer (e.g. the kernel boot animation) may have changed the ring
        active = true;
        if (ring != null && (frames == null || frames.isDone())) {
            frames = ring.scheduleAtFixedRate(this::frame, 0, FRAME_MS, TimeUnit.MILLISECONDS);
        }
    }

    // --- rendering (plugin thread) -----------------------------------------------------------

    private static final class Frame {
        final double[] level;
        final double master;
        final double[] rgb;

        Frame(double[] level, double master, double[] rgb) {
            this.level = level;
            this.master = master;
            this.rgb = rgb;
        }
    }

    private void frame() {
        try {
            Frame frame;
            boolean wakeSpinWasActive;
            String runtimeStatus = null;
            synchronized (lock) {
                wakeSpinWasActive = wakeSpinActive;
                frame = compute(System.currentTimeMillis());
                if (wakeSpinWasActive && !wakeSpinActive && voiceStage == VoiceStage.IDLE
                        && !voiceActive && !muted) {
                    runtimeStatus = "Waiting for a wake word.";
                }
                if (frame == null && frames != null) frames.cancel(false);
            }
            render(frame);
            if (runtimeStatus != null) status(runtimeStatus, false);
        } catch (IOException | RuntimeException error) {
            synchronized (lock) {
                active = false;
                if (frames != null) frames.cancel(false);
            }
            status("LED write failed: " + error.getMessage(), true);
        }
    }

    /** Caller holds lock. Returns null when the ring should be dark. */
    private Frame compute(long now) {
        if (muted) {
            double[] level = new double[LED_COUNT];
            for (int i = 0; i < LED_COUNT; i++) level[i] = 1.0;
            return new Frame(level, 1.0, RED);
        }
        if (wakeSpinActive) {
            long elapsed = now - wakeSpinStartedAt;
            if (elapsed < WAKE_SPIN_MS) {
                double head = (elapsed / (double) WAKE_SPIN_MS) * LED_COUNT * 2.0;
                double[] level = new double[LED_COUNT];
                for (int i = 0; i < LED_COUNT; i++) {
                    double distance = (head - i + LED_COUNT) % LED_COUNT;
                    level[i] = distance < 4 ? 1.0 - distance / 4.0 : 0.0;
                }
                return new Frame(level, 1.0, BLUE);
            }
            wakeSpinActive = false;
            if (voiceStage != VoiceStage.THINKING && voiceStage != VoiceStage.RESPONDING) {
                if (voiceActive || voiceStage == VoiceStage.LISTENING) {
                    voiceStage = VoiceStage.LISTENING;
                    return solidFrame(BLUE, 1.0);
                }
                active = false;
                return null;
            }
        }
        if (voiceStage == VoiceStage.THINKING) {
            double pulse = 0.2 + 0.8 * (0.5 + 0.5 * Math.sin(2 * Math.PI * (now - stageStartedAt) / 1400.0 - Math.PI / 2));
            return solidFrame(AMBER, pulse);
        }
        if (voiceStage == VoiceStage.RESPONDING) {
            return solidFrame(GREEN, 1.0);
        }
        if (voiceStage == VoiceStage.ERROR) {
            long elapsed = now - errorStartedAt;
            if (elapsed >= ERROR_FLASH_MS) {
                voiceStage = VoiceStage.IDLE;
                active = false;
                return null;
            }
            boolean lit = (elapsed >= 0 && elapsed < 150) || (elapsed >= 300 && elapsed < 450);
            return solidFrame(RED, lit ? 1.0 : 0.0);
        }
        if (voiceStage == VoiceStage.LISTENING) return solidFrame(BLUE, 1.0);
        if (!active) return null;
        return solidFrame(BLUE, 1.0);
    }

    private Frame solidFrame(double[] rgb, double brightness) {
        double[] level = new double[LED_COUNT];
        for (int i = 0; i < LED_COUNT; i++) level[i] = brightness;
        return new Frame(level, 1.0, rgb);
    }

    /** Turns one animation frame (or null = dark) into 36 channel values and writes them. */
    private void render(Frame frame) throws IOException {
        String order = COLOR_ORDER;
        int maxB = MAX_BRIGHTNESS;
        double[] rgb;
        int leds = LED_COUNT;
        int offset = RING_OFFSET;
        boolean flip = REVERSE;
        synchronized (lock) {
            rgb = frame != null && frame.rgb != null ? frame.rgb : BLUE;
        }
        int[] values = new int[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            int led = i / CHANNELS_PER_LED;
            if (frame == null || led >= leds) continue;
            int component = componentIndex(order.charAt(i % CHANNELS_PER_LED));
            double v = rgb[component] * frame.level[ringPosition(led, leds, offset, flip)] * frame.master;
            values[i] = (int) Math.round(Math.max(0, Math.min(1, v)) * maxB);
        }
        apply(values);
    }

    /** Maps a physical LED to its position after rotating and optionally mirroring the ring. */
    static int ringPosition(int led, int leds, int offset, boolean reverse) {
        int position = reverse ? leds - 1 - led : led;
        return (position + offset) % leds;
    }

    /** IS31FL3236 frame attribute: 36 bytes as 72 hex characters, one write for the whole ring. */
    private void apply(int[] values) throws IOException {
        String path;
        synchronized (lock) {
            path = framePath;
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < CHANNELS; i++) {
            int v = i < values.length ? values[i] : 0;
            hex.append(String.format(Locale.US, "%02x", Math.max(0, Math.min(255, v))));
        }
        writer.write(path, hex.toString());
    }

    // --- helpers -----------------------------------------------------------------------------

    private void status(String message, boolean error) {
        PluginHost h;
        synchronized (lock) {
            h = host;
        }
        if (h == null) return;
        try {
            h.status(message, error);
        } catch (RuntimeException revoked) {
            // host access revoked while stopping
        }
    }

    private static String statusForStage(VoiceStage stage) {
        switch (stage) {
            case LISTENING: return "Listening.";
            case THINKING: return "Thinking.";
            case RESPONDING: return "Responding.";
            case ERROR: return "Assist satellite error.";
            case IDLE:
            default: return "Waiting for a wake word.";
        }
    }

    private static int componentIndex(char c) {
        return c == 'R' ? 0 : c == 'G' ? 1 : 2;
    }

    private static String str(Map<String, Object> settings, String key, String fallback) {
        Object value = settings.get(key);
        return value instanceof String ? (String) value : fallback;
    }

}
