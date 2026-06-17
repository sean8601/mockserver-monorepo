<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Represents a MockServer expectation (request matcher + action).
 */
class Expectation implements \JsonSerializable
{
    /**
     * Raw expectation data captured by {@see Expectation::fromArray()}.
     *
     * When set (i.e. the expectation was reconstructed from a decoded JSON
     * payload such as the output of MockServer's {@code retrieve?format=php}
     * code generator), {@see Expectation::toArray()} returns this verbatim so
     * the round-trip is byte-faithful regardless of which expectation fields
     * are present. The typed builder path leaves this {@code null}.
     *
     * @var array<string, mixed>|null
     */
    private ?array $rawData = null;

    private ?string $id = null;
    private ?int $priority = null;
    private ?HttpRequest $httpRequest = null;
    private ?HttpResponse $httpResponse = null;
    private ?HttpForward $httpForward = null;
    private ?HttpError $httpError = null;
    private ?HttpSseResponse $httpSseResponse = null;
    private ?HttpWebSocketResponse $httpWebSocketResponse = null;
    private ?GrpcStreamResponse $grpcStreamResponse = null;
    private ?BinaryResponse $binaryResponse = null;
    private ?DnsResponse $dnsResponse = null;
    private ?Times $times = null;
    private ?TimeToLive $timeToLive = null;

    public function id(string $id): self
    {
        $this->id = $id;
        return $this;
    }

    public function priority(int $priority): self
    {
        $this->priority = $priority;
        return $this;
    }

    public function httpRequest(HttpRequest $request): self
    {
        $this->httpRequest = $request;
        return $this;
    }

    public function httpResponse(HttpResponse $response): self
    {
        $this->httpResponse = $response;
        return $this;
    }

    public function httpForward(HttpForward $forward): self
    {
        $this->httpForward = $forward;
        return $this;
    }

    public function httpError(HttpError $error): self
    {
        $this->httpError = $error;
        return $this;
    }

    public function httpSseResponse(HttpSseResponse $sseResponse): self
    {
        $this->httpSseResponse = $sseResponse;
        return $this;
    }

    public function httpWebSocketResponse(HttpWebSocketResponse $webSocketResponse): self
    {
        $this->httpWebSocketResponse = $webSocketResponse;
        return $this;
    }

    public function grpcStreamResponse(GrpcStreamResponse $grpcStreamResponse): self
    {
        $this->grpcStreamResponse = $grpcStreamResponse;
        return $this;
    }

    public function binaryResponse(BinaryResponse $binaryResponse): self
    {
        $this->binaryResponse = $binaryResponse;
        return $this;
    }

    public function dnsResponse(DnsResponse $dnsResponse): self
    {
        $this->dnsResponse = $dnsResponse;
        return $this;
    }

    public function times(Times $times): self
    {
        $this->times = $times;
        return $this;
    }

    public function timeToLive(TimeToLive $timeToLive): self
    {
        $this->timeToLive = $timeToLive;
        return $this;
    }

    public function getId(): ?string
    {
        return $this->id;
    }

    public function getPriority(): ?int
    {
        return $this->priority;
    }

    public function getHttpRequest(): ?HttpRequest
    {
        return $this->httpRequest;
    }

    public function getHttpResponse(): ?HttpResponse
    {
        return $this->httpResponse;
    }

    public function getHttpForward(): ?HttpForward
    {
        return $this->httpForward;
    }

    public function getHttpError(): ?HttpError
    {
        return $this->httpError;
    }

    public function getTimes(): ?Times
    {
        return $this->times;
    }

    public function getTimeToLive(): ?TimeToLive
    {
        return $this->timeToLive;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        if ($this->rawData !== null) {
            return $this->rawData;
        }

        $data = [];

        if ($this->id !== null) {
            $data['id'] = $this->id;
        }
        if ($this->priority !== null) {
            $data['priority'] = $this->priority;
        }
        if ($this->httpRequest !== null) {
            $data['httpRequest'] = $this->httpRequest->toArray();
        }
        if ($this->httpResponse !== null) {
            $data['httpResponse'] = $this->httpResponse->toArray();
        }
        if ($this->httpForward !== null) {
            $data['httpForward'] = $this->httpForward->toArray();
        }
        if ($this->httpError !== null) {
            $data['httpError'] = $this->httpError->toArray();
        }
        if ($this->httpSseResponse !== null) {
            $data['httpSseResponse'] = $this->httpSseResponse->toArray();
        }
        if ($this->httpWebSocketResponse !== null) {
            $data['httpWebSocketResponse'] = $this->httpWebSocketResponse->toArray();
        }
        if ($this->grpcStreamResponse !== null) {
            $data['grpcStreamResponse'] = $this->grpcStreamResponse->toArray();
        }
        if ($this->binaryResponse !== null) {
            $data['binaryResponse'] = $this->binaryResponse->toArray();
        }
        if ($this->dnsResponse !== null) {
            $data['dnsResponse'] = $this->dnsResponse->toArray();
        }
        if ($this->times !== null) {
            $data['times'] = $this->times->toArray();
        }
        if ($this->timeToLive !== null) {
            $data['timeToLive'] = $this->timeToLive->toArray();
        }

        return $data;
    }

    /**
     * Reconstruct an Expectation from a decoded JSON array (the inverse of
     * {@see Expectation::toArray()}).
     *
     * This is the factory used by code generated from
     * {@code retrieve?type=ACTIVE_EXPECTATIONS&format=php}: the generated code
     * does {@code Expectation::fromArray(json_decode($json, true))} and passes
     * the result to {@see MockServerClient::upsertExpectation()}.
     *
     * The decoded array is stored verbatim and replayed by {@see toArray()},
     * so every expectation field — including action types not yet modelled by
     * the typed builder — round-trips faithfully without lossy field-by-field
     * reconstruction.
     *
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        $expectation = new self();
        $expectation->rawData = $data;
        return $expectation;
    }
}
