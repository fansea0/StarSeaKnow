package com.starsea.ai.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultAndDevelopmentProfilesClearLogsOnEveryStartup() throws Exception {
        assertStartupClearsPreviousLogs(null, temporaryDirectory.resolve("default"));
        assertStartupClearsPreviousLogs("dev", temporaryDirectory.resolve("dev"));
    }

    @Test
    void productionProfileAppendsToOneApplicationAndErrorFilePerDay() throws Exception {
        Path logDirectory = temporaryDirectory.resolve("prod");

        assertProbeSucceeded(runProbe("prod", "inspect-prod", "inspect", logDirectory));
        assertProbeSucceeded(runProbe("prod", "levels", "first-run", logDirectory));
        assertProbeSucceeded(runProbe("prod", "levels", "second-run", logDirectory));

        String date = LocalDate.now().toString();
        Path applicationLog = logDirectory.resolve("application." + date + ".log");
        Path errorLog = logDirectory.resolve("error." + date + ".log");
        assertThat(applicationLog)
                .content(StandardCharsets.UTF_8)
                .contains("first-run", "second-run", "level=info", "level=warn", "level=error");
        assertThat(errorLog)
                .content(StandardCharsets.UTF_8)
                .contains("first-run", "second-run", "level=error")
                .doesNotContain("level=info", "level=warn");
        assertThat(logDirectory.resolve("application.log")).doesNotExist();
        assertThat(logDirectory.resolve("error.log")).doesNotExist();
    }

    private void assertStartupClearsPreviousLogs(String profile, Path logDirectory) throws Exception {
        assertProbeSucceeded(runProbe(profile, "inspect-dev", "inspect", logDirectory));
        assertProbeSucceeded(runProbe(profile, "levels", "first-run", logDirectory));
        assertProbeSucceeded(runProbe(profile, "levels", "second-run", logDirectory));

        assertThat(logDirectory.resolve("application.log"))
                .content(StandardCharsets.UTF_8)
                .contains("second-run", "level=info", "level=warn", "level=error")
                .doesNotContain("first-run");
        assertThat(logDirectory.resolve("error.log"))
                .content(StandardCharsets.UTF_8)
                .contains("second-run", "level=error")
                .doesNotContain("first-run", "level=info", "level=warn");
    }

    private void assertProbeSucceeded(ProbeResult result) {
        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
    }

    private ProbeResult runProbe(String profile,
                                 String mode,
                                 String marker,
                                 Path logDirectory) throws IOException, InterruptedException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath,
                LogbackSmokeProbe.class.getName(),
                mode,
                marker
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("LOG_PATH", logDirectory.toAbsolutePath().toString());
        if (profile == null) {
            processBuilder.environment().remove("SPRING_PROFILES_ACTIVE");
        } else {
            processBuilder.environment().put("SPRING_PROFILES_ACTIVE", profile);
        }
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.waitFor(), output);
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
