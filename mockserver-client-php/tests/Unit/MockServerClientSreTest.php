<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Delay;
use MockServer\Exception\FeatureNotEnabledException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\VerificationException;
use MockServer\HttpRequest;
use MockServer\LoadProfile;
use MockServer\LoadScenario;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the SRE control-plane methods: load scenarios, service chaos,
 * SLO verdicts, preemption and chaos experiments.
 */
class MockServerClientSreTest extends TestCase
{
    /**
     * @param array<Response> $responses
     * @param array<array> &$history
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $mock = new MockHandler($responses);
        $handlerStack = HandlerStack::create($mock);
        $handlerStack->push(Middleware::history($history));

        $client = new MockServerClient('localhost', 1080);

        $reflection = new \ReflectionClass($client);
        $prop = $reflection->getProperty('httpClient');
        $prop->setAccessible(true);
        $prop->setValue($client, new GuzzleClient([
            'handler' => $handlerStack,
            'http_errors' => false,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
        ]));

        return $client;
    }

    // -----------------------------------------------------------------
    // Load scenario
    // -----------------------------------------------------------------

    public function testLoadScenarioSendsCorrectJsonFromValueObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['status' => 'started', 'name' => 'checkout-load', 'steps' => 1])),
        ], $history);

        $scenario = LoadScenario::scenario('checkout-load')
            ->templateType('velocity')
            ->maxRequests(5000)
            ->labels(['team' => 'checkout'])
            ->profile(LoadProfile::constant(10, 30000)->iterationPacingMillis(50))
            ->addStep(
                HttpRequest::request()->method('GET')->path('/api/item/$iteration.index'),
                Delay::milliseconds(20),
                'fetch-item',
                ['route' => 'item'],
            );

        $result = $client->loadScenario($scenario);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-load', $body['name']);
        $this->assertSame('VELOCITY', $body['templateType']);
        $this->assertSame(5000, $body['maxRequests']);
        $this->assertSame(['team' => 'checkout'], $body['labels']);

        // profile
        $this->assertSame('CONSTANT', $body['profile']['type']);
        $this->assertSame(10, $body['profile']['vus']);
        $this->assertSame(30000, $body['profile']['durationMillis']);
        $this->assertSame(50, $body['profile']['iterationPacingMillis']);

        // steps
        $this->assertCount(1, $body['steps']);
        $step = $body['steps'][0];
        $this->assertSame('GET', $step['request']['method']);
        $this->assertSame('/api/item/$iteration.index', $step['request']['path']);
        $this->assertSame('MILLISECONDS', $step['thinkTime']['timeUnit']);
        $this->assertSame(20, $step['thinkTime']['value']);
        $this->assertSame('fetch-item', $step['name']);
        $this->assertSame(['route' => 'item'], $step['labels']);

        $this->assertSame('started', $result['status']);
        $this->assertSame(1, $result['steps']);
    }

    public function testLinearLoadProfileShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('ramp')
                ->profile(LoadProfile::linear(1, 20, 60000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('LINEAR', $body['profile']['type']);
        $this->assertSame(1, $body['profile']['startVus']);
        $this->assertSame(20, $body['profile']['endVus']);
        $this->assertSame(60000, $body['profile']['durationMillis']);
        $this->assertArrayNotHasKey('vus', $body['profile']);
    }

    public function testLoadScenarioAcceptsPlainArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario([
            'name' => 'raw',
            'profile' => ['type' => 'CONSTANT', 'vus' => 2, 'durationMillis' => 1000],
            'steps' => [['request' => ['method' => 'GET', 'path' => '/raw']]],
        ]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('raw', $body['name']);
        $this->assertSame('/raw', $body['steps'][0]['request']['path']);
    }

    public function testLoadScenarioThrowsFeatureNotEnabledOn403(): void
    {
        $client = $this->createClientWithMock([
            new Response(403, [], 'load generation disabled'),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $this->expectExceptionMessage('loadGenerationEnabled=true');

        $client->loadScenario(LoadScenario::scenario('x')
            ->profile(LoadProfile::constant(1, 1000))
            ->addStep(HttpRequest::request()->path('/x')));
    }

    public function testLoadScenarioStatusUsesGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'running', 'currentVus' => 10])),
        ], $history);

        $result = $client->loadScenarioStatus();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertSame('running', $result['state']);
        $this->assertSame(10, $result['currentVus']);
    }

    public function testStopLoadScenarioUsesDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['status' => 'stopped'])),
        ], $history);

        $result = $client->stopLoadScenario();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertSame('stopped', $result['status']);
    }

    public function testLoadScenarioStatusThrowsFeatureNotEnabledOn403(): void
    {
        $client = $this->createClientWithMock([
            new Response(403, [], 'disabled'),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $client->loadScenarioStatus();
    }

    // -----------------------------------------------------------------
    // Service chaos
    // -----------------------------------------------------------------

    public function testSetServiceChaosSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos(
            'payments.internal:8443',
            [
                'errorStatus' => 503,
                'errorProbability' => 0.3,
                'latency' => ['timeUnit' => 'MILLISECONDS', 'value' => 200],
            ],
            60000,
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/serviceChaos', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('payments.internal:8443', $body['host']);
        $this->assertSame(503, $body['chaos']['errorStatus']);
        $this->assertSame(0.3, $body['chaos']['errorProbability']);
        $this->assertSame('MILLISECONDS', $body['chaos']['latency']['timeUnit']);
        $this->assertSame(200, $body['chaos']['latency']['value']);
        $this->assertSame(60000, $body['ttlMillis']);
    }

    public function testSetServiceChaosOmitsTtlWhenNull(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos('api.local', ['errorStatus' => 500]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('api.local', $body['host']);
        $this->assertArrayNotHasKey('ttlMillis', $body);
    }

    public function testSetServiceChaosThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'invalid chaos profile'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->setServiceChaos('api.local', ['errorStatus' => 9999]);
    }

    // -----------------------------------------------------------------
    // SLO verdicts
    // -----------------------------------------------------------------

    public function testVerifySloSendsCorrectPayloadAndReturnsVerdict(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'checkout-slo', 'result' => 'PASS'])),
        ], $history);

        $verdict = $client->verifySlo([
            'name' => 'checkout-slo',
            'window' => ['type' => 'LOOKBACK', 'lookbackMillis' => 60000],
            'minimumSampleCount' => 20,
            'upstreamHosts' => ['payments.svc'],
            'objectives' => [
                ['sli' => 'LATENCY_P95', 'comparator' => 'LESS_THAN', 'threshold' => 250.0, 'scope' => 'FORWARD'],
                ['sli' => 'ERROR_RATE', 'comparator' => 'LESS_THAN_OR_EQUAL', 'threshold' => 0.01],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/verifySLO', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-slo', $body['name']);
        $this->assertSame('LATENCY_P95', $body['objectives'][0]['sli']);
        // PHP's json_encode renders 250.0 as "250", which json_decodes back to int 250; the server
        // accepts either, so assert numeric equality rather than the exact int/float type.
        $this->assertEquals(250.0, $body['objectives'][0]['threshold']);

        $this->assertSame('PASS', $verdict['result']);
    }

    public function testVerifySloThrowsVerificationExceptionOn406Fail(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], json_encode(['result' => 'FAIL'])),
        ]);

        $this->expectException(VerificationException::class);
        $client->verifySlo(['objectives' => []]);
    }

    public function testVerifySloThrowsInvalidRequestOn400Disabled(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'SLO tracking disabled'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('sloTrackingEnabled=true');

        $client->verifySlo(['objectives' => []]);
    }

    // -----------------------------------------------------------------
    // Preemption
    // -----------------------------------------------------------------

    public function testSetPreemptionSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining', 'inFlight' => 2])),
        ], $history);

        $result = $client->setPreemption([
            'mode' => 'both',
            'drainMillis' => 10000,
            'ttlMillis' => 60000,
            'lastStreamId' => 3,
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('both', $body['mode']);
        $this->assertSame(10000, $body['drainMillis']);
        $this->assertSame(60000, $body['ttlMillis']);
        $this->assertSame(3, $body['lastStreamId']);

        $this->assertSame('draining', $result['state']);
    }

    public function testSetPreemptionEmptyBodySendsJsonObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining'])),
        ], $history);

        $client->setPreemption();

        // An empty body must serialise as a JSON object "{}", not an array "[]".
        $this->assertSame('{}', (string) $history[0]['request']->getBody());
    }

    public function testPreemptionStatusUsesGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive', 'inFlight' => 0])),
        ], $history);

        $result = $client->preemptionStatus();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    public function testClearPreemptionUsesDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive'])),
        ], $history);

        $result = $client->clearPreemption();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    // -----------------------------------------------------------------
    // Chaos experiment
    // -----------------------------------------------------------------

    public function testStartChaosExperimentSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['status' => 'started', 'name' => 'gradual-degradation'])),
        ], $history);

        $result = $client->startChaosExperiment([
            'name' => 'gradual-degradation',
            'stages' => [
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.1]],
                ],
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.5]],
                ],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/chaosExperiment', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('gradual-degradation', $body['name']);
        $this->assertCount(2, $body['stages']);
        $this->assertSame(10000, $body['stages'][0]['durationMillis']);
        $this->assertSame(0.1, $body['stages'][0]['profiles']['api.example.com']['errorProbability']);

        $this->assertSame('started', $result['status']);
    }

    public function testStartChaosExperimentThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'empty stages'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->startChaosExperiment(['name' => 'bad', 'stages' => []]);
    }
}
