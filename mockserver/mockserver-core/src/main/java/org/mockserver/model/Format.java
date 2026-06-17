package org.mockserver.model;

/**
 * @author jamesdbloom
 */
public enum Format {
    JAVA,
    // Expectation builder code for the Node.js (JavaScript/TypeScript) and
    // Python clients. Unlike JAVA (which needs a typed builder DSL), these
    // clients accept the expectation as a JSON/dict object directly, so the
    // generated code embeds the expectation's existing JSON serialization in a
    // client call. Applicable to RECORDED_EXPECTATIONS and ACTIVE_EXPECTATIONS.
    JAVASCRIPT,
    PYTHON,
    JSON,
    LOG_ENTRIES,
    HAR,
    // Export formats — applicable to ACTIVE_EXPECTATIONS retrieval. Each
    // converts a list of expectations into a third-party tooling format that
    // can be imported elsewhere (specs, request collections).
    OPENAPI,
    POSTMAN,
    BRUNO,
    // cURL command(s) that reproduce recorded requests. Applicable to REQUESTS
    // and REQUEST_RESPONSES retrieval (not to expectation scopes).
    CURL
}
