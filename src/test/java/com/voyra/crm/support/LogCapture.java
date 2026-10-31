package com.voyra.crm.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects what one class logs (INFO and above), so a test can assert a job's log line. Close it after the test.
 * The level is forced for the capture because a Spring test context started earlier in the same JVM may have raised it.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    public LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        previousLevel = logger.getLevel();
        if (!logger.isInfoEnabled()) {
            logger.setLevel(Level.INFO);
        }
        appender.start();
        logger.addAppender(appender);
    }

    /** Each event as "LEVEL formatted message". */
    public List<String> lines() {
        return appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }
}
