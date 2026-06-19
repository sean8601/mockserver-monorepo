//! The MockServer client and its builder.

use reqwest::blocking::Client;
use serde_json::Value;

use crate::breakpoint::{
    BreakpointMatcherList, BreakpointMatcherRegistration, BreakpointMatcherResponse,
    BreakpointRequestHandler, BreakpointResponseHandler, BreakpointStreamFrameHandler,
    BreakpointWebSocketClient,
};
use crate::error::{Error, Result};
use crate::model::*;

// ---------------------------------------------------------------------------
// ClientBuilder
// ---------------------------------------------------------------------------

/// Builder for constructing a [`MockServerClient`].
///
/// # Example
/// ```no_run
/// use mockserver_client::ClientBuilder;
///
/// let client = ClientBuilder::new("localhost", 1080)
///     .context_path("/api")
///     .secure(true)
///     .build()
///     .unwrap();
/// ```
pub struct ClientBuilder {
    host: String,
    port: u16,
    context_path: String,
    secure: bool,
    tls_verify: bool,
}

impl ClientBuilder {
    /// Create a new builder targeting the given host and port.
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self {
            host: host.into(),
            port,
            context_path: String::new(),
            secure: false,
            tls_verify: true,
        }
    }

    /// Set a context path prefix (e.g., "/mockserver" if deployed behind a reverse proxy).
    pub fn context_path(mut self, path: impl Into<String>) -> Self {
        self.context_path = path.into();
        self
    }

    /// Use HTTPS instead of HTTP.
    pub fn secure(mut self, secure: bool) -> Self {
        self.secure = secure;
        self
    }

    /// Whether to verify TLS certificates (default: true).
    pub fn tls_verify(mut self, verify: bool) -> Self {
        self.tls_verify = verify;
        self
    }

    /// Build the client.
    pub fn build(self) -> Result<MockServerClient> {
        let scheme = if self.secure { "https" } else { "http" };
        let ctx = if self.context_path.is_empty() {
            String::new()
        } else if self.context_path.starts_with('/') {
            self.context_path
        } else {
            format!("/{}", self.context_path)
        };
        let base_url = format!("{scheme}://{}:{}{ctx}", self.host, self.port);

        let http_client = Client::builder()
            .danger_accept_invalid_certs(!self.tls_verify)
            .build()?;

        Ok(MockServerClient {
            base_url,
            http: http_client,
            breakpoint_ws: std::sync::Mutex::new(None),
        })
    }
}

// ---------------------------------------------------------------------------
// MockServerClient
// ---------------------------------------------------------------------------

/// A blocking client for the MockServer control-plane REST API.
///
/// Created via [`ClientBuilder`]. All methods are synchronous.
pub struct MockServerClient {
    pub(crate) base_url: String,
    http: Client,
    breakpoint_ws: std::sync::Mutex<Option<BreakpointWebSocketClient>>,
}

impl MockServerClient {
    // ------------------------------------------------------------------
    // Expectation creation
    // ------------------------------------------------------------------

