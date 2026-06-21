using System.Net;
using System.Net.Http;
using System.Text;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the SRE control-plane methods (load scenarios, service chaos, SLO verdicts,
/// preemption and chaos experiments). Uses a fake HttpMessageHandler so no real server is needed,
/// asserting the HTTP method, path and serialized body of each request.
/// </summary>
public class SreControlPlaneTests
{
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastRequestBody { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            LastRequestBody = request.Content != null
                ? await request.Content.ReadAsStringAsync(cancellationToken)
                : null;

            return new HttpResponseMessage(ResponseStatusCode)
            {
                Content = new StringContent(ResponseBody, Encoding.UTF8, "application/json")
            };
        }
    }

    private static (MockServerClient Client, FakeHandler Handler) CreateClient()
    {
        var handler = new FakeHandler();
        var httpClient = new HttpClient(handler);
        var client = new MockServerClient("http://localhost:1080", httpClient);
        return (client, handler);
    }

    // -------------------------------------------------------------------
    // Load scenarios
    // -------------------------------------------------------------------

    [Fact]
    public void LoadScenario_SendsPutWithCamelCaseBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"status\":\"started\",\"name\":\"checkout-load\",\"steps\":1}";

        var scenario = new LoadScenario
        {
            Name = "checkout-load",
            TemplateType = LoadTemplateType.VELOCITY,
            MaxRequests = 5000,
            Labels = new Dictionary<string, string> { ["team"] = "checkout" },
            Profile = new LoadProfile
            {
                Stages = new List<LoadStage>
                {
                    LoadStage.RampVus(0, 10, 30000, RampCurve.LINEAR),
                    LoadStage.ConstantVus(10, 60000),
                    LoadStage.Pause(5000)
                }
            },
            Steps = new List<LoadStep>
            {
                new()
                {
                    Name = "get-item",
                    Request = HttpRequest.Request().WithMethod("GET").WithPath("/api/item/1").Build(),
                    ThinkTime = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 20 }
                }
            }
        };

        client.LoadScenario(scenario);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-load\"");
        handler.LastRequestBody.Should().Contain("\"templateType\":\"VELOCITY\"");
        handler.LastRequestBody.Should().Contain("\"maxRequests\":5000");
        handler.LastRequestBody.Should().Contain("\"profile\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"VU\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"PAUSE\"");
        handler.LastRequestBody.Should().Contain("\"curve\":\"LINEAR\"");
        // A meaningful zero (startVus=0) must still be serialised.
        handler.LastRequestBody.Should().Contain("\"startVus\":0");
        handler.LastRequestBody.Should().Contain("\"endVus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":30000");
        handler.LastRequestBody.Should().Contain("\"vus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":5000");
        // Old v1 scalar profile fields must be gone.
        handler.LastRequestBody.Should().NotContain("iterationPacingMillis");
        handler.LastRequestBody.Should().Contain("\"steps\"");
        handler.LastRequestBody.Should().Contain("\"thinkTime\"");
    }

    [Fact]
    public void LoadScenario_Throws403WhenDisabled()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Forbidden;
        handler.ResponseBody = "{\"error\":\"load generation not enabled\"}";

        var scenario = new LoadScenario { Name = "x", Profile = new LoadProfile(), Steps = new List<LoadStep>() };

        var act = () => client.LoadScenario(scenario);
        act.Should().Throw<MockServerClientException>().WithMessage("*loadGenerationEnabled*");
    }

    [Fact]
    public void LoadScenarioStatus_SendsGetAndParsesResponse()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"checkout-load\",\"state\":\"running\",\"requestsSent\":42,\"p95Millis\":12.5}";

        var status = client.LoadScenarioStatus();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        status.Name.Should().Be("checkout-load");
        status.State.Should().Be("running");
        status.RequestsSent.Should().Be(42);
        status.P95Millis.Should().Be(12.5);
    }

    [Fact]
    public void StopLoadScenario_SendsDelete()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"status\":\"stopped\"}";

        client.StopLoadScenario();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
    }

    // -------------------------------------------------------------------
    // Service chaos
    // -------------------------------------------------------------------

    [Fact]
    public void SetServiceChaos_SendsPutWithHostChaosAndTtl()
    {
        var (client, handler) = CreateClient();

        var profile = new ServiceChaosProfile
        {
            ErrorStatus = 503,
            ErrorProbability = 0.3,
            Latency = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 200 }
        };

        client.SetServiceChaos("payments.internal:8443", profile, 60000);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"payments.internal:8443\"");
        handler.LastRequestBody.Should().Contain("\"chaos\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":503");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.3");
        handler.LastRequestBody.Should().Contain("\"latency\"");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
    }

    [Fact]
    public void SetServiceChaos_OmitsTtlWhenNull()
    {
        var (client, handler) = CreateClient();

        client.SetServiceChaos("api.svc", new ServiceChaosProfile { ErrorProbability = 0.5 });

        handler.LastRequestBody.Should().NotContain("ttlMillis");
    }

    [Fact]
    public void RemoveServiceChaos_SendsRemoveFlag()
    {
        var (client, handler) = CreateClient();

        client.RemoveServiceChaos("api.svc");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"api.svc\"");
        handler.LastRequestBody.Should().Contain("\"remove\":true");
    }

    [Fact]
    public void ClearServiceChaos_SendsClearFlag()
    {
        var (client, handler) = CreateClient();

        client.ClearServiceChaos();

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"clear\":true");
    }

    // -------------------------------------------------------------------
    // SLO verdicts
    // -------------------------------------------------------------------

    [Fact]
    public void VerifySlo_SendsPutAndParsesPassVerdict()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"PASS\",\"sampleCount\":50}";

        var criteria = new SloCriteria
        {
            Name = "checkout-slo",
            Window = SloWindow.Lookback(60000),
            MinimumSampleCount = 20,
            UpstreamHosts = new List<string> { "payments.svc" },
            Objectives = new List<SloObjective>
            {
                new() { Sli = Sli.LATENCY_P95, Comparator = SloComparator.LESS_THAN, Threshold = 250.0, Scope = SloScope.FORWARD }
            }
        };

        var verdict = client.VerifySlo(criteria);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/verifySLO");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-slo\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"LOOKBACK\"");
        handler.LastRequestBody.Should().Contain("\"lookbackMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"sli\":\"LATENCY_P95\"");
        handler.LastRequestBody.Should().Contain("\"comparator\":\"LESS_THAN\"");
        handler.LastRequestBody.Should().Contain("\"threshold\":250");
        handler.LastRequestBody.Should().Contain("\"upstreamHosts\"");

        verdict.Result.Should().Be(SloResult.PASS);
        verdict.Name.Should().Be("checkout-slo");
        verdict.SampleCount.Should().Be(50);
    }

    [Fact]
    public void VerifySlo_ReturnsVerdictOnFail406()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotAcceptable; // 406 = FAIL
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"FAIL\"}";

        var verdict = client.VerifySlo(new SloCriteria
        {
            Objectives = new List<SloObjective> { new() { Sli = Sli.ERROR_RATE, Comparator = SloComparator.LESS_THAN, Threshold = 0.01 } }
        });

        verdict.Result.Should().Be(SloResult.FAIL);
    }

    [Fact]
    public void VerifySlo_ThrowsOnDisabled400()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"SLO tracking disabled\"}";

        var act = () => client.VerifySlo(new SloCriteria { Objectives = new List<SloObjective>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*sloTrackingEnabled*");
    }

    // -------------------------------------------------------------------
    // Preemption
    // -------------------------------------------------------------------

    [Fact]
    public void SetPreemption_SendsPutWithBodyAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\",\"inFlight\":3,\"drainRemainingMillis\":9000,\"mode\":\"both\"}";

        var status = client.SetPreemption(new PreemptionRequest
        {
            Mode = PreemptionMode.both,
            DrainMillis = 10000,
            TtlMillis = 60000,
            LastStreamId = 3
        });

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().Contain("\"mode\":\"both\"");
        handler.LastRequestBody.Should().Contain("\"drainMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"lastStreamId\":3");

        status.State.Should().Be("draining");
        status.InFlight.Should().Be(3);
        status.DrainRemainingMillis.Should().Be(9000);
        status.Mode.Should().Be("both");
    }

    [Fact]
    public void SetPreemption_SendsEmptyBodyWhenNullRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\"}";

        client.SetPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().BeEmpty();
    }

    [Fact]
    public void PreemptionStatus_SendsGetAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\",\"inFlight\":0}";

        var status = client.PreemptionStatus();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        status.State.Should().Be("inactive");
        status.InFlight.Should().Be(0);
    }

    [Fact]
    public void ClearPreemption_SendsDelete()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\"}";

        client.ClearPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
    }

    // -------------------------------------------------------------------
    // Chaos experiment
    // -------------------------------------------------------------------

    [Fact]
    public void StartChaosExperiment_SendsPutWithStages()
    {
        var (client, handler) = CreateClient();

        var experiment = new ChaosExperiment
        {
            Name = "gradual-degradation",
            Loop = false,
            Stages = new List<ChaosExperimentStage>
            {
                new()
                {
                    DurationMillis = 10000,
                    Profiles = new Dictionary<string, ServiceChaosProfile>
                    {
                        ["api.example.com"] = new() { ErrorStatus = 500, ErrorProbability = 0.1 }
                    }
                }
            }
        };

        client.StartChaosExperiment(experiment);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/chaosExperiment");
        handler.LastRequestBody.Should().Contain("\"name\":\"gradual-degradation\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"profiles\"");
        handler.LastRequestBody.Should().Contain("\"api.example.com\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":500");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.1");
    }

    [Fact]
    public void StartChaosExperiment_ThrowsOnBadRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"too many stages\"}";

        var act = () => client.StartChaosExperiment(new ChaosExperiment { Stages = new List<ChaosExperimentStage>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid chaos experiment*");
    }
}
