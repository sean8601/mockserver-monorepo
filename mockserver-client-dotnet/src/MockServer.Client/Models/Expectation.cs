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

    [JsonPropertyName("times")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Times? Times { get; set; }

    [JsonPropertyName("timeToLive")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TimeToLive? TimeToLive { get; set; }
}
