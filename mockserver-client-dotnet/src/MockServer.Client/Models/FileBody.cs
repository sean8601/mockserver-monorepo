using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a FILE body type for MockServer. The file contents are served as the response body.
/// When <see cref="TemplateType"/> is set, the file is processed by the named template engine
/// against the request before being returned.
/// </summary>
public sealed class FileBody
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "FILE";

    [JsonPropertyName("filePath")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? FilePath { get; set; }

    [JsonPropertyName("contentType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ContentType { get; set; }

    [JsonPropertyName("templateType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TemplateType? TemplateType { get; set; }
}
