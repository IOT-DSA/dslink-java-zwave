package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.*;
import org.dsa.iot.dslink.node.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.zwave4j.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ZWaveConn {

	private static final Logger LOGGER;
	
	static {
		LOGGER = LoggerFactory.getLogger(ZWaveConn.class);
	}

	private final Map<String, ZWaveDevice> devices = new HashMap<>(); //stores the device information
	private Node node;
    private ZWaveLink link;
	private long homeId;
    private final Manager manager = Manager.create();
	private NotificationWatcher watcher;
    private static boolean watcherAdded = false;
	private String controllerPort;
    private Short controllerNode;

	public ZWaveConn(ZWaveLink link, Node node) {
		this.node = node;
        this.link = link;
	}

    public long getHomeId() {
        return homeId;
    }

    public Manager getManager() {
        return manager;
    }

    public Map<String, ZWaveDevice> getDevices() {
        return devices;
    }

    //create and build the manager object
    public void start() {
        {
            NodeBuilder b = node.createChild("Status");
            b.setValueType(ValueType.STRING);
            b.setValue(new Value("Loading..."));
            b.setWritable(Writable.NEVER);
            b.build();
        }

        controllerPort = node.getAttribute("comm port id").getString();
        manager.addDriver(controllerPort);
        init();
        //the if statement is needed so that a second watcher isn't attached to the manager on restarts
        if (!watcherAdded) {
            manager.addWatcher(watcher, null);
            watcherAdded = true;
        }
        LOGGER.info("Manager created");
    }

    //restarts the controller
    //if any nodes were added while the application is running (which requires the stick to be removed
    //from the USB port as per ZWave standard), this restarts the connection with the USB stick.
    //This is the only way I have found to add the new node to the tree without restarting the application
    protected void restart() {
        removeActions();
        NodeBuilder b = node.createChild("Status");
        b.setValueType(ValueType.STRING);
        b.setValue(new Value("Loading"));
        b.build();
        //do NOT use manager.removeWatcher(watcher) or Manager.destroy()
        //these two functions cause a JVM crash due to native method call errors
        synchronized (manager) {
            try {
                manager.removeDriver(controllerPort);
                manager.addDriver(controllerPort);
                Thread.sleep(2000); //needed to prevent the application from crashing
                // without the sleep above, this next line causes a crash
                manager.requestNodeState(homeId, controllerNode);
            } catch (InterruptedException e) {
                LOGGER.error("Sleep error - {}" + e);
            } catch (Exception e) {
                LOGGER.error("Manager handling error - {}", e);
            }
        }
    }

    private void stop() {
        //do NOT use Manager.destroy() - see restart() for further info
        manager.removeDriver(controllerPort);
    }

    //create the watcher that receives device notifications
	private void init() {
        watcher = new NotificationWatcher() {
            @Override
            public void onNotification(Notification notification, Object context) {
            switch (notification.getType()) {
                case DRIVER_READY:
                    driverReady(notification);
                    break;
                case DRIVER_FAILED:
                    driverFailed();
                    break;
                case DRIVER_RESET:
                    driverReset();
                    break;
                case DRIVER_REMOVED:
                    driverRemoved();
                    break;
                case AWAKE_NODES_QUERIED:
                    awakeNodesQueried();
                    break;
                case ALL_NODES_QUERIED:
                    allNodesQueried();
                    break;
                case ALL_NODES_QUERIED_SOME_DEAD:
                    allNodesQueriedSomeDead();
                    break;
                case POLLING_ENABLED:
                    pollingEnabled(notification);
                    break;
                case POLLING_DISABLED:
                    pollingDisabled(notification);
                    break;
                case NODE_NEW:
                    nodeNew(notification);
                    break;
                case NODE_ADDED:
                    nodeAdded(notification);
                    break;
                case NODE_REMOVED:
                    nodeRemoved(notification);
                    break;
                case ESSENTIAL_NODE_QUERIES_COMPLETE:
                    essentialNodeQueriesComplete(notification);
                    break;
                case NODE_QUERIES_COMPLETE:
                    nodeQueriesComplete(notification);
                    break;
                case NODE_EVENT:
                    nodeEvent(notification);
                    break;
                case NODE_NAMING:
                    nodeNaming(notification);
                    break;
                case NODE_PROTOCOL_INFO:
                    nodeProtocolInfo(notification);
                    break;
                case VALUE_ADDED:
                    valueAdded(notification);
                    break;
                case VALUE_REMOVED:
                    valueRemoved(notification);
                    break;
                case VALUE_CHANGED:
                    valueChanged(notification);
                    break;
                case VALUE_REFRESHED:
                    valueRefreshed(notification);
                    break;
                case GROUP:
                    group(notification);
                    break;
                case SCENE_EVENT:
                    sceneEvent(notification);
                    break;
                case CREATE_BUTTON:
                    createButton(notification);
                    break;
                case DELETE_BUTTON:
                    deleteButton(notification);
                    break;
                case BUTTON_ON:
                    buttonOn(notification);
                    break;
                case BUTTON_OFF:
                    buttonOff(notification);
                    break;
                case NOTIFICATION:
                    note(notification);
                    break;
                case CONTROLLER_COMMAND:
                    controllerCommand(notification);
                    break;
                case NOT_SUPPORTED:
                    LOGGER.error("NON_SUPPORTED notification type");
                    break;
                default:
                    LOGGER.error("NotificationWatcher default - unknown notification type: "
                            + notification.getType().name());
                    break;
            }
            }
        };
    }

    //build the new node based on previous session information
	private void nodeAdded(Notification notification) {
        Short nodeId = notification.getNodeId();
        if (devices.containsKey(nodeId.toString())) { //device is already recognized and running
            return;
        }
        String nid = nodeId.toString();
        Node c = node.getChild("Status");
        c.setValueType(ValueType.STRING);
        c.setValue(new Value("Adding node " + nid));
        NodeBuilder b = node.createChild(nid);
        String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
        b.setDisplayName(name + "-" + nid);
        Value val = new Value(nid);
        b.setAttribute("nodeId", val);
        b.setAttribute("pathName", val);
        if (controllerNode.equals(nodeId)) {
            b.setHidden(true);
        }
        Node child = b.build();
        ZWaveDevice zwd = new ZWaveDevice(node, child, this);
        devices.put(nid, zwd);


        Action childAct = zwd.setNameAction();
        child.createChild("Rename").setAction(childAct).build().setSerializable(false);

        LOGGER.info("Node added - " + nodeId);
	}

    //add the new data point to the node
	private void valueAdded(Notification notification) {
        Short nodeId = notification.getNodeId();
        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Adding value to node " + nodeId.toString()));
        child.setWritable(Writable.NEVER);
        ZWaveDevice zwp = devices.get(nodeId.toString());
        zwp.addValue(notification);
	}

    //update the data point
	private void valueChanged(Notification notification) {
		Short nodeId = notification.getNodeId();
		ZWaveDevice zwp = devices.get(nodeId.toString());
		zwp.changeValue(notification);
	}

    //remove the data point
	private void valueRemoved(Notification notification) {
		Short nodeId = notification.getNodeId();
        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Removing value from node " + nodeId.toString()));
        child.setWritable(Writable.NEVER);
		ZWaveDevice zwp = devices.get(nodeId.toString());
		zwp.removeValue(notification);
	}

	//currently, this method does the same thing as valueChanged
	private void valueRefreshed(Notification notification) {
        LOGGER.info("Value Refreshed - " + notification.getNodeId());
		valueChanged(notification);
	}

    //all the initialization queries on a node have been completed
	private void nodeQueriesComplete(Notification notification) {
        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Finalizing Node Queries..."));
        child.setWritable(Writable.NEVER);
        LOGGER.info("Node Queries Complete - " + notification.getNodeId());
	}

    //driver for a PC Z-Wave controller has been added and is ready to use
	private void driverReady(Notification notification) {
        LOGGER.info("Driver Ready");
        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Driver Ready"));
        child.setWritable(Writable.NEVER);
		homeId = notification.getHomeId();
        controllerNode = manager.getControllerNodeId(homeId);
	}

    //driver failed to load
	private void driverFailed() {
		LOGGER.info("Driver failed");
	}

    //all nodes and values for this driver have been removed
	private void driverReset() {
		LOGGER.info("Driver reset");
	}

    //driver has been removed from the manager
    private void driverRemoved() {
        LOGGER.info("Driver removed");
    }

    //all awake nodes have been queried, so client application can expect complete data for these nodes
	private void awakeNodesQueried() {
		LOGGER.info("Awake nodes queried");

        removeExtraNodes(); //clean out unused nodes
        addActions();

        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Ready"));
        child.setWritable(Writable.NEVER);
    }

    //all nodes have been queried, so client application can expected complete data
	private void allNodesQueried() {
		LOGGER.info("All nodes queried");
        manager.writeConfig(homeId);

        removeExtraNodes(); //clean out unused nodes

        addActions();

        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Ready"));
        child.setWritable(Writable.NEVER);
    }

    //all nodes have been queried but some dead nodes found
    private void allNodesQueriedSomeDead() {
		manager.writeConfig(homeId);

        removeExtraNodes(); //clean out unused nodes
        addActions();

        Node child = node.getChild("Status");
        child.setValueType(ValueType.STRING);
        child.setValue(new Value("Ready"));
        child.setWritable(Writable.NEVER);
    }

    //basic node information has been received
	private void nodeProtocolInfo(Notification notification) {
        LOGGER.info("Node Protocol Info - " + notification.getNodeId());
	}

    //the queries on a node that are essential to its operation have been completed.
    //The node can now handle incoming messages
	private void essentialNodeQueriesComplete(Notification notification) {
        LOGGER.info("Essential Node Queries Complete - " + notification.getNodeId());
	}

    //one of the node names has changed (name, manufacturer, product)
	private void nodeNaming(Notification notification) {
        LOGGER.info("Node Naming - " + notification.getNodeId());
        Short nid = notification.getNodeId();
        String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
        Node child = node.getChild(nid.toString());
        child.setDisplayName(name + "-" + nid);
	}

    //new node has been found
	private void nodeNew(Notification notification) {
        LOGGER.info("Node New - " + notification.getNodeId());
        // String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
	}

    //node has been removed from OpenZWave's list
	private void nodeRemoved(Notification notification) {
        Short nodeId = notification.getNodeId();
        String nid = nodeId.toString();
        devices.remove(nid);
        LOGGER.info("Node Removed - " + notification.getNodeId());
	}

    //node has triggered an event
	private void nodeEvent(Notification notification) {
        LOGGER.info("Node Event - " + notification.getNodeId());
	}

    //polling of a node has been successfully turned on
	private void pollingEnabled(Notification notification) {
		LOGGER.info("Polling enabled - " + notification.getNodeId());
	}

    //polling of a node has been successfully turned off
	private void pollingDisabled(Notification notification) {
		LOGGER.info("Polling disabled - " + notification.getNodeId());
	}

    //associations for the node have changed
	private void group(Notification notification) {
        LOGGER.info("Group - " + notification.getNodeId());
	}

    //scene Activation Set received
	private void sceneEvent(Notification notification) {
        LOGGER.info("Scene Event - " + notification.getNodeId());
	}

    //Handheld controller button event created
	private void createButton(Notification notification) {
        LOGGER.info("Create Button - " + notification.getNodeId());
	}

    //Handheld controller button event created
	private void deleteButton(Notification notification) {
        LOGGER.info("Delete Button - " + notification.getNodeId());
	}

    //Handheld controller button on pressed event
	private void buttonOn(Notification notification) {
        LOGGER.info("Button On - " + notification.getNodeId());
	}

    //Handheld controller button off pressed event
	private void buttonOff(Notification notification) {
        LOGGER.info("Button Off - " + notification.getNodeId());
	}

    //error has occurred that needs to be reported
	private void note(Notification notification) {
        LOGGER.info("Notification - " + notification.getNodeId() + ", code: " + notification.getNotification());
	}

    private void controllerCommand(Notification notification) {
        LOGGER.info("Controller Command - " + notification.getNodeId());
    }

    //handler that refreshed the controller
    private class ControllerRefreshHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            restart();
        }
    }

    //handler that turns all the devices connected to the controller on
    private class SetOnHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            manager.switchAllOn(homeId);
        }
    }

    //handler that turns all the devices connected to the controller off
    private class SetOffHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            manager.switchAllOff(homeId);
        }
    }

    //add all the actions for the controller node
    private void addActions() {
        Action delAct = new Action(Permission.WRITE, new DeleteHandler());
        node.createChild("Delete").setAction(delAct).setSerializable(false).build();

        Action editAct;
        {
            editAct = new Action(Permission.WRITE, new EditHandler());
            editAct.addParameter(new Parameter("Name", ValueType.STRING, new Value(node.getName())));
            Set<String> ports = link.findPorts();
            editAct.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(ports),
                    new Value(node.getAttribute("comm port id").getString())));
        }
        node.createChild("Edit").setAction(editAct).setSerializable(false).build();

        Action actOn = new Action(Permission.WRITE, new SetOnHandler());
        node.createChild("All On").setAction(actOn).setSerializable(false).build();

        Action actOff = new Action(Permission.WRITE, new SetOffHandler());;
        node.createChild("All Off").setAction(actOff).setSerializable(false).build();

        Action actRefresh = new Action(Permission.READ, new ControllerRefreshHandler());
        node.createChild("Refresh").setAction(actRefresh).setSerializable(false).build();
    }

    //remove the actions fro the controller during a refresh
    private void removeActions() {
        node.removeChild("Delete");
        node.removeChild("Edit");
        node.removeChild("All On");
        node.removeChild("All Off");
        node.removeChild("Refresh");
    }

    //handler for editing the controller node
	private class EditHandler implements Handler<ActionResult> {
        @Override
		public void handle(ActionResult event) {
            if (!(event.getParameter("Name").toString()).equals(node.getDisplayName())) {
                final String name = event.getParameter("Name", ValueType.STRING).getString();
                node.setDisplayName(name);
            }
            String cp = event.getParameter("Comm Port ID", ValueType.STRING).getString();
            if (!controllerPort.equals(cp)) {
                controllerPort = cp;
                node.setAttribute("comm port id", new Value(controllerPort));
                stop();
                restart();
            }
		}
	}

    //handler for deleting the controller node
    private class DeleteHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            stop();
            link.stop(node);
        }
    }

    //remove unused nodes that were disconnected during runtime
    private void removeExtraNodes() {
        for (ZWaveDevice zwd : devices.values()) {
            String name = zwd.getName();
            if (!node.hasChild(name)) {
                devices.remove(name);
            }
        }
    }

}
