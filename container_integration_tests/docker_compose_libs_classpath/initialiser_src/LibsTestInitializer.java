package org.mockserver.test;

import org.mockserver.mock.Expectation;
import org.mockserver.server.initialize.ExpectationInitializer;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LibsTestInitializer implements ExpectationInitializer {
    @Override
    public Expectation[] initializeExpectations() {
        return new Expectation[]{
            new Expectation(
                request("/libs-test-path")
            ).thenRespond(
                response("libs_classpath_response")
            )
        };
    }
}
