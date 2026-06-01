package org.mockserver.llm;

/**
 * The wire format used by a provider for streaming responses.
 * <ul>
 *   <li>{@link #SSE} — Server-Sent Events ({@code text/event-stream}): each chunk
 *       is emitted as {@code data: <payload>\n\n} with optional {@code event:},
 *       {@code id:}, and {@code retry:} fields.</li>
 *   <li>{@link #NDJSON} — Newline-Delimited JSON ({@code application/x-ndjson}):
 *       each chunk is a single JSON object followed by a newline character
 *       ({@code <json>\n}). Used by Ollama.</li>
 * </ul>
 */
public enum StreamingFormat {
    SSE,
    NDJSON
}
