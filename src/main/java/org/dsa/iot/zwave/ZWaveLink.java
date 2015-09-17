package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.*;
import org.dsa.iot.dslink.node.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.zwave4j.*;
import jssc.SerialNativeInterface;
import jssc.SerialPortList;

import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by Peter Weise on 8/12/15.
 */

public class ZWaveLink {

	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(ZWaveLink.class);
	}

	private Node node;
    private boolean locked = false;

    //constructor, initialize "node"
	private ZWaveLink(Node node) {
		this.node = node;
	}

    //create a new ZWaveLink object and initialize
	public static void start(Node parent) {
		ZWaveLink zwave = new ZWaveLink(parent);
		zwave.init();
	}

    //load native library, build action for loading path and comm port
	private void init() {
        node.clearChildren(); //used for testing purposes

        NativeLibraryLoader.loadLibrary(ZWave4j.LIBRARY_NAME, ZWave4j.class);
        LOGGER.info("Native library loaded");
        restoreLastSession();

        Action act = connAction();
        node.createChild("Add Connection").setAction(act).build().setSerializable(false);
	}

    //reload the nodes and objects used during the last application execution
    private void restoreLastSession() {
        Map<String, Node> children = node.getChildren();
        if (children == null /*|| children.size() == 1*/) return;
        for (Node child: children.values()) {
            if (child.getAttribute("comm port id") != null) {
                options();
                ZWaveConn conn = new ZWaveConn(this, child);
                conn.start();
            } else if (child.getAction() == null) {
                node.removeChild(child);
            }
        }
    }

    //create action tree for setting the comm port
    private Action connAction() {
        Action act = new Action(Permission.READ, new AddConnHandler());
        act.addParameter(new Parameter("Name", ValueType.STRING, new Value("USB Port")));//).setPlaceHolder("USB Port"));
        Set<String> ports = findPorts();
        act.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(ports), new Value("/dev/cu.SLAB_USBtoUART")));
        return act;
    }

    //set and lock the Options object
    private void options() {
        URL url = this.getClass().getResource("/config");
        final String path = url.toString().replaceFirst("file:", "");
        final Options options = Options.create(path, "", "");
        options.addOptionBool("ConsoleOutput", false);
        options.lock();
        locked = true;
        LOGGER.info("Options locked");
    }

    //receive the comm port value and start a connection with devices
    private class AddConnHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            if (!locked) { options(); }
            String name = event.getParameter("Name", ValueType.STRING).getString();
            if (name.isEmpty()) {
                LOGGER.warn("Missing parameter - Name is required");
                return;
            }
            if (node.hasChild(name)) {
                LOGGER.warn("Connection with that name already exists");
                return;
            }
            String commPort = event.getParameter("Comm Port ID").getString();
            NodeBuilder b = node.createChild(name);
            b.setDisplayName(name);
            b.setAttribute("comm port id", new Value(commPort));
            Node child = b.build();
            ZWaveConn conn = new ZWaveConn(ZWaveLink.this, child);
            conn.start();
        }
    }

    //remove the controller node (called from ZWaveConn)
    protected void stop(Node child) {
        child.clearChildren();
        node.removeChild(child);
        LOGGER.info("Disconnected from comm port");
    }

    //find comm ports and return them for listing in the action tree
    protected Set<String> findPorts() {
        String[] portNames;

        switch(SerialNativeInterface.getOsType()){
            case SerialNativeInterface.OS_LINUX:
                portNames = SerialPortList.getPortNames
                        (Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
                break;
            case SerialNativeInterface.OS_MAC_OS_X:
                portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*"));
                break;
            default:
                portNames = SerialPortList.getPortNames();
                break;
        }
        Set<String> ports = new HashSet<>();

        Collections.addAll(ports, portNames);
        return ports;
    }
}
