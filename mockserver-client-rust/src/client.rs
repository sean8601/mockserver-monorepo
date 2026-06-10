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
            http_request: request,
            times: Some(times),
        };
        let resp = self
            .http
            .put(self.url("/mockserver/verify"))
            .json(&verification)
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

    /// Verify that requests were received in the given order.
    pub fn verify_sequence(&self, requests: Vec<HttpRequest>) -> Result<()> {
        let verification = VerificationSequence {
            http_requests: requests,
        };
        let resp = self
            .http
            .put(self.url("/mockserver/verifySequence"))
            .json(&verification)
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
        let text =
            self.do_retrieve(request, RetrieveType::Requests, RetrieveFormat::Json)?;
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

    /// Retrieve log messages matching the optional filter.
    pub fn retrieve_log_messages(
        &self,
        request: Option<&HttpRequest>,
    ) -> Result<Vec<String>> {
        let text =
            self.do_retrieve(request, RetrieveType::Logs, RetrieveFormat::LogEntries)?;
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
    pub fn retrieve_request_responses(
        &self,
        request: Option<&HttpRequest>,
    ) -> Result<Vec<Value>> {
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
            return Err(Error::UnexpectedStatus {
                status,
                body: text,
            });
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
            return Err(Error::UnexpectedStatus {
                status,
                body: text,
            });
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
    // Internal helpers
    // ------------------------------------------------------------------

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
