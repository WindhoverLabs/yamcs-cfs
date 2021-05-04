package com.windhoverlabs.yamcs.studio;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.yamcs.api.HttpBody;
import org.yamcs.api.Observer;
import org.yamcs.http.Context;

import com.google.protobuf.ByteString;
import com.google.protobuf.ByteString.Output;

public class HelloWorldApi extends AbstractHelloWorldApi<Context> {

	@Override
	public void helloWorld(Context ctx, HelloWorldResponse request, Observer<HelloWorldResponse> observer) {
		// TODO Auto-generated method stub
		
		System.out.println("Hello World from server...");
		
        try (Output out = ByteString.newOutput(); Writer writer = new OutputStreamWriter(out)) {
            writer.close();
            
            HelloWorldResponse.Builder hwResponse = HelloWorldResponse.newBuilder();
            
            ListHelloInfo.Builder listHWInfo = ListHelloInfo.newBuilder();
            
            HelloInfo.Builder helloInfo = HelloInfo.newBuilder();
            
            helloInfo.setJob("Software Engineer");
            helloInfo.setName("Lorenzo Gomez"); 
            helloInfo.setAge(25);
            
            listHWInfo.addInfo(helloInfo);
            
            hwResponse.addHelloInfo(listHWInfo);
            
            HttpBody body = HttpBody.newBuilder()
                    .setContentType("1")
                    .setData(out.toByteString())
                    .build();
            
            observer.complete(hwResponse.build());
        } catch (IOException e) {
            observer.completeExceptionally(e);
        }
        
	}


}
