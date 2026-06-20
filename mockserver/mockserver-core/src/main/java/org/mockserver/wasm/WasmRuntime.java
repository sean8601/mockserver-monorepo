package org.mockserver.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around a compiled chicory WASM instance.
 * <p>
 * Thread-safety: chicory {@link Instance} is NOT thread-safe, so the stored WASM
 * bytes are parsed into a {@link WasmModule} and a fresh {@link Instance} is
 * created for each invocation.
 * <p>
 * <strong>ABI.</strong> Two export shapes are supported, both returning non-zero
 * for a match:
 * <ul>
 *   <li><strong>Legacy body-only</strong> — {@code match(i32 ptr, i32 len) -> i32}.
 *       The request body is written into linear memory at offset 0 and the function
 *       is called with {@code (0, bodyLength)}.</li>
 *   <li><strong>Richer request envelope</strong> — {@code match_request(i32 ptr, i32 len) -> i32}.
 *       A JSON envelope {@code {"method","path","headers","body"}} is written into
 *       linear memory at offset 0 and the function is called with {@code (0, jsonLength)}.
 *       This lets a module read the method, path and headers in addition to the body.</li>
 * </ul>
 * If the module exports {@code match_request} it is preferred; otherwise the runtime
 * falls back to {@code match} with the body only, so existing body-only modules keep
 * working unchanged.
 * <p>
 * This class <strong>fails closed</strong>: any error returns {@code false}.
 */
public class WasmRuntime {

    /** Legacy body-only export name. */
    static final String MATCH = "match";
    /** Richer request-envelope export name. */
    static final String MATCH_REQUEST = "match_request";

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private final byte[] wasmBytes;
    private final int maxMemoryPages;

    /**
     * Create a runtime with the default memory page limit from
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmMaxMemoryPages()}.
     */
    public WasmRuntime(byte[] wasmBytes) {
        this(wasmBytes, org.mockserver.configuration.ConfigurationProperties.wasmMaxMemoryPages());
    }

    /**
     * Create a runtime with an explicit memory page limit.
     *
     * @param wasmBytes      the compiled WASM binary
     * @param maxMemoryPages maximum number of WASM linear memory pages (each page is 64 KiB)
     */
    public WasmRuntime(byte[] wasmBytes, int maxMemoryPages) {
        this.wasmBytes = wasmBytes;
        this.maxMemoryPages = maxMemoryPages;
    }

    /**
     * Call the WASM module with just the request body (legacy body-only ABI).
     * <p>
     * Retained for back-compat; equivalent to {@code callMatch(WasmRequest.ofBody(requestBody))}.
     *
     * @param requestBody the HTTP request body (may be null)
     * @return {@code true} if the module reports a match
     */
    public boolean callMatch(String requestBody) {
        return callMatch(WasmRequest.ofBody(requestBody));
    }

    /**
     * Call the WASM module with the full request envelope (method, path, headers, body).
     * <p>
     * If the module exports {@link #MATCH_REQUEST} the JSON envelope is passed and that
     * function is invoked; otherwise the runtime falls back to the legacy {@link #MATCH}
     * export with only the body, preserving back-compat for body-only modules.
     *
     * @param request the request parts to expose to the module (must not be null)
     * @return {@code true} if the module reports a match
     */
    public boolean callMatch(WasmRequest request) {
        try {
            WasmModule module = Parser.parse(wasmBytes);
            Instance.Builder builder = Instance.builder(module);

            // Cap the WASM module's linear memory at maxMemoryPages while preserving
            // the module's declared initial pages (needed for data segment initialization).
            if (module.memorySection().isPresent()
                && module.memorySection().get().memoryCount() > 0) {
                MemoryLimits declared = module.memorySection().get().getMemory(0).limits();
                int effectiveMax = Math.min(declared.maximumPages(), maxMemoryPages);
                int effectiveInit = Math.min(declared.initialPages(), effectiveMax);
                builder.withMemoryLimits(new MemoryLimits(effectiveInit, effectiveMax));
            }

            Instance instance = builder.build();

            byte[] input;
            ExportFunction matchFn = tryExport(instance, MATCH_REQUEST);
            if (matchFn != null) {
                // richer ABI: pass the full request envelope as JSON
                input = buildEnvelope(request).getBytes(StandardCharsets.UTF_8);
            } else {
                // legacy ABI: pass only the body
                matchFn = instance.export(MATCH);
                input = request.getBody() != null
                    ? request.getBody().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            }

            // Write input into the WASM module's linear memory at offset 0
            instance.memory().write(0, input);

            long[] result = matchFn.apply(0L, input.length);
            return result.length > 0 && result[0] != 0;
        } catch (Exception e) {
            // fail closed
            return false;
        }
    }

    /**
     * Resolve an exported function by name, returning {@code null} if the module does
     * not export it (chicory throws rather than returning null for a missing export).
     */
    private static ExportFunction tryExport(Instance instance, String name) {
        try {
            return instance.export(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serialise the request parts into the JSON envelope passed to {@code match_request}.
     * Shape: {@code {"method":string,"path":string,"headers":{name:[values]},"body":string|null}}.
     */
    static String buildEnvelope(WasmRequest request) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("method", request.getMethod());
        root.put("path", request.getPath());
        ObjectNode headers = root.putObject("headers");
        for (Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
            ArrayNode values = headers.putArray(entry.getKey());
            if (entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    values.add(value);
                }
            }
        }
        if (request.getBody() == null) {
            root.putNull("body");
        } else {
            root.put("body", request.getBody());
        }
        return root.toString();
    }
}
