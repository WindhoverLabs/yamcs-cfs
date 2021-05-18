package com.windhoverlabs.yamcs.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.Spec.OptionType;
import org.yamcs.http.HttpServer;
import org.yamcs.logging.Log;

import org.yamcs.http.api.CfsApi;

public class CfsPlugin implements Plugin {
    private static final Log log = new Log(CfsPlugin.class);
    private String registry;
    
    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("registry", OptionType.STRING);
        return spec;
    }

    @Override
    public void onLoad(YConfiguration config) throws PluginException {
        YamcsServer yamcs = YamcsServer.getServer();
        registry = config.getString("registry");

        List<HttpServer> httpServers = yamcs.getGlobalServices(HttpServer.class);
        if (httpServers.isEmpty()) {
            log.warn("Yamcs does not appear to be running an HTTP Server.");
            return;
        }

        HttpServer httpServer = httpServers.get(0);
        // Add API to server
        try (InputStream in = getClass().getResourceAsStream("/yamcs-cfs.protobin")) {
            httpServer.getProtobufRegistry().importDefinitions(in);
        } catch (IOException e) {
            throw new PluginException(e);
        }

        httpServer.addApi(new CfsApi());

    }
    
    public String getRegistry() 
    {
        return registry;
    }


}
