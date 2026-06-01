package org.mockserver.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.proxy.TransparentProxyHandler;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.socket.tls.NettySslContextFactory;

@ChannelHandler.Sharable
public class MockServerUnificationInitializer extends ChannelHandlerAdapter {
    private final Configuration configuration;
    private final LifeCycle server;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final NettySslContextFactory nettySslContextFactory;
    private final McpSessionManager mcpSessionManager;

    public MockServerUnificationInitializer(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.nettySslContextFactory = nettySslContextFactory;
        this.mcpSessionManager = new McpSessionManager(httpState.getMockServerLogger());
    }

    public McpSessionManager getMcpSessionManager() {
        return mcpSessionManager;
    }

    public HttpActionHandler getActionHandler() {
        return actionHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // When transparent proxy mode is enabled, add the handler that resolves
        // the original destination (via conntrack on Linux) before any HTTP
        // processing occurs. This must be first in the pipeline so it fires on
        // channelActive before data is read.
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            ctx.pipeline().addLast("transparent-proxy", new TransparentProxyHandler(configuration, httpState.getMockServerLogger()));
        }
        ctx.pipeline().replace(this, null, new PortUnificationHandler(configuration, server, httpState, actionHandler, nettySslContextFactory, mcpSessionManager));
    }
}
