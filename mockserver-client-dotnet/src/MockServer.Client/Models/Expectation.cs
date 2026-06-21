using System.Text.Json.Serialization;
using MockServer.Client.Llm;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a MockServer expectation (request matcher + action).
/// </summary>
public sealed class Expectation
{
    [JsonPropertyName("id")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Id { get; set; }

    [JsonPropertyName("priority")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Priority { get; set; }

    [JsonPropertyName("httpRequest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? HttpRequest { get; set; }

    [JsonPropertyName("httpResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpResponse? HttpResponse { get; set; }

    /// <summary>
    /// Multiple responses for a stateful scenario. When set, takes priority over the singular
    /// <see cref="HttpResponse"/>; the response served per match is selected by <see cref="ResponseMode"/>.
    /// </summary>
    [JsonPropertyName("httpResponses")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<HttpResponse>? HttpResponses { get; set; }

    [JsonPropertyName("httpForward")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpForward? HttpForward { get; set; }

    [JsonPropertyName("httpResponseTemplate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpTemplate? HttpResponseTemplate { get; set; }

    [JsonPropertyName("httpForwardTemplate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpTemplate? HttpForwardTemplate { get; set; }

    [JsonPropertyName("httpError")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpError? HttpError { get; set; }

    [JsonPropertyName("httpSseResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpSseResponse? HttpSseResponse { get; set; }

    [JsonPropertyName("httpWebSocketResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpWebSocketResponse? HttpWebSocketResponse { get; set; }

    [JsonPropertyName("grpcStreamResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public GrpcStreamResponse? GrpcStreamResponse { get; set; }

    [JsonPropertyName("binaryResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public BinaryResponse? BinaryResponse { get; set; }

    [JsonPropertyName("dnsResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public DnsResponse? DnsResponse { get; set; }

    [JsonPropertyName("httpLlmResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpLlmResponse? HttpLlmResponse { get; set; }

    [JsonPropertyName("scenarioName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ScenarioName { get; set; }

    [JsonPropertyName("scenarioState")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ScenarioState { get; set; }

    [JsonPropertyName("newScenarioState")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? NewScenarioState { get; set; }

    /// <summary>
    /// How a response is selected from <see cref="HttpResponses"/> on each match:
    /// SEQUENTIAL (default), RANDOM, WEIGHTED or SWITCH.
    /// </summary>
    [JsonPropertyName("responseMode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public ResponseMode? ResponseMode { get; set; }

    /// <summary>
    /// Relative weights index-aligned with <see cref="HttpResponses"/>; only meaningful when
    /// <see cref="ResponseMode"/> is <see cref="Models.ResponseMode.WEIGHTED"/>.
    /// </summary>
    [JsonPropertyName("responseWeights")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<int>? ResponseWeights { get; set; }

    /// <summary>
    /// Number of requests served per response block before advancing to the next; only meaningful when
    /// <see cref="ResponseMode"/> is <see cref="Models.ResponseMode.SWITCH"/> (default 1 on the server).
    /// </summary>
    [JsonPropertyName("switchAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? SwitchAfter { get; set; }

    /// <summary>
    /// Cross-protocol triggers that advance a named scenario when a DNS query, WebSocket connect,
    /// gRPC request or HTTP request is observed.
    /// </summary>
    [JsonPropertyName("crossProtocolScenarios")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<CrossProtocolScenario>? CrossProtocolScenarios { get; set; }

    [JsonPropertyName("times")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Times? Times { get; set; }

    [JsonPropertyName("timeToLive")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TimeToLive? TimeToLive { get; set; }
}
