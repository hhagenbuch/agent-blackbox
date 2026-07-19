package io.github.hhagenbuch.blackbox.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the recorder, bound from the {@code blackbox.*} namespace. */
@ConfigurationProperties("blackbox")
public class BlackboxProperties {

    /** Master switch. */
    private boolean enabled = true;
    /** Directory traces are written to (one file per request). */
    private String traceDir = "traces";
    /** Scrub secrets on write. */
    private boolean redact = true;
    /** fsync each event to disk before returning (durability vs. write latency). */
    private boolean fsyncPerEvent = false;
    /** Recorded runtime app name (metadata only). */
    private String app = "spring-ai-agent-starter";
    /** Recorded model id (metadata only; the seam doesn't expose it). */
    private String model = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTraceDir() {
        return traceDir;
    }

    public void setTraceDir(String traceDir) {
        this.traceDir = traceDir;
    }

    public boolean isRedact() {
        return redact;
    }

    public void setRedact(boolean redact) {
        this.redact = redact;
    }

    public boolean isFsyncPerEvent() {
        return fsyncPerEvent;
    }

    public void setFsyncPerEvent(boolean fsyncPerEvent) {
        this.fsyncPerEvent = fsyncPerEvent;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