    /// Create one or more expectations on the server.
    ///
    /// Returns the created expectations as echoed by the server.
    pub fn upsert(&self, expectations: &[Expectation]) -> Result<Vec<Expectation>> {
        let body = serde_json::to_value(expectations)?;
        let resp = self
            .http
            .put(self.url("/mockserver/expectation"))
            .json(&body)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 | 201 => {
                let text = resp.text()?;
                if text.is_empty() {
                    Ok(expectations.to_vec())
                } else {
                    Ok(serde_json::from_str(&text)?)
                }
            }
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    // ------------------------------------------------------------------
    // OpenAPI import
    // ------------------------------------------------------------------

    /// Register expectations from an OpenAPI/Swagger specification.
    ///
    /// Sends a `PUT /mockserver/openapi` with the given [`OpenApiExpectation`].
    /// MockServer parses the spec and creates request matchers and example
    /// responses for the selected operations (or every operation when none are
    /// specified). Returns the created expectations as echoed by the server.
    ///
    /// # Example
    /// ```no_run
    /// use mockserver_client::{ClientBuilder, OpenApiExpectation};
    ///
    /// let client = ClientBuilder::new("localhost", 1080).build().unwrap();
    /// client.openapi(
    ///     &OpenApiExpectation::new("https://example.com/petstore.yaml")
    ///         .operation("listPets", "200"),
    /// ).unwrap();
    /// ```
    pub fn openapi(&self, expectation: &OpenApiExpectation) -> Result<Vec<Expectation>> {
        let resp = self
            .http
            .put(self.url("/mockserver/openapi"))
            .json(expectation)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 | 201 => {
                let text = resp.text()?;
                if text.is_empty() {
                    Ok(vec![])
                } else {
                    Ok(serde_json::from_str(&text)?)
                }
            }
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    // ------------------------------------------------------------------
    // Fluent API entry point
    // ------------------------------------------------------------------

    /// Begin building an expectation with the fluent `when(...).respond(...)` API.
    ///
    /// # Example
    /// ```no_run
    /// use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse};
    ///
    /// let client = ClientBuilder::new("localhost", 1080).build().unwrap();
    /// client.when(HttpRequest::new().method("GET").path("/foo"))
    ///     .respond(HttpResponse::new().status_code(200).body("bar"))
    ///     .unwrap();
    /// ```
    pub fn when(&self, request: HttpRequest) -> ForwardChainExpectation<'_> {
        ForwardChainExpectation {
            client: self,
            request,
            times: None,
            time_to_live: None,
            priority: None,
            id: None,
        }
    }

    // ------------------------------------------------------------------
    // Verify
    // ------------------------------------------------------------------

    /// Verify that a request was received the specified number of times.
    ///
    /// Returns `Ok(())` if verification passes, or
    /// `Err(Error::VerificationFailure)` with the server's failure message.
    pub fn verify(&self, request: HttpRequest, times: VerificationTimes) -> Result<()> {
        let verification = Verification {
            http_request: Some(request),
            http_response: None,
            times: Some(times),
            maximum_number_of_request_to_return_in_verification_failure: None,
        };
        self.do_verify(&verification)
    }

    /// Verify that a request/response pair was received the specified number of times.
    ///
    /// Both the request matcher and the response matcher must match for a
    /// recorded exchange to count. The response matcher uses the same
    /// [`HttpResponse`] type as expectations — the server matches against the
    /// recorded response's status code, headers, and body.
    pub fn verify_request_and_response(
        &self,
        request: HttpRequest,
        response: HttpResponse,
        times: VerificationTimes,
    ) -> Result<()> {
        let verification = Verification {
            http_request: Some(request),
            http_response: Some(response),
            times: Some(times),
            maximum_number_of_request_to_return_in_verification_failure: None,
        };
        self.do_verify(&verification)
    }

    /// Verify that a response (regardless of request) was returned the
    /// specified number of times.
    ///
    /// The `httpRequest` field is omitted from the JSON so the server matches
    /// any request.
    pub fn verify_response(&self, response: HttpResponse, times: VerificationTimes) -> Result<()> {
        let verification = Verification {
            http_request: None,
            http_response: Some(response),
            times: Some(times),
            maximum_number_of_request_to_return_in_verification_failure: None,
        };
        self.do_verify(&verification)
    }

    /// Verify that no requests at all were received by the server.
    ///
    /// Thin wrapper over [`verify`](Self::verify): matches any request (an empty
    /// matcher) with `exactly(0)` times. Returns `Ok(())` if the server received
    /// no requests, or `Err(Error::VerificationFailure)` otherwise.
    pub fn verify_zero_interactions(&self) -> Result<()> {
        self.verify(HttpRequest::new(), VerificationTimes::exactly(0))
    }

    /// Send a fully constructed [`Verification`] to the server.
    ///
    /// This is the most flexible form — callers can set every field,
    /// including `maximum_number_of_request_to_return_in_verification_failure`.
    pub fn verify_raw(&self, verification: &Verification) -> Result<()> {
        self.do_verify(verification)
    }

    /// Verify that requests were received in the given order.
    pub fn verify_sequence(&self, requests: Vec<HttpRequest>) -> Result<()> {
        let verification = VerificationSequence {
            http_requests: Some(requests),
            http_responses: None,
        };
        self.do_verify_sequence(&verification)
    }

    /// Verify that request/response pairs were received in the given order.
    ///
    /// `responses` is index-aligned with `requests` — each entry constrains
    /// the response that must have been returned for the corresponding request.
    pub fn verify_sequence_with_responses(
        &self,
        requests: Vec<HttpRequest>,
        responses: Vec<HttpResponse>,
    ) -> Result<()> {
        let verification = VerificationSequence {
            http_requests: Some(requests),
            http_responses: Some(responses),
        };
        self.do_verify_sequence(&verification)
    }

    /// Send a fully constructed [`VerificationSequence`] to the server.
    pub fn verify_sequence_raw(&self, verification: &VerificationSequence) -> Result<()> {
        self.do_verify_sequence(verification)
    }

    // ------------------------------------------------------------------
    // Clear / Reset
    // ------------------------------------------------------------------

    /// Clear expectations and/or logs matching the given request.
    ///
    /// If `request` is `None`, clears everything of the specified type.
    pub fn clear(
        &self,
        request: Option<&HttpRequest>,
        clear_type: Option<ClearType>,
    ) -> Result<()> {
        let mut url = self.url("/mockserver/clear");
        if let Some(ct) = clear_type {
            url = format!("{url}?type={}", ct.as_str());
        }

        let mut builder = self.http.put(&url);
        builder = builder.header("Content-Type", "application/json");
        if let Some(req) = request {
            builder = builder.json(req);
        } else {
            builder = builder.body("");
        }

        let resp = builder.send()?;
        let status = resp.status().as_u16();
        match status {
            200 => Ok(()),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Clear expectations by expectation ID.
    pub fn clear_by_id(
        &self,
        expectation_id: impl Into<String>,
        clear_type: Option<ClearType>,
    ) -> Result<()> {
        let mut url = self.url("/mockserver/clear");
        if let Some(ct) = clear_type {
            url = format!("{url}?type={}", ct.as_str());
        }

        let body = serde_json::json!({ "id": expectation_id.into() });
        let resp = self.http.put(&url).json(&body).send()?;

        let status = resp.status().as_u16();
        match status {
            200 => Ok(()),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Reset all expectations and recorded requests.
    pub fn reset(&self) -> Result<()> {
        let resp = self
            .http
            .put(self.url("/mockserver/reset"))
            .header("Content-Type", "application/json")
            .body("")
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => Ok(()),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    // ------------------------------------------------------------------
    // Retrieve
    // ------------------------------------------------------------------

    /// Retrieve recorded requests matching the optional filter.
    pub fn retrieve_recorded_requests(
        &self,
        request: Option<&HttpRequest>,
    ) -> Result<Vec<HttpRequest>> {
        let text = self.do_retrieve(request, RetrieveType::Requests, RetrieveFormat::Json)?;
        if text.is_empty() {
            return Ok(vec![]);
        }
        Ok(serde_json::from_str(&text)?)
    }

    /// Retrieve active expectations matching the optional filter.
    pub fn retrieve_active_expectations(
        &self,
        request: Option<&HttpRequest>,
    ) -> Result<Vec<Expectation>> {
        let text = self.do_retrieve(
            request,
            RetrieveType::ActiveExpectations,
            RetrieveFormat::Json,
        )?;
        if text.is_empty() {
            return Ok(vec![]);
        }
        Ok(serde_json::from_str(&text)?)
    }

    /// Retrieve recorded expectations matching the optional filter.
    pub fn retrieve_recorded_expectations(
        &self,
        request: Option<&HttpRequest>,
    ) -> Result<Vec<Expectation>> {
        let text = self.do_retrieve(
            request,
            RetrieveType::RecordedExpectations,
            RetrieveFormat::Json,
        )?;
        if text.is_empty() {
            return Ok(vec![]);
        }
        Ok(serde_json::from_str(&text)?)
    }

    /// Retrieve the active expectations as MockServer SDK setup code (the
    /// builder code that recreates the expectations) in the requested language.
    ///
    /// `format` must be one of the code-generation variants of
    /// [`RetrieveFormat`] (e.g. [`RetrieveFormat::Java`],
    /// [`RetrieveFormat::Rust`]). The generated code is returned as a string.
    pub fn retrieve_expectations_as_code(
        &self,
        format: RetrieveFormat,
        request: Option<&HttpRequest>,
    ) -> Result<String> {
        self.do_retrieve(request, RetrieveType::ActiveExpectations, format)
    }

    /// Retrieve the recorded (proxied) request/response pairs as MockServer SDK
    /// setup code in the requested language.
    ///
    /// `format` must be one of the code-generation variants of
    /// [`RetrieveFormat`]. The generated code is returned as a string.
    pub fn retrieve_recorded_expectations_as_code(
        &self,
        format: RetrieveFormat,
        request: Option<&HttpRequest>,
    ) -> Result<String> {
        self.do_retrieve(request, RetrieveType::RecordedExpectations, format)
    }

    /// Retrieve log messages matching the optional filter.
    pub fn retrieve_log_messages(&self, request: Option<&HttpRequest>) -> Result<Vec<String>> {
        let text = self.do_retrieve(request, RetrieveType::Logs, RetrieveFormat::LogEntries)?;
        if text.is_empty() {
            return Ok(vec![]);
        }
        // Log messages may be returned as a JSON array of strings or as a
        // separator-delimited block. Try JSON first.
        if let Ok(arr) = serde_json::from_str::<Vec<String>>(&text) {
            return Ok(arr);
        }
        // Fall back to splitting on the separator used by MockServer.
        Ok(text
            .split("------------------------------------\n")
            .map(|s| s.to_string())
            .filter(|s| !s.is_empty())
            .collect())
    }

    /// Retrieve recorded request/response pairs.
    pub fn retrieve_request_responses(&self, request: Option<&HttpRequest>) -> Result<Vec<Value>> {
        let text = self.do_retrieve(
            request,
            RetrieveType::RequestResponses,
            RetrieveFormat::Json,
        )?;
        if text.is_empty() {
            return Ok(vec![]);
        }
        Ok(serde_json::from_str(&text)?)
    }

    // ------------------------------------------------------------------
    // Status / Bind
    // ------------------------------------------------------------------

    /// Query the server's listening ports.
    pub fn status(&self) -> Result<Ports> {
        let resp = self
            .http
            .put(self.url("/mockserver/status"))
            .header("Content-Type", "application/json")
            .body("")
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => {
                let text = resp.text()?;
                Ok(serde_json::from_str(&text)?)
            }
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Bind additional listening ports.
    pub fn bind(&self, ports: &[u16]) -> Result<Ports> {
        let body = Ports {
            ports: ports.to_vec(),
        };
        let resp = self
            .http
            .put(self.url("/mockserver/bind"))
            .json(&body)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => {
                let text = resp.text()?;
                Ok(serde_json::from_str(&text)?)
            }
            400 => Err(Error::InvalidRequest(resp.text()?)),
            406 => Err(Error::VerificationFailure(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Check if the MockServer has started (polls with retries).
    pub fn has_started(&self, attempts: u32, timeout_ms: u64) -> bool {
        for i in 0..attempts {
            match self.status() {
                Ok(_) => return true,
                Err(_) => {
                    if i < attempts - 1 {
                        std::thread::sleep(std::time::Duration::from_millis(timeout_ms));
                    }
                }
            }
        }
        false
    }

    // ------------------------------------------------------------------
    // Breakpoints
    // ------------------------------------------------------------------

    /// Ensure the breakpoint WebSocket client is connected and return the clientId.
    /// If the existing connection's read loop has exited, it is replaced transparently.
    fn ensure_breakpoint_ws(&self) -> Result<String> {
        let mut guard = self.breakpoint_ws.lock().unwrap();
        let needs_connect = match guard.as_ref() {
            None => true,
            Some(ws) => ws.is_dead(),
        };
        if needs_connect {
            // Close the old dead connection if present
            if let Some(old) = guard.take() {
                old.close();
            }
            let ws = BreakpointWebSocketClient::connect(&self.base_url)?;
            *guard = Some(ws);
        }
        Ok(guard.as_ref().unwrap().client_id.clone())
    }

    /// Register a breakpoint matcher with the given phases and handlers.
    /// Returns the server-assigned breakpoint id.
    pub fn add_breakpoint(
        &self,
        matcher: HttpRequest,
        phases: &[&str],
        request_handler: Option<BreakpointRequestHandler>,
        response_handler: Option<BreakpointResponseHandler>,
        stream_frame_handler: Option<BreakpointStreamFrameHandler>,
    ) -> Result<String> {
        if phases.is_empty() {
            return Err(Error::InvalidRequest(
                "At least one phase is required".into(),
            ));
        }

        let client_id = self.ensure_breakpoint_ws()?;

        let reg = BreakpointMatcherRegistration {
            http_request: matcher,
            phases: phases.iter().map(|s| s.to_string()).collect(),
            client_id: Some(client_id),
        };

        let resp = self
            .http
            .put(self.url("/mockserver/breakpoint/matcher"))
            .json(&reg)
            .send()?;

        let status = resp.status().as_u16();
        let text = resp.text()?;
        if status >= 400 {
            return Err(Error::UnexpectedStatus { status, body: text });
        }

        let result: BreakpointMatcherResponse = serde_json::from_str(&text)?;
        let id = result.id.clone();

        // Register handlers
        let guard = self.breakpoint_ws.lock().unwrap();
        if let Some(ws) = guard.as_ref() {
            if let Some(h) = request_handler {
                ws.set_request_handler(&id, h);
            }
            if let Some(h) = response_handler {
                ws.set_response_handler(&id, h);
            }
            if let Some(h) = stream_frame_handler {
                ws.set_stream_frame_handler(&id, h);
            }
        }

        Ok(id)
    }

    /// Convenience: register a REQUEST-only breakpoint.
    pub fn add_request_breakpoint(
        &self,
        matcher: HttpRequest,
        handler: BreakpointRequestHandler,
    ) -> Result<String> {
        self.add_breakpoint(
            matcher,
            &[crate::breakpoint::phase::REQUEST],
            Some(handler),
            None,
            None,
        )
    }

    /// Convenience: register a REQUEST + RESPONSE breakpoint.
    pub fn add_request_response_breakpoint(
        &self,
        matcher: HttpRequest,
        request_handler: BreakpointRequestHandler,
        response_handler: BreakpointResponseHandler,
    ) -> Result<String> {
        self.add_breakpoint(
            matcher,
            &[
                crate::breakpoint::phase::REQUEST,
                crate::breakpoint::phase::RESPONSE,
            ],
            Some(request_handler),
            Some(response_handler),
            None,
        )
    }

    /// Convenience: register a streaming-phase breakpoint.
    pub fn add_stream_breakpoint(
        &self,
        matcher: HttpRequest,
        phases: &[&str],
        handler: BreakpointStreamFrameHandler,
    ) -> Result<String> {
        self.add_breakpoint(matcher, phases, None, None, Some(handler))
    }

    /// List all registered breakpoint matchers.
    pub fn list_breakpoint_matchers(&self) -> Result<BreakpointMatcherList> {
        let resp = self
            .http
            .get(self.url("/mockserver/breakpoint/matchers"))
            .send()?;

        let status = resp.status().as_u16();
        let text = resp.text()?;
        if status >= 400 {
            return Err(Error::UnexpectedStatus { status, body: text });
        }

        Ok(serde_json::from_str(&text)?)
    }

    /// Remove a breakpoint matcher by id.
    pub fn remove_breakpoint_matcher(&self, id: impl Into<String>) -> Result<()> {
        let id = id.into();
        let body = serde_json::json!({ "id": &id });
        let resp = self
            .http
            .put(self.url("/mockserver/breakpoint/matcher/remove"))
            .json(&body)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => {
                let guard = self.breakpoint_ws.lock().unwrap();
                if let Some(ws) = guard.as_ref() {
                    ws.remove_handlers(&id);
                }
                Ok(())
            }
            404 => Err(Error::InvalidRequest(format!(
                "Breakpoint matcher not found: {id}"
            ))),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Remove all registered breakpoint matchers.
    pub fn clear_breakpoint_matchers(&self) -> Result<()> {
        let resp = self
            .http
            .put(self.url("/mockserver/breakpoint/matcher/clear"))
            .header("Content-Type", "application/json")
            .body("")
            .send()?;

        let status = resp.status().as_u16();
        if status >= 400 {
            return Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            });
        }

        let guard = self.breakpoint_ws.lock().unwrap();
        if let Some(ws) = guard.as_ref() {
            ws.clear_handlers();
        }
        Ok(())
    }

    /// Close the breakpoint callback WebSocket connection.
    pub fn close_breakpoint_websocket(&self) {
        let mut guard = self.breakpoint_ws.lock().unwrap();
        if let Some(ws) = guard.take() {
            ws.close();
        }
    }

    // ------------------------------------------------------------------
    // gRPC descriptor management
    // ------------------------------------------------------------------

    /// Upload a compiled protobuf descriptor set so gRPC requests can be matched.
    ///
    /// `descriptor` must be the raw bytes of a `FileDescriptorSet` (e.g. the
    /// output of `protoc --descriptor_set_out=... --include_imports`). The bytes
    /// are sent verbatim as `application/octet-stream` — they are **not**
    /// base64-encoded. Sends a `PUT /mockserver/grpc/descriptors`; the server
    /// responds `201 Created` on success.
    ///
    /// # Example
    /// ```no_run
    /// use mockserver_client::ClientBuilder;
    ///
    /// let client = ClientBuilder::new("localhost", 1080).build().unwrap();
    /// let descriptor_set: Vec<u8> = std::fs::read("greeter.desc").unwrap();
    /// client.upload_grpc_descriptor(&descriptor_set).unwrap();
    /// ```
    pub fn upload_grpc_descriptor(&self, descriptor: &[u8]) -> Result<()> {
        if descriptor.is_empty() {
            return Err(Error::InvalidRequest(
                "descriptor set bytes must not be empty".into(),
            ));
        }
        let resp = self
            .http
            .put(self.url("/mockserver/grpc/descriptors"))
            .header("Content-Type", "application/octet-stream")
            .body(descriptor.to_vec())
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 | 201 => Ok(()),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Retrieve the gRPC services registered from uploaded descriptor sets.
    ///
    /// Sends a `PUT /mockserver/grpc/services` and returns the parsed list of
    /// [`GrpcService`]s, each with its [`GrpcMethod`]s.
    pub fn retrieve_grpc_services(&self) -> Result<Vec<GrpcService>> {
        let resp = self
            .http
            .put(self.url("/mockserver/grpc/services"))
            .header("Content-Type", "application/json")
            .body("")
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => {
                let text = resp.text()?;
                if text.is_empty() {
                    Ok(vec![])
                } else {
                    Ok(serde_json::from_str(&text)?)
                }
            }
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    /// Clear all uploaded gRPC descriptor sets and registered services.
    ///
    /// Sends a `PUT /mockserver/grpc/clear`; the server responds `200 OK`.
    pub fn clear_grpc_descriptors(&self) -> Result<()> {
        let resp = self
            .http
            .put(self.url("/mockserver/grpc/clear"))
            .header("Content-Type", "application/json")
            .body("")
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 => Ok(()),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    fn do_verify(&self, verification: &Verification) -> Result<()> {
        let resp = self
            .http
            .put(self.url("/mockserver/verify"))
            .json(verification)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 | 202 => Ok(()),
            406 => Err(Error::VerificationFailure(resp.text()?)),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    fn do_verify_sequence(&self, verification: &VerificationSequence) -> Result<()> {
        let resp = self
            .http
            .put(self.url("/mockserver/verifySequence"))
            .json(verification)
            .send()?;

        let status = resp.status().as_u16();
        match status {
            200 | 202 => Ok(()),
            406 => Err(Error::VerificationFailure(resp.text()?)),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base_url)
    }

    fn do_retrieve(
        &self,
        request: Option<&HttpRequest>,
        retrieve_type: RetrieveType,
        format: RetrieveFormat,
    ) -> Result<String> {
        let url = format!(
            "{}?type={}&format={}",
            self.url("/mockserver/retrieve"),
            retrieve_type.as_str(),
            format.as_str(),
        );

        let mut builder = self.http.put(&url);
        builder = builder.header("Content-Type", "application/json");
        if let Some(req) = request {
            builder = builder.json(req);
        } else {
            builder = builder.body("");
        }

        let resp = builder.send()?;
        let status = resp.status().as_u16();
        match status {
            200 => Ok(resp.text()?),
            400 => Err(Error::InvalidRequest(resp.text()?)),
            _ => Err(Error::UnexpectedStatus {
                status,
                body: resp.text().unwrap_or_default(),
            }),
        }
    }
}

// ---------------------------------------------------------------------------
// ForwardChainExpectation (fluent builder)
// ---------------------------------------------------------------------------

/// Fluent builder for creating an expectation via `client.when(...).respond(...)`.
pub struct ForwardChainExpectation<'a> {
    client: &'a MockServerClient,
    request: HttpRequest,
    times: Option<Times>,
    time_to_live: Option<TimeToLive>,
    priority: Option<i32>,
    id: Option<String>,
}

impl<'a> ForwardChainExpectation<'a> {
    /// Set how many times this expectation should match.
    pub fn times(mut self, times: Times) -> Self {
        self.times = Some(times);
        self
    }

    /// Set the time-to-live for this expectation.
    pub fn time_to_live(mut self, ttl: TimeToLive) -> Self {
        self.time_to_live = Some(ttl);
        self
    }

    /// Set the priority for this expectation.
    pub fn priority(mut self, priority: i32) -> Self {
        self.priority = Some(priority);
        self
    }

    /// Set the expectation ID (for upsert semantics).
    pub fn with_id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Complete the expectation with a response action.
    pub fn respond(self, response: HttpResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond(response);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a forward action.
    pub fn forward(self, forward: HttpForward) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.forward(forward);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with an error action.
    pub fn error(self, error: HttpError) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.error(error);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a Server-Sent Events (SSE) response action.
    pub fn respond_sse(self, sse: HttpSseResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond_sse(sse);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a WebSocket response action.
    pub fn respond_web_socket(self, ws: HttpWebSocketResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond_web_socket(ws);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a DNS response action.
    pub fn respond_dns(self, dns: DnsResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond_dns(dns);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a raw binary response action.
    pub fn respond_binary(self, binary: BinaryResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond_binary(binary);
        client.upsert(&[expectation])
    }

    /// Complete the expectation with a gRPC streaming response action.
    pub fn respond_grpc_stream(self, grpc: GrpcStreamResponse) -> Result<Vec<Expectation>> {
        let (client, expectation) = self.into_parts();
        let expectation = expectation.respond_grpc_stream(grpc);
        client.upsert(&[expectation])
    }

    // ------------------------------------------------------------------
    // `respond_with_*` aliases (cross-client naming parity)
    //
    // The Python, PHP, and .NET clients expose these advanced response
    // builders under `respond_with_*` names. These aliases give the Rust
    // fluent chain the same surface so examples translate verbatim across
    // clients; each simply delegates to its idiomatic Rust counterpart.
    // ------------------------------------------------------------------

    /// Alias of [`respond_sse`](Self::respond_sse) for cross-client naming parity.
    pub fn respond_with_sse(self, sse: HttpSseResponse) -> Result<Vec<Expectation>> {
        self.respond_sse(sse)
    }

    /// Alias of [`respond_web_socket`](Self::respond_web_socket) for cross-client naming parity.
    pub fn respond_with_web_socket(self, ws: HttpWebSocketResponse) -> Result<Vec<Expectation>> {
        self.respond_web_socket(ws)
    }

    /// Alias of [`respond_dns`](Self::respond_dns) for cross-client naming parity.
    pub fn respond_with_dns(self, dns: DnsResponse) -> Result<Vec<Expectation>> {
        self.respond_dns(dns)
    }

    /// Alias of [`respond_binary`](Self::respond_binary) for cross-client naming parity.
    pub fn respond_with_binary(self, binary: BinaryResponse) -> Result<Vec<Expectation>> {
        self.respond_binary(binary)
    }

    /// Alias of [`respond_grpc_stream`](Self::respond_grpc_stream) for cross-client naming parity.
    pub fn respond_with_grpc_stream(self, grpc: GrpcStreamResponse) -> Result<Vec<Expectation>> {
        self.respond_grpc_stream(grpc)
    }

    fn into_parts(self) -> (&'a MockServerClient, Expectation) {
        let ForwardChainExpectation {
            client,
            request,
            times,
            time_to_live,
            priority,
            id,
        } = self;
        let mut exp = Expectation::new(request);
        exp.times = times;
        exp.time_to_live = time_to_live;
        exp.priority = priority;
        exp.id = id;
        (client, exp)
    }
}
