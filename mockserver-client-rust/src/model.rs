//! Domain model types for the MockServer control-plane API.
//!
//! All types implement `Serialize`/`Deserialize` and use builder methods that
//! take `self` and return `Self`, enabling fluent construction.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ---------------------------------------------------------------------------
// HttpRequest
// ---------------------------------------------------------------------------

/// Matcher for an HTTP request. Uses builder methods for fluent construction.
///
/// # Example
/// ```
/// use mockserver_client::HttpRequest;
///
/// let request = HttpRequest::new()
///     .method("POST")
///     .path("/api/users")
///     .header("Content-Type", "application/json")
///     .query_param("page", "1")
///     .body("{}");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub query_string_parameters: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<Body>,
}

impl HttpRequest {
    /// Create a new empty request matcher.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP method to match.
    pub fn method(mut self, method: impl Into<String>) -> Self {
        self.method = Some(method.into());
        self
    }

    /// Set the path to match.
    pub fn path(mut self, path: impl Into<String>) -> Self {
        self.path = Some(path.into());
        self
    }

    /// Add a query string parameter (multiple values per key supported).
    pub fn query_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let params = self.query_string_parameters.get_or_insert_with(HashMap::new);
        params
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Add a header (multiple values per key supported).
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Set a plain string body matcher.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(Body::Plain(body.into()));
        self
    }

    /// Set a typed JSON body matcher.
    pub fn json_body(mut self, json: serde_json::Value) -> Self {
        self.body = Some(Body::Typed {
            body_type: "JSON".to_string(),
            json: json.to_string(),
        });
        self
    }

    /// Set a file body (type "FILE") with optional content type and template type.
    ///
    /// Use [`Body::file`] for richer construction if you need content type or
    /// template type set.
    pub fn file_body(mut self, file_path: impl Into<String>) -> Self {
        self.body = Some(Body::File {
            file_path: file_path.into(),
            content_type: None,
            template_type: None,
        });
        self
    }

    /// Set a pre-built [`Body`] value (use with [`Body::file`] for FILE bodies).
    pub fn body_value(mut self, body: Body) -> Self {
        self.body = Some(body);
        self
    }
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

/// Request/response body — either a plain string, a typed object, or a file reference.
#[derive(Debug, Clone, PartialEq)]
pub enum Body {
    /// A plain string body.
    Plain(String),
    /// A typed body (e.g., JSON).
    Typed { body_type: String, json: String },
    /// A file body (`type: "FILE"`), with optional template evaluation.
    File {
        file_path: String,
        content_type: Option<String>,
        template_type: Option<String>,
    },
}

impl Body {
    /// Create a FILE body referencing a path on the server filesystem.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::Body;
    ///
    /// let body = Body::file("/data/response.json")
    ///     .with_content_type("application/json")
    ///     .with_template_type("VELOCITY");
    /// ```
    pub fn file(file_path: impl Into<String>) -> Self {
        Body::File {
            file_path: file_path.into(),
            content_type: None,
            template_type: None,
        }
    }

    /// Set the content type on a FILE body. No-op on other variants.
    pub fn with_content_type(mut self, content_type: impl Into<String>) -> Self {
        if let Body::File {
            content_type: ref mut ct,
            ..
        } = self
        {
            *ct = Some(content_type.into());
        }
        self
    }

    /// Set the template type (e.g., "VELOCITY", "MUSTACHE") on a FILE body.
    /// No-op on other variants.
    pub fn with_template_type(mut self, template_type: impl Into<String>) -> Self {
        if let Body::File {
            template_type: ref mut tt,
            ..
        } = self
        {
            *tt = Some(template_type.into());
        }
        self
    }
}

impl Serialize for Body {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        match self {
            Body::Plain(s) => serializer.serialize_str(s),
            Body::Typed { body_type, json } => {
                use serde::ser::SerializeMap;
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("type", body_type)?;
                map.serialize_entry("json", json)?;
                map.end()
            }
            Body::File {
                file_path,
                content_type,
                template_type,
            } => {
                use serde::ser::SerializeMap;
                let count = 2
                    + content_type.as_ref().map_or(0, |_| 1)
                    + template_type.as_ref().map_or(0, |_| 1);
                let mut map = serializer.serialize_map(Some(count))?;
                map.serialize_entry("type", "FILE")?;
                map.serialize_entry("filePath", file_path)?;
                if let Some(ct) = content_type {
                    map.serialize_entry("contentType", ct)?;
                }
                if let Some(tt) = template_type {
                    map.serialize_entry("templateType", tt)?;
                }
                map.end()
            }
        }
    }
}

impl<'de> Deserialize<'de> for Body {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        use serde_json::Value;
        let v = Value::deserialize(deserializer)?;
        match v {
            Value::String(s) => Ok(Body::Plain(s)),
            Value::Object(map) => {
                let body_type = map
                    .get("type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("JSON")
                    .to_string();
                if body_type == "FILE" {
                    let file_path = map
                        .get("filePath")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let content_type = map
                        .get("contentType")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());
                    let template_type = map
                        .get("templateType")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());
                    Ok(Body::File {
                        file_path,
                        content_type,
                        template_type,
                    })
                } else {
                    let json = map
                        .get("json")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    Ok(Body::Typed { body_type, json })
                }
            }
            _ => Ok(Body::Plain(v.to_string())),
        }
    }
}

