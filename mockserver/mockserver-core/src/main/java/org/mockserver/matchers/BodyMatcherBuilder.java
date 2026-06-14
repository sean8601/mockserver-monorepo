package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;

import static org.mockserver.matchers.NotMatcher.notMatcher;

/**
 * Builds a {@link BodyMatcher} for a given {@link Body} template.
 * <p>
 * Extracted from {@link HttpRequestPropertiesMatcher} so the same body-matching
 * logic can be reused for response verification without duplication.
 */
public class BodyMatcherBuilder {

    public static BodyMatcher buildBodyMatcher(Configuration configuration, MockServerLogger mockServerLogger, Body body, boolean controlPlaneMatcher) {
        BodyMatcher bodyMatcher = null;
        if (body != null) {
            switch (body.getType()) {
                case STRING:
                    StringBody stringBody = (StringBody) body;
                    if (stringBody.isSubString()) {
                        bodyMatcher = new SubStringMatcher(mockServerLogger, NottableString.string(stringBody.getValue()));
                    } else {
                        bodyMatcher = new ExactStringMatcher(mockServerLogger, NottableString.string(stringBody.getValue()));
                    }
                    break;
                case REGEX:
                    RegexBody regexBody = (RegexBody) body;
                    bodyMatcher = new RegexStringMatcher(mockServerLogger, NottableString.string(regexBody.getValue()), controlPlaneMatcher);
                    break;
                case PARAMETERS:
                    ParameterBody parameterBody = (ParameterBody) body;
                    bodyMatcher = new ParameterStringMatcher(configuration, mockServerLogger, parameterBody.getValue(), controlPlaneMatcher);
                    break;
                case XPATH:
                    XPathBody xPathBody = (XPathBody) body;
                    bodyMatcher = new XPathMatcher(mockServerLogger, xPathBody.getValue(), xPathBody.getNamespacePrefixes());
                    break;
                case XML:
                    XmlBody xmlBody = (XmlBody) body;
                    bodyMatcher = new XmlStringMatcher(mockServerLogger, xmlBody.getValue());
                    break;
                case JSON:
                    JsonBody jsonBody = (JsonBody) body;
                    bodyMatcher = new JsonStringMatcher(mockServerLogger, jsonBody.getValue(), jsonBody.getMatchType(), jsonBody.isMatchNumbersAsStrings());
                    break;
                case JSON_SCHEMA:
                    JsonSchemaBody jsonSchemaBody = (JsonSchemaBody) body;
                    bodyMatcher = new JsonSchemaMatcher(mockServerLogger, jsonSchemaBody.getValue()).withParameterStyle(jsonSchemaBody.getParameterStyles());
                    break;
                case JSON_PATH:
                    JsonPathBody jsonPathBody = (JsonPathBody) body;
                    bodyMatcher = new JsonPathMatcher(mockServerLogger, jsonPathBody.getValue());
                    break;
                case XML_SCHEMA:
                    XmlSchemaBody xmlSchemaBody = (XmlSchemaBody) body;
                    bodyMatcher = new XmlSchemaMatcher(mockServerLogger, xmlSchemaBody.getValue(), xmlSchemaBody.getSourceUri());
                    break;
                case JSON_RPC:
                    JsonRpcBody jsonRpcBody = (JsonRpcBody) body;
                    bodyMatcher = new JsonRpcMatcher(mockServerLogger, jsonRpcBody.getMethod(), jsonRpcBody.getParamsSchema());
                    break;
                case GRAPHQL:
                    GraphQLBody graphQLBody = (GraphQLBody) body;
                    bodyMatcher = new GraphQLMatcher(mockServerLogger, graphQLBody);
                    break;
                case BINARY:
                    BinaryBody binaryBody = (BinaryBody) body;
                    bodyMatcher = new BinaryMatcher(mockServerLogger, binaryBody.getValue());
                    break;
                case WASM:
                    WasmBody wasmBody = (WasmBody) body;
                    bodyMatcher = new WasmBodyMatcher(wasmBody.getModuleName());
                    break;
            }
            if (body.isNot()) {
                //noinspection ConstantConditions
                bodyMatcher = notMatcher(bodyMatcher);
            }
        }
        return bodyMatcher;
    }

}
