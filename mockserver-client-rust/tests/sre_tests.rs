//! SRE control-plane tests.
//!
//! Two kinds of tests, no running MockServer required:
//!  * serde round-trip / shape tests proving the JSON matches
//!    `jekyll-www.mock-server.com/mockserver-openapi.yaml`, and
//!  * stub-server tests using `tiny_http` (already a dev-dependency) that assert
//!    the exact HTTP method, path and request body each client method sends, and
//!    that the documented status codes map to the right `Result`.

use std::sync::mpsc;
use std::thread;

use mockserver_client::*;

// ---------------------------------------------------------------------------
// Stub HTTP server harness
// ---------------------------------------------------------------------------

/// What the stub captured about the single request it served.
struct Captured {
    method: String,
    url: String,
    body: String,
}

/// Start a one-shot stub server that replies with `status`/`resp_body` to the
/// first request, capturing its method, url and body. Returns a client pointed
/// at the stub and a receiver that yields the captured request.
fn stub(status: u16, resp_body: &'static str) -> (MockServerClient, mpsc::Receiver<Captured>) {
    let server = tiny_http::Server::http("127.0.0.1:0").expect("bind stub server");
    let port = match server.server_addr() {
        tiny_http::ListenAddr::IP(addr) => addr.port(),
        #[allow(unreachable_patterns)]
        _ => panic!("expected IP listen address"),
    };
    let (tx, rx) = mpsc::channel();

    thread::spawn(move || {
        let mut request = server.recv().expect("stub recv");
        let method = request.method().as_str().to_string();
        let url = request.url().to_string();
        let mut body = String::new();
        request.as_reader().read_to_string(&mut body).ok();
        tx.send(Captured { method, url, body }).ok();

        let response =
            tiny_http::Response::from_string(resp_body).with_status_code(tiny_http::StatusCode(status));
        request.respond(response).ok();
    });

    let client = ClientBuilder::new("127.0.0.1", port)
        .build()
        .expect("build client");
    (client, rx)
}

// ---------------------------------------------------------------------------
// Load scenario — serde shape (matches the OpenAPI LoadScenario example)
// ---------------------------------------------------------------------------

#[test]
fn test_load_scenario_serializes_to_spec_shape() {
    let scenario = LoadScenario::new(
        "checkout-load",
        LoadProfile::constant(10, 30_000).iteration_pacing_millis(50),
        vec![LoadStep::new(
            HttpRequest::new()
                .method("GET")
                .path("/api/item/$iteration.index")
                .header("Host", "target.svc:8080")
                .socket_address(SocketAddress::new("target.svc", 8080).scheme("HTTP")),
        )
        .think_time(Delay::milliseconds(20))],
    )
    .template_type("VELOCITY")
    .max_requests(5000);

    let json = serde_json::to_value(&scenario).unwrap();

    assert_eq!(json["name"], "checkout-load");
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["maxRequests"], 5000);
    assert_eq!(json["profile"]["type"], "CONSTANT");
    assert_eq!(json["profile"]["vus"], 10);
    assert_eq!(json["profile"]["durationMillis"], 30000);
    assert_eq!(json["profile"]["iterationPacingMillis"], 50);

    let step = &json["steps"][0];
    assert_eq!(step["request"]["method"], "GET");
    assert_eq!(step["request"]["path"], "/api/item/$iteration.index");
    assert_eq!(step["request"]["socketAddress"]["host"], "target.svc");
    assert_eq!(step["request"]["socketAddress"]["port"], 8080);
    assert_eq!(step["request"]["socketAddress"]["scheme"], "HTTP");
    assert_eq!(step["thinkTime"]["timeUnit"], "MILLISECONDS");
    assert_eq!(step["thinkTime"]["value"], 20);
}

