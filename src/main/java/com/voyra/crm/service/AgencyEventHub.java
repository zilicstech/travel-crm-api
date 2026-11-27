package com.voyra.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live "something changed" signals for an agency's open screens (2026-11-27): each signed-in tab holds one
 * Server-Sent Events stream, and any write in the agency tells every stream of that agency to re-read its counts.
 * The event carries no data at all (the client re-asks the API in its own scope), so nothing can leak between users.
 * shortcut: streams live in this instance's memory only, so a write served by another instance reaches only that
 * instance's streams; the screens' own 5-minute re-count covers the rest. Upgrade to Postgres LISTEN/NOTIFY when
 * more than one instance runs for long.
 */
@Slf4j
@Service
public class AgencyEventHub {

    /** A stream ends after 30 minutes and the browser opens a new one, so a half-dead connection never lingers. */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String tenantId) {
        SseEmitter emitter = newEmitter();
        Set<SseEmitter> set = streams.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable drop = () -> drop(tenantId, emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        send(tenantId, emitter, SseEmitter.event().comment("ready"));
        return emitter;
    }

    /** Tells every open screen of the agency that something changed. A null tenant (a platform request) is ignored. */
    public void publish(String tenantId) {
        if (tenantId == null) return;
        Set<SseEmitter> set = streams.get(tenantId);
        if (set == null) return;
        for (SseEmitter e : set) send(tenantId, e, SseEmitter.event().name("changed").data("{}"));
    }

    /** A comment line now and then, so proxies do not close a quiet stream and dead ones are found. */
    public void heartbeat() {
        streams.forEach((tenantId, set) -> set.forEach(e -> send(tenantId, e, SseEmitter.event().comment("ping"))));
    }

    int openStreams() {
        return streams.values().stream().mapToInt(Set::size).sum();
    }

    SseEmitter newEmitter() {
        return new SseEmitter(STREAM_TIMEOUT.toMillis());
    }

    private void send(String tenantId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // the browser went away or the stream already completed
            drop(tenantId, emitter);
        }
    }

    private void drop(String tenantId, SseEmitter emitter) {
        streams.computeIfPresent(tenantId, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
