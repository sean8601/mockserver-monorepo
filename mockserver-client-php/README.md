# MockServer PHP Client

PHP client for [MockServer](https://www.mock-server.com) - enables easy mocking of any system you integrate with via HTTP or HTTPS.

## Requirements

- PHP 8.1+
- Composer

## Installation

```bash
composer require mock-server/mockserver-client
```

## Quick Start

```php
<?php

use MockServer\MockServerClient;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\VerificationTimes;

// Connect to MockServer
$client = new MockServerClient('localhost', 1080);

// Create an expectation
$client->when(
    HttpRequest::request()->method('GET')->path('/hello')
)->respond(
    HttpResponse::response()
        ->statusCode(200)
        ->header('Content-Type', 'application/json')
        ->body('{"message":"world"}')
);

// Verify the request was received
$client->verify(
    HttpRequest::request()->path('/hello'),
    VerificationTimes::atLeast(1)
);

// Reset all expectations
$client->reset();
```

## API

### Creating Expectations

```php
use MockServer\Times;
use MockServer\TimeToLive;
use MockServer\Delay;
use MockServer\HttpForward;

// Respond with a delay
$client->when(
    HttpRequest::request()->method('POST')->path('/api/data')
        ->header('Content-Type', 'application/json')
        ->jsonBody(['key' => 'value'])
)->respond(
    HttpResponse::response()
        ->statusCode(201)
        ->body('{"id": 1}')
        ->delay(Delay::milliseconds(500))
);

// Match only 3 times, with priority
$client->when(
    HttpRequest::request()->path('/limited'),
    Times::exactly(3),
    TimeToLive::exactly('SECONDS', 60),
    priority: 10
)->respond(
    HttpResponse::response()->statusCode(200)
);

// Forward to another server
$client->when(
    HttpRequest::request()->path('/proxy')
)->forward(
    HttpForward::forward()->host('backend.local')->port(8080)->scheme('HTTP')
);
```

### Verification

```php
use MockServer\VerificationTimes;

// Verify at least once
$client->verify(
    HttpRequest::request()->path('/hello'),
    VerificationTimes::atLeast(1)
);

// Verify exactly 3 times
$client->verify(
    HttpRequest::request()->method('POST')->path('/api'),
    VerificationTimes::exactly(3)
);

// Verify sequence
$client->verifySequence(
    HttpRequest::request()->path('/first'),
    HttpRequest::request()->path('/second')
);
```

### Retrieving Recorded Data

```php
// Retrieve recorded requests
$requests = $client->retrieveRecordedRequests(
    HttpRequest::request()->path('/api')
);

// Retrieve active expectations
$expectations = $client->retrieveActiveExpectations();

// Retrieve log messages
$logs = $client->retrieveLogMessages();
```

### Control Operations

```php
// Clear specific expectations/logs
$client->clear(HttpRequest::request()->path('/old'));
$client->clear(null, 'EXPECTATIONS');  // type: EXPECTATIONS, LOG, or ALL
$client->clearById('my-expectation-id');

// Reset everything
$client->reset();

// Check server status
$status = $client->status();  // ['ports' => [1080]]

// Bind additional ports
$client->bind(1081, 1082);

// Check if server is running
if ($client->hasStarted()) {
    echo "MockServer is ready";
}
```

## Mocking LLM APIs

Fluent builders under the `MockServer\Llm` namespace mock provider-agnostic LLM
completions, embeddings, multi-turn conversations and provider failover. The
wire JSON they produce is identical to the Java, Node and Python clients, so a
mock scripted from PHP behaves the same as one scripted anywhere else.

```php
<?php

use MockServer\Llm\Completion;
use MockServer\Llm\IsolationSource;
use MockServer\Llm\LlmConversationBuilder;
use MockServer\Llm\LlmFailoverBuilder;
use MockServer\Llm\LlmMockBuilder;
use MockServer\Llm\Provider;
use MockServer\Llm\Role;
use MockServer\Llm\Usage;

// Single completion mock
LlmMockBuilder::llmMock('/v1/chat/completions')
    ->withProvider(Provider::OPENAI)
    ->withModel('gpt-4o')
    ->respondingWith(
        Completion::completion()
            ->withText('Hello from a mocked model!')
            ->withStopReason('stop')
            ->withUsage(Usage::usage()->withInputTokens(12)->withOutputTokens(8))
    )
    ->applyTo($client);

// Multi-turn conversation (advances MockServer scenario state per turn),
// isolated per session by a request header
LlmConversationBuilder::conversation()
    ->withPath('/v1/chat/completions')
    ->withProvider(Provider::ANTHROPIC)
    ->isolateBy(IsolationSource::header('x-session-id'))
    ->turn()
        ->whenLatestMessageRole(Role::USER)
        ->respondingWith(Completion::completion()->withText('Hi! How can I help?'))
    ->turn()
        ->respondingWith(Completion::completion()->withText('Goodbye!'))
    ->applyTo($client);

// Provider failover: fail twice, then succeed (consecutive identical failures
// are coalesced; default JSON error bodies are supplied per status code)
LlmFailoverBuilder::llmFailover()
    ->withPath('/v1/chat/completions')
    ->withProvider(Provider::OPENAI)
    ->failWith(429)
    ->failWith(503, 2)
    ->thenRespondWith(Completion::completion()->withText('Recovered'))
    ->applyTo($client);
```

## Mocking MCP Servers

`MockServer\Mcp\McpMockBuilder` emulates a Streamable-HTTP MCP (Model Context
Protocol) server speaking JSON-RPC 2.0. It generates the full set of
expectations — `initialize`, `ping`, `notifications/initialized`, plus
`tools/list` + `tools/call`, `resources/list` + `resources/read` and
`prompts/list` + `prompts/get` for any tools, resources and prompts declared.

```php
<?php

use MockServer\Mcp\McpMockBuilder;
use MockServer\Llm\Role;

McpMockBuilder::mcpMock('/mcp')
    ->withServerName('WeatherServer')
    ->withTool('get_weather')
        ->withDescription('Get the weather for a city')
        ->withInputSchema('{"type":"object","properties":{"city":{"type":"string"}}}')
        ->respondingWith('72F and sunny')
    ->and()
    ->withResource('file:///config.json')
        ->withName('config')
        ->withMimeType('application/json')
        ->withContent('{"debug":true}')
    ->and()
    ->withPrompt('greeting')
        ->withArgument('name', 'Who to greet', true)
        ->respondingWith(Role::ASSISTANT, 'Hello there!')
    ->and()
    ->applyTo($client);
```

Every builder also exposes `build()` to obtain the raw `Expectation`
object(s) without registering them (a single `Expectation` for `llmMock`,
or an array of `Expectation` for conversations, failover and MCP).

## Start / Launch MockServer

The PHP client does not include a binary launcher (PHP lacks the native WebSocket and subprocess management required for embedded launch). To start MockServer, use one of the following approaches:

- **Docker:** `docker run -d -p 1080:1080 mockserver/mockserver`
- **Executable JAR:** `java -jar mockserver-netty-no-dependencies-<version>.jar -serverPort 1080`
- **Homebrew:** `brew install mockserver && mockserver -serverPort 1080`
- **Another client's launcher:** The Node, Python, Ruby, Go, .NET, and Rust clients can each download and launch MockServer automatically without Java or Docker.

See the [Running MockServer](https://www.mock-server.com/mock_server/running_mock_server.html) documentation for all available options.

## Building

```bash
composer install
```

## Using in tests (PHPUnit)

`MockServer\Testing\MockServerTestTrait` provides a `MockServerClient` and resets
the server before and after each test, so recorded requests, expectations and
logs never leak between tests. Mix it into your PHPUnit test case and call the
lifecycle helpers from `setUp()` / `tearDown()`:

```php
use MockServer\Testing\MockServerTestTrait;
use PHPUnit\Framework\TestCase;

final class MyTest extends TestCase
{
    use MockServerTestTrait;

    protected function setUp(): void    { $this->setUpMockServer(); }
    protected function tearDown(): void { $this->tearDownMockServer(); }

    public function testSomething(): void
    {
        // $this->mockServer is a reset MockServerClient ready to use
        $this->mockServer->reset();
    }
}
```

The server URL is read from the `MOCKSERVER_URL` environment variable (for
example `http://localhost:1080`); when it is unset the test is skipped.

## Running Tests

Unit tests (no server required):

```bash
vendor/bin/phpunit --testsuite Unit
```

Integration tests (requires a running MockServer):

```bash
MOCKSERVER_URL=http://localhost:1080 vendor/bin/phpunit --testsuite Integration
```

## License

Apache 2.0 - see [LICENSE](../LICENSE.md)
