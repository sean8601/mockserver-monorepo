package mockserver

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// stubServer captures the method, path and body of a single request and replies
// with the given status code and body.
func stubServer(t *testing.T, status int, respBody string, capMethod, capPath *string, capBody *[]byte) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if capMethod != nil {
			*capMethod = r.Method
		}
		if capPath != nil {
			*capPath = r.URL.Path
		}
		if capBody != nil {
			*capBody, _ = io.ReadAll(r.Body)
		}
		if respBody != "" {
			w.Header().Set("Content-Type", "application/json")
		}
		w.WriteHeader(status)
		if respBody != "" {
			_, _ = w.Write([]byte(respBody))
		}
	}))
}

// ---------------------------------------------------------------------------
// 1. Load scenario
// ---------------------------------------------------------------------------

func TestClient_SetLoadScenario(t *testing.T) {
	var method, path string
	var body []byte
	ts := stubServer(t, 200, `{"status":"started","name":"checkout-load","steps":1}`, &method, &path, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.SetLoadScenario(LoadScenario{
		Name:         "checkout-load",
		TemplateType: "VELOCITY",
		MaxRequests:  5000,
		Labels:       map[string]string{"team": "checkout"},
		Profile: &LoadProfile{
			Stages: []LoadStage{
				RampVusStage(0, 10, 30000, RampLinear),
				ConstantVusStage(10, 60000),
				PauseStage(5000),
			},
		},
		Steps: []LoadStep{
			{
				Request: &HttpRequest{
					Method:        "GET",
					Path:          "/api/item/$iteration.index",
					SocketAddress: &SocketAddress{Host: "target.svc", Port: 8080, Scheme: "HTTP"},
				},
				ThinkTime: &Delay{TimeUnit: "MILLISECONDS", Value: 20},
				Name:      "fetch-item",
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	if method != "PUT" {
		t.Errorf("expected PUT, got %s", method)
	}
	if path != "/mockserver/loadScenario" {
		t.Errorf("unexpected path %s", path)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["name"] != "checkout-load" || m["templateType"] != "VELOCITY" {
		t.Errorf("unexpected body fields: %v", m)
	}
	if m["maxRequests"].(float64) != 5000 {
		t.Errorf("expected maxRequests 5000, got %v", m["maxRequests"])
	}
	profile := m["profile"].(map[string]interface{})
	stages := profile["stages"].([]interface{})
	if len(stages) != 3 {
		t.Fatalf("expected 3 stages, got %d", len(stages))
	}
	stage0 := stages[0].(map[string]interface{})
	if stage0["type"] != "VU" {
		t.Errorf("expected stage 0 type VU, got %v", stage0["type"])
	}
	// A meaningful zero (startVus=0) must still be serialised (pointer + omitempty).
	if v, ok := stage0["startVus"]; !ok || v.(float64) != 0 {
		t.Errorf("expected stage 0 startVus 0 present, got %v (present=%v)", v, ok)
	}
	if stage0["endVus"].(float64) != 10 {
		t.Errorf("expected stage 0 endVus 10, got %v", stage0["endVus"])
	}
	if stage0["curve"] != "LINEAR" {
		t.Errorf("expected stage 0 curve LINEAR, got %v", stage0["curve"])
	}
	if stage0["durationMillis"].(float64) != 30000 {
		t.Errorf("expected stage 0 durationMillis 30000, got %v", stage0["durationMillis"])
	}
	// VU-ramp stage must not leak RATE fields.
	if _, ok := stage0["rate"]; ok {
		t.Errorf("did not expect rate on a VU stage, got %v", stage0["rate"])
	}
	stage1 := stages[1].(map[string]interface{})
	if stage1["type"] != "VU" || stage1["vus"].(float64) != 10 {
		t.Errorf("expected stage 1 VU hold vus 10, got %v", stage1)
	}
	if _, ok := stage1["startVus"]; ok {
		t.Errorf("did not expect startVus on a VU hold stage, got %v", stage1["startVus"])
	}
	stage2 := stages[2].(map[string]interface{})
	if stage2["type"] != "PAUSE" || stage2["durationMillis"].(float64) != 5000 {
		t.Errorf("expected stage 2 PAUSE durationMillis 5000, got %v", stage2)
	}
	steps := m["steps"].([]interface{})
	step0 := steps[0].(map[string]interface{})
	if step0["thinkTime"] == nil {
		t.Errorf("expected thinkTime in step, got %v", step0)
	}
	req0 := step0["request"].(map[string]interface{})
	sock := req0["socketAddress"].(map[string]interface{})
	if sock["host"] != "target.svc" {
		t.Errorf("expected socketAddress.host target.svc, got %v", sock)
	}
}

func TestClient_SetLoadScenario_FeatureDisabled(t *testing.T) {
	ts := stubServer(t, 403, `{"error":"load generation not enabled"}`, nil, nil, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.SetLoadScenario(LoadScenario{Name: "x"})
	if err == nil {
		t.Fatal("expected error for 403 status")
	}
	var fde *FeatureDisabledError
	if !errors.As(err, &fde) {
		t.Fatalf("expected FeatureDisabledError, got %T: %v", err, err)
	}
	if fde.Status != 403 {
		t.Errorf("expected status 403, got %d", fde.Status)
	}
}

func TestClient_LoadScenarioStatus(t *testing.T) {
	var method, path string
	ts := stubServer(t, 200, `{"name":"checkout-load","state":"running","currentVus":10,"requestsSent":120,"p95Millis":42.5}`, &method, &path, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	status, err := client.LoadScenarioStatus()
	if err != nil {
		t.Fatal(err)
	}
	if method != "GET" {
		t.Errorf("expected GET, got %s", method)
	}
	if path != "/mockserver/loadScenario" {
		t.Errorf("unexpected path %s", path)
	}
	if status.State != "running" || status.CurrentVus != 10 || status.RequestsSent != 120 {
		t.Errorf("unexpected status: %+v", status)
	}
	if status.P95Millis != 42.5 {
		t.Errorf("expected p95 42.5, got %v", status.P95Millis)
	}
}

func TestClient_StopLoadScenario(t *testing.T) {
	var method, path string
	ts := stubServer(t, 200, `{"status":"stopped"}`, &method, &path, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.StopLoadScenario(); err != nil {
		t.Fatal(err)
	}
	if method != "DELETE" {
		t.Errorf("expected DELETE, got %s", method)
	}
	if path != "/mockserver/loadScenario" {
		t.Errorf("unexpected path %s", path)
	}
}

// ---------------------------------------------------------------------------
// 2. Service chaos
// ---------------------------------------------------------------------------

func TestClient_SetServiceChaos(t *testing.T) {
	var method, path string
	var body []byte
	ts := stubServer(t, 200, `{}`, &method, &path, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.SetServiceChaos("payments.internal:8443", ServiceChaosProfile{
		ErrorStatus:      503,
		ErrorProbability: 0.3,
		Latency:          &ChaosLatency{TimeUnit: "MILLISECONDS", Value: 200},
	}, 60000)
	if err != nil {
		t.Fatal(err)
	}
	if method != "PUT" {
		t.Errorf("expected PUT, got %s", method)
	}
	if path != "/mockserver/serviceChaos" {
		t.Errorf("unexpected path %s", path)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["host"] != "payments.internal:8443" {
		t.Errorf("unexpected host: %v", m["host"])
	}
	if m["ttlMillis"].(float64) != 60000 {
		t.Errorf("expected ttlMillis 60000, got %v", m["ttlMillis"])
	}
	chaos := m["chaos"].(map[string]interface{})
	if chaos["errorStatus"].(float64) != 503 || chaos["errorProbability"].(float64) != 0.3 {
		t.Errorf("unexpected chaos: %v", chaos)
	}
	lat := chaos["latency"].(map[string]interface{})
	if lat["value"].(float64) != 200 || lat["timeUnit"] != "MILLISECONDS" {
		t.Errorf("unexpected latency: %v", lat)
	}
}

func TestClient_RemoveServiceChaos(t *testing.T) {
	var body []byte
	ts := stubServer(t, 200, `{}`, nil, nil, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.RemoveServiceChaos("payments.internal:8443"); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["remove"] != true {
		t.Errorf("expected remove:true, got %v", m)
	}
	if m["host"] != "payments.internal:8443" {
		t.Errorf("unexpected host: %v", m["host"])
	}
}

func TestClient_ClearServiceChaos(t *testing.T) {
	var body []byte
	ts := stubServer(t, 200, `{}`, nil, nil, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.ClearServiceChaos(); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["clear"] != true {
		t.Errorf("expected clear:true, got %v", m)
	}
}

// ---------------------------------------------------------------------------
// 3. SLO verdict
// ---------------------------------------------------------------------------

func sloCriteria() SloCriteria {
	return SloCriteria{
		Name:               "checkout-slo",
		Window:             &SloWindow{Type: "LOOKBACK", LookbackMillis: 60000},
		MinimumSampleCount: 20,
		UpstreamHosts:      []string{"payments.svc"},
		Objectives: []SloObjective{
			{Sli: "LATENCY_P95", Comparator: "LESS_THAN", Threshold: 250.0, Scope: "FORWARD"},
			{Sli: "ERROR_RATE", Comparator: "LESS_THAN_OR_EQUAL", Threshold: 0.01},
		},
	}
}

func TestClient_VerifySLO_Pass(t *testing.T) {
	var method, path string
	var body []byte
	ts := stubServer(t, 200, `{"name":"checkout-slo","result":"PASS","sampleCount":120}`, &method, &path, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	verdict, err := client.VerifySLO(sloCriteria())
	if err != nil {
		t.Fatal(err)
	}
	if method != "PUT" {
		t.Errorf("expected PUT, got %s", method)
	}
	if path != "/mockserver/verifySLO" {
		t.Errorf("unexpected path %s", path)
	}
	if verdict.Result != "PASS" || verdict.SampleCount != 120 {
		t.Errorf("unexpected verdict: %+v", verdict)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	objectives := m["objectives"].([]interface{})
	if len(objectives) != 2 {
		t.Fatalf("expected 2 objectives, got %d", len(objectives))
	}
	obj0 := objectives[0].(map[string]interface{})
	if obj0["sli"] != "LATENCY_P95" || obj0["comparator"] != "LESS_THAN" || obj0["threshold"].(float64) != 250.0 {
		t.Errorf("unexpected objective[0]: %v", obj0)
	}
	window := m["window"].(map[string]interface{})
	if window["lookbackMillis"].(float64) != 60000 {
		t.Errorf("unexpected window: %v", window)
	}
}

func TestClient_VerifySLO_Fail(t *testing.T) {
	ts := stubServer(t, 406, `{"name":"checkout-slo","result":"FAIL","objectiveResults":[{"sli":"LATENCY_P95","result":"FAIL","observedValue":420.0}]}`, nil, nil, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	verdict, err := client.VerifySLO(sloCriteria())
	if err == nil {
		t.Fatal("expected error for FAIL verdict (406)")
	}
	var ve *VerificationError
	if !errors.As(err, &ve) {
		t.Fatalf("expected VerificationError, got %T: %v", err, err)
	}
	// The verdict is still decoded so the caller can inspect objective results.
	if verdict.Result != "FAIL" {
		t.Errorf("expected decoded FAIL verdict, got %+v", verdict)
	}
	if len(verdict.ObjectiveResults) != 1 || verdict.ObjectiveResults[0].ObservedValue == nil {
		t.Errorf("expected objective results with observedValue, got %+v", verdict.ObjectiveResults)
	}
}

func TestClient_VerifySLO_Disabled(t *testing.T) {
	ts := stubServer(t, 400, `{"error":"SLO tracking is disabled"}`, nil, nil, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.VerifySLO(sloCriteria())
	if err == nil {
		t.Fatal("expected error for disabled (400)")
	}
	var fde *FeatureDisabledError
	if !errors.As(err, &fde) {
		t.Fatalf("expected FeatureDisabledError, got %T: %v", err, err)
	}
}

// ---------------------------------------------------------------------------
// 4. Preemption
// ---------------------------------------------------------------------------

func TestClient_SetPreemption(t *testing.T) {
	var method, path string
	var body []byte
	ts := stubServer(t, 200, `{"state":"draining","inFlight":3,"mode":"both"}`, &method, &path, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	lastStream := int64(3)
	err := client.SetPreemption(PreemptionRequest{
		Mode:         "both",
		DrainMillis:  10000,
		TTLMillis:    60000,
		LastStreamID: &lastStream,
	})
	if err != nil {
		t.Fatal(err)
	}
	if method != "PUT" {
		t.Errorf("expected PUT, got %s", method)
	}
	if path != "/mockserver/preemption" {
		t.Errorf("unexpected path %s", path)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["mode"] != "both" || m["drainMillis"].(float64) != 10000 || m["ttlMillis"].(float64) != 60000 {
		t.Errorf("unexpected body: %v", m)
	}
	if m["lastStreamId"].(float64) != 3 {
		t.Errorf("expected lastStreamId 3, got %v", m["lastStreamId"])
	}
}

func TestClient_PreemptionStatus(t *testing.T) {
	var method, path string
	ts := stubServer(t, 200, `{"state":"draining","inFlight":2,"drainRemainingMillis":5000,"mode":"goaway"}`, &method, &path, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	status, err := client.PreemptionStatus()
	if err != nil {
		t.Fatal(err)
	}
	if method != "GET" {
		t.Errorf("expected GET, got %s", method)
	}
	if path != "/mockserver/preemption" {
		t.Errorf("unexpected path %s", path)
	}
	if status.State != "draining" || status.InFlight != 2 || status.DrainRemainingMillis != 5000 || status.Mode != "goaway" {
		t.Errorf("unexpected status: %+v", status)
	}
}

func TestClient_ClearPreemption(t *testing.T) {
	var method, path string
	ts := stubServer(t, 200, `{"state":"inactive"}`, &method, &path, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.ClearPreemption(); err != nil {
		t.Fatal(err)
	}
	if method != "DELETE" {
		t.Errorf("expected DELETE, got %s", method)
	}
	if path != "/mockserver/preemption" {
		t.Errorf("unexpected path %s", path)
	}
}

// ---------------------------------------------------------------------------
// 5. Chaos experiment
// ---------------------------------------------------------------------------

func TestClient_StartChaosExperiment(t *testing.T) {
	var method, path string
	var body []byte
	ts := stubServer(t, 200, `{"status":"started","name":"gradual-degradation"}`, &method, &path, &body)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.StartChaosExperiment(ChaosExperiment{
		Name: "gradual-degradation",
		Loop: false,
		Stages: []ChaosExperimentStage{
			{
				DurationMillis: 10000,
				Profiles: map[string]ServiceChaosProfile{
					"api.example.com": {ErrorStatus: 500, ErrorProbability: 0.1},
				},
			},
			{
				DurationMillis: 10000,
				Profiles: map[string]ServiceChaosProfile{
					"api.example.com": {ErrorStatus: 500, ErrorProbability: 0.5},
				},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if method != "PUT" {
		t.Errorf("expected PUT, got %s", method)
	}
	if path != "/mockserver/chaosExperiment" {
		t.Errorf("unexpected path %s", path)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["name"] != "gradual-degradation" {
		t.Errorf("unexpected name: %v", m["name"])
	}
	stages := m["stages"].([]interface{})
	if len(stages) != 2 {
		t.Fatalf("expected 2 stages, got %d", len(stages))
	}
	stage0 := stages[0].(map[string]interface{})
	if stage0["durationMillis"].(float64) != 10000 {
		t.Errorf("unexpected durationMillis: %v", stage0["durationMillis"])
	}
	profiles := stage0["profiles"].(map[string]interface{})
	api := profiles["api.example.com"].(map[string]interface{})
	if api["errorStatus"].(float64) != 500 || api["errorProbability"].(float64) != 0.1 {
		t.Errorf("unexpected profile: %v", api)
	}
}

func TestClient_StartChaosExperiment_Invalid(t *testing.T) {
	ts := stubServer(t, 400, `{"error":"empty stages"}`, nil, nil, nil)
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.StartChaosExperiment(ChaosExperiment{Name: "x"})
	if err == nil {
		t.Fatal("expected error for 400 status")
	}
}