// ---------------------------------------------------------------------------
// HttpResponse
// ---------------------------------------------------------------------------

/// Builder for an HTTP response action.
///
/// # Example
/// ```
/// use mockserver_client::HttpResponse;
///
/// let response = HttpResponse::new()
///     .status_code(201)
///     .header("Location", "/api/users/42")
///     .body("{\"id\": 42}");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,
}

impl HttpResponse {
    /// Create a new empty response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP status code.
    pub fn status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }

    /// Add a response header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Set the response body as a string.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(body.into());
        self
    }

    /// Set a response delay.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpTemplate (response or forward)
// ---------------------------------------------------------------------------

/// Template action — evaluate a response or forward template (Velocity, Mustache, etc.).
///
/// Used as `httpResponseTemplate` or `httpForwardTemplate` in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::HttpTemplate;
///
/// let tmpl = HttpTemplate::new("VELOCITY", "{ \"statusCode\": 200 }")
///     .template_file("/path/to/template.vm");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpTemplate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_file: Option<String>,
}

impl HttpTemplate {
    /// Create a template action with the given type and inline template body.
    pub fn new(template_type: impl Into<String>, template: impl Into<String>) -> Self {
        Self {
            template_type: Some(template_type.into()),
            template: Some(template.into()),
            template_file: None,
        }
    }

    /// Create a template action that loads from a file path.
    pub fn from_file(template_type: impl Into<String>, file_path: impl Into<String>) -> Self {
        Self {
            template_type: Some(template_type.into()),
            template: None,
            template_file: Some(file_path.into()),
        }
    }

    /// Set the template type (e.g., "VELOCITY", "MUSTACHE").
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Set the inline template body.
    pub fn template(mut self, template: impl Into<String>) -> Self {
        self.template = Some(template.into());
        self
    }

    /// Set the template file path (alternative to inline template).
    pub fn template_file(mut self, file_path: impl Into<String>) -> Self {
        self.template_file = Some(file_path.into());
        self
    }
}

// ---------------------------------------------------------------------------
// HttpForward
// ---------------------------------------------------------------------------

/// Forward action — proxy the matched request to another host.
///
/// # Example
/// ```
/// use mockserver_client::HttpForward;
///
/// let forward = HttpForward::new("backend.local", 8080);
/// ```
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpForward {
    pub host: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,
}

impl HttpForward {
    /// Create a forward action to the given host and port.
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self {
            host: host.into(),
            port: Some(port),
            scheme: None,
        }
    }

    /// Set the scheme (HTTP or HTTPS).
    pub fn scheme(mut self, scheme: impl Into<String>) -> Self {
        self.scheme = Some(scheme.into());
        self
    }
}

// ---------------------------------------------------------------------------
// HttpError
// ---------------------------------------------------------------------------

/// Error action — return a connection-level error to the caller.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpError {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drop_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_bytes: Option<String>,
}

impl HttpError {
    /// Create a new error action.
    pub fn new() -> Self {
        Self::default()
    }

    /// Drop the connection without a response.
    pub fn drop_connection(mut self, drop: bool) -> Self {
        self.drop_connection = Some(drop);
        self
    }

    /// Send arbitrary bytes then close.
    pub fn response_bytes(mut self, bytes: impl Into<String>) -> Self {
        self.response_bytes = Some(bytes.into());
        self
    }
}

// ---------------------------------------------------------------------------
// Delay
// ---------------------------------------------------------------------------

/// A time delay (e.g., for response delays).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Delay {
    pub time_unit: String,
    pub value: u64,
}

impl Delay {
    /// Create a delay in milliseconds.
    pub fn milliseconds(value: u64) -> Self {
        Self {
            time_unit: "MILLISECONDS".to_string(),
            value,
        }
    }

    /// Create a delay in seconds.
    pub fn seconds(value: u64) -> Self {
        Self {
            time_unit: "SECONDS".to_string(),
            value,
        }
    }
}

// ---------------------------------------------------------------------------
// Times
// ---------------------------------------------------------------------------

/// How many times an expectation should be matched.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Times {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub remaining_times: Option<u32>,

    pub unlimited: bool,
}

impl Times {
    /// Match unlimited times.
    pub fn unlimited() -> Self {
        Self {
            remaining_times: None,
            unlimited: true,
        }
    }

    /// Match exactly `n` times.
    pub fn exactly(n: u32) -> Self {
        Self {
            remaining_times: Some(n),
            unlimited: false,
        }
    }

    /// Match once.
    pub fn once() -> Self {
        Self::exactly(1)
    }
}

// ---------------------------------------------------------------------------
// TimeToLive
// ---------------------------------------------------------------------------

/// How long an expectation remains active.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TimeToLive {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_unit: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<u64>,

    pub unlimited: bool,
}

