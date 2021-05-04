package com.windhoverlabs.yamcs.studio;

import org.yamcs.api.Observer;
import org.yamcs.http.Context;

public class HelloWorldApi extends AbstractHelloWorldApi<Context> {

	@Override
	public void helloWorld(Context ctx, HelloWorldRequest request, Observer observer) {
		// TODO Auto-generated method stub
		
		System.out.println("Hello World from server...");
		
	}

}
