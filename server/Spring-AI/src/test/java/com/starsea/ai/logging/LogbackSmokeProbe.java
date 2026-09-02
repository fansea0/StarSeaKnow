package com.starsea.ai.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

final class LogbackSmokeProbe {

    private LogbackSmokeProbe() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ProbeApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setWebApplicationType(WebApplicationType.NONE);

        ConfigurableApplicationContext applicationContext = application.run();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            switch (args[0]) {
                case "inspect-dev" -> inspectDevelopmentConfiguration(loggerContext);
                case "inspect-prod" -> inspectProductionConfiguration(loggerContext);
                case "levels" -> writeLevels(args[1]);
                default -> throw new IllegalArgumentException("Unknown probe mode: " + args[0]);
            }
        } finally {
            applicationContext.close();
            loggerContext.stop();
        }
    }

    private static void inspectDevelopmentConfiguration(LoggerContext context) {
        assertDevelopmentFile(assertAsyncAppender(context, "ASYNC_APPLICATION", "APPLICATION_FILE"));
        assertDevelopmentFile(assertAsyncAppender(context, "ASYNC_ERROR", "ERROR_FILE"));
    }

    private static void inspectProductionConfiguration(LoggerContext context) {
        assertProductionDailyFile(
                assertAsyncAppender(context, "ASYNC_APPLICATION", "APPLICATION_FILE"),
                "application");
        assertProductionDailyFile(
                assertAsyncAppender(context, "ASYNC_ERROR", "ERROR_FILE"),
                "error");
    }

    private static Appender<ILoggingEvent> assertAsyncAppender(LoggerContext context,
                                                                String asyncName,
                                                                String nestedName) {
        Appender<ILoggingEvent> configured = context.getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender(asyncName);
        if (!(configured instanceof AsyncAppender asyncAppender)) {
            throw new AssertionError(asyncName + " must be an AsyncAppender");
        }
        if (asyncAppender.isNeverBlock() || asyncAppender.getDiscardingThreshold() != 0) {
            throw new AssertionError(asyncName + " must preserve operational logs when its queue is full");
        }
        if (asyncAppender.getMaxFlushTime() != 0) {
            throw new AssertionError(asyncName + " must fully drain its queue during shutdown");
        }
        Appender<ILoggingEvent> nested = asyncAppender.getAppender(nestedName);
        if (nested == null) {
            throw new AssertionError(nestedName + " must be attached to " + asyncName);
        }
        return nested;
    }

    private static void assertDevelopmentFile(Appender<ILoggingEvent> appender) {
        if (!(appender instanceof FileAppender<?> fileAppender)
                || appender instanceof RollingFileAppender<?>) {
            throw new AssertionError(appender.getName() + " must be a non-rolling development FileAppender");
        }
        if (fileAppender.isAppend()) {
            throw new AssertionError(appender.getName() + " must clear its file on development startup");
        }
    }

    private static void assertProductionDailyFile(Appender<ILoggingEvent> appender,
                                                  String filePrefix) {
        if (!(appender instanceof RollingFileAppender<?> rollingAppender)) {
            throw new AssertionError(appender.getName() + " must roll in production");
        }
        if (!(rollingAppender.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy)
                || policy instanceof SizeAndTimeBasedRollingPolicy<?>) {
            throw new AssertionError(appender.getName() + " must roll by date only in production");
        }
        if (policy.getMaxHistory() != 30) {
            throw new AssertionError(appender.getName() + " must retain 30 days by default");
        }
        if (policy.getParentsRawFileProperty() != null) {
            throw new AssertionError(appender.getName() + " must derive its active file from the date");
        }
        String expectedSuffix = "/" + filePrefix + ".%d{yyyy-MM-dd}.log.gz";
        if (!policy.getFileNamePattern().endsWith(expectedSuffix)
                || policy.getCompressionMode() != CompressionMode.GZ) {
            throw new AssertionError(appender.getName() + " must use gzip-compressed daily files");
        }
        if (!policy.isCleanHistoryOnStart()) {
            throw new AssertionError(appender.getName() + " must enforce retention on startup");
        }
    }

    private static void writeLevels(String marker) {
        Logger log = LoggerFactory.getLogger(LogbackSmokeProbe.class);
        log.info("event=logging_probe marker={} level=info", marker);
        log.warn("event=logging_probe marker={} level=warn", marker);
        log.error("event=logging_probe marker={} level=error",
                marker, new IllegalStateException("probe stacktrace " + marker));
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeApplication {
    }
}
