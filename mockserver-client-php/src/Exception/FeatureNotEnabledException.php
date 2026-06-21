<?php

declare(strict_types=1);

namespace MockServer\Exception;

/**
 * Thrown when a control-plane feature is disabled on the server (HTTP 403).
 *
 * Several SRE control-plane endpoints are gated behind a server start-up flag and
 * return {@code 403 Forbidden} until that flag is set. For example the load
 * generator ({@see \MockServer\MockServerClient::loadScenario()}) returns 403
 * until MockServer is started with {@code loadGenerationEnabled=true}.
 */
class FeatureNotEnabledException extends MockServerException
{
}
