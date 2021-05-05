package com.windhoverlabs.yamcs.studio;

import org.yamcs.api.Observer;
import org.yamcs.http.Context;


public class StudioLinkApi extends AbstractStudioLinkApi<Context> {

	@Override
	public void configureUpdTcDataLink(Context ctx, configureUpdTcDataLinkResponse request, Observer<configureUpdTcDataLinkResponse> observer) {
		// TODO Auto-generated method stub		
        try  {            
        	configureUpdTcDataLinkResponse.Builder response = configureUpdTcDataLinkResponse.newBuilder();
            
        	
        	//Socket code
        	System.out.println(String.format("reconfigure udpTcLink for %s:%d", request.getIpaddress(), request.getPort()));
        	
        	//Send response to client
        	response.setPort(1);
        	response.setIpaddress("1");
            observer.complete(response.build());
        } catch (Exception e) {
            observer.completeExceptionally(e);
        }
        
	}
	
	
}
