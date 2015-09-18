package org.dsa.iot.zwave;

import jssc.SerialNativeInterface;
import jssc.SerialPortList;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.zwave4j.NativeLibraryLoader;
import org.zwave4j.Options;
import org.zwave4j.ZWave4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
        NativeLibraryLoader.loadLibrary(ZWave4j.LIBRARY_NAME, ZWave4j.class);
        options();
        LOGGER.info("Native library loaded");
        restoreLastSession();

        {
            final Action act = connAction();
            NodeBuilder b = node.createChild("addConnection");
            b.setDisplayName("Add Connection");
            b.setSerializable(false);
            b.setAction(act);
            b.getListener().setOnListHandler(new Handler<Node>() {
                @Override
                public void handle(Node event) {
                    Objects.getDaemonThreadPool().execute(new Runnable() {
                        @Override
                        public void run() {
                            Set<String> ports = findPorts();
                            List<Parameter> params = new LinkedList<>();
                            params.add(new Parameter("Name", ValueType.STRING, new Value("USB Port")));
                            params.add(new Parameter("Comm Port ID", ValueType.makeEnum(ports)));
                            act.setParams(params);
                        }
                    });
                }
            });
            b.build();
        }
	}

    //reload the nodes and objects used during the last application execution
    private void restoreLastSession() {
        Map<String, Node> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (Node child: children.values()) {
            if (child.getAttribute("comm port id") != null) {
                ZWaveConn conn = new ZWaveConn(this, child);
                conn.start();
            } else if (child.getAction() == null) {
                node.removeChild(child);
            }
        }
    }

    //create action tree for setting the comm port
    private Action connAction() {
        Action act = new Action(Permission.WRITE, new AddConnHandler());
        act.addParameter(new Parameter("Name", ValueType.STRING, new Value("USB Port")));
        Set<String> ports = findPorts();
        act.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(ports)));
        return act;
    }

    //set and lock the Options object
    private void options() {
        if (locked) {
            return;
        }

        final String configPath;
        final File jar;
        {
            ProtectionDomain domain = getClass().getProtectionDomain();
            CodeSource source = domain.getCodeSource();
            URL location = source.getLocation();
            jar = new File(location.getPath());
        }
        if (!jar.isDirectory()) {
            final File basePath = new File("zwave-config");
            if (basePath.exists()) {
                configPath = basePath.getAbsolutePath();
            } else {
                if (!basePath.mkdir()) {
                    throw new RuntimeException("Failed to create config dir");
                }

                try {
                    ZipFile zf = new ZipFile(jar);
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith("config/")) {
                            name = name.substring(7);
                            File f = new File(basePath, name);
                            if (entry.isDirectory()) {
                                continue;
                            }
                            f.getParentFile().mkdirs();

                            FileOutputStream stream = new FileOutputStream(f);
                            int read;
                            byte[] buf = new byte[4096];
                            InputStream is = zf.getInputStream(entry);
                            while ((read = is.read(buf, 0, buf.length)) > -1) {
                                stream.write(buf, 0, read);
                            }
                            is.close();
                            stream.close();
                        }
                    }
                    zf.close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                configPath = basePath.getAbsolutePath();
            }
        } else {
            URL url = getClass().getResource("/config");
            configPath = url.toString().replaceFirst("file:", "");
        }

        final Options options = Options.create(configPath, "", "");
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
