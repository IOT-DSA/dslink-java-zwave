package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.zwave4j.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by Peter Weise on 8/12/15.
 */

public class ZWaveConn {

	private static final Logger LOGGER;
	
	static {
		LOGGER = LoggerFactory.getLogger(ZWaveConn.class);
	}

	protected Map<String, ZWaveDevice> devices = new HashMap<>(); //stores the device information
	private ZWaveLink link;
	protected Node node;
	private long homeId;
	private boolean ready = false;
    protected boolean refresh = false;
    protected Manager manager;
	protected NotificationWatcher watcher;
	protected String controllerPort;
    protected Short controllerNode;

	public ZWaveConn (ZWaveLink link, Node node) {
		this.link = link;
		this.node = node;
	}

	protected void stop() {
	}

    //create and build the manager object
    public void start() {
        manager = Manager.create();
        controllerPort = node.getAttribute("comm port id").getString();
        manager.addDriver(controllerPort);
        init();
        manager.addWatcher(watcher, null);
    }

    protected void restart() {
        refresh = true;
        synchronized (manager) {
            manager.removeDriver(controllerPort); //sometimes hangs up on this line, not sure why yet
            manager.addDriver(controllerPort);try {
                Thread.sleep(2000); //needed to prevent the application from crashing
            } catch (Exception e) {
                LOGGER.info("Sleep error: " + e);
            }
            // without the sleep above, this next line causes a crash
            manager.requestNodeState(homeId, controllerNode);
        }
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
                            LOGGER.info("NON_SUPPORTED notification type");
                            break;
                        default:
                            LOGGER.info("NotificationWatcher default - unknown notification type: " + notification.getType().name());
                            break;
                    }
            }
        };
    }

    //build the new node based on previous session information
	private void nodeAdded(final Notification notification) {
            Short nodeId = new Short(notification.getNodeId());
            ZWaveDevice zwp = null;
            Node child = null;
            if (!devices.containsKey(nodeId.toString())) {
                String nid = nodeId.toString();
                Map<String, Node> kids = node.getChildren();
                if (kids == null || (kids.size() == 1 && refresh)) {
                    child = node.createChild(nid).build();
                    String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                    child.setDisplayName(name + "-" + nid);
                    Value val = new Value(nid);
                    child.setAttribute("nodeId", val);
                    child.setAttribute("pathName", val);
                    zwp = new ZWaveDevice(node, child, homeId, link, getMe());
                    devices.put(nid, zwp);
                } else {
                    boolean inserted = false;
                    for (Node kid : kids.values()) {
                        if (!kid.getName().equals("rename") && kid.getAttribute("nodeId").toString().equals(nid)) {
                            child = kid;
                            zwp = new ZWaveDevice(node, child, homeId, link, getMe());
                            devices.put(child.getAttribute("nodeId").getString(), zwp);
                            inserted = true;
                        }
                    }

                    if (!inserted) {
                        child = node.createChild(nid).build();
                        String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                        child.setDisplayName(name + "-" + nid);
                        Value val = new Value(nid);
                        child.setAttribute("nodeId", val);
                        child.setAttribute("pathName", val);
                        zwp = new ZWaveDevice(node, child, homeId, link, getMe());
                        devices.put(nid, zwp);
                    }
                }

                if (controllerNode.equals(nodeId)) {
                    zwp.addAllOnOff();
                }

                Action childAct = zwp.setNameAction();
                child.createChild("rename").setAction(childAct).build().setSerializable(false);
            }
            LOGGER.info("Node added - " + nodeId);
	}

    //add the new data point to the node
	private void valueAdded(Notification notification) {
        Short nodeId = notification.getNodeId();
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
        LOGGER.info("Node Queries Complete - " + notification.getNodeId());
	}

    //driver for a PC Z-Wave controller has been added and is ready to use
	private void driverReady (Notification notification) {
        LOGGER.info("Driver Ready");
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

    private void driverRemoved() {
        LOGGER.info("Driver removed");
    }

    //all awake nodes have been queried, so client application can expect complete data for these nodes
	private void awakeNodesQueried() {
		LOGGER.info("Awake nodes queried");
		ready = true;
        refresh = false;

        removeExtraNodes(); //clean out unused nodes

        //turn on the rename option for the node
        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

    //all nodes have been queried, so client application can expected complete data
	private void allNodesQueried() {
		LOGGER.info("All nodes queried");
        manager.writeConfig(homeId);
		ready = true;
        refresh = false;

        removeExtraNodes(); //clean out unused nodes

        //turn on the rename option for the node
        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

    //all nodes have been queried but some dead nodes found
    private void allNodesQueriedSomeDead() {
		manager.writeConfig(homeId);
		ready = true;
        refresh = false;

        removeExtraNodes(); //clean out unused nodes

        //turn on the rename option for the node
        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

    //basic node information has been received
	private void nodeProtocolInfo(Notification notification) {
        LOGGER.info("Node Protocol Info - " + notification.getNodeId());
	}

    //the queries on a node that are essential to its operation have been completed. The node can now handle incoming messages
	private void essentialNodeQueriesComplete(Notification notification) {
        LOGGER.info("Essential Node Queries Complete - " + notification.getNodeId());
		ready = true;
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
        String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
	}

    //node has been removed from OpenZWave's list
	private void nodeRemoved(Notification notification) {
        Short nodeId = notification.getNodeId();
        String nid = nodeId.toString();
        //node.removeChild(nid);
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

    //set up for the node rename action
	private Action setNameAction() {
		Action act = new Action(Permission.READ, new SetNameHandler(node));
		act.addParameter(new Parameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING, new Value(node.getDisplayName())));
		return act;
	}

    //node rename action handler
	private class SetNameHandler implements Handler<ActionResult> {
		Node child;

		public SetNameHandler(Node child) {
			this.child = child;
		}

		public void handle(ActionResult event) {
			if (ready) {
				final String name = event.getParameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING).getString();
				child.setDisplayName(name);
				final short val3 = child.getAttribute("nodeId").getNumber().shortValue();
				manager.setNodeProductName(homeId, val3, name);
			}
		}
	}

    //remove unused nodes
    private void removeExtraNodes() {
        Map<String, Node> kids = node.getChildren();
        for (Node kid: kids.values()) {
            if (devices.get(kid.getName()) == null) {
                node.removeChild(kid);
            }
        }
    }

	private ZWaveConn getMe() { return this; }

	/***** this block may be helpful for debugging *****/
	protected Object getValue(ValueId valueId) {
		switch (valueId.getType()) {
			case BOOL:
				AtomicReference<Boolean> b = new AtomicReference<>();
				Manager.get().getValueAsBool(valueId, b);
				return b.get();
			case BYTE:
				AtomicReference<Short> bb = new AtomicReference<>();
				Manager.get().getValueAsByte(valueId, bb);
				return bb.get();
			case DECIMAL:
				AtomicReference<Float> f = new AtomicReference<>();
				Manager.get().getValueAsFloat(valueId, f);
				return f.get();
			case INT:
				AtomicReference<Integer> i = new AtomicReference<>();
				Manager.get().getValueAsInt(valueId, i);
				return i.get();
			case LIST:
				return null;
			case SCHEDULE:
				return null;
			case SHORT:
				AtomicReference<Short> s = new AtomicReference<>();
				Manager.get().getValueAsShort(valueId, s);
				return s.get();
			case STRING:
				AtomicReference<String> ss = new AtomicReference<>();
				Manager.get().getValueAsString(valueId, ss);
				return ss.get();
			case BUTTON:
				return null;
			case RAW:
				AtomicReference<short[]> sss = new AtomicReference<>();
				Manager.get().getValueAsRaw(valueId, sss);
				return sss.get();
			default:
				return null;
		}
	}
	/***** *****/

	/***** this block may be helpful for debugging *****/
	/*private void readInput() {
		final BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String line = null;
		do {
			try {
				line = br.readLine();
			} catch (IOException e) {
				LOGGER.info("br.readLine(): " + e);
			}
			if (!ready || line == null) {
				continue;
			}

			short i = 4;
			switch (line) {
				case "on":
					//manager.switchAllOn(homeId);
					LOGGER.info(manager.getNodeType(homeId, i));
					LOGGER.info(manager.isNodeAwake(homeId, i));
					manager.setNodeOn(homeId, i);

					break;
				case "off":
					//manager.switchAllOff(homeId);
					manager.setNodeOff(homeId, i);
					break;
			}
		} while(line != null && !line.equals("q"));


		try {
			br.close();
		} catch (IOException e) {
			LOGGER.info("br.close():" + e);
		}
	}*/
	/***** *****/
}