#[test]
fn test_load_profile_linear_shape() {
    let profile = LoadProfile::linear(1, 50, 60_000);
    let json = serde_json::to_value(&profile).unwrap();
    assert_eq!(json["type"], "LINEAR");
    assert_eq!(json["startVus"], 1);
    assert_eq!(json["endVus"], 50);
    assert_eq!(json["durationMillis"], 60000);
    // Optional fields absent.
    assert!(json.get("vus").is_none());
    assert!(json.get("iterationPacingMillis").is_none());
}

#[test]
fn test_set_load_scenario_sends_put_to_load_scenario() {
    let (client, rx) = stub(200, r#"{"status":"started","name":"s","steps":1}"#);
    let scenario = LoadScenario::new(
        "s",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );

    let result = client.set_load_scenario(&scenario).unwrap();
    assert_eq!(result["status"], "started");

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["name"], "s");
    assert_eq!(sent["profile"]["vus"], 1);
}

#[test]
fn test_load_scenario_status_sends_get() {
    let (client, rx) = stub(200, r#"{"state":"running","currentVus":3}"#);
    let result = client.load_scenario_status().unwrap();
    assert_eq!(result["state"], "running");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/loadScenario");
}

#[test]
fn test_stop_load_scenario_sends_delete() {
    let (client, rx) = stub(200, r#"{"status":"stopped"}"#);
    let result = client.stop_load_scenario().unwrap();
    assert_eq!(result["status"], "stopped");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "DELETE");
    assert_eq!(cap.url, "/mockserver/loadScenario");
}

#[test]
fn test_set_load_scenario_403_is_feature_disabled() {
    let (client, _rx) = stub(403, r#"{"error":"load generation not enabled"}"#);
    let scenario = LoadScenario::new(
        "s",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );
    match client.set_load_scenario(&scenario) {
        Err(Error::FeatureDisabled(msg)) => assert!(msg.contains("not enabled")),
        other => panic!("expected FeatureDisabled, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// Service chaos
// ---------------------------------------------------------------------------

#[test]
fn test_set_service_chaos_sends_put_with_host_and_chaos() {
    let (client, rx) = stub(200, "{}");
    let profile = HttpChaosProfile::new()
        .error_status(503)
        .error_probability(0.3)
        .latency(Delay::milliseconds(200));

    client
        .set_service_chaos("payments.internal:8443", &profile, Some(60_000))
        .unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/serviceChaos");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["host"], "payments.internal:8443");
    assert_eq!(sent["chaos"]["errorStatus"], 503);
    assert_eq!(sent["chaos"]["errorProbability"], 0.3);
    assert_eq!(sent["chaos"]["latency"]["timeUnit"], "MILLISECONDS");
    assert_eq!(sent["chaos"]["latency"]["value"], 200);
    assert_eq!(sent["ttlMillis"], 60000);
}

#[test]
fn test_set_service_chaos_without_ttl_omits_field() {
    let (client, rx) = stub(200, "{}");
    let profile = HttpChaosProfile::new().error_status(500);
    client.set_service_chaos("h", &profile, None).unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert!(sent.get("ttlMillis").is_none());
}

#[test]
fn test_remove_and_clear_service_chaos() {
    let (client, rx) = stub(200, "{}");
    client.remove_service_chaos("h").unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["host"], "h");
    assert_eq!(sent["remove"], true);

    let (client, rx) = stub(200, "{}");
    client.clear_service_chaos().unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["clear"], true);
}

// ---------------------------------------------------------------------------
// SLO verdict
// ---------------------------------------------------------------------------

#[test]
fn test_slo_criteria_serializes_to_spec_shape() {
    let criteria = SloCriteria::new(vec![
        SloObjective::new("LATENCY_P95", "LESS_THAN", 250.0).scope("FORWARD"),
        SloObjective::new("ERROR_RATE", "LESS_THAN_OR_EQUAL", 0.01),
    ])
    .name("checkout-slo")
    .window(SloWindow::lookback(60_000))
    .minimum_sample_count(20)
    .upstream_hosts(vec!["payments.svc".to_string()]);

    let json = serde_json::to_value(&criteria).unwrap();
    assert_eq!(json["name"], "checkout-slo");
    assert_eq!(json["window"]["type"], "LOOKBACK");
    assert_eq!(json["window"]["lookbackMillis"], 60000);
    assert_eq!(json["minimumSampleCount"], 20);
    assert_eq!(json["upstreamHosts"][0], "payments.svc");
    assert_eq!(json["objectives"][0]["sli"], "LATENCY_P95");
    assert_eq!(json["objectives"][0]["comparator"], "LESS_THAN");
    assert_eq!(json["objectives"][0]["threshold"], 250.0);
    assert_eq!(json["objectives"][0]["scope"], "FORWARD");
    assert_eq!(json["objectives"][1]["sli"], "ERROR_RATE");
    assert!(json["objectives"][1].get("scope").is_none());
}

#[test]
fn test_verify_slo_200_is_pass_verdict() {
    let (client, rx) = stub(
        200,
        r#"{"name":"checkout-slo","result":"PASS","sampleCount":42,
            "objectiveResults":[{"sli":"LATENCY_P95","result":"PASS","observedValue":120.0}]}"#,
    );
    let criteria = SloCriteria::new(vec![SloObjective::new("LATENCY_P95", "LESS_THAN", 250.0)]);
    let verdict = client.verify_slo(&criteria).unwrap();
    assert!(verdict.is_pass());
    assert_eq!(verdict.name.as_deref(), Some("checkout-slo"));
    assert_eq!(verdict.sample_count, Some(42));
    assert_eq!(verdict.objective_results.len(), 1);

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/verifySLO");
}

#[test]
fn test_verify_slo_406_is_fail_verdict_not_error() {
    let (client, _rx) = stub(406, r#"{"name":"s","result":"FAIL","sampleCount":10}"#);
    let criteria = SloCriteria::new(vec![SloObjective::new("ERROR_RATE", "LESS_THAN", 0.01)]);
    // 406 must deserialize to a verdict (so a CI gate can branch), not Err.
    let verdict = client.verify_slo(&criteria).unwrap();
    assert!(verdict.is_fail());
}

#[test]
fn test_verify_slo_400_is_feature_disabled() {
    let (client, _rx) = stub(400, r#"{"error":"SLO tracking disabled"}"#);
    let criteria = SloCriteria::new(vec![SloObjective::new("ERROR_RATE", "LESS_THAN", 0.01)]);
    match client.verify_slo(&criteria) {
        Err(Error::FeatureDisabled(msg)) => assert!(msg.contains("disabled")),
        other => panic!("expected FeatureDisabled, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// Preemption
// ---------------------------------------------------------------------------

#[test]
fn test_preemption_request_serializes_to_spec_shape() {
    let req = PreemptionRequest::new()
        .mode("both")
        .drain_millis(10_000)
        .ttl_millis(60_000)
        .last_stream_id(3);
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["mode"], "both");
    assert_eq!(json["drainMillis"], 10000);
    assert_eq!(json["ttlMillis"], 60000);
    assert_eq!(json["lastStreamId"], 3);
}

#[test]
fn test_empty_preemption_request_serializes_to_empty_object() {
    let json = serde_json::to_value(PreemptionRequest::new()).unwrap();
    assert_eq!(json, serde_json::json!({}));
}

#[test]
fn test_set_preemption_returns_status() {
    let (client, rx) = stub(200, r#"{"state":"draining","inFlight":2,"mode":"both"}"#);
    let status = client.set_preemption(&PreemptionRequest::new().mode("both")).unwrap();
    assert_eq!(status.state.as_deref(), Some("draining"));
    assert_eq!(status.in_flight, Some(2));
    assert_eq!(status.mode.as_deref(), Some("both"));

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/preemption");
}

#[test]
fn test_preemption_status_sends_get() {
    let (client, rx) = stub(200, r#"{"state":"inactive","inFlight":0}"#);
    let status = client.preemption_status().unwrap();
    assert_eq!(status.state.as_deref(), Some("inactive"));
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/preemption");
}

#[test]
fn test_clear_preemption_sends_delete() {
    let (client, rx) = stub(200, r#"{"state":"inactive"}"#);
    client.clear_preemption().unwrap();
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "DELETE");
    assert_eq!(cap.url, "/mockserver/preemption");
}

// ---------------------------------------------------------------------------
// Chaos experiment
// ---------------------------------------------------------------------------

#[test]
fn test_chaos_experiment_serializes_to_spec_shape() {
    let experiment = ChaosExperiment::new(vec![
        ChaosStage::new(10_000).profile(
            "api.example.com",
            HttpChaosProfile::new().error_status(500).error_probability(0.1),
        ),
        ChaosStage::new(10_000).profile(
            "api.example.com",
            HttpChaosProfile::new().error_status(500).error_probability(0.5),
        ),
    ])
    .name("gradual-degradation")
    .loop_back(true);

    let json = serde_json::to_value(&experiment).unwrap();
    assert_eq!(json["name"], "gradual-degradation");
    // `loop` is a reserved-ish field name on the wire (not loopBack).
    assert_eq!(json["loop"], true);
    assert!(json.get("loopBack").is_none());
    assert_eq!(json["stages"][0]["durationMillis"], 10000);
    assert_eq!(
        json["stages"][0]["profiles"]["api.example.com"]["errorStatus"],
        500
    );
    assert_eq!(
        json["stages"][1]["profiles"]["api.example.com"]["errorProbability"],
        0.5
    );
}

#[test]
fn test_start_chaos_experiment_sends_put() {
    let (client, rx) = stub(200, r#"{"status":"started","name":"e"}"#);
    let experiment = ChaosExperiment::new(vec![ChaosStage::new(1000)
        .profile("h", HttpChaosProfile::new().error_status(500))])
    .name("e");
    let result = client.start_chaos_experiment(&experiment).unwrap();
    assert_eq!(result["status"], "started");

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/chaosExperiment");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["stages"][0]["durationMillis"], 1000);
}

#[test]
fn test_start_chaos_experiment_400_is_invalid_request() {
    let (client, _rx) = stub(400, r#"{"error":"empty stages"}"#);
    let experiment = ChaosExperiment::new(vec![]);
    match client.start_chaos_experiment(&experiment) {
        Err(Error::InvalidRequest(msg)) => assert!(msg.contains("stages")),
        other => panic!("expected InvalidRequest, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// SloVerdict deserialization from the documented wire shape
// ---------------------------------------------------------------------------

#[test]
fn test_slo_verdict_deserializes_full_wire_shape() {
    let wire = r#"{
        "name":"checkout-slo",
        "result":"FAIL",
        "windowFromEpochMillis":1000,
        "windowToEpochMillis":2000,
        "sampleCount":30,
        "objectiveResults":[
            {"sli":"LATENCY_P95","comparator":"LESS_THAN","threshold":250.0,
             "observedValue":310.5,"result":"FAIL","detail":"observed 310.5 > 250.0"}
        ]
    }"#;
    let verdict: SloVerdict = serde_json::from_str(wire).unwrap();
    assert!(verdict.is_fail());
    assert_eq!(verdict.window_from_epoch_millis, Some(1000));
    assert_eq!(verdict.window_to_epoch_millis, Some(2000));
    assert_eq!(verdict.sample_count, Some(30));
    let r = &verdict.objective_results[0];
    assert_eq!(r.sli.as_deref(), Some("LATENCY_P95"));
    assert_eq!(r.observed_value, Some(310.5));
    assert_eq!(r.result.as_deref(), Some("FAIL"));
}
