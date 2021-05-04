package com.windhoverlabs.yamcs.studio;

import org.yamcs.api.Observer;
import org.yamcs.http.Context;
import org.yamcs.http.api.ManagementApi;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;


public class HelloWorldApi extends AbstractHelloWorldApi<Context> {

	@Override
	public void helloWorld(Context ctx, HelloWorldResponse request, Observer<HelloWorldResponse> observer) {
		// TODO Auto-generated method stub
		
		System.out.println("Hello World from server...");
		
        try  {            
            HelloWorldResponse.Builder hwResponse = HelloWorldResponse.newBuilder();
            
            ListHelloInfo.Builder listHWInfo = ListHelloInfo.newBuilder();
            
            HelloInfo.Builder helloInfo = HelloInfo.newBuilder();
            
            //Make an object to send back to the client
            helloInfo.setJob("Software Engineer");
            helloInfo.setName("Lorenzo Gomez"); 
            helloInfo.setAge(25);
            
            listHWInfo.addInfo(helloInfo);
            
            hwResponse.addHelloInfo(listHWInfo);
            
            System.out.println(request.getInstance());
            
            
            //Query the database
            String instance = ManagementApi.verifyInstance(request.getInstance());
            XtceDb mdb = XtceDbFactory.getInstance(instance);
            
            System.out.println("success...");
            
//            System.out.println("telem from mdb:" + mdb.getParameter("/cfs//cfe_es/CFE_ES_HkPacket_t.Payload.SysLogBytesUsed"));
            
            System.out.println("telem from mdb2:" +  mdb.getParameters().iterator().next().getName());
            
            //Send response to client
            observer.complete(hwResponse.build());
        } catch (Exception e) {
            observer.completeExceptionally(e);
        }
        
	}
	
	
}
