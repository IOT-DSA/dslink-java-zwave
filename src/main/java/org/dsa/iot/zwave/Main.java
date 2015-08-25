package org.dsa.iot.zwave;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.zwave.log.Log4jBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Peter Weise on 8/12/15.
 */

public class Main extends DSLinkHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	//initialize logging and start the link factory
	public static void main(String[] args) {
		Log4jBridge.init();
		DSLinkFactory.start(args, new Main());
	}

	//set as a responder
	@Override
	public boolean isResponder() {
		return true;
	}

	//load the node and pass to ZWaveLink
	@Override
	public void onResponderConnected(DSLink link) {
		LOGGER.info("Connected");
		NodeManager manager = link.getNodeManager();
		Serializer ser = new Serializer(manager);
		Deserializer deser = new Deserializer(manager);
		Node superRoot = manager.getNode("/").getNode();
		ZWaveLink.start(superRoot, ser, deser);
	}
}
