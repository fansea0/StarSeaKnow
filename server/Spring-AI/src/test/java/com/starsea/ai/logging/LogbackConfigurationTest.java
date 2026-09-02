package com.starsea.ai.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

    @TempDir
    Path logDirectory;

    @Test
    void configuresNonDiscardingAsyncRollingFiles() throws Exception {
        ProbeResult result = runProbe("inspect", "100MB");

        assertThat(result.exitCode())
                .withFailMessage(result.output())
                .isZero();
    }

    @Test
    void writesAllOperationalLevelsAndKeepsErrorsEasyToFind() throws Exception {
        ProbeResult result = runProbe("levels", "100MB");

        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
        assertThat(logDirectory.resolve("application.log"))
                .content(StandardCharsets.UTF_8)
                .contains("level=info", "level=warn", "level=error", "probe stacktrace");
        assertThat(logDirectory.resolve("error.log"))
                .content(StandardCharsets.UTF_8)
                .contains("level=error", "probe stacktrace")
                .doesNotContain("level=info", "level=warn");
    }

    @Test
    void rollsAndCompressesLogsWhenTheConfiguredSizeIsReached() throws Exception {
        ProbeResult result = runProbe("rolling", "1KB");

        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
        try (var files = Files.list(logDirectory.resolve("archive"))) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("application.") && name.endsWith(".log.gz"));
        }
    }

    private ProbeResult runProbe(String mode, String maxFileSize) throws IOException, InterruptedException {
        Path configuration = Path.of("src/main/resources/logback-spring.xml").toAbsolutePath();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dlogback.configurationFile=" + configuration,
                "-cp", classpath,
                LogbackSmokeProbe.class.getName(),
                mode
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("LOG_PATH", logDirectory.toAbsolutePath().toString());
        processBuilder.environment().put("LOG_MAX_FILE_SIZE", maxFileSize);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.waitFor(), output);
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
