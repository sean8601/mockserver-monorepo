package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BodyWithContentType;
import org.mockserver.model.FileBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @author jamesdbloom
 */
public class HttpResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private VelocityTemplateEngine velocityTemplateEngine;
    private MustacheTemplateEngine mustacheTemplateEngine;

    public HttpResponseActionHandler(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    public HttpResponse handle(HttpResponse httpResponse) {
        return handle(httpResponse, null);
    }

    public HttpResponse handle(HttpResponse httpResponse, HttpRequest httpRequest) {
        HttpResponse response = httpResponse.clone();
        if (httpRequest != null) {
            BodyWithContentType body = response.getBody();
            if (body instanceof FileBody && ((FileBody) body).getTemplateType() != null) {
                response.withBody(renderTemplatedFileBody((FileBody) body, httpRequest));
            }
        }
        return response;
    }

    /**
     * Reads the file referenced by a {@link FileBody} and renders its contents through the configured
     * template engine against the request, so an externally stored response body can contain template
     * placeholders. The content type declared on the FileBody (when any) is preserved on the result.
     */
    private BodyWithContentType renderTemplatedFileBody(FileBody fileBody, HttpRequest httpRequest) {
        TemplateEngine templateEngine;
        switch (fileBody.getTemplateType()) {
            case VELOCITY:
                templateEngine = getVelocityTemplateEngine();
                break;
            case MUSTACHE:
                templateEngine = getMustacheTemplateEngine();
                break;
            default:
                // JavaScript is not supported for file body templating (see TemplateEngine.renderTemplate);
                // fall back to the raw file contents rather than failing the response.
                return fileBody;
        }
        String fileTemplate = FileReader.readFileFromClassPathOrPath(fileBody.getFilePath());
        String rendered = templateEngine.renderTemplate(fileTemplate, httpRequest);
        String contentType = fileBody.getContentType();
        return isNotBlank(contentType)
            ? new StringBody(rendered, MediaType.parse(contentType))
            : new StringBody(rendered);
    }

    private VelocityTemplateEngine getVelocityTemplateEngine() {
        if (velocityTemplateEngine == null) {
            velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        }
        return velocityTemplateEngine;
    }

    private MustacheTemplateEngine getMustacheTemplateEngine() {
        if (mustacheTemplateEngine == null) {
            mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
        }
        return mustacheTemplateEngine;
    }
}
