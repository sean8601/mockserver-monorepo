# Configuration Reference

MockServer is configured through a single mechanism — a flat set of named properties — exposed in four equivalent forms. The authoritative list of every property, with defaults and inline documentation, is the example file checked into the repo:

**[`mockserver/mockserver.example.properties`](../../mockserver/mockserver.example.properties)**

That file is the source of truth. When a property is added, removed, or renamed it MUST be reflected there with a short comment explaining what it does. This doc explains the mechanism around it — *how* a value reaches the running server, *how* the layers interact, and *which* code does the loading.

For the user-facing rendition of the same properties (with examples and cross-links), see [Configuration Properties on the website](https://www.mock-server.com/mock_server/configuration_properties.html).

## How values are loaded

`ConfigurationProperties` is a static holder in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/`. Every property has a typed getter (e.g. `serverPort()`, `tlsMutualAuthenticationRequired()`) that resolves the value in this order — first hit wins:

1. **JVM system property** — `-Dmockserver.serverPort=1080`
2. **Properties file** — pointed to by `-Dmockserver.propertyFile=…` (default: `./mockserver.properties` if present)
3. **Environment variable** — `MOCKSERVER_SERVER_PORT=1080` (upper-snake form of the system-property suffix)
4. **Built-in default** — coded into the typed getter

This means properties file entries override environment variables, system properties override properties file entries, and explicit `Configuration` instance setters override all of the above (see "Instance-scoped configuration" below). The resolution is implemented in `readPropertyHierarchically`: `System.getProperty(key, properties.getProperty(key, envOrDefault))`.

The loader logs the resolved property source on startup at `TRACE` — useful when a value isn't what you expect.

## Unknown-key warning

A misspelled property (e.g. `-Dmockserver.maxExpectatons=…` or `MOCKSERVER_METRICS_ENABLE=…`) is silently ignored by the per-key resolution above — the typo never matches a getter, so the default is used and "my config did nothing". To catch this, `ConfigurationProperties` runs a one-time validation pass at startup (in its static initialiser, so it fires regardless of whether a properties file is present, covering env-only and `-D`-only deployments). It logs a `WARN` naming any key that is in the `mockserver.` / `MOCKSERVER_` namespace but is **not** a recognised property:

- a JVM system property whose name starts with `mockserver.` but isn't a known key,
- an environment variable whose name starts with `MOCKSERVER_` but isn't a known key,
- a `mockserver.*` key in the loaded properties file that isn't a known key.

The recognised-key set is derived **reflectively from the `MOCKSERVER_*` constant fields** in `ConfigurationProperties` (the constant's value is the `mockserver.*` key; the constant's field name is the `MOCKSERVER_*` env-var key), so it can never drift from the actual properties — adding a new property automatically extends recognition. Keys outside the `mockserver.`/`MOCKSERVER_` namespace (e.g. `JAVA_HOME`, `PATH`) are never flagged, so unrelated environment variables do not produce false positives. The check can never throw, so it cannot break startup. Only key **names** are logged, never values.

## The four equivalent forms

| Form | Use when | Example |
|------|----------|---------|
| Properties file | Shipping reproducible config alongside the JAR / Docker image; CI default; what `mockserver.example.properties` documents | `mockserver.serverPort=1080` |
| Environment variable | Container deployments where each setting is its own knob | `MOCKSERVER_SERVER_PORT=1080` |
| JVM system property | Overriding a single value at launch time (CLI, IDE) | `-Dmockserver.serverPort=1080` |
| Programmatic — `Configuration` instance | Embedded MockServer in tests; per-instance overrides without touching globals | `new Configuration().serverPort(1080)` |

The first three feed into the **static** `ConfigurationProperties`. The fourth uses a **per-instance** `Configuration` object that falls back to `ConfigurationProperties` for any unset field. Use the instance form for tests that run multiple MockServers in the same JVM with different settings.

## Property categories

`mockserver.example.properties` groups the core properties into blocks. `ConfigurationProperties.java` defines the full set — there are currently **~170 properties** (one `private static final String MOCKSERVER_*` key constant per property). The categories below cover both the blocks in the example file and the additional groups defined only in `ConfigurationProperties.java`:

| Category | Representative properties |
|----------|--------------------------|
| Ports & proxy | `serverPort`, `proxyRemoteHost`, `proxyRemotePort` |
| Logging | `logLevel`, `disableSystemOut`, `detailedMatchFailures`, `compactLogFormat`, `metricsEnabled`, `slowRequestThresholdMillis`, `attachMismatchDiagnosticToResponse` |
| Dev mode | `devMode` |
| Memory usage | `maxExpectations`, `maxLogEntries`, `maxWebSocketExpectations`, `outputMemoryUsageCsv` |
| HTTP behaviour | `nioEventLoopThreadCount`, `actionHandlerThreadCount`, `webSocketClientEventLoopThreadCount`, `clientNioEventLoopThreadCount`, `streamingResponsesEnabled`, `maxStreamingCaptureBytes` |
| Initialisation / OpenAPI | `initializationClass`, `initializationJsonPath`, `persistExpectations`, `persistedExpectationsPath`, `openAPIContextPathPrefix`, `openAPIResponseValidation`, `generateRealisticExampleValues`, `validateProxyOpenAPISpec`, `validateProxyEnforce` |
| CORS | `enableCORSForAPI`, `enableCORSForAllResponses`, `corsAllowOrigin`, `corsAllowMethods`, `corsAllowHeaders`, `corsAllowCredentials` |
| Proxy auth | `forwardHttpsProxy`, `forwardSocksProxy`, `proxyAuthenticationUsername`, `proxyAuthenticationPassword`, `proxyAuthenticationRealm` |
| Control-plane JWT auth | `controlPlaneJWTAuthenticationRequired`, `controlPlaneJWTAuthenticationJWKSource`, `controlPlaneJWTAuthenticationExpectedAudience` |
| TLS inbound — dynamic | `certificateAuthorityPrivateKey`, `certificateAuthorityCertificate`, `dynamicallyCreateCertificateAuthorityCertificate`, `directoryToSaveDynamicSSLCertificate`, `preventCertificateDynamicUpdate`, `sslCertificateDomainName`, `sslSubjectAlternativeNameDomains`, `sslSubjectAlternativeNameIps` |
| TLS inbound — fixed | `privateKeyPath`, `x509CertificatePath` |
| mTLS | `tlsMutualAuthenticationRequired`, `tlsMutualAuthenticationCertificateChain` |
| TLS outbound | `forwardProxyTLSX509CertificatesTrustManagerType`, `forwardProxyTLSCustomTrustX509Certificates`, `forwardProxyPrivateKey`, `forwardProxyCertificateChain` |
| Protocol selection | `tlsProtocols`, `proactivelyInitialiseTLS`, `useBouncyCastleForKeyAndCertificateGeneration`, `useSemicolonAsQueryParameterSeparator` |
| MCP | `mcpEnabled` |
| WASM rules | `wasmEnabled`, `wasmMaxMemoryPages` |
| gRPC | `grpcEnabled`, `grpcDescriptorDirectory`, `grpcProtoDirectory`, `grpcProtocPath` |
| DNS | `dnsEnabled`, `dnsPort` |
| HTTP/3 (QUIC) | `http3Port`, `http3AltSvcMaxAge`, `http3AdvertiseAltSvc`, `http3ConnectUdpEnabled`, `http3MaxIdleTimeout`, `http3InitialMaxData`, `http3InitialMaxStreamDataBidirectional`, `http3InitialMaxStreamsBidirectional`, `http3QpackMaxTableCapacity` |
| Service mesh / transparent proxy | `transparentProxyEnabled`, `transparentProxyTproxy`, `transparentProxyEbpf`, `transparentProxyEbpfMapPath` |
| OpenTelemetry | `otelMetricsEnabled`, `otelTracesEnabled`, `otelEndpoint`, `otelMetricsExportIntervalSeconds`, `otelPropagateTraceContext`, `otelGenerateTraceId` |
| Chaos auto-halt | `chaosAutoHaltEnabled`, `chaosAutoHaltErrorThreshold`, `chaosAutoHaltWindowMillis` |
| Breakpoints | `breakpointTimeoutMillis`, `breakpointMaxHeld` (breakpoint activation is now via the matcher-based registry REST API) |
| Drift detection | `driftSemanticAnalysisEnabled`, `driftResponseTimeThresholdMs` |
| Clustered state | `stateBackend`, `clusterEnabled`, `clusterName`, `clusterTransportConfig` |
| Blob store | `blobStoreType`, `blobStoreBucket`, `blobStoreRegion`, `blobStoreEndpoint`, `blobStoreKeyPrefix`, `blobStoreAccessKeyId`, `blobStoreSecretAccessKey`, `blobStoreContainer`, `blobStoreConnectionString`, `blobStoreProjectId` |
| Async messaging | `asyncKafkaBootstrapServers`, `asyncMqttBrokerUrl`, `asyncRecordedMessageMaxEntries` |
| JSON Schema matching | `jsonSchemaAllowRemoteRefs` (JVM system property only — not in `ConfigurationProperties`; read via `System.getProperty` in `JsonSchemaValidator` at schema-build time) |
| LLM mocking | `llmProvider`, `llmApiKey`, `llmModel`, `llmBaseUrl`, `llmBackendsConfig`, `llmSemanticMatchingEnabled`, `llmVcrStrict`, `fixtureBodyRedactFields` |
| LLM metrics & budget | `llmMetricsEnabled`, `llmCostBudgetUsd`, `perExpectationMetricsEnabled` |
| Recorded expectations | `deduplicateRecordedExpectations`, `redactSecretsInRecordedExpectations` |
| Lifecycle | `stopDrainMillis` |

The example file documents the most commonly tuned properties (≈220 lines). For the complete list including newer subsystems, read `ConfigurationProperties.java` or the consumer reference page.

### `redactSecretsInRecordedExpectations`

Opt-in (default `false`). When `true`, MockServer masks sensitive header values — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`, `api-key` (which also covers bearer/token credentials carried in those headers) — with `***REDACTED***` on the recorded-expectation retrieval path (`HttpState.postProcessRecordedExpectations`). Because that path feeds every retrieve format, redaction covers retrieve-as-JSON, retrieve-as-code (generated client code), and persisted recorded JSON. Redaction reuses `FixtureRedactor` (the same masking already applied on the import path) and operates on copies, so the live event log is never mutated. On this path it uses the constraint-preserving variant (`redact(..., preserveConstraints=true)`), so the redacted recordings keep their original `times`, `timeToLive`, `priority`, and `id` — only the sensitive values are masked.

**Trade-off:** a redacted recorded expectation can no longer replay against an upstream that requires the masked credential, so this is off by default — enable it only when you want to share or persist recordings without leaking proxied secrets.

## Adding a property

Five places to touch — there are no implicit registrations.

1. **Typed getter + private setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/ConfigurationProperties.java` — define the constant key, the system-property name, the env-var alias, and the default.
2. **Instance-scoped fluent setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/Configuration.java`, with `fileExists(...)` if the value is a path (see the existing patterns; this guard is part of the [TLS validation contract](tls-and-security.md)).
3. **Documentation** in `mockserver/mockserver.example.properties` — same section ordering as above.
4. **Tests** in `mockserver/mockserver-core/src/test/java/org/mockserver/configuration/ConfigurationTest.java` covering: env-var → property, system-property → property, fluent setter → property, default, and any validation guard.
5. **Consumer docs** at `jekyll-www.mock-server.com/mock_server/configuration_properties.html` — keep the user-facing description aligned with the inline comment.

See [docs/code/domain-model.md](domain-model.md) for the wider configuration architecture and [docs/code/memory-management.md](memory-management.md) for the memory-ring-buffer properties specifically (they need extra care because the wrong values can OOM the JVM).
