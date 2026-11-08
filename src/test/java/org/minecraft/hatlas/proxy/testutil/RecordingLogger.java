package org.minecraft.hatlas.proxy.testutil;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.List;

/** SLF4J logger that keeps every line, so tests can count warnings. */
public final class RecordingLogger extends AbstractLogger {

    public record Line(Level level, String message) {
    }

    public final List<Line> lines = new ArrayList<>();

    public RecordingLogger() {
        this.name = "test";
    }

    public List<String> at(Level level) {
        return lines.stream().filter(l -> l.level() == level).map(Line::message).toList();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected synchronized void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
                                                            Object[] args, Throwable t) {
        lines.add(new Line(level, MessageFormatter.basicArrayFormat(pattern, args)));
    }

    @Override public boolean isTraceEnabled() { return true; }
    @Override public boolean isTraceEnabled(Marker m) { return true; }
    @Override public boolean isDebugEnabled() { return true; }
    @Override public boolean isDebugEnabled(Marker m) { return true; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isInfoEnabled(Marker m) { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isWarnEnabled(Marker m) { return true; }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public boolean isErrorEnabled(Marker m) { return true; }
}
