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
    protected Manager manager;
	protected NotificationWatcher watcher;
	protected String controllerPort;
    protected Short controllerNode;
    //protected ZWaveReceiver zwr;
    short nid;
    boolean connected;

	public ZWaveConn (ZWaveLink link, Node node) {
		this.link = link;
		this.node = node;
	}

    // does not work yet
	protected void stop() {
		manager.removeWatcher(watcher, null); // this crashes JVM
		manager.removeDriver(controllerPort);
		manager.destroy();
		//Options.destroy();
	}

    protected void start() {
        manager = Manager.create();
        controllerPort = node.getAttribute("comm port id").getString();// "/dev/cu.SLAB_USBtoUART"; //
        manager.addDriver(controllerPort);
    }
    public void restoreLastSession() {
        init();
    }

	public void init() {
        //zwr = new ZWaveReceiver(getMe());
        /*zwr.get(new Handler<Manager>() {
            @Override
            public void handle(Manager event) {
                manager = event.get();
            }
        }, false);*/
        start();

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
                        pollingEnabled();
                        break;
                    case POLLING_DISABLED:
                        pollingDisabled();
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
                    default:
                        LOGGER.info("NotificationWatcher default: " + notification.getType().name());
                        break;
                }
            }
        };

        manager.addWatcher(watcher, null);


    }

	private void nodeAdded(final Notification notification) {
		Short nodeId = new Short(notification.getNodeId());
        ZWaveDevice zwp = null;
        Node child = null;
		if (!devices.containsKey(nodeId.toString())) {
            String nid = nodeId.toString();
            Map<String, Node> kids = node.getChildren();
            if (kids == null) {
                child = node.createChild(nid).build();
                /*zwr.get(new Handler<Manager>() {
                    @Override
                    public void handle(Manager event) {
                        name = event.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                    }
                }, false);*/
                String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                child.setDisplayName(name + "-" + nid);
                Value val = new Value(nid);
                child.setAttribute("nodeId", val);
                child.setAttribute("pathName", val);
                //nodeMap.put(nodeId, nodeId);
                zwp = new ZWaveDevice(node, child, homeId, /*manager,*/ link, getMe());
                devices.put(nodeId.toString(), zwp);
            } else {
                boolean inserted = false;
                for (Node kid : kids.values()) {
                    if (kid.getAttribute("nodeId").toString().equals(nid)) {
                        child = kid;
                        zwp = new ZWaveDevice(node, child, homeId, /*manager,*/ link, getMe());
                        devices.put(child.getAttribute("nodeId").getString(), zwp);
                        inserted = true;
                    }
                }

                if (!inserted) {
                    child = node.createChild(nid).build();
                    /*zwr.get(new Handler<Manager>() {
                        @Override
                        public void handle(Manager event) {
                            name = event.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                        }
                    }, false);*/
                    String name = manager.getNodeProductName(notification.getHomeId(), notification.getNodeId());
                    child.setDisplayName(name + "-" + nid);
                    Value val = new Value(nid);
                    child.setAttribute("nodeId", val);
                    child.setAttribute("pathName", val);
                    zwp = new ZWaveDevice(node, child, homeId, link, getMe());
                    devices.put(nodeId.toString(), zwp);
                }
            }

			if (controllerNode.equals(nodeId)) {
				zwp.addAllOnOff();
			}

			Action childAct = zwp.setNameAction();
			child.createChild("rename").setAction(childAct).build().setSerializable(false);
		}
	}

	private void valueAdded(Notification notification) {

		/*LOGGER.info(String.format("Value added\n" +
						"\tnode id: %d\n" +
						"\tcommand class: %d\n" +
						"\tinstance: %d\n" +
						"\tindex: %d\n" +
						"\tgenre: %s\n" +
						"\ttype: %s\n" +
						"\tlabel: %s\n" +
						"\tvalue: %s",
				notification.getNodeId(),
				notification.getValueId().getCommandClassId(),
				notification.getValueId().getInstance(),
				notification.getValueId().getIndex(),
				notification.getValueId().getGenre().name(),
				notification.getValueId().getType().name(),
				manager.getValueLabel(notification.getValueId()),
				getValue(notification.getValueId())
		));*/
        Short nodeId = notification.getNodeId();
        ZWaveDevice zwp = devices.get(nodeId.toString());
        zwp.addValue(notification);
	}

	private void valueChanged(Notification notification) {
		Short nodeId = notification.getNodeId();
		ZWaveDevice zwp = devices.get(nodeId.toString());
		zwp.changeValue(notification);
	}

	private void valueRemoved(Notification notification) {
        LOGGER.info("Value Removed");
		Short nodeId = notification.getNodeId();
		ZWaveDevice zwp = devices.get(nodeId.toString());
		zwp.removeValue(notification);
	}

	//currently, this method does the same thing as valueChanged(Notification)
	private void valueRefreshed(Notification notification) {
        LOGGER.info("Value Refreshed");
		valueChanged(notification);
	}

	private void nodeQueriesComplete(Notification notification) {
        LOGGER.info("Node Queries Complete");
		short nodeId = notification.getNodeId();
	}

	private void driverReady (Notification notification) {
        LOGGER.info("Driver Ready");
		homeId = notification.getHomeId();
        /*zwr.get(new Handler<Manager>() {
            @Override
            public void handle(Manager event) {
                controllerNode = event.getControllerNodeId(homeId);
            }
        }, false);*/
        controllerNode = manager.getControllerNodeId(homeId);
	}

	private void driverFailed() {
		LOGGER.info("Driver failed");
	}

	private void driverReset() {
		LOGGER.info("Driver reset");
	}

	private void awakeNodesQueried() {
		LOGGER.info("Awake nodes queried");
		ready = true;


        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

	private void allNodesQueried() {
		LOGGER.info("All nodes queried");
        /*zwr.get(new Handler<Manager>() {
            @Override
            public void handle(Manager event) {
                event.writeConfig(homeId);
            }
        }, false);*/
		manager.writeConfig(homeId);
		ready = true;


        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

    private void allNodesQueriedSomeDead() {
		LOGGER.info("All nodes queried some dead");
        /*zwr.get(new Handler<Manager>() {
            @Override
            public void handle(Manager event) {
                event.writeConfig(homeId);
            }
        }, false);*/
		manager.writeConfig(homeId);
		ready = true;


        Action act = setNameAction();
        node.createChild("rename").setAction(act).build().setSerializable(false);
    }

	private void nodeProtocolInfo(Notification notification) {
        LOGGER.info("Node Protocol Info");
		short nodeId = notification.getNodeId();
		/*LOGGER.info(String.format("Node protocol info\n" +
						"\tnode id: %d\n" +
						"\ttype: %s",
				notification.getNodeId(),
				manager.getNodeType(notification.getHomeId(), notification.getNodeId())
		));*/
	}

	private void essentialNodeQueriesComplete(Notification notification) {
        LOGGER.info("Essential Node Queries Complete");
		short nodeId = notification.getNodeId();
		ready = true;
	}

	private void nodeNaming(Notification notification) {
        LOGGER.info("Node Naming");
		short nodeId = notification.getNodeId();
	}

	private void nodeNew(Notification notification) {
        LOGGER.info("Node New");
		short nodeId = notification.getNodeId();
	}

	private void nodeRemoved(Notification notification) {
        LOGGER.info("Node Removed");
		short nodeId = notification.getNodeId();
	}

	private void nodeEvent(Notification notification) {
        LOGGER.info("Node Event");
		short nodeId = notification.getNodeId();
	}

	private void pollingEnabled() {
		LOGGER.info("Polling enabled");
	}

	private void pollingDisabled() {
		LOGGER.info("Polling disabled");
	}

	private void group(Notification notification) {
        LOGGER.info("Group");
		short nodeId = notification.getNodeId();
	}

	private void sceneEvent(Notification notification) {
        LOGGER.info("Scene Event");
		short nodeId = notification.getNodeId();
	}

	private void createButton(Notification notification) {
        LOGGER.info("Create Button");
		short nodeId = notification.getNodeId();
	}

	private void deleteButton(Notification notification) {
        LOGGER.info("Delete Button");
		short nodeId = notification.getNodeId();
	}

	private void buttonOn(Notification notification) {
        LOGGER.info("Button On");
		short nodeId = notification.getNodeId();
	}

	private void buttonOff(Notification notification) {
        LOGGER.info("Button Off");
		short nodeId = notification.getNodeId();
	}

	private void note(Notification notification) {
		LOGGER.info("Notification");
        LOGGER.info("******** Note Info: {}", notification.getNodeId());
        nid = manager.getControllerNodeId(homeId);
        connected = manager.isNodeAwake(homeId, nid);
        if (!connected) {
            LOGGER.info("Controller disconnected");
            connected = false;
            stop();
            while (!connected) {
                try {
                    Thread.sleep(1);
                    LOGGER.info("Trying to reconnect");
                    connected = manager.isNodeFailed(homeId, nid);
                } catch (InterruptedException e) {
                    LOGGER.info("note() sleep error " + e);
                }
            }
            start();
        }
	}

	private Action setNameAction() {
		Action act = new Action(Permission.READ, new SetNameHandler(node));
		act.addParameter(new Parameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING, new Value(node.getDisplayName())));
		return act;
	}

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
                /*zwr.get(new Handler<Manager>() {
                    @Override
                    public void handle(Manager event) {
                        event.setNodeProductName(homeId, val3, name);
                    }
                }, false);*/
				manager.setNodeProductName(homeId, val3, name);
			}
		}
	}

	private ZWaveConn getMe() { return this; }

	/***** this block may be helpful for debugging *****/
	private Object getValue(ValueId valueId) {
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
