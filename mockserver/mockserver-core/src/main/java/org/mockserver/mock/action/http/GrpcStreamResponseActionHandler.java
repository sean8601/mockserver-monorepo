package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcStreamMessageEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class GrpcStreamResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final Configuration configuration;

    public GrpcStreamResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, GrpcProtoDescriptorStore descriptorStore, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.descriptorStore = descriptorStore;
        this.configuration = configuration;
    }

    public void handle(GrpcStreamResponse grpcStreamResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        String serviceName = request.getFirstHeader("x-grpc-service");
        String methodName = request.getFirstHeader("x-grpc-method");

        com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor = null;
        if (serviceName != null && !serviceName.isEmpty() && methodName != null && !methodName.isEmpty()) {
            methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        }

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        );

        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        if (grpcStreamResponse.getHeaders() != null) {
            grpcStreamResponse.getHeaders().getEntries().forEach(header ->
                header.getValues().forEach(value ->
                    initialResponse.headers().add(header.getName().getValue(), value.getValue())
                )
            );
        }

        ctx.writeAndFlush(initialResponse);

        // Determine if stream-frame breakpoints are active
        final boolean streamBreakpointsActive = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM) != null;
        final String streamId;
        final String reqMethod;
        final String reqPath;
        if (streamBreakpointsActive) {
            streamId = (request.getLogCorrelationId() != null
                ? request.getLogCorrelationId() : java.util.UUID.randomUUID().toString()) + "-grpc-stream";
            reqMethod = request.getMethod() != null ? request.getMethod().getValue() : null;
            reqPath = request.getPath() != null ? request.getPath().getValue() : null;
        } else {
            streamId = null;
            reqMethod = null;
            reqPath = null;
        }

        List<GrpcStreamMessage> messages = grpcStreamResponse.getMessages();
        if (messages != null && !messages.isEmpty()) {
            scheduleMessages(messages, 0, ctx, grpcStreamResponse, request, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath);
        } else {
            finishStream(ctx, grpcStreamResponse, streamBreakpointsActive, streamId);
        }
    }

    private void scheduleMessages(List<GrpcStreamMessage> messages, int index, ChannelHandlerContext ctx,
                                   GrpcStreamResponse grpcStreamResponse, org.mockserver.model.HttpRequest request,
                                   com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor,
                                   boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath) {
        if (index >= messages.size() || !ctx.channel().isActive()) {
            finishStream(ctx, grpcStreamResponse, streamBreakpointsActive, streamId);
            return;
        }

        GrpcStreamMessage message = messages.get(index);
        Delay delay = message.getDelay();

        Runnable writeMessage = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                byte[] frameBytes = encodeMessage(message, methodDescriptor);

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write immediately
                    writeGrpcFrame(frameBytes, ctx, request, messages, index, grpcStreamResponse, methodDescriptor,
                        streamBreakpointsActive, streamId, reqMethod, reqPath);
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // frameBytes is already a byte[] copy (from encodeMessage) -- no ByteBuf refcount concern
                PausedStreamFrame pausedFrame = StreamFrameBreakpointRegistry.getInstance()
                    .pauseFrame(streamId, frameBytes, reqMethod, reqPath, configuration);

                if (pausedFrame == null) {
                    // Cap reached -- write immediately
                    writeGrpcFrame(frameBytes, ctx, request, messages, index, grpcStreamResponse, methodDescriptor,
                        streamBreakpointsActive, streamId, reqMethod, reqPath);
                    return;
                }

                // Frame is parked. Chain the decision callback onto the channel's event loop.
                pausedFrame.getDecisionFuture().thenAccept(decision ->
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            scheduleMessages(messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                                streamBreakpointsActive, streamId, reqMethod, reqPath);
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE -> writeGrpcFrame(pausedFrame.getCapturedBytes(), ctx, request, messages, index,
                                grpcStreamResponse, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath);
                            case MODIFY -> writeGrpcFrame(decision.getReplacementBody(), ctx, request, messages, index,
                                grpcStreamResponse, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath);
                            case DROP ->
                                // Skip this frame -- proceed to next message
                                scheduleMessages(messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                                    streamBreakpointsActive, streamId, reqMethod, reqPath);
                            case INJECT -> {
                                // Write original frame, then inject an extra frame, then proceed
                                DefaultHttpContent originalContent = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(pausedFrame.getCapturedBytes()));
                                ctx.writeAndFlush(originalContent).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        DefaultHttpContent injectedContent = new DefaultHttpContent(
                                            Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                        ctx.writeAndFlush(injectedContent).addListener(f2 ->
                                            scheduleMessages(messages, index + 1, ctx, grpcStreamResponse, request,
                                                methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath));
                                    } else {
                                        scheduleMessages(messages, index + 1, ctx, grpcStreamResponse, request,
                                            methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath);
                                    }
                                });
                            }
                            case CLOSE -> {
                                // End the stream: evict remaining frames and send trailers
                                StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                finishStream(ctx, grpcStreamResponse, false, null);
                            }
                        }
                    })
                ).exceptionally(ex -> {
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("stream frame decision callback failed for gRPC stream{}:{}")
                                .setArguments(streamId, ex.getMessage())
                        );
                    }
                    return null;
                });
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending gRPC stream message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishStream(ctx, grpcStreamResponse, streamBreakpointsActive, streamId);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeMessage, false, delay);
        } else {
            writeMessage.run();
        }
    }

    /**
     * Writes a gRPC frame (byte[]) to the channel and chains to the next message on success.
     * Shared between the default-off fast path and the breakpoint resume path.
     */
    private void writeGrpcFrame(byte[] frameBytes, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request,
                                List<GrpcStreamMessage> messages, int index, GrpcStreamResponse grpcStreamResponse,
                                com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor,
                                boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath) {
        DefaultHttpContent content = new DefaultHttpContent(Unpooled.wrappedBuffer(frameBytes));
        ctx.writeAndFlush(content).addListener(future -> {
            if (future.isSuccess()) {
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("sent gRPC stream message {} of {} for request:{}")
                            .setArguments(index + 1, messages.size(), request)
                    );
                }
                scheduleMessages(messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                    streamBreakpointsActive, streamId, reqMethod, reqPath);
            } else {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("async write failure for gRPC stream message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(future.cause())
                    );
                }
                finishStream(ctx, grpcStreamResponse, streamBreakpointsActive, streamId);
            }
        });
    }

    private byte[] encodeMessage(GrpcStreamMessage message, com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor) {
        // Delegate to the transport-neutral encoder so HTTP/2 and HTTP/3 server-streaming
        // produce byte-identical gRPC frames.
        return GrpcStreamMessageEncoder.encode(message, methodDescriptor, descriptorStore);
    }

    private void finishStream(ChannelHandlerContext ctx, GrpcStreamResponse grpcStreamResponse,
                              boolean streamBreakpointsActive, String streamId) {
        if (streamBreakpointsActive && streamId != null) {
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
        }
        if (ctx.channel().isActive()) {
            GrpcStatusMapper.GrpcStatusCode statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
            if (grpcStreamResponse.getStatusName() != null && !grpcStreamResponse.getStatusName().isEmpty()) {
                statusCode = GrpcStatusMapper.fromName(grpcStreamResponse.getStatusName());
            }

            DefaultLastHttpContent trailers = new DefaultLastHttpContent();
            trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()));
            if (grpcStreamResponse.getStatusMessage() != null && !grpcStreamResponse.getStatusMessage().isEmpty()) {
                trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, grpcStreamResponse.getStatusMessage());
            }

            ctx.writeAndFlush(trailers).addListener(future -> {
                if (grpcStreamResponse.getCloseConnection() != null && grpcStreamResponse.getCloseConnection()) {
                    ctx.close();
                }
            });
        }
    }
}
