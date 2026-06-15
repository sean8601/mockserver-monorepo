/*
 * mockserver
 * http://mock-server.com
 *
 * Original definitions by: David Tanner <https://github.com/DavidTanner>
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import {Expectation, ExpectationId, HttpChaosProfile, HttpRequest, HttpRequestAndHttpResponse, HttpResponse, KeyToMultiValue, OpenAPIExpectation, RequestDefinition, Times, TimeToLive,} from './mockServer';

export type Host = string;
export type Port = number;
export type ContextPath = string;
export type TLS = boolean;
export type CaCertPemFilePath = string;
// Retains backwards compatability.
export type KeysToMultiValues = KeyToMultiValue;

export type ClearType = 'EXPECTATIONS' | 'LOG' | 'ALL';

export interface ClockStatus {
    currentInstant: string;
    currentEpochMillis: number;
    frozen: boolean;
}

export interface SuccessFullRequest {
    statusCode: number;
    body: string;
}

export type RequestResponse = SuccessFullRequest | string;

export type PathOrRequestDefinition = string | Expectation | RequestDefinition | undefined | null;

export interface MockServerClient {
    openAPIExpectation(expectation: OpenAPIExpectation): Promise<RequestResponse>;

    mockAnyResponse(expectation: Expectation | Expectation[]): Promise<RequestResponse>;

    mockWithCallback(requestMatcher: RequestDefinition, requestHandler: (request: HttpRequest) => HttpResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockWithForwardCallback(requestMatcher: RequestDefinition, forwardHandler: (request: HttpRequest) => HttpRequest, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockWithForwardAndResponseCallback(requestMatcher: RequestDefinition, forwardHandler: (request: HttpRequest) => HttpRequest, responseHandler: (request: HttpRequest, response: HttpResponse) => HttpResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockSimpleResponse<T = any>(path: string, responseBody: T, statusCode?: number): Promise<RequestResponse>;

    setDefaultHeaders(responseHeaders: KeysToMultiValues, requestHeaders: KeysToMultiValues): MockServerClient;

    verify(matcher: RequestDefinition, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyResponse(responseMatcher: HttpResponse, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyRequestAndResponse(requestMatcher: RequestDefinition, responseMatcher: HttpResponse, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyById(expectationId: ExpectationId, atLeast?: number, atMost?: number): Promise<void | string>;

    verifySequence(...matchers: RequestDefinition[]): Promise<void | string>;

    verifySequenceWithResponses(requestsAndResponses: Array<{request: RequestDefinition, response: HttpResponse}>): Promise<void | string>;

    verifySequenceById(...expectationIds: ExpectationId[]): Promise<void | string>;

    verifyZeroInteractions(): Promise<void | string>;

    reset(): Promise<RequestResponse>;

    clear(pathOrRequestDefinition: PathOrRequestDefinition, type: ClearType): Promise<RequestResponse>;

    clearById(expectationId: string, type: ClearType): Promise<RequestResponse>;

    freezeClock(instant?: string): Promise<RequestResponse>;

    advanceClock(durationMillis: number): Promise<RequestResponse>;

    resetClock(): Promise<RequestResponse>;

    clockStatus(): Promise<ClockStatus>;

    setServiceChaos(host: string, chaos: HttpChaosProfile, ttlMillis?: number): Promise<RequestResponse>;

    removeServiceChaos(host: string): Promise<RequestResponse>;

    clearServiceChaos(): Promise<RequestResponse>;

    serviceChaosStatus(): Promise<{ services: { [host: string]: HttpChaosProfile } }>;

    bind(ports: Port[]): Promise<RequestResponse>;

    retrieveRecordedRequests(pathOrRequestDefinition: PathOrRequestDefinition): Promise<HttpResponse[]>;

    retrieveRecordedRequestsAndResponses(pathOrRequestDefinition: PathOrRequestDefinition): Promise<HttpRequestAndHttpResponse[]>;

    retrieveActiveExpectations(pathOrRequestDefinition: PathOrRequestDefinition): Promise<Expectation[]>;

    retrieveRecordedExpectations(pathOrRequestDefinition: PathOrRequestDefinition): Promise<Expectation[]>;

    retrieveLogMessages(pathOrRequestDefinition: PathOrRequestDefinition): Promise<string[]>;

    /**
     * Register a breakpoint matcher with per-phase handlers.
     * The callback WebSocket is opened lazily on the first call and reused.
     *
     * @param requestMatcher  the request definition to match (same shape as an expectation matcher)
     * @param phases          array of phases: "REQUEST", "RESPONSE", "RESPONSE_STREAM", "INBOUND_STREAM"
     * @param requestHandler  handler for REQUEST phase: (request) => request (continue/modify) or response (abort)
     * @param responseHandler handler for RESPONSE phase: (request, response) => response
     * @param streamFrameHandler handler for streaming phases: (pausedFrame) => {correlationId, action, body?}
     * @return promise resolving to the server-assigned breakpoint matcher id (string)
     */
    addBreakpoint(
        requestMatcher: RequestDefinition,
        phases: string[],
        requestHandler?: ((request: HttpRequest) => HttpRequest | HttpResponse) | null,
        responseHandler?: ((request: HttpRequest, response: HttpResponse) => HttpResponse) | null,
        streamFrameHandler?: ((pausedFrame: any) => any) | null,
    ): Promise<string>;

    /**
     * Register a request-only breakpoint (convenience).
     */
    addRequestBreakpoint(
        requestMatcher: RequestDefinition,
        requestHandler: (request: HttpRequest) => HttpRequest | HttpResponse,
    ): Promise<string>;

    /**
     * Register a request+response breakpoint (convenience).
     */
    addRequestAndResponseBreakpoint(
        requestMatcher: RequestDefinition,
        requestHandler: (request: HttpRequest) => HttpRequest | HttpResponse,
        responseHandler: (request: HttpRequest, response: HttpResponse) => HttpResponse,
    ): Promise<string>;

    /**
     * List all registered breakpoint matchers.
     */
    listBreakpointMatchers(): Promise<{ matchers: any[] }>;

    /**
     * Remove a breakpoint matcher by id.
     */
    removeBreakpointMatcher(breakpointId: string): Promise<RequestResponse>;

    /**
     * Clear all registered breakpoint matchers.
     */
    clearBreakpointMatchers(): Promise<RequestResponse>;
}

/**
 * Start the client communicating at the specified host and port
 * for example:
 *
 *   var client = mockServerClient("localhost", 1080);
 *
 * @param host {string} the host for the server to communicate with
 * @param port {number} the port for the server to communicate with
 * @param contextPath {string} the context path if server was deployed as a war
 * @param tls {boolean} enable TLS (i.e. HTTPS) for communication to server
 * @param caCertPemFilePath {string} provide custom CA Certificate (defaults to MockServer CA Certificate)
 */
export declare function mockServerClient(
    host: Host,
    port: Port,
    contextPath?: ContextPath,
    tls?: TLS,
    caCertPemFilePath?: CaCertPemFilePath
): MockServerClient;

