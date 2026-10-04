// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.dotring;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against the real SDK interfaces and a temporary file that stands in for the sysfs frame attribute. */
public final class DotRingTest {
    static final class Host implements PluginHost {
        final List<String> subscriptions = Collections.synchronizedList(new ArrayList<>());
        final List<String> statuses = Collections.synchronizedList(new ArrayList<>());
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        @Override public void subscribe(String event) { subscriptions.add(event); }
        @Override public void executeCommand(String command, Map<String, Object> args, CommandCallback callback) {
            commands.add(command);
            Map<String, Object> state = new HashMap<>();
            if ("getVoiceState".equals(command)) {
                state.put("enabled", true);
                state.put("state", "idle");
            } else {
                state.put("muted", false);
            }
            callback.onResult(true, state, null);
        }
        @Override public void status(String message, boolean error) { statuses.add((error ? "ERROR " : "") + message); }
    }

    static String rep(String unit, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(unit);
        return out.toString();
    }

    static final String DARK = rep("00", 36);

    static Map<String, Object> settings(File frame) {
        return new HashMap<>();
    }

    /** A regular file is truncated before it is rewritten, sysfs is not. Retry if the read hit that window. */
    static String read(File file) throws Exception {
        for (int i = 0; i < 50; i++) {
            String text = new String(Files.readAllBytes(file.toPath()), "US-ASCII").trim();
            if (text.length() == 72) return text;
            Thread.sleep(5);
        }
        return "";
    }

    static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    static String lastStatus(Host host) {
        synchronized (host.statuses) {
            return host.statuses.isEmpty() ? "" : host.statuses.get(host.statuses.size() - 1);
        }
    }

