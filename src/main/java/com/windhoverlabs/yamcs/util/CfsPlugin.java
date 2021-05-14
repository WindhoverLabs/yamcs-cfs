package com.windhoverlabs.yamcs.util;


import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.http.HttpServer;
import org.yamcs.logging.Log;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.XtceDb;

import org.yamcs.http.api.CfsUtilApi;

public class CfsPlugin implements Plugin {
    private static final Log log = new Log(CfsPlugin.class);
    private XtceDb mdb;
    private SystemParameter spSchTable;
    public static final SystemPrivilege PRIV_GET_METRICS = new SystemPrivilege("Prometheus.GetMetrics");
    
    @Override
    public void onLoad(YConfiguration config) throws PluginException {
        YamcsServer yamcs = YamcsServer.getServer();
        
        System.out.println("CfsUtil plugin loaded");
        
//        mdb = yamcs.getInstance("yamcs-cfs").getXtceDb();
        
//        System.out.println("sch diag-->" + mdb.getParameter("/cfs//sch/SCH_DiagPacket_t"));
        
        List<HttpServer> httpServers = yamcs.getGlobalServices(HttpServer.class);
        if (httpServers.isEmpty()) {    
            log.warn("Yamcs does not appear to be running an HTTP Server.");
            return;
        }
        
        HttpServer httpServer = httpServers.get(0);
      //Add API to server
        try (InputStream in = getClass().getResourceAsStream("/yamcs-cfs.protobin")) {
            httpServer.getProtobufRegistry().importDefinitions(in);
        } catch (IOException e) {
            throw new PluginException(e);
        }
        
        httpServer.addApi(new CfsUtilApi());
        
}
    
    public void collectSchEntriesForApp(int messageId, ParameterValue schDiag)
    {
        
        schDiag.getEngValue();
//       schDiag
    }

//    @Override
    public Collection<ParameterValue> getSystemParameters() {
        // TODO Auto-generated method stub
        return null;
    }
//    public void setupSystemParameters(SystemParametersService sysParamCollector) 
//    {
//        //Not sure if I'll take this approach...
//        String relativeName = "";
//        AggregateParameterType type = new AggregateParameterType("SCH Table") ;
//        String description = "sch table";
//        sysParamCollector.createSystemParameter(relativeName, type, description);        
//        AggregateParameterType aggregateType = (AggregateParameterType) sysParamCollector.getBasicType(Type.AGGREGATE);
//        
//        
////        spSchTable = mdb.createSystemParameter(qualifiedName(YAMCS_SPACESYSTEM_NAME,  "/port"), intType,
////                "The port number this link is connected to"); 
//    }
//    
}
