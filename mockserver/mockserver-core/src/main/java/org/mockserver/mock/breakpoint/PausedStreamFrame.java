package org.mockserver.mock.breakpoint;

import org.mockserver.time.TimeService;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a single streaming response frame that has been paused at a breakpoint,
 * awaiting external resolution via the control-plane REST API or an automatic timeout.
 *
 * <p>The held frame's bytes are captured as a {@code byte[]} copy at park time — the
 * original Netty {@code ByteBuf} is NOT retained here. Instead, the caller retains the
 * ByteBuf, and the decision callback tells the caller what to do with it (write, replace,
 * drop, etc.). This avoids the complexity of holding a refcounted ByteBuf across threads
 * and timeouts.
 *
 * <p>Thread-safety: the {@link #decisionFuture} is a {@link CompletableFuture} which is
 * thread-safe. Fields are immutable after construction.
 */
public class PausedStreamFrame {

    private final String frameId;
    private final String streamId;
    private final int sequenceNumber;
    private final byte[] capturedBytes;
    private final CompletableFuture<StreamFrameDecision> decisionFuture;
    private final long createdAtMillis;
    private final String requestPath;
    private final String requestMethod;

    public PausedStreamFrame(String frameId, String streamId, int sequenceNumber, byte[] capturedBytes,
                             String requestMethod, String requestPath) {
        this.frameId = frameId;
        this.streamId = streamId;
        this.sequenceNumber = sequenceNumber;
        this.capturedBytes = capturedBytes;
        this.decisionFuture = new CompletableFuture<>();
        this.createdAtMillis = TimeService.currentTimeMillis();
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
    }

    /**
     * Unique id for this frame within the stream breakpoint registry.
     */
    public String getFrameId() {
        return frameId;
    }

    /**
     * The stream id (correlation id) this frame belongs to. Multiple frames
     * from the same forwarded response share the same stream id.
     */
    public String getStreamId() {
        return streamId;
    }

    /**
     * 0-based sequence number within the stream. Frames MUST be resumed in order.
     */
    public int getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * A copy of the frame's payload bytes at the time it was parked.
     */
    public byte[] getCapturedBytes() {
        return capturedBytes;
    }

    /**
     * The future that the relay loop awaits. Completing this future resumes frame processing.
     */
    public CompletableFuture<StreamFrameDecision> getDecisionFuture() {
        return decisionFuture;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    /**
     * Age in milliseconds since this frame was paused.
     */
    public long ageMillis() {
        return TimeService.currentTimeMillis() - createdAtMillis;
    }

    /**
     * The request method of the original forwarded request (for context/logging).
     */
    public String getRequestMethod() {
        return requestMethod;
    }

    /**
     * The request path of the original forwarded request (for context/logging).
     */
    public String getRequestPath() {
        return requestPath;
    }
}
