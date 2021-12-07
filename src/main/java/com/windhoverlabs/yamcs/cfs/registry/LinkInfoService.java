package com.windhoverlabs.yamcs.cfs.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.Spec.OptionType;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.Link;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.Parameter;

/**
 * Extra info about such links as ip addresses and ports.
 * 
 * @author lgomez
 *
 */
public class LinkInfoService extends AbstractYamcsService implements SystemParametersProducer {

	private Parameter linkHost;
	private List<String> linkNames;

	@Override
	public Spec getSpec() {
		Spec spec = new Spec();
		spec.addOption("links", OptionType.LIST).withElementType(OptionType.STRING);
		return spec;
	}

	@Override
	public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
		super.init(yamcsInstance, serviceName, config);
		EventProducer eventProducer = EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(),
				10000);

		if (!config.toMap().containsKey("links")) {
			eventProducer.sendWarning("No Links provided for LinkInfoService.");
			return;
		}
		linkNames = config.getList("links");

		for (String name : linkNames) {

			Link link = YamcsServer.getServer().getInstance(yamcsInstance).getLinkManager().getLink(name);
			if (link == null) {
				eventProducer.sendWarning("Link" + "\"" + name + "\"" + " does not exist.");
			}
		}
	}

	@Override
	protected void doStart() {
		setupSystemParameters();
		notifyStarted();
	}

	@Override
	public Collection<ParameterValue> getSystemParameters(long gentime) {
		List<ParameterValue> pvlist = new ArrayList<>();

		for (String linkName : linkNames) {
			Link link = YamcsServer.getServer().getInstance(yamcsInstance).getLinkManager().getLink(linkName);

			Value host = ValueUtility.getStringValue(link.getConfig().getString("host", null));

			ParameterValue hostLinkPV = new ParameterValue(linkHost);
			hostLinkPV.setGenerationTime(gentime);
			hostLinkPV.setEngValue(host);

			pvlist.add(hostLinkPV);
		}
		return pvlist;
	}

	void setupSystemParameters() {
		SystemParametersService collector = SystemParametersService.getInstance(yamcsInstance);
		if (collector != null) {
			makeParameterStatus();
			
			for (String linkName : linkNames) {
				Link link = YamcsServer.getServer().getInstance(yamcsInstance).getLinkManager().getLink(linkName);
				if (link != null) {
					linkHost = collector.createSystemParameter(linkName + "/host", Type.STRING, "");
				} else {
					linkHost = collector.createSystemParameter(linkName + "/host", Type.STRING, "");
				}
			}

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
