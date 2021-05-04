package com.windhoverlabs.yamcs.studio;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.client.YamcsClient;
import org.yamcs.http.Handler;
import org.yamcs.http.HttpRequestHandler;
import org.yamcs.http.HttpServer;
import org.yamcs.logging.Log;

import org.yamcs.security.SystemPrivilege;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
//import yamcs.protobuf.studio.AbstractHelloWorldApi;

public class StudioPlugin  implements Plugin {
	

    public static final SystemPrivilege PRIV_GET_METRICS = new SystemPrivilege("Prometheus.GetMetrics");

    private static final Log log = new Log(StudioPlugin.class);

    @Override
    public void onLoad(YConfiguration config) throws PluginException {
        YamcsServer yamcs = YamcsServer.getServer();
        
        System.out.println("studio plugin loaded");

        List<HttpServer> httpServers = yamcs.getGlobalServices(HttpServer.class);
        if (httpServers.isEmpty()) {
            log.warn("Can't mount metrics endpoint. Yamcs does not appear to be running an HTTP Server.");
            return;
        }
        
//        CollectorRegistry registry = CollectorRegistry.defaultRegistry;

        HttpServer httpServer = httpServers.get(0);
//        new ApiExports(httpServer.getMetricRegistry()).register(registry);

        try (InputStream in = getClass().getResourceAsStream("/yamcs-cfs.protobin")) {
            httpServer.getProtobufRegistry().importDefinitions(in);
        } catch (IOException e) {
            throw new PluginException(e);
        }

        httpServer.addApi(new HelloWorldApi());
        
//        System.out.println("adding handler");
//        
//        Handler redirectHandler = new RedirectHandler();
//        httpServer.addHandler("hello_world", () -> redirectHandler);
    }
    
    @Sharable
    private static final class RedirectHandler extends Handler {
        @Override
        public void handle(ChannelHandlerContext ctx, FullHttpRequest req) {
        	System.out.println("executing handle function");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK);
//            QueryStringDecoder qs = new QueryStringDecoder(req.uri());
//            String location = qs.rawPath().replaceFirst("metrics", "api/prometheus/metrics");
//            String q = qs.rawQuery();
//            if (!q.isEmpty()) {
//                location += "?" + q;
//            }
//            response.headers().add(HttpHeaderNames.LOCATION, location);
            HttpRequestHandler.sendResponse(ctx, req, response);
        }
    }
}