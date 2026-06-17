<?php

declare(strict_types=1);

namespace MockServer;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Exception\GuzzleException;
use GuzzleHttp\RequestOptions;
use MockServer\Exception\ConnectionException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\MockServerException;
use MockServer\Exception\VerificationException;

/**
 * PHP client for MockServer.
 *
 * Provides the full MockServer control-plane REST API with a fluent builder DSL.
 *
 * @example Basic usage
 *   $client = new MockServerClient('localhost', 1080);
 *   $client->when(
 *       HttpRequest::request()->method('GET')->path('/hello')
 *   )->respond(
 *       HttpResponse::response()->statusCode(200)->body('world')
 *   );
 *   $client->verify(
 *       HttpRequest::request()->path('/hello'),
 *       VerificationTimes::atLeast(1)
 *   );
 *   $client->reset();
 */
class MockServerClient
{
    private GuzzleClient $httpClient;
    private string $baseUri;

    /**
     * @param string $host MockServer hostname
     * @param int $port MockServer port (default 1080)
     * @param string $contextPath Optional context path prefix
     * @param bool $secure Use HTTPS
     * @param array<string, mixed> $guzzleOptions Additional Guzzle client options
     */
    public function __construct(
        string $host = 'localhost',
        int $port = 1080,
        string $contextPath = '',
        bool $secure = false,
        array $guzzleOptions = [],
    ) {
        $scheme = $secure ? 'https' : 'http';
        $ctxPath = '';
        if ($contextPath !== '') {
            $ctxPath = str_starts_with($contextPath, '/') ? $contextPath : '/' . $contextPath;
        }
        $this->baseUri = "{$scheme}://{$host}:{$port}{$ctxPath}";

        $defaultOptions = [
            'base_uri' => $this->baseUri,
            'http_errors' => false,
            RequestOptions::TIMEOUT => 60,
            RequestOptions::CONNECT_TIMEOUT => 10,
            RequestOptions::HEADERS => [
                'Content-Type' => 'application/json; charset=utf-8',
            ],
        ];

        $this->httpClient = new GuzzleClient(array_merge($defaultOptions, $guzzleOptions));
    }

    // -----------------------------------------------------------------
    // Fluent API
    // -----------------------------------------------------------------

    /**
     * Begin building an expectation via the fluent when/respond API.
     *
     * @param HttpRequest $request The request matcher
     * @param Times|null $times How many times to match (null = unlimited)
     * @param TimeToLive|null $timeToLive How long the expectation lives (null = forever)
     * @param int|null $priority Expectation priority
     * @return ForwardChainExpectation
     */
    public function when(
        HttpRequest $request,
        ?Times $times = null,
        ?TimeToLive $timeToLive = null,
        ?int $priority = null,
    ): ForwardChainExpectation {
        $expectation = new Expectation();
        $expectation->httpRequest($request);

        if ($times !== null) {
            $expectation->times($times);
        }
        if ($timeToLive !== null) {
            $expectation->timeToLive($timeToLive);
        }
        if ($priority !== null) {
            $expectation->priority($priority);
        }

        return new ForwardChainExpectation($this, $expectation);
    }

    // -----------------------------------------------------------------
    // Core API methods
    // -----------------------------------------------------------------

