package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.IncrementalGrpcFrameDecoder;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.NottableString;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-stream handler for true bidirectional gRPC streaming. NOT {@code @Sharable} --
 * holds per-stream state (the incremental frame decoder, finished guard).
 * <p>
 * <strong>Phase 3b behaviour (rule-driven via GrpcBidiResponse):</strong>
 * <ul>
 *   <li>On {@link Http2HeadersFrame}: writes the initial response HEADERS
 *       ({@code :status=200}, {@code content-type=application/grpc}, plus any configured
 *       headers from the GrpcBidiResponse, {@code endStream=false}). Then writes
 *       any EAGER messages from the response immediately (note: per-message
 *       {@link GrpcStreamMessage#getDelay()} is NOT currently applied on the bidi path;
 *       the field is accepted for forward-compatibility and may be honoured in a future
 *       increment).</li>
 *   <li>On {@link Http2DataFrame}: feeds content bytes to {@link IncrementalGrpcFrameDecoder};
 *       for each complete inbound message, converts to JSON via the converter, evaluates
 *       rules in order (first match emits its responses as DATA frames). If no rule matches,
 *       no response is emitted. If the frame has {@code endStream=true}, calls
 *       {@link #finish(ChannelHandlerContext)}.</li>
 *   <li>{@code finish()}: writes trailing HEADERS with configured grpc-status and
 *       {@code endStream=true}. Guarded to run at most once.</li>
 * </ul>
 * <p>
 * The handler supports two modes:
 * <ul>
 *   <li><strong>Phase 3b (GrpcBidiResponse-driven):</strong> Constructed with a
 *       {@link GrpcBidiResponse} config; eager messages, rules, status, and headers come
 *       from the config.</li>
 *   <li><strong>Phase 3a (legacy responder function):</strong> Constructed with a
 *       {@link Function} responder for backward compatibility with existing tests.</li>
 * </ul>
 * <p>
 * Flow control: the channel's autoRead is set to {@code false} when this handler is
 * added; after processing each inbound frame, {@code ctx.read()} is called to request
 * the next frame. If the decoder's buffer cap is exceeded, a RESOURCE_EXHAUSTED trailing
 * status is written and the stream is finished.
 * <p>
 * Error handling: exceptions during channelRead are caught and result in an INTERNAL
 * grpc-status trailer, never an uncaught exception propagating up the pipeline.
 */
public class GrpcBidiStreamHandler extends ChannelInboundHandlerAdapter {

    private final Descriptors.MethodDescriptor methodDescriptor;
    private final GrpcJsonMessageConverter converter;
    private final IncrementalGrpcFrameDecoder decoder;
    private volatile boolean finished;

    // Phase 3a mode: function-based responder
    private final Function<String, List<String>> responder;

    // Phase 3b mode: GrpcBidiResponse-driven
    private final GrpcBidiResponse config;

    /**
     * Phase 3a constructor: function-based responder (backward compatible).
     *
     * @param methodDescriptor the resolved gRPC method descriptor
     * @param converter        JSON/protobuf converter for the method's message types
     * @param responder        maps an inbound message JSON string to a list of response
     *                         JSON strings; for 3a the default is echo: returns {@code [inboundJson]}
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder
    ) {
        this(methodDescriptor, converter, responder, null, new IncrementalGrpcFrameDecoder());
    }

    /**
     * Phase 3b constructor: GrpcBidiResponse-driven.
     *
     * @param methodDescriptor the resolved gRPC method descriptor
     * @param converter        JSON/protobuf converter for the method's message types
     * @param config           the GrpcBidiResponse configuration from the matched expectation
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        GrpcBidiResponse config
    ) {
        this(methodDescriptor, converter, null, config, new IncrementalGrpcFrameDecoder());
    }

    /**
     * Visible-for-testing constructor that accepts a custom decoder (e.g. with a small cap).
     */
    GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder,
        GrpcBidiResponse config,
        IncrementalGrpcFrameDecoder decoder
    ) {
        this.methodDescriptor = methodDescriptor;
        this.converter = converter;
        this.responder = responder;
        this.config = config;
        this.decoder = decoder;
        this.finished = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ctx.channel().config().setAutoRead(false);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame) {
                handleHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                handleData(ctx, (Http2DataFrame) msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        } catch (GrpcException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeded maximum")) {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED, e.getMessage());
            } else {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL, e.getMessage());
            }
        } catch (Exception e) {
            writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                e.getMessage() != null ? e.getMessage() : "internal error");
        }
    }

    private void handleHeaders(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
        // Write initial response headers
        DefaultHttp2Headers responseHeaders = new DefaultHttp2Headers();
        responseHeaders.status("200");
        responseHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);

        // Add configured headers from GrpcBidiResponse
        if (config != null && config.getHeaders() != null) {
            Headers configHeaders = config.getHeaders();
            for (Header entry : configHeaders.getEntries()) {
                for (org.mockserver.model.NottableString value : entry.getValues()) {
                    responseHeaders.add(entry.getName().getValue().toLowerCase(), value.getValue());
                }
            }
        }

        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, false));

        // Send eager messages if configured (Phase 3b)
        if (config != null && config.getMessages() != null) {
            for (GrpcStreamMessage eagerMsg : config.getMessages()) {
                writeGrpcMessage(ctx, eagerMsg.getJson());
            }
        }

        if (headersFrame.isEndStream()) {
            finish(ctx);
        } else {
            ctx.read();
        }
    }

    private void handleData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            byte[] bytes = new byte[dataFrame.content().readableBytes()];
            dataFrame.content().readBytes(bytes);

            List<byte[]> completedMessages = decoder.feed(bytes);

            for (byte[] messageBytes : completedMessages) {
                String inboundJson = converter.toJson(messageBytes, methodDescriptor.getInputType());

                if (config != null) {
                    // Phase 3b: rule-driven matching
                    processWithRules(ctx, inboundJson);
                } else if (responder != null) {
                    // Phase 3a: function-based responder
                    List<String> responseJsons = responder.apply(inboundJson);
                    if (responseJsons != null) {
                        for (String responseJson : responseJsons) {
                            writeGrpcMessage(ctx, responseJson);
                        }
                    }
                }
            }

            if (dataFrame.isEndStream()) {
                finish(ctx);
            } else {
                ctx.read();
            }
        } finally {
            dataFrame.release();
        }
    }

    private void processWithRules(ChannelHandlerContext ctx, String inboundJson) {
        if (config.getRules() == null) {
            return;
        }
        for (GrpcBidiRule rule : config.getRules()) {
            if (matchesRule(rule, inboundJson)) {
                if (rule.getResponses() != null) {
                    for (GrpcStreamMessage response : rule.getResponses()) {
                        writeGrpcMessage(ctx, response.getJson());
                    }
                }
                return; // first match wins
            }
        }
        // no match -> no response (documented behaviour)
    }

    /**
     * Matches the inbound message JSON against the rule's matchJson pattern.
     * Semantics mirror {@code BidirectionalWebSocketFrameHandler.matches}: exact string
     * match first, then regex (no substring/contains step). Uses {@link Pattern#DOTALL}
     * for regex matching because gRPC JSON from protobuf's {@code JsonFormat.printer()}
     * contains newlines, so {@code '.'} must match line terminators for patterns like
     * {@code ".*Alice.*"} to work as expected.
     * <p>
     * If the rule's {@link NottableString#isNot()} flag is {@code true}, the match result
     * is inverted: a negated matchJson matches when the value does NOT match the pattern.
     */
    boolean matchesRule(GrpcBidiRule rule, String inboundJson) {
        if (rule.getMatchJson() == null) {
            return true; // null matcher matches everything
        }
        String pattern = rule.getMatchJson().getValue();
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        boolean matched;
        // Exact match first
        if (inboundJson.equals(pattern)) {
            matched = true;
        } else {
            // Regex match with DOTALL (so '.' matches newlines in multiline JSON)
            try {
                matched = Pattern.compile(pattern, Pattern.DOTALL).matcher(inboundJson).matches();
            } catch (PatternSyntaxException e) {
                matched = false;
            }
        }
        // Honour NottableString negation: if isNot() is true, invert the result
        if (rule.getMatchJson().isNot()) {
            matched = !matched;
        }
        return matched;
    }

    private void writeGrpcMessage(ChannelHandlerContext ctx, String json) {
        byte[] responseProto = converter.toProtobuf(json, methodDescriptor.getOutputType());
        byte[] framedResponse = GrpcFrameCodec.encode(responseProto);
        ctx.writeAndFlush(new DefaultHttp2DataFrame(
            Unpooled.wrappedBuffer(framedResponse), false));
    }

    private void finish(ChannelHandlerContext ctx) {
        if (finished) {
            return;
        }
        finished = true;

        // Determine grpc-status from config
        String statusCode = "0";
        String statusMessage = null;
        if (config != null) {
            if (config.getStatusName() != null) {
                GrpcStatusMapper.GrpcStatusCode code = GrpcStatusMapper.fromName(config.getStatusName());
                statusCode = String.valueOf(code.getCode());
            }
            statusMessage = config.getStatusMessage();
        }

        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, statusCode);
        if (statusMessage != null) {
            trailers.set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, statusMessage);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));

        // Close connection if configured
        if (config != null && Boolean.TRUE.equals(config.getCloseConnection())) {
            ctx.close();
        }
    }

    private void writeTrailer(ChannelHandlerContext ctx, GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        if (finished) {
            return;
        }
        finished = true;
        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()));
        if (message != null) {
            trailers.set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
            cause.getMessage() != null ? cause.getMessage() : "internal error");
    }
}
