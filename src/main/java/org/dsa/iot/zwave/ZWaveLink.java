package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.*;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.zwave4j.*;
import jssc.SerialNativeInterface;
import jssc.SerialPortList;
import java.util.HashSet;
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

	private static Node node;
    private String path;

    //constructor, initialize "node"
	private ZWaveLink(Node node) {
		this.node = node;
	}

    //create a new ZWaveLink object and initialize
	public static void start(Node parent) {
		ZWaveLink zwave = new ZWaveLink(parent);
        //node.clearChildren();
		zwave.init();
	}

    //load native library, build action for loading path and comm port
	private void init() {
        NativeLibraryLoader.loadLibrary(ZWave4j.LIBRARY_NAME, ZWave4j.class);

        restoreLastSession();

        Action act = getManagerAction();
        node.createChild("add connection").setAction(act).build().setSerializable(false);
	}

    //reload the nodes and objects used during the last application execution
    private void restoreLastSession() {
        if (node.getChildren() == null) return;
        for (Node child: node.getChildren().values()) {
            if (child.getAttribute("path to openZWave") != null) {
                path = child.getAttribute("path to openZWave").getString();
                options();
                ZWaveConn conn = new ZWaveConn(getMe(), child);
                conn.start();
            } else if (child.getAction() == null) {
                node.removeChild(child);
            }
        }
    }

    //create action tree for setting the comm port
    private Action getManagerAction() {
        Action act = new Action(Permission.READ, new AddConnHandler());
        act.addParameter(new Parameter("path to openZWave", ValueType.STRING, new Value("/Users/DGLogik-Git/open-zwave-master/config")));
        act.addParameter(new Parameter("name", ValueType.STRING, new Value("USB Port")));
        Set<String> ports = findPorts();
        act.addParameter(new Parameter("comm port id", ValueType.makeEnum(ports), new Value("/dev/cu.SLAB_USBtoUART")));
        return act;
    }

    //set and lock the Options object
    private void options() {
        final Options options = Options.create(path, "", "");
        options.addOptionBool("ConsoleOutput", false);
        options.lock();
    }

    //receive the comm port value and start a connection with devices
    private class AddConnHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            path = event.getParameter("path to openZWave", org.dsa.iot.dslink.node.value.ValueType.STRING).getString();
            options();

            String name = event.getParameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING).getString();
            String commPort = event.getParameter("comm port id").getString();
            Node child = node.createChild(name).build();
            child.setAttribute("path to openZWave", new Value(path));
            child.setAttribute("comm port id", new Value(commPort));
            ZWaveConn conn = new ZWaveConn(getMe(), child);
            conn.start();
        }
    }

    //find comm ports and return them for listing in the action tree
    private Set<String> findPorts() {
        String[] portNames;

        switch(SerialNativeInterface.getOsType()){
            case SerialNativeInterface.OS_LINUX:
                portNames = SerialPortList.getPortNames(Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
                break;
            case SerialNativeInterface.OS_MAC_OS_X:
                portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*"));
                break;
            default:
                portNames = SerialPortList.getPortNames();
                break;
        }
        Set<String> ports = new HashSet<>();

        for (String portName: portNames) {
            ports.add(portName);
        }

        return ports;
    }

    //return this ZWaveLink object
    private ZWaveLink getMe() { return this; }
}