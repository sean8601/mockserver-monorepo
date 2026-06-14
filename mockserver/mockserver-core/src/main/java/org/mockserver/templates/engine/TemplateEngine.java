package org.mockserver.templates.engine;

import org.mockserver.serialization.model.DTO;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * @author jamesdbloom
 */
public interface TemplateEngine {

    <T> T executeTemplate(String template, HttpRequest httpRequest, Class<? extends DTO<T>> dtoClass);

    <T> T executeTemplate(String template, HttpRequest httpRequest, HttpResponse httpResponse, Class<? extends DTO<T>> dtoClass);

    /**
     * Renders a template against the request and returns the raw rendered text, without attempting to
     * deserialize it into an {@link org.mockserver.model.HttpResponse} / {@link HttpRequest}. Used to
     * template the contents of a {@link org.mockserver.model.FileBody} so an externally stored response
     * body can contain template placeholders. Supported by the text-based engines (Velocity, Mustache).
     */
    String renderTemplate(String template, HttpRequest httpRequest);

}
