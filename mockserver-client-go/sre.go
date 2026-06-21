package mockserver

import (
	"encoding/json"
	"fmt"
)

// This file adds the SRE control-plane operations: load scenarios (load
// generation / SLI production), service-scoped HTTP chaos, SLO verdicts,
// preemption (cordon/drain) simulation, and scheduled multi-stage chaos
// experiments. Each operation mirrors the MockServer OpenAPI contract
// (jekyll-www.mock-server.com/mockserver-openapi.yaml).
//
// Several of these features are off by default on the server and return an
// HTTP 403 (or 400 for SLO) until the corresponding configuration flag is
// enabled. Those statuses are surfaced as clear, dedicated errors so callers
// can distinguish "feature disabled" from a malformed request.

// FeatureDisabledError indicates the requested SRE feature is disabled on the
// server (e.g. loadGenerationEnabled=false or sloTrackingEnabled=false). It is
// returned when MockServer responds with the status code it uses to signal a
// disabled feature for that endpoint.
type FeatureDisabledError struct {
	Feature string
	Status  int
	Body    string
}

func (e *FeatureDisabledError) Error() string {
	return fmt.Sprintf("mockserver: %s is disabled on the server (status %d): %s", e.Feature, e.Status, e.Body)
}

// -----------------------------------------------------------------------------
// 1. Load scenario  (PUT/GET/DELETE /mockserver/loadScenario)
//    OpenAPI: paths ./mockserver/loadScenario (lines 2666-2835),
//    schemas LoadScenario (4427), LoadProfile (4375), LoadStep (4402).
// -----------------------------------------------------------------------------

// LoadProfile is the ramp profile describing target concurrency over time.
// Wire keys match schema LoadProfile (OpenAPI line 4375).
type LoadProfile struct {
	// Type is the ramp shape: "CONSTANT" (hold Vus for the whole duration) or
	// "LINEAR" (ramp from StartVus to EndVus). Defaults to CONSTANT on the server.
	Type string `json:"type,omitempty"`
	// Vus is the number of virtual users to hold for a CONSTANT profile.
	Vus int `json:"vus,omitempty"`
	// StartVus is the number of virtual users at the start of a LINEAR ramp.
	StartVus int `json:"startVus,omitempty"`
	// EndVus is the number of virtual users at the end of a LINEAR ramp.
	EndVus int `json:"endVus,omitempty"`
	// DurationMillis is how long the scenario runs in milliseconds.
	DurationMillis int64 `json:"durationMillis,omitempty"`
	// IterationPacingMillis is an optional minimum delay between successive
	// iterations of a virtual user.
	IterationPacingMillis int64 `json:"iterationPacingMillis,omitempty"`
}

// LoadStep is a single templated request step in a load scenario.
// Wire keys match schema LoadStep (OpenAPI line 4402). The step Request reuses
// the client's existing HttpRequest type (the schema $refs HttpRequest).
type LoadStep struct {
	// Request is the templated request fired for this step (required).
	Request *HttpRequest `json:"request,omitempty"`
	// ThinkTime is an optional inter-step pause (a Delay).
	ThinkTime *Delay `json:"thinkTime,omitempty"`
	// Name is an optional human label for the step (used as the metric label).
	Name string `json:"name,omitempty"`
	// Labels are optional step-level annotation labels, merged over the
	// scenario labels (step keys win).
	Labels map[string]string `json:"labels,omitempty"`
}

// LoadScenario is an API-driven load scenario: ordered templated steps driven
// at a target concurrency. Wire keys match schema LoadScenario (OpenAPI line
// 4427). Name, Profile and Steps are required by the server.
type LoadScenario struct {
	// Name is the human-readable scenario name (required).
	Name string `json:"name,omitempty"`
	// TemplateType is the template engine: "VELOCITY" (default) or "MUSTACHE".
	TemplateType string `json:"templateType,omitempty"`
	// MaxRequests is an optional hard cap on total requests dispatched.
	MaxRequests int `json:"maxRequests,omitempty"`
	// Labels are optional scenario-level annotation labels.
	Labels map[string]string `json:"labels,omitempty"`
	// Profile is the ramp profile (required).
	Profile *LoadProfile `json:"profile,omitempty"`
	// Steps is the ordered list of request steps fired each iteration (required).
	Steps []LoadStep `json:"steps,omitempty"`
}

