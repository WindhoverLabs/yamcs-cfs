package com.windhoverlabs.yamcs.studio;

import org.yamcs.api.Observer;
import org.yamcs.http.Context;



public class HelloWorldApi extends AbstractHelloWorldApi<Context> {

	@Override
	public void helloWorld(Context ctx, HelloWorldResponse request, Observer<HelloWorldResponse> observer) {
		// TODO Auto-generated method stub
		
		System.out.println("Hello World from server...");
		
        try  {            
            HelloWorldResponse.Builder hwResponse = HelloWorldResponse.newBuilder();
            
            ListHelloInfo.Builder listHWInfo = ListHelloInfo.newBuilder();
            
            HelloInfo.Builder helloInfo = HelloInfo.newBuilder();
            
            helloInfo.setJob("Software Engineer");
            helloInfo.setName("Lorenzo Gomez"); 
            helloInfo.setAge(25);
            
            listHWInfo.addInfo(helloInfo);
            
            hwResponse.addHelloInfo(listHWInfo);
            
            observer.complete(hwResponse.build());
        } catch (Exception e) {
            observer.completeExceptionally(e);
        }
        
	}


}
