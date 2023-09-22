package org.yamcs.http.api;

import com.windhoverlabs.yamcs.udp.api.AbstractUDPApi;
import com.windhoverlabs.yamcs.udp.api.StartUDPRequest;
import com.windhoverlabs.yamcs.udp.api.StopUDPRequest;
import com.windhoverlabs.yamcs.udp.api.UDPConfig;
import org.yamcs.AbstractYamcsService;
import org.yamcs.YamcsServer;
import org.yamcs.api.Observer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.http.Context;

public class UDPApi extends AbstractUDPApi<Context> {

  private EventProducer eventProducer =
      EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(), 10000);
  ;

  @Override
  public void startUDP(Context ctx, StartUDPRequest request, Observer<UDPConfig> observer) {
    // TODO Auto-generated method stub
    org.yamcs.AbstractYamcsService l =
        (AbstractYamcsService)
            YamcsServer.getServer()
                .getInstance(request.getInstance())
                .getService(request.getLinkName());

    if (l == null) {
      eventProducer.sendInfo("Link:" + request.getLinkName() + " does not exist");
      observer.complete();
      return;
    }
    ((com.windhoverlabs.yamcs.tctm.UdpStreamOutProvider) l).startReceving();
    observer.complete(UDPConfig.newBuilder().build());
  }

  @Override
  public void stopUDP(Context ctx, StopUDPRequest request, Observer<UDPConfig> observer) {
    // TODO Auto-generated method stub
    org.yamcs.AbstractYamcsService l =
        (AbstractYamcsService)
            YamcsServer.getServer()
                .getInstance(request.getInstance())
                .getService(request.getLinkName());

    if (l == null) {
      eventProducer.sendInfo("Link:" + request.getLinkName() + " does not exist");
      observer.complete();
      return;
    }
    ((com.windhoverlabs.yamcs.tctm.UdpStreamOutProvider) l).stopReceving();
    observer.complete(UDPConfig.newBuilder().build());
  }
}