// LoadScenarioStatusResult is the status of the current (or most recent) load
// scenario. Wire keys match the GET /mockserver/loadScenario response (OpenAPI
// line 2754).
type LoadScenarioStatusResult struct {
	Name          string        `json:"name,omitempty"`
	State         string        `json:"state,omitempty"`
	ElapsedMillis int64         `json:"elapsedMillis,omitempty"`
	CurrentVus    int           `json:"currentVus,omitempty"`
	RequestsSent  int64         `json:"requestsSent,omitempty"`
	Succeeded     int64         `json:"succeeded,omitempty"`
	Failed        int64         `json:"failed,omitempty"`
	P50Millis     float64       `json:"p50Millis,omitempty"`
	P95Millis     float64       `json:"p95Millis,omitempty"`
	P99Millis     float64       `json:"p99Millis,omitempty"`
	RunID         string        `json:"runId,omitempty"`
	StartedAt     int64         `json:"startedAt,omitempty"`
	EndedAt       int64         `json:"endedAt,omitempty"`
	Definition    *LoadScenario `json:"definition,omitempty"`
}

// SetLoadScenario starts a load scenario (PUT /mockserver/loadScenario).
// Returns a FeatureDisabledError if load generation is disabled on the server
// (HTTP 403, loadGenerationEnabled=false).
func (c *Client) SetLoadScenario(scenario LoadScenario) error {
	body, err := json.Marshal(scenario)
	if err != nil {
		return fmt.Errorf("mockserver: marshal load scenario: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/loadScenario", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "load generation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: start load scenario failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// LoadScenarioStatus retrieves the status of the current (or most recent) load
// scenario (GET /mockserver/loadScenario).
func (c *Client) LoadScenarioStatus() (*LoadScenarioStatusResult, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/loadScenario", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode == 403 {
		return nil, &FeatureDisabledError{Feature: "load generation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: load scenario status failed (status %d): %s", statusCode, string(respBody))
	}
	var result LoadScenarioStatusResult
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &result); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal load scenario status: %w", err)
		}
	}
	return &result, nil
}

