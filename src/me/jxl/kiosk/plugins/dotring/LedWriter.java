// SPDX-License-Identifier: Apache-2.0

package me.jxl.kiosk.plugins.dotring;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * Writes one hex string into the sysfs frame attribute. Auto mode tries a direct write first and
 * falls back to a persistent root shell ("su"), so a frame costs a single flush. Uses only API 24
 * classes (no Process.isAlive, no java.nio.file).
 */
final class LedWriter {
    static final String AUTO = "Auto";
    static final String DIRECT = "Direct";
    static final String SU = "Su";

    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9_.:/-]+$");
    private static final Pattern SAFE_VALUE = Pattern.compile("^[0-9a-fA-F]{1,128}$");

    private String mode = AUTO;
    private boolean useShell;
    private Process shell;
    private OutputStream shellIn;
    private volatile String shellError = "";
    private String lastPath;
    private String lastValue;

    synchronized void setMode(String next) {
        if (next.equals(mode)) return;
        mode = next;
        useShell = SU.equals(next);
        closeShell();
        forget();
    }

    /** Last stderr/stdout line of the root shell, for diagnostics. */
    String shellError() {
        return shellError;
    }

    synchronized boolean usingShell() {
        return useShell;
    }

    /** Forgets the cached value so the next write is sent even if it equals the previous one. */
    synchronized void forget() {
        lastPath = null;
        lastValue = null;
    }

    /** Writes the value unless it equals the previous one for the same path. */
    synchronized void write(String path, String hex) throws IOException {
        if (!SAFE_PATH.matcher(path).matches()) throw new IOException("Unsafe path: " + path);
        if (!SAFE_VALUE.matcher(hex).matches()) throw new IOException("Unsafe value");
        if (path.equals(lastPath) && hex.equals(lastValue)) return;
        if (!useShell) {
            try {
                writeDirect(path, hex);
            } catch (IOException error) {
                if (DIRECT.equals(mode)) throw error;
                useShell = true; // Auto: sysfs is root-only, switch to su for good
            }
        }
        if (useShell) writeShell(path, hex);
        lastPath = path;
        lastValue = hex;
    }

    synchronized void close() {
        closeShell();
        forget();
    }

    private void writeDirect(String path, String hex) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write((hex + "\n").getBytes("US-ASCII"));
        }
    }

    private void writeShell(String path, String hex) throws IOException {
        ensureShell();
        try {
            shellIn.write(("echo " + hex + " > '" + path + "'\n").getBytes("US-ASCII"));
            shellIn.flush();
        } catch (IOException error) {
            closeShell();
            throw new IOException("su shell closed: " + shellError, error);
        }
    }

    private void ensureShell() throws IOException {
        if (shell != null && alive(shell)) return;
        closeShell();
        shellError = "";
        shell = Runtime.getRuntime().exec(new String[] {"su"});
        shellIn = shell.getOutputStream();
        drain(shell.getInputStream());
        drain(shell.getErrorStream());
        // A denied su exits right away. Give it a moment so the error is reported on this frame.
        try {
            Thread.sleep(80);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!alive(shell)) {
            String message = shellError.isEmpty() ? "su exited immediately (is the SU appop allowed for this app?)" : shellError;
            closeShell();
            throw new IOException(message);
        }
    }

    private void drain(final InputStream stream) {
        Thread thread = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = in.readLine()) != null) if (!line.trim().isEmpty()) shellError = line.trim();
            } catch (IOException ignored) {
                // process ended
            }
        }, "dot-ring-su-drain");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private void closeShell() {
        if (shell == null) return;
        try {
            if (shellIn != null) {
                shellIn.write("exit\n".getBytes("US-ASCII"));
                shellIn.flush();
                shellIn.close();
            }
        } catch (IOException ignored) {
            // shell already gone
        }
        shell.destroy();
        shell = null;
        shellIn = null;
    }
}
