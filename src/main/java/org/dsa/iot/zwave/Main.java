package org.dsa.iot.zwave;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main extends DSLinkHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	@Override
	public boolean isResponder() {
		return true;
	}

	@Override
	public void onResponderInitialized(DSLink link) {
		NodeManager manager = link.getNodeManager();
		Node superRoot = manager.getNode("/").getNode();
		ZWaveLink.start(superRoot);
	}

	@Override
	public void onResponderConnected(DSLink link) {
		LOGGER.info("Connected");
	}

    public static void main(String[] args) {
        DSLinkFactory.start(args, new Main());
    }
}
