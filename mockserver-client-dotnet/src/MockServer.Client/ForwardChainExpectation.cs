using MockServer.Client.Models;

namespace MockServer.Client;

/// <summary>
/// Fluent chain: after When(...), call Respond(...), Forward(...), or Error(...) to complete the expectation.
/// </summary>
public sealed class ForwardChainExpectation
{
    private readonly MockServerClient _client;
    private readonly Expectation _expectation;

    internal ForwardChainExpectation(MockServerClient client, Expectation expectation)
    {
        _client = client;
        _expectation = expectation;
    }

    /// <summary>
    /// Set the expectation ID.
    /// </summary>
    public ForwardChainExpectation WithId(string id)
    {
        _expectation.Id = id;
        return this;
    }

    /// <summary>
    /// Set the expectation priority.
    /// </summary>
    public ForwardChainExpectation WithPriority(int priority)
    {
        _expectation.Priority = priority;
        return this;
    }

    /// <summary>
    /// Complete the expectation with a response action.
    /// </summary>
    public List<Expectation> Respond(HttpResponse response)
    {
        _expectation.HttpResponse = response;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondAsync(HttpResponse response)
    {
        _expectation.HttpResponse = response;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward action.
    /// </summary>
    public List<Expectation> Forward(HttpForward forward)
    {
        _expectation.HttpForward = forward;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward action (async).
    /// </summary>
    public Task<List<Expectation>> ForwardAsync(HttpForward forward)
    {
        _expectation.HttpForward = forward;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a response template action.
    /// </summary>
    public List<Expectation> RespondWithTemplate(HttpTemplate template)
    {
        _expectation.HttpResponseTemplate = template;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a response template action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithTemplateAsync(HttpTemplate template)
    {
        _expectation.HttpResponseTemplate = template;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward template action.
    /// </summary>
    public List<Expectation> ForwardWithTemplate(HttpTemplate template)
    {
        _expectation.HttpForwardTemplate = template;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward template action (async).
    /// </summary>
    public Task<List<Expectation>> ForwardWithTemplateAsync(HttpTemplate template)
    {
        _expectation.HttpForwardTemplate = template;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a Server-Sent Events (SSE) response action.
    /// </summary>
    public List<Expectation> RespondWithSse(HttpSseResponse sseResponse)
    {
        _expectation.HttpSseResponse = sseResponse;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a Server-Sent Events (SSE) response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithSseAsync(HttpSseResponse sseResponse)
    {
        _expectation.HttpSseResponse = sseResponse;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a WebSocket response action.
    /// </summary>
    public List<Expectation> RespondWithWebSocket(HttpWebSocketResponse webSocketResponse)
    {
        _expectation.HttpWebSocketResponse = webSocketResponse;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a WebSocket response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithWebSocketAsync(HttpWebSocketResponse webSocketResponse)
    {
        _expectation.HttpWebSocketResponse = webSocketResponse;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a server-streaming gRPC response action.
    /// </summary>
    public List<Expectation> RespondWithGrpcStream(GrpcStreamResponse grpcStreamResponse)
    {
        _expectation.GrpcStreamResponse = grpcStreamResponse;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a server-streaming gRPC response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithGrpcStreamAsync(GrpcStreamResponse grpcStreamResponse)
    {
        _expectation.GrpcStreamResponse = grpcStreamResponse;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a raw binary response action.
    /// </summary>
    public List<Expectation> RespondWithBinary(BinaryResponse binaryResponse)
    {
        _expectation.BinaryResponse = binaryResponse;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a raw binary response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithBinaryAsync(BinaryResponse binaryResponse)
    {
        _expectation.BinaryResponse = binaryResponse;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a DNS response action.
    /// </summary>
    public List<Expectation> RespondWithDns(DnsResponse dnsResponse)
    {
        _expectation.DnsResponse = dnsResponse;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a DNS response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondWithDnsAsync(DnsResponse dnsResponse)
    {
        _expectation.DnsResponse = dnsResponse;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with an error action (drops/corrupts the connection).
    /// </summary>
    public List<Expectation> Error(HttpError error)
    {
        _expectation.HttpError = error;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with an error action (async).
    /// </summary>
    public Task<List<Expectation>> ErrorAsync(HttpError error)
    {
        _expectation.HttpError = error;
        return _client.UpsertExpectationAsync(_expectation);
    }
}
