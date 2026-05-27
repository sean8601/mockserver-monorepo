package org.mockserver.model;

/**
 * @author jamesdbloom
 */
public enum Format {
    JAVA,
    JSON,
    LOG_ENTRIES,
    HAR,
    // Export formats — applicable to ACTIVE_EXPECTATIONS retrieval. Each
    // converts a list of expectations into a third-party tooling format that
    // can be imported elsewhere (specs, request collections).
    OPENAPI,
    POSTMAN,
    BRUNO
}
