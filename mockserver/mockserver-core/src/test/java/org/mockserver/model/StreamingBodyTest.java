package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

public class StreamingBodyTest {

    @Test
    public void shouldCaptureChunksWithinLimit() {
        StreamingBody body = new StreamingBody(1024);
        List<String> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        body.subscribe(
            chunk -> {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), bytes);
                received.add(new String(bytes, StandardCharsets.UTF_8));
            },
            () -> completed.set(true),
            error -> {}
        );

        ByteBuf chunk1 = Unpooled.copiedBuffer("hello ", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("world", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.complete();

        assertThat(received, hasSize(2));
        assertThat(received.get(0), is("hello "));
        assertThat(received.get(1), is("world"));
        assertThat(completed.get(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.isCompleted(), is(true));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("hello world"));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldTruncateCaptureWhenExceedingLimit() {
        StreamingBody body = new StreamingBody(10); // 10 byte limit
        List<String> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        body.subscribe(
            chunk -> {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), bytes);
                received.add(new String(bytes, StandardCharsets.UTF_8));
            },
            () -> completed.set(true),
            error -> {}
        );

        ByteBuf chunk1 = Unpooled.copiedBuffer("12345678", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("ABCDEF", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.complete();

        // All chunks should be forwarded to subscriber (even past capture limit)
        assertThat(received, hasSize(2));
        assertThat(received.get(0), is("12345678"));
        assertThat(received.get(1), is("ABCDEF"));

        // But capture should be truncated
        assertThat(body.isTruncated(), is(true));
        assertThat(completed.get(), is(true));

        // Captured bytes should be exactly 10 (the limit):
        // 8 bytes from chunk 1 ("12345678") + 2 bytes from chunk 2 ("AB")
        byte[] captured = body.capturedBytes();
        assertThat(captured.length, is(10));
        assertThat(new String(captured, StandardCharsets.UTF_8), is("12345678AB"));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldHandleErrorMidStream() {
        StreamingBody body = new StreamingBody(1024);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        body.subscribe(
            chunk -> {},
            () -> completed.set(true),
            error -> receivedError.set(error)
        );

        ByteBuf chunk = Unpooled.copiedBuffer("partial", StandardCharsets.UTF_8);
        body.addChunk(chunk);

        RuntimeException cause = new RuntimeException("connection lost");
        body.error(cause);

        assertThat(completed.get(), is(false));
        assertThat(receivedError.get(), is(cause));
        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(true));
        assertThat(body.getError(), is(cause));

        // Captured bytes should still contain what was received before the error
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("partial"));

        chunk.release();
    }

    @Test
    public void shouldInvokeCompletionListenerOnComplete() {
        StreamingBody body = new StreamingBody(1024);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        body.addCompletionListener(() -> listenerCalled.set(true));

        assertThat(listenerCalled.get(), is(false));
        body.complete();
        assertThat(listenerCalled.get(), is(true));
    }

    @Test
    public void shouldInvokeCompletionListenerOnError() {
        StreamingBody body = new StreamingBody(1024);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        body.addCompletionListener(() -> listenerCalled.set(true));

        body.error(new RuntimeException("test"));
        assertThat(listenerCalled.get(), is(true));
    }

    @Test
    public void shouldInvokeCompletionListenerImmediatelyIfAlreadyCompleted() {
        StreamingBody body = new StreamingBody(1024);
        body.subscribe(chunk -> {}, () -> {}, error -> {});
        body.complete();

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        body.addCompletionListener(() -> listenerCalled.set(true));
        assertThat(listenerCalled.get(), is(true));
    }

    @Test
    public void shouldIgnoreChunksAfterComplete() {
        StreamingBody body = new StreamingBody(1024);
        List<String> received = new ArrayList<>();

        body.subscribe(
            chunk -> {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), bytes);
                received.add(new String(bytes, StandardCharsets.UTF_8));
            },
            () -> {},
            error -> {}
        );

        ByteBuf chunk1 = Unpooled.copiedBuffer("before", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.complete();

        ByteBuf chunk2 = Unpooled.copiedBuffer("after", StandardCharsets.UTF_8);
        body.addChunk(chunk2);

        assertThat(received, hasSize(1));
        assertThat(received.get(0), is("before"));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("before"));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldSafelyDeliverCompletionListenerUnderRace() throws Exception {
        // Verify that addCompletionListener and complete() racing from different threads
        // always results in the listener being called exactly once (finding 2 fix).
        for (int iteration = 0; iteration < 200; iteration++) {
            StreamingBody body = new StreamingBody(1024);
            body.subscribe(chunk -> {}, () -> {}, error -> {});

            AtomicInteger listenerCallCount = new AtomicInteger(0);
            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch done = new CountDownLatch(2);

            Thread completer = new Thread(() -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                    body.complete();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });

            Thread adder = new Thread(() -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                    body.addCompletionListener(listenerCallCount::incrementAndGet);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });

            completer.start();
            adder.start();
            assertThat("threads should complete within timeout", done.await(5, TimeUnit.SECONDS), is(true));

            assertThat("listener must be called exactly once (iteration " + iteration + ")",
                listenerCallCount.get(), is(1));
        }
    }

    @Test
    public void shouldSafelyDeliverCompletionListenerUnderErrorRace() throws Exception {
        // Same race test but with error() instead of complete()
        for (int iteration = 0; iteration < 200; iteration++) {
            StreamingBody body = new StreamingBody(1024);
            body.subscribe(chunk -> {}, () -> {}, error -> {});

            AtomicInteger listenerCallCount = new AtomicInteger(0);
            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch done = new CountDownLatch(2);

            Thread errorThread = new Thread(() -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                    body.error(new RuntimeException("test error"));
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });

            Thread adder = new Thread(() -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                    body.addCompletionListener(listenerCallCount::incrementAndGet);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });

            errorThread.start();
            adder.start();
            assertThat("threads should complete within timeout", done.await(5, TimeUnit.SECONDS), is(true));

            assertThat("listener must be called exactly once (iteration " + iteration + ")",
                listenerCallCount.get(), is(1));
        }
    }

    @Test
    public void shouldRequestMoreViaBackpressureCallback() {
        StreamingBody body = new StreamingBody(1024);
        AtomicBoolean requestMoreCalled = new AtomicBoolean(false);
        body.setRequestMoreCallback(() -> requestMoreCalled.set(true));

        assertThat(requestMoreCalled.get(), is(false));
        body.requestMore();
        assertThat(requestMoreCalled.get(), is(true));
    }

    @Test
    public void shouldHandleRequestMoreWithNoCallback() {
        StreamingBody body = new StreamingBody(1024);
        // Should not throw
        body.requestMore();
    }

    @Test
    public void shouldClampNegativeMaxCaptureBytes() {
        StreamingBody body = new StreamingBody(-10);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        body.complete();

        // With maxCaptureBytes clamped to 0, no bytes should be captured
        assertThat(body.capturedBytes().length, is(0));
        assertThat(body.isTruncated(), is(true));

        chunk.release();
    }

    @Test
    public void shouldBufferChunksBeforeSubscribeAndDrainInOrder() {
        // Simulate the race: chunks arrive before subscribe() is called.
        // Without an event loop, subscribe runs inline so we can test the
        // buffering/draining logic directly.
        StreamingBody body = new StreamingBody(1024);
        AtomicBoolean requestMoreCalled = new AtomicBoolean(false);
        body.setRequestMoreCallback(() -> requestMoreCalled.set(true));

        // Deliver chunks BEFORE subscribing
        ByteBuf chunk1 = Unpooled.copiedBuffer("alpha", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("beta", StandardCharsets.UTF_8);
        ByteBuf chunk3 = Unpooled.copiedBuffer("gamma", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.addChunk(chunk3);

        // Now subscribe
        List<String> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        body.subscribe(
            chunk -> {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), bytes);
                received.add(new String(bytes, StandardCharsets.UTF_8));
            },
            () -> completed.set(true),
            error -> {}
        );

        // All three pre-subscribe chunks should have been drained in order
        assertThat(received, hasSize(3));
        assertThat(received.get(0), is("alpha"));
        assertThat(received.get(1), is("beta"));
        assertThat(received.get(2), is("gamma"));

        // requestMore should have been called to trigger the first upstream read
        assertThat(requestMoreCalled.get(), is(true));

        // The capture buffer should also contain the bytes
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("alphabetagamma"));

        // Subsequent chunks after subscribe should work normally
        ByteBuf chunk4 = Unpooled.copiedBuffer("delta", StandardCharsets.UTF_8);
        body.addChunk(chunk4);
        assertThat(received, hasSize(4));
        assertThat(received.get(3), is("delta"));

        body.complete();
        assertThat(completed.get(), is(true));

        chunk1.release();
        chunk2.release();
        chunk3.release();
        chunk4.release();
    }

    @Test
    public void shouldReplayCompleteSignalIfCompletedBeforeSubscribe() {
        // Stream completes (empty body) before subscribe() is called
        StreamingBody body = new StreamingBody(1024);
        body.complete();

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        body.subscribe(
            chunk -> {},
            () -> completed.set(true),
            error -> receivedError.set(error)
        );

        assertThat(completed.get(), is(true));
        assertThat(receivedError.get(), is(nullValue()));
    }

    @Test
    public void shouldReplayErrorSignalIfErroredBeforeSubscribe() {
        // Stream errors before subscribe() is called
        StreamingBody body = new StreamingBody(1024);
        RuntimeException cause = new RuntimeException("premature close");
        body.error(cause);

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        body.subscribe(
            chunk -> {},
            () -> completed.set(true),
            error -> receivedError.set(error)
        );

        assertThat(completed.get(), is(false));
        assertThat(receivedError.get(), is(cause));
    }

    @Test
    public void shouldBufferChunksThenReplayCompleteBeforeSubscribe() {
        // Chunks arrive AND complete() fires before subscribe
        StreamingBody body = new StreamingBody(1024);

        ByteBuf chunk1 = Unpooled.copiedBuffer("data1", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("data2", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.complete();

        List<String> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        body.subscribe(
            chunk -> {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), bytes);
                received.add(new String(bytes, StandardCharsets.UTF_8));
            },
            () -> completed.set(true),
            error -> {}
        );

        // Chunks should be drained, then onComplete replayed
        assertThat(received, hasSize(2));
        assertThat(received.get(0), is("data1"));
        assertThat(received.get(1), is("data2"));
        assertThat(completed.get(), is(true));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("data1data2"));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldMarshalSubscribeOntoEventLoopWhenCalledFromDifferentThread() throws Exception {
        // Use a DefaultEventLoopGroup to get a real EventLoop, simulating the upstream channel
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            EventLoop eventLoop = group.next();
            StreamingBody body = new StreamingBody(1024);
            body.setEventLoop(eventLoop);

            AtomicBoolean requestMoreCalled = new AtomicBoolean(false);
            body.setRequestMoreCallback(() -> requestMoreCalled.set(true));

            // Deliver chunks on the event loop (simulating upstream channel reads)
            CountDownLatch chunksDelivered = new CountDownLatch(1);
            eventLoop.execute(() -> {
                ByteBuf chunk1 = Unpooled.copiedBuffer("first", StandardCharsets.UTF_8);
                ByteBuf chunk2 = Unpooled.copiedBuffer("second", StandardCharsets.UTF_8);
                body.addChunk(chunk1);
                body.addChunk(chunk2);
                chunk1.release();
                chunk2.release();
                chunksDelivered.countDown();
            });
            assertThat("chunks should be delivered", chunksDelivered.await(5, TimeUnit.SECONDS), is(true));

            // Subscribe from THIS thread (not the event loop) — should be marshalled
            List<String> received = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            CountDownLatch subscribeDone = new CountDownLatch(1);

            body.subscribe(
                chunk -> {
                    byte[] bytes = new byte[chunk.readableBytes()];
                    chunk.getBytes(chunk.readerIndex(), bytes);
                    received.add(new String(bytes, StandardCharsets.UTF_8));
                },
                () -> {
                    completed.set(true);
                    subscribeDone.countDown();
                },
                error -> subscribeDone.countDown()
            );

            // Now complete the body on the event loop
            eventLoop.execute(() -> body.complete());

            assertThat("subscribe should complete", subscribeDone.await(5, TimeUnit.SECONDS), is(true));

            // Pending chunks should have been drained
            assertThat(received, hasSize(2));
            assertThat(received.get(0), is("first"));
            assertThat(received.get(1), is("second"));
            assertThat(completed.get(), is(true));
            assertThat(requestMoreCalled.get(), is(true));
        } finally {
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void shouldMarshalSubscribeOntoEventLoopWithEmbeddedChannel() {
        // Use EmbeddedChannel to provide an EventLoop
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            EventLoop eventLoop = channel.eventLoop();
            StreamingBody body = new StreamingBody(1024);
            body.setEventLoop(eventLoop);

            AtomicBoolean requestMoreCalled = new AtomicBoolean(false);
            body.setRequestMoreCallback(() -> requestMoreCalled.set(true));

            // With EmbeddedChannel, everything runs on the calling thread when
            // we execute on the event loop and call runPendingTasks
            ByteBuf chunk = Unpooled.copiedBuffer("embedded", StandardCharsets.UTF_8);
            body.addChunk(chunk);

            List<String> received = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);

            // subscribe from the event loop thread (EmbeddedChannel's event loop
            // reports inEventLoop() = true for the calling thread)
            body.subscribe(
                c -> {
                    byte[] bytes = new byte[c.readableBytes()];
                    c.getBytes(c.readerIndex(), bytes);
                    received.add(new String(bytes, StandardCharsets.UTF_8));
                },
                () -> completed.set(true),
                error -> {}
            );

            // Run any pending tasks
            channel.runPendingTasks();

            assertThat(received, hasSize(1));
            assertThat(received.get(0), is("embedded"));
            assertThat(requestMoreCalled.get(), is(true));

            body.complete();
            assertThat(completed.get(), is(true));

            chunk.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
