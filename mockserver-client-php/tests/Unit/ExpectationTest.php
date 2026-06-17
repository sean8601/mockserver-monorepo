<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Delay;
use MockServer\Expectation;
use MockServer\HttpForward;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\TimeToLive;
use MockServer\Times;
use PHPUnit\Framework\TestCase;

class ExpectationTest extends TestCase
{
    public function testMinimalExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->method('GET')->path('/hello')
            )
            ->httpResponse(
                HttpResponse::response()->statusCode(200)->body('world')
            );

        $expected = [
            'httpRequest' => [
                'method' => 'GET',
                'path' => '/hello',
            ],
            'httpResponse' => [
                'statusCode' => 200,
                'body' => 'world',
            ],
        ];

        $this->assertSame($expected, $expectation->toArray());
    }

    public function testFullExpectation(): void
    {
        $expectation = (new Expectation())
            ->id('my-expectation')
            ->priority(10)
            ->httpRequest(
                HttpRequest::request()
                    ->method('POST')
                    ->path('/api/users')
                    ->header('Content-Type', 'application/json')
                    ->queryStringParameter('version', '2')
                    ->body('{"name":"test"}')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(201)
                    ->header('Location', '/api/users/1')
                    ->body('{"id":1}')
                    ->delay(Delay::milliseconds(100))
            )
            ->times(Times::exactly(3))
            ->timeToLive(TimeToLive::exactly('SECONDS', 60));

        $array = $expectation->toArray();

        $this->assertSame('my-expectation', $array['id']);
        $this->assertSame(10, $array['priority']);
        $this->assertSame('POST', $array['httpRequest']['method']);
        $this->assertSame('/api/users', $array['httpRequest']['path']);
        $this->assertSame(201, $array['httpResponse']['statusCode']);
        $this->assertSame(100, $array['httpResponse']['delay']['value']);
        $this->assertSame(3, $array['times']['remainingTimes']);
        $this->assertFalse($array['times']['unlimited']);
        $this->assertSame('SECONDS', $array['timeToLive']['timeUnit']);
        $this->assertSame(60, $array['timeToLive']['timeToLive']);
        $this->assertFalse($array['timeToLive']['unlimited']);
    }

    public function testForwardExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->path('/proxy')
            )
            ->httpForward(
                HttpForward::forward()
                    ->host('backend.local')
                    ->port(8080)
                    ->scheme('HTTP')
            );

        $array = $expectation->toArray();

        $this->assertSame('/proxy', $array['httpRequest']['path']);
        $this->assertSame('backend.local', $array['httpForward']['host']);
        $this->assertSame(8080, $array['httpForward']['port']);
        $this->assertSame('HTTP', $array['httpForward']['scheme']);
        $this->assertArrayNotHasKey('httpResponse', $array);
    }

    public function testTimesUnlimited(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/any'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->times(Times::unlimited());

        $array = $expectation->toArray();

        $this->assertTrue($array['times']['unlimited']);
        $this->assertArrayNotHasKey('remainingTimes', $array['times']);
    }

    public function testTimesOnce(): void
    {
        $times = Times::once();
        $array = $times->toArray();

        $this->assertSame(1, $array['remainingTimes']);
        $this->assertFalse($array['unlimited']);
    }

    public function testTimeToLiveUnlimited(): void
    {
        $ttl = TimeToLive::unlimited();
        $array = $ttl->toArray();

        $this->assertTrue($array['unlimited']);
        $this->assertArrayNotHasKey('timeUnit', $array);
        $this->assertArrayNotHasKey('timeToLive', $array);
    }

    public function testJsonSerialize(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/test'))
            ->httpResponse(HttpResponse::response()->statusCode(200));

        $json = json_encode($expectation, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame('GET', $decoded['httpRequest']['method']);
        $this->assertSame(200, $decoded['httpResponse']['statusCode']);
    }

    public function testFileBodyExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->method('GET')->path('/template')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->fileBody('/templates/page.html', 'text/html', 'VELOCITY')
            );

        $array = $expectation->toArray();

        $this->assertSame('/template', $array['httpRequest']['path']);
        $this->assertSame(200, $array['httpResponse']['statusCode']);
        $this->assertSame('FILE', $array['httpResponse']['body']['type']);
        $this->assertSame('/templates/page.html', $array['httpResponse']['body']['filePath']);
        $this->assertSame('text/html', $array['httpResponse']['body']['contentType']);
        $this->assertSame('VELOCITY', $array['httpResponse']['body']['templateType']);
    }

    public function testFromArrayRoundTripsBuilderOutput(): void
    {
        $built = (new Expectation())
            ->id('rt')
            ->priority(7)
            ->httpRequest(
                HttpRequest::request()
                    ->method('POST')
                    ->path('/api/"weird"\\path' . "\n" . 'x')
                    ->body('{"name":"a\\b","q":"\"quoted\""}')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(201)
                    ->body("body with \"quotes\", \\backslash and \n newline")
                    ->delay(Delay::milliseconds(50))
            )
            ->times(Times::exactly(3));

        $array = $built->toArray();
        // simulate the generated code: json_encode then json_decode then fromArray
        $decoded = json_decode(json_encode($array, JSON_THROW_ON_ERROR), true);
        $reconstructed = Expectation::fromArray($decoded);

        $this->assertSame($array, $reconstructed->toArray());
        $this->assertSame(
            json_encode($array, JSON_THROW_ON_ERROR),
            json_encode($reconstructed, JSON_THROW_ON_ERROR)
        );
    }

    public function testFromArrayPreservesFieldsNotModelledByBuilder(): void
    {
        // httpForwardTemplate has no typed builder, but fromArray must keep it
        $raw = [
            'httpRequest' => ['path' => '/x'],
            'httpForwardTemplate' => [
                'templateType' => 'JAVASCRIPT',
                'template' => 'return { path: request.path };',
            ],
            'times' => ['remainingTimes' => 2, 'unlimited' => false],
        ];

        $reconstructed = Expectation::fromArray($raw);

        $this->assertSame($raw, $reconstructed->toArray());
        $this->assertSame($raw, $reconstructed->jsonSerialize());
    }

    public function testGetters(): void
    {
        $request = HttpRequest::request()->path('/x');
        $response = HttpResponse::response()->statusCode(200);

        $expectation = (new Expectation())
            ->id('test-id')
            ->priority(5)
            ->httpRequest($request)
            ->httpResponse($response);

        $this->assertSame('test-id', $expectation->getId());
        $this->assertSame(5, $expectation->getPriority());
        $this->assertSame($request, $expectation->getHttpRequest());
        $this->assertSame($response, $expectation->getHttpResponse());
        $this->assertNull($expectation->getHttpForward());
        $this->assertNull($expectation->getHttpError());
        $this->assertNull($expectation->getTimes());
        $this->assertNull($expectation->getTimeToLive());
    }
}
