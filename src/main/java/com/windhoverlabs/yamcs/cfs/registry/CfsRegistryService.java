package com.windhoverlabs.yamcs.cfs.registry;

import static org.yamcs.parameter.SystemParametersService.getPV;
import static org.yamcs.xtce.NameDescription.qualifiedName;
import static org.yamcs.xtce.XtceDb.YAMCS_SPACESYSTEM_NAME;

import com.windhoverlabs.yamcs.util.CfsPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.PluginManager;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.mdb.Mdb;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

public class CfsRegistryService extends AbstractYamcsService implements SystemParametersProducer {

  private Parameter workspaceDirParam;
  //  private AggregateParameterType spWorkspaceHKType; // Housekeeping info for the workspace.
  // Can't be as easily done after Version 5.8.8. Need to write types to a writeable namespace.
  // https://github.com/yamcs/yamcs/commit/7abba0a93013e8b4ec1020be3df592191614da33
  //  private AggregateParameterType spWorkspaceHKType; // Housekeeping info for the workspace.
  private XtceDb mdb;

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config)
      throws InitException {
    super.init(yamcsInstance, serviceName, config);
  }

  @Override
  protected void doStart() {
    setupSystemParameters();
    notifyStarted();
  }

  @Override
  public Collection<ParameterValue> getSystemParameters(long gentime) {
    List<ParameterValue> pvlist = new ArrayList<>();

    PluginManager pluginManager = YamcsServer.getServer().getPluginManager();
    CfsPlugin plugin = pluginManager.getPlugin(CfsPlugin.class);

    pvlist.add(getPV(workspaceDirParam, gentime, plugin.getWorkspaceDir()));
    return pvlist;
  }

  void setupSystemParameters() {
    mdb = YamcsServer.getServer().getInstance(yamcsInstance).getMdb();
    SystemParametersService collector = SystemParametersService.getInstance(yamcsInstance);
    if (collector != null) {
      makeParameterStatus();

      workspaceDirParam =
          ((Mdb) mdb)
              .createSystemParameter(
                  qualifiedName(YAMCS_SPACESYSTEM_NAME, "Registry/Workspace"),
                  collector.getBasicType(Type.STRING),
                  "Current configuration of registry.");

      collector.registerProducer(this);
    }
  }

  private void makeParameterStatus() {
    // TODO Implement

  }

  @Override
  protected void doStop() {
    notifyStopped();
  }
}