    static void assertFrameNeverAppears(File file, String unexpected, long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            if (frameIs(file, unexpected)) throw new AssertionError("Unexpected LED frame: " + unexpected);
            Thread.sleep(20);
        }
    }

    static boolean frameIs(File file, String expected) {
        try {
            return read(file).equals(expected);
        } catch (Exception error) {
            return false;
        }
    }

    static boolean isAmberFrame(File file) {
        try {
            String hex = read(file);
            if (hex.length() != 72) return false;
            for (int i = 0; i < 12; i++) {
                int r = Integer.parseInt(hex.substring(i * 6, i * 6 + 2), 16);
                int g = Integer.parseInt(hex.substring(i * 6 + 2, i * 6 + 4), 16);
                int b = Integer.parseInt(hex.substring(i * 6 + 4, i * 6 + 6), 16);
                if (r == 0 || g == 0 || b != 0) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    static boolean isBlueSpinnerFrame(File file) {
        try {
            String hex = read(file);
            if (hex.length() != 72) return false;
            int bright = 0;
            int dark = 0;
            for (int i = 0; i < 12; i++) {
                int r = Integer.parseInt(hex.substring(i * 6, i * 6 + 2), 16);
                int g = Integer.parseInt(hex.substring(i * 6 + 2, i * 6 + 4), 16);
                int b = Integer.parseInt(hex.substring(i * 6 + 4, i * 6 + 6), 16);
                if (r != 0) return false;
                // Unlit LEDs are all zero; only lit pixels must carry blue's green component.
                if (b > 40) {
                    if (g == 0) return false;
                    bright++;
                }
                if (b == 0 && g == 0) dark++;
            }
            return bright > 0 && bright < 12 && dark > 0;
        } catch (Exception error) {
            return false;
        }
    }

    static Map<String, Object> voice(String source, boolean active) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("source", source);
        payload.put("active", active);
        return payload;
    }

    static Map<String, Object> satellite(String state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "available");
        payload.put("state", state);
        return payload;
    }

    public static void main(String[] args) throws Exception {
        File frame = File.createTempFile("dot-ring-frame", ".txt");
        frame.deleteOnExit();
        Host host = new Host();
        DotRingPlugin plugin = new DotRingPlugin(frame.getAbsolutePath());
        Map<String, Object> settings = settings(frame);

        // Ring geometry: offset rotates, reverse mirrors, both wrap around.
        assert DotRingPlugin.ringPosition(0, 12, 0, false) == 0;
        assert DotRingPlugin.ringPosition(0, 12, 3, false) == 3;
        assert DotRingPlugin.ringPosition(11, 12, 3, false) == 2;
        assert DotRingPlugin.ringPosition(0, 12, 0, true) == 11;
        assert DotRingPlugin.ringPosition(0, 12, 1, true) == 0;

        plugin.start(host, settings);
        assert lastStatus(host).equals("Waiting for a wake word.");
        assert host.subscriptions.contains("wakeword.detected") && host.subscriptions.contains("voice.interaction");
        assert host.subscriptions.contains("wakeword.state");
        assert host.subscriptions.contains("voice.state");
        assert !host.subscriptions.contains("ha.entity.assist_satellite.echo_dot");
        assert host.commands.contains("getVoiceState");

        // Wake word: spinner makes two full turns in fixed RGB blue, then goes dark.
        long spinStarted = System.currentTimeMillis();
        plugin.onEvent("ks.wakeword.detected", new HashMap<>());
        assert lastStatus(host).equals("Wake word detected.");
        await("blue wake spinner", () -> isBlueSpinnerFrame(frame));
        await("ring dark after two full spins", () -> frameIs(frame, DARK));
        await("waiting status after wake spinner", () -> lastStatus(host).equals("Waiting for a wake word."));
        assert lastStatus(host).equals("Waiting for a wake word.");
        long spinElapsed = System.currentTimeMillis() - spinStarted;
        assert spinElapsed >= 2200 && spinElapsed < 3500 : "Wake spinner did not run for two turns: " + spinElapsed;

        // Every documented interaction source keeps the ring blue after the wake-word spin.
        plugin.onEvent("ks.voice.interaction", voice("command", true));
        assert lastStatus(host).equals("Listening.");
        await("ring lit during voice", () -> frameIs(frame, rep("0066ff", 12)));
        Thread.sleep(1500);
        assert frameIs(frame, rep("0066ff", 12)) : "Ring went dark during a voice interaction";
        plugin.onEvent("ks.voice.state", satellite("processing"));
        assert lastStatus(host).equals("Thinking.");
        await("amber thinking frame", () -> isAmberFrame(frame));
        plugin.onEvent("ks.voice.state", satellite("responding"));
        assert lastStatus(host).equals("Responding.");
        await("green response frame", () -> frameIs(frame, rep("00ff00", 12)));
        plugin.onEvent("ks.voice.state", satellite("idle"));
        assert lastStatus(host).equals("Waiting for a wake word.");
        await("ring dark after responding returns to idle", () -> frameIs(frame, DARK));
        assertFrameNeverAppears(frame, rep("0066ff", 12), 1100);

        // A direct listening -> idle transition turns the ring off without a completion flash.
        plugin.onEvent("ks.voice.state", satellite("listening"));
        await("blue listening frame", () -> frameIs(frame, rep("0066ff", 12)));
        plugin.onEvent("ks.voice.state", satellite("idle"));
        await("ring dark after listening returns to idle", () -> frameIs(frame, DARK));
        assertFrameNeverAppears(frame, rep("0066ff", 12), 1100);

        // Returning to idle directly from processing is presented as a brief error double flash.
        plugin.onEvent("ks.voice.state", satellite("processing"));
        Thread.sleep(400);
        plugin.onEvent("ks.voice.state", satellite("idle"));
        await("error red flash", () -> frameIs(frame, rep("ff0000", 12)));
        await("error flash ends", () -> frameIs(frame, DARK));

        // Muted state is steady red and takes precedence over wake-word events.
        Map<String, Object> wakewordState = new HashMap<>();
        wakewordState.put("muted", true);
        plugin.onEvent("ks.wakeword.state", wakewordState);
        assert lastStatus(host).equals("Muted.");
        await("red mute frame", () -> frameIs(frame, rep("ff0000", 12)));
        plugin.onEvent("ks.wakeword.detected", new HashMap<>());
        Thread.sleep(250);
        assert frameIs(frame, rep("ff0000", 12)) : "Wake word replaced mute indication";
        wakewordState.put("muted", false);
        plugin.onEvent("ks.wakeword.state", wakewordState);
        await("ring dark after unmute", () -> frameIs(frame, DARK));

        // stop() leaves the ring dark.
        plugin.stop();
        assert frameIs(frame, DARK) : "Ring not dark after stop";

        // A bad path is reported as a status, not thrown into the host.
        Host failing = new Host();
        // Force direct mode so the invalid-path error is synchronous and testable; AUTO falls back to su.
        DotRingPlugin broken = new DotRingPlugin("/nonexistent/dir/frame", LedWriter.DIRECT);
        Map<String, Object> badSettings = settings(frame);
        broken.start(failing, badSettings);
        broken.onEvent("ks.wakeword.detected", new HashMap<>());
        await("write error status", () -> {
            synchronized (failing.statuses) {
                for (String status : failing.statuses) if (status.startsWith("ERROR LED write failed")) return true;
            }
            return false;
        });
        broken.stop();

        // No actions are exposed by the plugin.
        DotRingPlugin other = new DotRingPlugin(frame.getAbsolutePath());
        other.start(new Host(), settings);
        try {
            other.execute("nope", new HashMap<>());
            throw new AssertionError("Unknown command accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        other.stop();
        System.out.println("DotRingTest passed");
    }
}