// StopLoadScenario stops the current load scenario (DELETE
// /mockserver/loadScenario). Idempotent — returns nil whether or not a scenario
// was running.
func (c *Client) StopLoadScenario() error {
	respBody, statusCode, err := c.doRequest("DELETE", "/mockserver/loadScenario", nil, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "load generation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: stop load scenario failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// -----------------------------------------------------------------------------
// 2. Service chaos  (PUT /mockserver/serviceChaos)
//    OpenAPI: path ./mockserver/serviceChaos (lines 644-684),
//    schemas ServiceChaosRequest (4290), HttpChaosProfile (4312).
// -----------------------------------------------------------------------------

// ChaosLatency models the HttpChaosProfile.latency object (OpenAPI line 4323):
// an injected latency expressed as a value plus a time unit.
type ChaosLatency struct {
	Value    int    `json:"value,omitempty"`
	TimeUnit string `json:"timeUnit,omitempty"`
}

// ServiceChaosProfile is the HTTP chaos / fault-injection profile registered
// for a downstream host. Wire keys match schema HttpChaosProfile (OpenAPI line
// 4312).
type ServiceChaosProfile struct {
	// ErrorStatus is the HTTP status to return instead of the real response.
	ErrorStatus int `json:"errorStatus,omitempty"`
	// ErrorProbability is the probability (0.0-1.0) a request triggers the error.
	ErrorProbability float64 `json:"errorProbability,omitempty"`
	// Latency is the injected latency, if any.
	Latency *ChaosLatency `json:"latency,omitempty"`
	// ConnectionDrop, when true, drops the TCP connection without responding.
	ConnectionDrop *bool `json:"connectionDrop,omitempty"`
	// Seed is a fixed seed for deterministic probabilistic outcomes.
	Seed *int `json:"seed,omitempty"`
}

// serviceChaosRequest is the wire body for PUT /mockserver/serviceChaos. Wire
// keys match schema ServiceChaosRequest (OpenAPI line 4290).
type serviceChaosRequest struct {
	Host      string               `json:"host,omitempty"`
	Chaos     *ServiceChaosProfile `json:"chaos,omitempty"`
	Remove    bool                 `json:"remove,omitempty"`
	Clear     bool                 `json:"clear,omitempty"`
	TTLMillis int64                `json:"ttlMillis,omitempty"`
}

// SetServiceChaos registers an HTTP chaos profile for a downstream host
// (PUT /mockserver/serviceChaos). ttlMillis is an optional auto-revert window;
// pass 0 to omit it.
func (c *Client) SetServiceChaos(host string, profile ServiceChaosProfile, ttlMillis int64) error {
	reqBody := serviceChaosRequest{
		Host:      host,
		Chaos:     &profile,
		TTLMillis: ttlMillis,
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "service chaos", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: set service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// RemoveServiceChaos removes the chaos profile registered for a single host
// (PUT /mockserver/serviceChaos with remove:true).
func (c *Client) RemoveServiceChaos(host string) error {
	reqBody := serviceChaosRequest{Host: host, Remove: true}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos removal: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: remove service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// ClearServiceChaos clears all service-scoped chaos profiles
// (PUT /mockserver/serviceChaos with clear:true).
func (c *Client) ClearServiceChaos() error {
	reqBody := serviceChaosRequest{Clear: true}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos clear: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// -----------------------------------------------------------------------------
// 3. SLO verdict  (PUT /mockserver/verifySLO)
//    OpenAPI: path ./mockserver/verifySLO (lines 2836-2891),
//    schemas SloCriteria (4484), SloObjective (4459), SloVerdict (4548),
//    SloObjectiveResult (4527).
// -----------------------------------------------------------------------------

// SloObjective is a single service-level objective over the recorded SLI
// samples. Wire keys match schema SloObjective (OpenAPI line 4459).
type SloObjective struct {
	// Sli is the indicator: LATENCY_P50, LATENCY_P95, LATENCY_P99 or ERROR_RATE.
	Sli string `json:"sli,omitempty"`
	// Comparator: LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL.
	Comparator string `json:"comparator,omitempty"`
	// Threshold is the objective threshold (ms for latency, 0.0-1.0 for ERROR_RATE).
	Threshold float64 `json:"threshold,omitempty"`
	// Scope: FORWARD (default) or INBOUND.
	Scope string `json:"scope,omitempty"`
}

// SloWindow is the time window an SloCriteria is evaluated over. Wire keys
// match the inline window object of schema SloCriteria (OpenAPI line 4494).
type SloWindow struct {
	// Type: LOOKBACK (default) or EXPLICIT.
	Type string `json:"type,omitempty"`
	// LookbackMillis is the LOOKBACK window length ending now.
	LookbackMillis int64 `json:"lookbackMillis,omitempty"`
	// FromEpochMillis is the EXPLICIT window start in epoch milliseconds.
	FromEpochMillis int64 `json:"fromEpochMillis,omitempty"`
	// ToEpochMillis is the EXPLICIT window end in epoch milliseconds.
	ToEpochMillis int64 `json:"toEpochMillis,omitempty"`
}

// SloCriteria is a named set of service-level objectives over a time window.
// Wire keys match schema SloCriteria (OpenAPI line 4484). Objectives is required.
type SloCriteria struct {
	Name               string         `json:"name,omitempty"`
	Window             *SloWindow     `json:"window,omitempty"`
	MinimumSampleCount int            `json:"minimumSampleCount,omitempty"`
	UpstreamHosts      []string       `json:"upstreamHosts,omitempty"`
	Objectives         []SloObjective `json:"objectives,omitempty"`
}

// SloObjectiveResult is the evaluated result of a single objective. Wire keys
// match schema SloObjectiveResult (OpenAPI line 4527).
type SloObjectiveResult struct {
	Sli           string   `json:"sli,omitempty"`
	Comparator    string   `json:"comparator,omitempty"`
	Threshold     float64  `json:"threshold,omitempty"`
	ObservedValue *float64 `json:"observedValue,omitempty"`
	Result        string   `json:"result,omitempty"`
	Detail        string   `json:"detail,omitempty"`
}

// SloVerdict is the overall verdict of an SLO evaluation (the AND of all
// objective results). Wire keys match schema SloVerdict (OpenAPI line 4548).
// Result is one of PASS, FAIL, INCONCLUSIVE.
type SloVerdict struct {
	Name                  string               `json:"name,omitempty"`
	Result                string               `json:"result,omitempty"`
	WindowFromEpochMillis int64                `json:"windowFromEpochMillis,omitempty"`
	WindowToEpochMillis   int64                `json:"windowToEpochMillis,omitempty"`
	SampleCount           int                  `json:"sampleCount,omitempty"`
	ObjectiveResults      []SloObjectiveResult `json:"objectiveResults,omitempty"`
}

// VerifySLO evaluates a set of service-level objectives over a window
// (PUT /mockserver/verifySLO). The server encodes the verdict in the HTTP
// status: 200 for PASS or INCONCLUSIVE, 406 for FAIL, 400 for a malformed
// criteria or when SLO tracking is disabled (sloTrackingEnabled=false).
//
// The decoded SloVerdict is returned for both PASS/INCONCLUSIVE (200) and FAIL
// (406) so callers can inspect the per-objective results; the returned error is
// non-nil only on FAIL or a real error. A 400 disabled response is surfaced as
// a FeatureDisabledError.
func (c *Client) VerifySLO(criteria SloCriteria) (SloVerdict, error) {
	var verdict SloVerdict

	body, err := json.Marshal(criteria)
	if err != nil {
		return verdict, fmt.Errorf("mockserver: marshal slo criteria: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/verifySLO", body, nil)
	if err != nil {
		return verdict, err
	}

	if statusCode == 400 {
		return verdict, &FeatureDisabledError{Feature: "SLO tracking", Status: statusCode, Body: string(respBody)}
	}
	if statusCode != 200 && statusCode != 406 {
		return verdict, fmt.Errorf("mockserver: verify SLO failed (status %d): %s", statusCode, string(respBody))
	}

	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &verdict); err != nil {
			return verdict, fmt.Errorf("mockserver: unmarshal slo verdict: %w", err)
		}
	}

	if statusCode == 406 {
		return verdict, &VerificationError{Message: fmt.Sprintf("SLO verdict FAIL: %s", string(respBody))}
	}
	return verdict, nil
}

// -----------------------------------------------------------------------------
// 4. Preemption  (PUT/GET/DELETE /mockserver/preemption)
//    OpenAPI: path ./mockserver/preemption (lines 2893-2964),
//    schemas PreemptionRequest (4570), PreemptionStatus (4592).
// -----------------------------------------------------------------------------

// PreemptionRequest holds preemption simulation parameters (all optional).
// Wire keys match schema PreemptionRequest (OpenAPI line 4570).
type PreemptionRequest struct {
	// Mode: reject503, goaway or both (default both).
	Mode string `json:"mode,omitempty"`
	// DrainMillis is how long in-flight requests are allowed to drain.
	DrainMillis int64 `json:"drainMillis,omitempty"`
	// TTLMillis auto-uncordons after this many milliseconds (0 = no auto-uncordon).
	TTLMillis int64 `json:"ttlMillis,omitempty"`
	// LastStreamID is the HTTP/2 GOAWAY last_stream_id to advertise (-1 = server chooses).
	LastStreamID *int64 `json:"lastStreamId,omitempty"`
}

// PreemptionStatus is the current cordon/drain status of the server. Wire keys
// match schema PreemptionStatus (OpenAPI line 4592).
type PreemptionStatus struct {
	// State: inactive, draining or drained.
	State string `json:"state,omitempty"`
	// InFlight is the number of requests currently in flight.
	InFlight int `json:"inFlight,omitempty"`
	// DrainRemainingMillis is the milliseconds left in the drain window.
	DrainRemainingMillis int64 `json:"drainRemainingMillis,omitempty"`
	// Mode is the active signalling mode (omitted when inactive).
	Mode string `json:"mode,omitempty"`
}

// SetPreemption cordons and drains the server, simulating a preemption
// (PUT /mockserver/preemption). The returned status reflects the started (or
// replaced) simulation.
func (c *Client) SetPreemption(req PreemptionRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("mockserver: marshal preemption request: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/preemption", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: set preemption failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// PreemptionStatus retrieves the current cordon/drain status
// (GET /mockserver/preemption).
func (c *Client) PreemptionStatus() (*PreemptionStatus, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/preemption", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode == 403 {
		return nil, &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: preemption status failed (status %d): %s", statusCode, string(respBody))
	}
	var status PreemptionStatus
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &status); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal preemption status: %w", err)
		}
	}
	return &status, nil
}

// ClearPreemption uncordons the server, clearing any active preemption
// simulation (DELETE /mockserver/preemption). Idempotent.
func (c *Client) ClearPreemption() error {
	respBody, statusCode, err := c.doRequest("DELETE", "/mockserver/preemption", nil, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear preemption failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// -----------------------------------------------------------------------------
// 5. Chaos experiment  (PUT /mockserver/chaosExperiment)
//    OpenAPI: path ./mockserver/chaosExperiment (lines 2130-2208),
//    schema ChaosExperiment (4611), HttpChaosProfile (4312).
// -----------------------------------------------------------------------------

// ChaosExperimentStage is a single stage of a chaos experiment: a set of
// host -> chaos profile mappings applied for a fixed duration. Wire keys match
// the inline stage object of schema ChaosExperiment (OpenAPI line 4624).
type ChaosExperimentStage struct {
	// DurationMillis is how long the stage runs before advancing.
	DurationMillis int64 `json:"durationMillis,omitempty"`
	// Profiles maps a downstream host to the chaos profile applied during the stage.
	Profiles map[string]ServiceChaosProfile `json:"profiles,omitempty"`
}

// ChaosExperiment is a scheduled multi-stage chaos experiment definition. Wire
// keys match schema ChaosExperiment (OpenAPI line 4611). Stages is required.
type ChaosExperiment struct {
	// Name is the human-readable experiment name.
	Name string `json:"name,omitempty"`
	// Loop, when true, loops back to stage 0 after the last stage completes.
	Loop bool `json:"loop,omitempty"`
	// Stages is the ordered sequence of stages (required).
	Stages []ChaosExperimentStage `json:"stages,omitempty"`
}

// StartChaosExperiment starts a scheduled multi-stage chaos experiment
// (PUT /mockserver/chaosExperiment). Only one experiment may be active at a
// time; starting a new one stops the previous one.
func (c *Client) StartChaosExperiment(experiment ChaosExperiment) error {
	body, err := json.Marshal(experiment)
	if err != nil {
		return fmt.Errorf("mockserver: marshal chaos experiment: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/chaosExperiment", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "chaos experiment", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: start chaos experiment failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}
