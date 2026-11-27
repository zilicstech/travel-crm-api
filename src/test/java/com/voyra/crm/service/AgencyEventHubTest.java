package com.voyra.crm.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgencyEventHubTest {

    /** Hands out mock emitters and keeps the completion callbacks the hub registers on them. */
    static class TestHub extends AgencyEventHub {
        final List<SseEmitter> made = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<java.util.function.Consumer<Throwable>> errors = new ArrayList<>();

        @Override
        SseEmitter newEmitter() {
            SseEmitter e = mock(SseEmitter.class);
            org.mockito.Mockito.doAnswer(i -> { completions.add(i.getArgument(0)); return null; }).when(e).onCompletion(any());
            org.mockito.Mockito.doAnswer(i -> { timeouts.add(i.getArgument(0)); return null; }).when(e).onTimeout(any());
            org.mockito.Mockito.doAnswer(i -> { errors.add(i.getArgument(0)); return null; }).when(e).onError(any());
            made.add(e);
            return e;
        }
    }

    @Test
    void aWriteReachesEveryStreamOfItsAgencyOnlyAndAPlatformRequestReachesNone() throws IOException {
        TestHub hub = new TestHub();
        SseEmitter a1 = hub.subscribe("T1");
        SseEmitter a2 = hub.subscribe("T1");
        SseEmitter b = hub.subscribe("T2");
        verify(a1).send(any(SseEmitter.SseEventBuilder.class)); // the opening comment

        hub.publish("T1");
        hub.publish(null);
        hub.publish("T9");

        verify(a1, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(a2, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(b, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(hub.openStreams()).isEqualTo(3);
    }

    @Test
    void aStreamThatEndsTimesOutFailsOrCannotBeWrittenIsDropped() throws IOException {
        TestHub hub = new TestHub();
        hub.subscribe("T1");
        hub.subscribe("T1");
        hub.subscribe("T1");
        SseEmitter broken = hub.subscribe("T2");
        assertThat(hub.openStreams()).isEqualTo(4);

        hub.completions.get(0).run();
        hub.timeouts.get(1).run();
        hub.errors.get(2).accept(new IOException("reset"));
        assertThat(hub.openStreams()).isEqualTo(1);
        hub.completions.get(0).run(); // a second end of a stream already gone changes nothing

        doThrow(new IOException("gone")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        hub.heartbeat();
        assertThat(hub.openStreams()).isZero();
        hub.heartbeat();
        verify(broken, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aStreamAlreadyCompletedWhenOpenedIsDroppedAtOnce() throws IOException {
        AgencyEventHub hub = new AgencyEventHub() {
            @Override
            SseEmitter newEmitter() {
                SseEmitter e = mock(SseEmitter.class);
                try {
                    doThrow(new IllegalStateException("completed")).when(e).send(any(SseEmitter.SseEventBuilder.class));
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
                return e;
            }
        };
        hub.subscribe("T1");
        assertThat(hub.openStreams()).isZero();
    }

    @Test
    void theRealEmitterEndsAfterHalfAnHour() {
        assertThat(new AgencyEventHub().newEmitter().getTimeout()).isEqualTo(30 * 60_000L);
    }
}
