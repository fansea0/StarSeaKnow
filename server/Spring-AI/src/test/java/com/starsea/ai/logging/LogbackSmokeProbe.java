package com.starsea.ai.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LogbackSmokeProbe {

    private static final Logger log = LoggerFactory.getLogger(LogbackSmokeProbe.class);

    private LogbackSmokeProbe() {
    }

    public static void main(String[] args) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            switch (args[0]) {
                case "inspect" -> inspectConfiguration(context);
                case "levels" -> writeLevels();
                case "rolling" -> writeEnoughToRoll();
                default -> throw new IllegalArgumentException("Unknown probe mode: " + args[0]);
            }
        } finally {
            context.stop();
        }
    }

    private static void inspectConfiguration(LoggerContext context) {
        assertAsyncRollingAppender(context, "ASYNC_APPLICATION", "APPLICATION_FILE");
        assertAsyncRollingAppender(context, "ASYNC_ERROR", "ERROR_FILE");
    }

    private static void assertAsyncRollingAppender(LoggerContext context,
                                                     String asyncName,
                                                     String rollingName) {
        Appender<ILoggingEvent> configured = context.getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender(asyncName);
        if (!(configured instanceof AsyncAppender asyncAppender)) {
            throw new AssertionError(asyncName + " must be an AsyncAppender");
        }
        if (asyncAppender.isNeverBlock()) {
            throw new AssertionError(asyncName + " must preserve logs when its queue is full");
        }
        if (asyncAppender.getDiscardingThreshold() != 0) {
            throw new AssertionError(asyncName + " must not discard INFO, WARN, or ERROR logs");
        }
        if (asyncAppender.getMaxFlushTime() != 0) {
            throw new AssertionError(asyncName + " must fully drain its queue during shutdown");
        }

        Appender<ILoggingEvent> nested = asyncAppender.getAppender(rollingName);
        if (!(nested instanceof RollingFileAppender<?> rollingAppender)) {
            throw new AssertionError(rollingName + " must be a RollingFileAppender");
        }
        if (!(rollingAppender.getRollingPolicy() instanceof SizeAndTimeBasedRollingPolicy<?>)) {
            throw new AssertionError(rollingName + " must roll by both date and size");
        }
    }

    private static void writeLevels() {
        log.info("event=logging_probe level=info");
        log.warn("event=logging_probe level=warn");
        log.error("event=logging_probe level=error", new IllegalStateException("probe stacktrace"));
    }

    private static void writeEnoughToRoll() {
        String payload = "x".repeat(256);
        for (int index = 0; index < 500; index++) {
            log.info("event=logging_roll_probe sequence={} payload={}", index, payload);
        }
    }
}