impl TimeToLive {
    /// Unlimited TTL (never expires).
    pub fn unlimited() -> Self {
        Self {
            time_unit: None,
            time_to_live: None,
            unlimited: true,
        }
    }

    /// Expire after the given number of seconds.
    pub fn seconds(seconds: u64) -> Self {
        Self {
            time_unit: Some("SECONDS".to_string()),
            time_to_live: Some(seconds),
            unlimited: false,
        }
    }

    /// Expire after the given number of milliseconds.
    pub fn milliseconds(millis: u64) -> Self {
        Self {
            time_unit: Some("MILLISECONDS".to_string()),
            time_to_live: Some(millis),
            unlimited: false,
        }
    }
}

// ---------------------------------------------------------------------------
// VerificationTimes
// ---------------------------------------------------------------------------

/// Verification constraints — how many times a request must have been received.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationTimes {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_least: Option<u32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_most: Option<u32>,
}

impl VerificationTimes {
    /// Require at least `n` matching requests.
    pub fn at_least(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: None,
        }
    }

    /// Require at most `n` matching requests.
    pub fn at_most(n: u32) -> Self {
        Self {
            at_least: None,
            at_most: Some(n),
        }
    }

    /// Require exactly `n` matching requests.
    pub fn exactly(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: Some(n),
        }
    }

    /// Require between `min` and `max` matching requests (inclusive).
    pub fn between(min: u32, max: u32) -> Self {
        Self {
            at_least: Some(min),
            at_most: Some(max),
        }
    }
}

// ---------------------------------------------------------------------------
// Expectation
// ---------------------------------------------------------------------------

/// A full expectation combining a request matcher with an action.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Expectation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<i32>,

    pub http_request: HttpRequest,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward: Option<HttpForward>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response_template: Option<HttpTemplate>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_template: Option<HttpTemplate>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_error: Option<HttpError>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<Times>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<TimeToLive>,
}

impl Expectation {
    /// Create a new expectation with the given request matcher.
    pub fn new(request: HttpRequest) -> Self {
        Self {
            http_request: request,
            ..Default::default()
        }
    }

    /// Set the expectation ID (for upsert semantics).
    pub fn id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Set the priority (higher = matched first).
    pub fn priority(mut self, priority: i32) -> Self {
        self.priority = Some(priority);
        self
    }

    /// Set a response action.
    pub fn respond(mut self, response: HttpResponse) -> Self {
        self.http_response = Some(response);
        self
    }

    /// Set a forward action.
    pub fn forward(mut self, forward: HttpForward) -> Self {
        self.http_forward = Some(forward);
        self
    }

    /// Set a response template action.
    pub fn respond_template(mut self, template: HttpTemplate) -> Self {
        self.http_response_template = Some(template);
        self
    }

    /// Set a forward template action.
    pub fn forward_template(mut self, template: HttpTemplate) -> Self {
        self.http_forward_template = Some(template);
        self
    }

    /// Set an error action.
    pub fn error(mut self, error: HttpError) -> Self {
        self.http_error = Some(error);
        self
    }

    /// Set the number of times this expectation matches.
    pub fn times(mut self, times: Times) -> Self {
        self.times = Some(times);
        self
    }

    /// Set the time-to-live.
    pub fn time_to_live(mut self, ttl: TimeToLive) -> Self {
        self.time_to_live = Some(ttl);
        self
    }
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

/// A verification request sent to MockServer.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Verification {
    pub http_request: HttpRequest,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<VerificationTimes>,
}

/// A verification sequence request.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationSequence {
    pub http_requests: Vec<HttpRequest>,
}

// ---------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------

/// Port list (used by status and bind endpoints).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Ports {
    pub ports: Vec<u16>,
}

// ---------------------------------------------------------------------------
// Retrieve types
// ---------------------------------------------------------------------------

/// The type of data to retrieve from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetrieveType {
    /// Recorded inbound requests.
    Requests,
    /// Active (live) expectations.
    ActiveExpectations,
    /// Recorded expectations (from proxy mode).
    RecordedExpectations,
    /// Log messages.
    Logs,
    /// Request/response pairs.
    RequestResponses,
}

impl RetrieveType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveType::Requests => "REQUESTS",
            RetrieveType::ActiveExpectations => "ACTIVE_EXPECTATIONS",
            RetrieveType::RecordedExpectations => "RECORDED_EXPECTATIONS",
            RetrieveType::Logs => "LOGS",
            RetrieveType::RequestResponses => "REQUEST_RESPONSES",
        }
    }
}

/// The response format for retrieve calls.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetrieveFormat {
    Json,
    LogEntries,
}

impl RetrieveFormat {
    /// The query parameter value for this format.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveFormat::Json => "JSON",
            RetrieveFormat::LogEntries => "LOG_ENTRIES",
        }
    }
}

/// The type of data to clear from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClearType {
    All,
    Log,
    Expectations,
}

impl ClearType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            ClearType::All => "ALL",
            ClearType::Log => "LOG",
            ClearType::Expectations => "EXPECTATIONS",
        }
    }
}
