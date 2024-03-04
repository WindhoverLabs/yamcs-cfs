package com.windhoverlabs.beb;

import static com.windhoverlabs.beb.BebPlugin.CONFIG_SECTION;

import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.http.Context;
import org.yamcs.http.NotFoundException;
import org.yamcs.web.api.AbstractWebApi;
import org.yamcs.web.api.GetInstanceConfigurationRequest;
import org.yamcs.web.api.InstanceConfiguration;

/** Extension routes to Yamcs HTTP API for use by the Web UI only. */
public class BebApi extends AbstractWebApi<Context> {

  /** Get instance-level Web UI configuration options. */
  @Override
  public void getInstanceConfiguration(
      Context ctx,
      GetInstanceConfigurationRequest request,
      Observer<InstanceConfiguration> observer) {
    YamcsServer yamcs = YamcsServer.getServer();
    YamcsServerInstance yamcsInstance = yamcs.getInstance(request.getInstance());
    if (yamcsInstance == null) {
      throw new NotFoundException("No such instance");
    }

    YConfiguration globalConfig = yamcs.getConfig().getConfigOrEmpty(CONFIG_SECTION);
    YConfiguration instanceConfig = yamcsInstance.getConfig().getConfigOrEmpty(CONFIG_SECTION);

    org.yamcs.web.api.InstanceConfiguration.Builder b = InstanceConfiguration.newBuilder();
    //
    //        String displayBucket = globalConfig.getString(CONFIG_DISPLAY_BUCKET);
    //        if (instanceConfig.containsKey(CONFIG_DISPLAY_BUCKET)) {
    //            displayBucket = instanceConfig.getString(CONFIG_DISPLAY_BUCKET);
    //        }
    //        b.setDisplayBucket(displayBucket);
    //
    //        var stackBucket = globalConfig.getString(CONFIG_STACK_BUCKET);
    //        if (instanceConfig.containsKey(CONFIG_STACK_BUCKET)) {
    //            stackBucket = instanceConfig.getString(CONFIG_STACK_BUCKET);
    //        }
    //        b.setStackBucket(stackBucket);

    observer.next(b.build());
  }
}
