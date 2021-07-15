package com.windhoverlabs.yamcs.cfs.registry;

import static org.yamcs.xtce.NameDescription.qualifiedName;
import static org.yamcs.xtce.XtceDb.YAMCS_SPACESYSTEM_NAME;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.PluginManager;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

import com.windhoverlabs.yamcs.util.CfsPlugin;

public class CfsRegistryService extends AbstractYamcsService implements SystemParametersProducer {

    private Parameter workspaceLinkHKParam;
    private AggregateParameterType spWorkspaceHKType; // Housekeeping info for the workspace.
    private XtceDb mdb;

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
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

        AggregateValue serialTmFrameLinkAggregateV = new AggregateValue(spWorkspaceHKType.getMemberNames());

        serialTmFrameLinkAggregateV.setMemberValue("registryDir", ValueUtility
                .getStringValue(plugin.getWorkspaceDir()));

        ParameterValue serialTmFrameLinkPV = new ParameterValue(workspaceLinkHKParam);

        serialTmFrameLinkPV.setGenerationTime(gentime);
        serialTmFrameLinkPV.setEngValue(serialTmFrameLinkAggregateV);

        pvlist.add(serialTmFrameLinkPV);
        return pvlist;
    }

    void setupSystemParameters() {
        mdb = YamcsServer.getServer().getInstance(yamcsInstance).getXtceDb();
        SystemParametersService collector = SystemParametersService.getInstance(yamcsInstance);
        if (collector != null) {
            makeParameterStatus();

            spWorkspaceHKType = new AggregateParameterType.Builder().setName("Workspace_HK")
                    .addMember(new Member("registryDir", collector.getBasicType(Type.STRING)))
                    .build();

            workspaceLinkHKParam = mdb.createSystemParameter(
                    qualifiedName(YAMCS_SPACESYSTEM_NAME, "Registry/Workspace"),
                    spWorkspaceHKType,
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
