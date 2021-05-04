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
	public void helloWorld(Context ctx, HelloWorldRequest request, Observer<HttpBody> observer) {
		// TODO Auto-generated method stub
		
		System.out.println("Hello World from server...");
		
        try (Output out = ByteString.newOutput(); Writer writer = new OutputStreamWriter(out)) {
            writer.close();

            HttpBody body = HttpBody.newBuilder()
                    .setContentType("1")
                    .setData(out.toByteString())
                    .build();
            observer.complete(body);
        } catch (IOException e) {
            observer.completeExceptionally(e);
        }
	}


}