    /**
     * Create or update an expectation.
     *
     * @internal Called by ForwardChainExpectation; prefer when()->respond() for external use.
     * @param Expectation $expectation
     * @return array The server response (created expectations).
     * @throws InvalidRequestException
     * @throws MockServerException
     */
    public function upsertExpectation(Expectation $expectation): array
    {
        $body = json_encode([$expectation->toArray()], JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/expectation', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 400 || $status === 406) {
            throw new InvalidRequestException("Invalid expectation: {$responseBody}");
        }
        if ($status >= 400) {
            throw new MockServerException("Failed to create expectation (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return [$expectation->toArray()];
    }

    /**
     * Verify that a request was received the expected number of times.
     *
     * Optionally also verify the response that was returned. When both
     * $request and $httpResponse are provided, the server checks that the
     * request was received AND the matching response was returned.
     *
     * @param HttpRequest $request The request to verify
     * @param VerificationTimes|null $times Expected call count (null = at least once)
     * @param HttpResponse|null $httpResponse Optional response matcher
     * @throws VerificationException If verification fails
     * @throws MockServerException On communication errors
     */
    public function verify(
        HttpRequest $request,
        ?VerificationTimes $times = null,
        ?HttpResponse $httpResponse = null,
    ): void {
        $payload = ['httpRequest' => $request->toArray()];
        if ($httpResponse !== null) {
            $payload['httpResponse'] = $httpResponse->toArray();
        }
        if ($times !== null) {
            $payload['times'] = $times->toArray();
        }

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verify', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verification request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Verify that zero interactions (requests) were received by the server.
     *
     * Sends {@code {"httpRequest": {}, "times": {"atMost": 0}}} so the server
     * checks that no request matching the empty (match-everything) matcher was
     * received — i.e. the server received no requests at all.
     *
     * @throws VerificationException If any request was received
     * @throws MockServerException On communication errors
     */
    public function verifyZeroInteractions(): void
    {
        $this->verify(HttpRequest::request(), VerificationTimes::atMost(0));
    }

    /**
     * Verify that a request+response pair was received the expected number of times.
     *
     * Convenience method combining request and response matchers.
     *
     * @param HttpRequest $request The request matcher
     * @param HttpResponse $httpResponse The response matcher
     * @param VerificationTimes|null $times Expected call count (null = at least once)
     * @throws VerificationException If verification fails
     * @throws MockServerException On communication errors
     */
    public function verifyRequestAndResponse(
        HttpRequest $request,
        HttpResponse $httpResponse,
        ?VerificationTimes $times = null,
    ): void {
        $this->verify($request, $times, $httpResponse);
    }

    /**
     * Verify that a response was returned the expected number of times,
     * regardless of which request triggered it (response-only verification).
     *
     * The httpRequest field is omitted from the JSON payload so the server
     * matches any request that produced a response matching $httpResponse.
     *
     * @param HttpResponse $httpResponse The response matcher
     * @param VerificationTimes|null $times Expected call count (null = at least once)
     * @throws VerificationException If verification fails
     * @throws MockServerException On communication errors
     */
    public function verifyResponse(
        HttpResponse $httpResponse,
        ?VerificationTimes $times = null,
    ): void {
        $payload = ['httpResponse' => $httpResponse->toArray()];
        if ($times !== null) {
            $payload['times'] = $times->toArray();
        }

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verify', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verification request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Verify that requests were received in the specified sequence.
     *
     * @param HttpRequest ...$requests The requests in expected order
     * @throws VerificationException If sequence verification fails
     * @throws MockServerException On communication errors
     */
    public function verifySequence(HttpRequest ...$requests): void
    {
        $payload = [
            'httpRequests' => array_map(fn(HttpRequest $r) => $r->toArray(), $requests),
        ];

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verifySequence', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Sequence verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verify sequence request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Verify that requests were received in sequence with specific responses.
     *
     * Responses are index-aligned with requests: $responses[0] is the expected
     * response for $requests[0], etc. Both arrays must have the same length.
     *
     * When a request entry is null, the httpRequests array entry is omitted
     * for that index (response-only verification for that position).
     *
     * @param array<HttpRequest|null> $requests Request matchers (null entries omitted from payload)
     * @param array<HttpResponse> $responses Response matchers (index-aligned with requests)
     * @throws \InvalidArgumentException If array lengths differ
     * @throws VerificationException If sequence verification fails
     * @throws MockServerException On communication errors
     */
    public function verifySequenceWithResponses(array $requests, array $responses): void
    {
        if (count($requests) !== count($responses)) {
            throw new \InvalidArgumentException(
                sprintf(
                    'Requests and responses arrays must have the same length, got %d requests and %d responses',
                    count($requests),
                    count($responses),
                )
            );
        }

        $payload = [];

        // Build httpRequests array — null entries become absent (not serialized as null)
        $hasRequests = false;
        $httpRequests = [];
        foreach ($requests as $request) {
            if ($request !== null) {
                $hasRequests = true;
                $httpRequests[] = $request->toArray();
            } else {
                // Placeholder to maintain index alignment; server expects
                // httpRequests and httpResponses to be index-aligned
                $httpRequests[] = new \stdClass();
            }
        }
        if ($hasRequests) {
            $payload['httpRequests'] = $httpRequests;
        }

        $payload['httpResponses'] = array_map(
            fn(HttpResponse $r) => $r->toArray(),
            $responses,
        );

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verifySequence', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Sequence verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verify sequence request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Clear expectations and/or logs matching the request.
     *
     * @param HttpRequest|null $request Matcher to select what to clear (null = clear all)
     * @param string|null $type "EXPECTATIONS", "LOG", or "ALL" (null = ALL)
     */
    public function clear(?HttpRequest $request = null, ?string $type = null): void
    {
        $path = '/mockserver/clear';
        if ($type !== null) {
            $path .= '?type=' . urlencode($type);
        }

        $body = $request !== null ? json_encode($request->toArray(), JSON_THROW_ON_ERROR) : '';
        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Clear failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Clear by expectation ID.
     *
     * @param string $expectationId The ID of the expectation to clear
     * @param string|null $type "EXPECTATIONS", "LOG", or "ALL"
     */
    public function clearById(string $expectationId, ?string $type = null): void
    {
        $path = '/mockserver/clear';
        if ($type !== null) {
            $path .= '?type=' . urlencode($type);
        }

        $body = json_encode(['id' => $expectationId], JSON_THROW_ON_ERROR);
        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Clear by ID failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Reset MockServer: remove all expectations and clear all recorded requests.
     */
    public function reset(): void
    {
        $response = $this->put('/mockserver/reset', '');

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Reset failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Retrieve recorded requests matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of recorded request arrays
     */
    public function retrieveRecordedRequests(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'REQUESTS', 'JSON');
    }

    /**
     * Retrieve active expectations matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of expectation arrays
     */
    public function retrieveActiveExpectations(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'ACTIVE_EXPECTATIONS', 'JSON');
    }

    /**
     * Retrieve recorded expectations matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of expectation arrays
     */
    public function retrieveRecordedExpectations(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'RECORDED_EXPECTATIONS', 'JSON');
    }

    /**
     * Retrieve log messages matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of log entry strings/arrays
     */
    public function retrieveLogMessages(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'LOGS', 'JSON');
    }

    /**
     * Check the server status (bound ports).
     *
     * @return array{ports: list<int>} Status response
     */
    public function status(): array
    {
        $response = $this->put('/mockserver/status', '');

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Status request failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return ['ports' => []];
    }

    /**
     * Bind additional ports.
     *
     * @param int ...$ports Ports to bind
     * @return array{ports: list<int>} Bound ports response
     */
    public function bind(int ...$ports): array
    {
        $body = json_encode(['ports' => array_values($ports)], JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/bind', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Bind failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return ['ports' => []];
    }

    // -----------------------------------------------------------------
    // OpenAPI import
    // -----------------------------------------------------------------

    /**
     * Import expectations from an OpenAPI / Swagger specification.
     *
     * The spec may be supplied as a URL, a file/classpath path, or inline
     * (JSON or YAML) via {@see OpenAPIExpectation::openAPI()}.
     *
     * @param OpenAPIExpectation $expectation The OpenAPI definition to import
     * @throws InvalidRequestException If the spec is rejected by the server
     * @throws MockServerException On communication errors
     */
    public function openApiExpectation(OpenAPIExpectation $expectation): void
    {
        $body = json_encode($expectation->toArray(), JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/openapi', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 400 || $status === 406) {
            throw new InvalidRequestException("Invalid OpenAPI expectation: {$responseBody}");
        }
        if ($status >= 400) {
            throw new MockServerException("Failed to create OpenAPI expectation (HTTP {$status}): {$responseBody}");
        }
    }

    // -----------------------------------------------------------------
    // gRPC descriptor management
    // -----------------------------------------------------------------

    /**
     * Upload a compiled protobuf descriptor set so gRPC requests can be matched.
     *
     * The bytes must be the raw contents of a {@code FileDescriptorSet} (e.g. the
     * output of {@code protoc --descriptor_set_out=... --include_imports}). They
     * are sent verbatim as {@code application/octet-stream} (NOT base64-encoded).
     *
     * @param string $descriptorBytes Raw descriptor set bytes (binary string)
     * @throws \InvalidArgumentException If the descriptor bytes are empty
     * @throws InvalidRequestException If the descriptor set is rejected
     * @throws MockServerException On communication errors
     */
    public function uploadGrpcDescriptor(string $descriptorBytes): void
    {
        if ($descriptorBytes === '') {
            throw new \InvalidArgumentException('descriptor bytes must not be empty');
        }

        $response = $this->put(
            '/mockserver/grpc/descriptors',
            $descriptorBytes,
            'application/octet-stream',
        );

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 400) {
            throw new InvalidRequestException("Invalid gRPC descriptor: {$responseBody}");
        }
        if ($status >= 400) {
            throw new MockServerException("Failed to upload gRPC descriptor (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Retrieve the gRPC services registered from uploaded descriptor sets.
     *
     * Each entry has a "name" and a list of "methods", where each method has
     * "name", "inputType", "outputType", "clientStreaming", "serverStreaming".
     *
     * @return array<int, array<string, mixed>> List of service descriptors
     * @throws MockServerException On communication errors
     */
    public function retrieveGrpcServices(): array
    {
        $response = $this->put('/mockserver/grpc/services', '');

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Failed to retrieve gRPC services (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return [];
    }

    /**
     * Clear all uploaded gRPC descriptor sets and registered services.
     *
     * @throws MockServerException On communication errors
     */
    public function clearGrpcDescriptors(): void
    {
        $response = $this->put('/mockserver/grpc/clear', '');

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Failed to clear gRPC descriptors (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Check if MockServer has started (polls with retries).
     *
     * @param int $attempts Number of attempts
     * @param float $timeout Seconds to wait between attempts
     * @return bool True if server is reachable
     */
    public function hasStarted(int $attempts = 10, float $timeout = 0.5): bool
    {
        for ($i = 0; $i < $attempts; $i++) {
            try {
                $response = $this->put('/mockserver/status', '');
                if ($response->getStatusCode() === 200) {
                    return true;
                }
            } catch (ConnectionException) {
                // Not yet started, retry
            }

            if ($i < $attempts - 1) {
                usleep((int) ($timeout * 1_000_000));
            }
        }

        return false;
    }

    /**
     * Get the base URI of this client.
     */
    public function getBaseUri(): string
    {
        return $this->baseUri;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Generic retrieve method.
     *
     * @param HttpRequest|null $request Filter
     * @param string $type REQUESTS, ACTIVE_EXPECTATIONS, RECORDED_EXPECTATIONS, LOGS
     * @param string $format JSON or LOG_ENTRIES
     * @return array
     */
    private function retrieve(?HttpRequest $request, string $type, string $format): array
    {
        $path = '/mockserver/retrieve?type=' . urlencode($type) . '&format=' . urlencode($format);
        $body = $request !== null ? json_encode($request->toArray(), JSON_THROW_ON_ERROR) : '';

        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Retrieve ({$type}) failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return [];
    }

    /**
     * Send a PUT request to MockServer.
     *
     * @param string $path Request path (including query string if any)
     * @param string $body Request body (JSON by default, or raw bytes)
     * @param string|null $contentType Override the request Content-Type (e.g. application/octet-stream)
     * @return \Psr\Http\Message\ResponseInterface
     * @throws ConnectionException
     */
    private function put(
        string $path,
        string $body,
        ?string $contentType = null,
    ): \Psr\Http\Message\ResponseInterface {
        $options = [
            RequestOptions::BODY => $body,
        ];
        if ($contentType !== null) {
            // Per-request Content-Type override (replaces the client default).
            $options[RequestOptions::HEADERS] = ['Content-Type' => $contentType];
        }

        try {
            return $this->httpClient->request('PUT', $path, $options);
        } catch (ConnectException $e) {
            throw new ConnectionException(
                "Failed to connect to MockServer at {$this->baseUri}: {$e->getMessage()}",
                (int) $e->getCode(),
                $e
            );
        } catch (GuzzleException $e) {
            throw new ConnectionException(
                "Request to MockServer at {$this->baseUri} failed: {$e->getMessage()}",
                (int) $e->getCode(),
                $e
            );
        }
    }
}
