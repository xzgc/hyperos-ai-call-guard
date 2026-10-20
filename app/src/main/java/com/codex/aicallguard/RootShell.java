package com.codex.aicallguard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    static final class Result {
        final boolean ok;
        final int exitCode;
        final String stdout;
        final String stderr;

        Result(boolean ok, int exitCode, String stdout, String stderr) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        String combined() {
            if (stderr.isEmpty()) {
                return stdout;
            }
            if (stdout.isEmpty()) {
                return stderr;
            }
            return stdout + "\n" + stderr;
        }
    }

    private RootShell() {
    }

    static Result run(String command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            StreamCollector out = new StreamCollector(process.getInputStream());
            StreamCollector err = new StreamCollector(process.getErrorStream());
            out.start();
            err.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, -1, out.result(), "root command timed out");
            }
            int code = process.exitValue();
            return new Result(code == 0, code, out.result(), err.result());
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(false, -1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static boolean isAvailable() {
        Result result = run("id", 4);
        return result.ok && result.stdout.contains("uid=0");
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final StringBuilder output = new StringBuilder();

        StreamCollector(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        }

        synchronized String result() {
            try {
                join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return output.toString().trim();
        }
    }
}
