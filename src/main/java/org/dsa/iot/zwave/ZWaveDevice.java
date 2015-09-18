package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.zwave4j.Manager;
import org.zwave4j.Notification;
import org.zwave4j.ValueGenre;
import org.zwave4j.ValueId;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class ZWaveDevice {

    private static final Logger LOGGER;

    static {
        LOGGER = LoggerFactory.getLogger(ZWaveDevice.class);
    }

    private Node node, parent;
    private long homeId;
    private ZWaveConn conn;
    private final Manager manager;

    public ZWaveDevice(Node parent, Node child, ZWaveConn conn) {
        this.node = child;
        this.homeId = conn.getHomeId();
        this.parent = parent;
        this.conn = conn;
        manager = conn.getManager();
    }

    public String getName() {
        return node.getName();
    }

    //add a new data point and value to the node
    protected void addValue(Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before the node stored as a child
        // which throws and error
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(manager.getValueLabel(notification.getValueId())
                    .replace("(%)", "(Percent)"));
            // at least one device has been found to have a data point called "Unknown".
            // the following if statement corrects the issue for that specific device
            // other devices with an "Unknown" data point may have a name other than
            // "Energy" for their point named "Unknown".
            /*if (name.equals("Unknown")) {
                LOGGER.info("This is the problem - ZWaveDevice - addValue");
                name = "Energy";
            }*/
            if (!name.isEmpty()) {
                NodeBuilder b = node.createChild(name);

                Value val = new Value(notification.getNodeId());
                b.setAttribute("nodeId", val);

                val = new Value(notification.getValueId().getCommandClassId());
                b.setAttribute("cc", val);

                val = new Value(notification.getGroupIdx());
                b.setAttribute("group", val);

                val = new Value(notification.getValueId().getInstance());
                b.setAttribute("instance", val);

                val = new Value((notification.getValueId().getGenre().name()));
                b.setAttribute("genre", val);

                val = new Value(notification.getValueId().getIndex());
                b.setAttribute("index", val);

                val = new Value(notification.getSceneId());
                b.setAttribute("scene", val);

                val = new Value(notification.getButtonId());
                b.setAttribute("button", val);

                val = new Value(manager.getValueUnits(notification.getValueId()));
                b.setAttribute("unit", val);

                val = new Value(notification.getValueId().getType().name());
                b.setAttribute("type", val);

                b.setValueType(ValueType.STRING);
                b.setValue(null);
                Node child = b.build();

                setValue(notification.getValueId(), child);

                addActions(child);
            }
        }
        LOGGER.info("Value added - " + notification.getNodeId());
    }

    //action method to set the handler for changing a node ID
    private Action setNodeAction() {
        Action act = new Action(Permission.READ, new SetNodeHandler());
        act.addParameter(new Parameter("Node ID", ValueType.STRING, node.getAttribute("nodeId")));
        return act;
    }

    //handler for changing a node ID
    private class SetNodeHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            String name = event.getParameter("Node ID", ValueType.STRING).getString();
            Value val = new Value(name);
            parent.setAttribute("nodeId", val);
            Short newId = Short.valueOf(name);
            LOGGER.info("New node ID: {}", newId);
        }
    }

    //add the actions and setter for the data point
    private void addActions(Node child) {
        Action refresh = refreshAction(child);
        node.createChild("Refresh").setAction(refresh).build().setSerializable(false);

        Action request = setNodeAction();
        node.createChild("Change Node ID").setAction(request).build().setSerializable(false);

        Action act = setNameAction();
        node.createChild("Rename").setAction(act).build().setSerializable(false);
    }

    //action method to set the handler for refreshing the values of the node
    private Action refreshAction(Node child) {
        return new Action(Permission.READ, new RefreshHandler(child));
    }

    //handler for refreshing the node's values
    private class RefreshHandler implements Handler<ActionResult> {
        private Node kid;
        public RefreshHandler(Node kid) {
            this.kid = kid;
        }
        @Override
        public void handle(ActionResult event) {
            final short val = kid.getAttribute("nodeId").getNumber().shortValue();
            manager.refreshNodeInfo(homeId, val);
        }
    }

    //handler for setting the value a data point
    private class SetPointHandler implements Handler<ValuePair> {
        private Node kid;
        public SetPointHandler(Node kid) {
            this.kid = kid;
        }
        @Override
        public void handle(ValuePair event) {
            if (!event.isFromExternalSource()) return;
            sendValue(kid, event);
        }
    }

    //action method to set the handler for renaming the node
    protected Action setNameAction() {
        Action act = new Action(Permission.READ, new SetNameHandler());
        act.addParameter(new Parameter("Name", ValueType.STRING,
                new Value(node.getDisplayName())));
        return act;
    }

    //handler for renaming the node
    private class SetNameHandler implements Handler<ActionResult> {
        @Override
        public void handle(ActionResult event) {
            String name = event.getParameter("Name", ValueType.STRING).getString();
            rename(name);
            LOGGER.info("New node name: {}", name);
        }
    }

    //helper method to rename the node
    protected void rename(String newName) {
        ZWaveDevice zwd = duplicate(newName);
        removeAndReplace(zwd);
    }

    //creates a new node with the new name
    protected ZWaveDevice duplicate(String newName) {
        Node newNode = parent.createChild(newName).build();
        newNode.setAttribute("nodeId", node.getAttribute("nodeId"));
        ZWaveDevice zwd = new ZWaveDevice(parent, newNode, conn);
        moveAttrib(newNode);
        return zwd;
    }

    //copies the attributes of the old node to the new one
    private void moveAttrib(Node newNode) {
        for (Node kid : node.getChildren().values()) {
            newNode.addChild(kid);
            addActions(newNode.getChild(kid.getName()));
        }
    }

    //removes the old node from the devices list and inserts the new one
    private void removeAndReplace(ZWaveDevice zwd) {
        Map<String, ZWaveDevice> devices = conn.getDevices();
        devices.remove(node.getAttribute("nodeId").getString());
        devices.put(zwd.node.getAttribute("nodeId").getString(), zwd);
        parent.removeChild(node);
    }

    //changes the value of a data point
    protected void changeValue(Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before it is stored as a child
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(manager.getValueLabel(notification.getValueId())
                    .replace("(%)", "(Percent)"));
            Node child = node.getChild(name);
            if (child == null) {
                NodeBuilder b = node.createChild(name);
                b.setValueType(ValueType.DYNAMIC);
                child = b.build();
            }
            setValue(notification.getValueId(), child);
        }
        //LOGGER.info("Value changed - " + notification.getNodeId());
    }

    //remove a data point from the node
    protected void removeValue(Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before it is stored as a child
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(manager.getValueLabel(notification.getValueId())
                    .replace("(%)", "(Percent)"));
            node.removeChild(name);
        }
        LOGGER.info("Value removed - " + notification.getNodeId());
    }

    //get the value from ZWave and set it to the data point
    private void setValue(final ValueId valueId, Node child) {
        Value val;
        JsonArray valJson;
        switch (valueId.getType()) {
            case BOOL:
                final AtomicReference<Boolean> b = new AtomicReference<>();
                manager.getValueAsBool(valueId, b);
                child.setValueType(ValueType.BOOL);
                val = new Value(b.get());
                child.setValue(val);
                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case BYTE:
                final AtomicReference<Short> bb = new AtomicReference<>();
                manager.getValueAsByte(valueId, bb);
                child.setValueType(ValueType.NUMBER);
                val = new Value(bb.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case DECIMAL:
                final AtomicReference<Float> f = new AtomicReference<>();
                manager.getValueAsFloat(valueId, f);
                child.setValueType(ValueType.NUMBER);
                val = new Value(f.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case INT:
                final AtomicReference<Integer> i = new AtomicReference<>();
                manager.getValueAsInt(valueId, i);
                child.setValueType(ValueType.NUMBER);
                val = new Value(i.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case LIST:
                AtomicReference<String> l = new AtomicReference<>();
                manager.getValueListSelectionString(valueId, l);
                List<String> ll = new ArrayList<>();
                manager.getValueListItems(valueId, ll);
                Set<String> ls = new HashSet<>(ll);
                child.setValueType(ValueType.makeEnum(ls));
                val = new Value(l.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case SCHEDULE:
                // ToDo
                //needs to be further implemented
                //don't have a device to test this data type yet
                /*AtomicReference<Short> hrs = new AtomicReference<>();
                AtomicReference<Short> min = new AtomicReference<>();
                AtomicReference<Byte> sec = new AtomicReference<>();
                short numPoints = manager.getNumSwitchPoints(valueId);
                Short[] hours = new Short[numPoints];
                Short[] minutes = new Short[numPoints];
                Byte[] setback = new Byte[numPoints];
                for (short n = 0; n < numPoints; n++) {
                    manager.getSwitchPoint(valueId, n, hrs, min, sec);
                    hours[n] = hrs.get();
                    minutes[n] = min.get();
                    setback[n] = sec.get();
                }

                child.setValueType(ValueType.STRING);
                val = new Value("null");
                child.setValue(val);*/
                LOGGER.error("ZWave Value Type SCHEDULE is not implemented yet");
                break;
            case SHORT:
                AtomicReference<Short> s = new AtomicReference<>();
                manager.getValueAsShort(valueId, s);
                child.setValueType(ValueType.NUMBER);
                val = new Value(s.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case STRING:
                final AtomicReference<String> ss = new AtomicReference<>();
                manager.getValueAsString(valueId, ss);
                child.setValueType(ValueType.STRING);
                val = new Value(ss.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case BUTTON:
                // ToDo
                /*AtomicReference<Boolean> bt = new AtomicReference<>();
                manager.getValueAsBool(valueId, bt);
                child.setValueType(ValueType.BOOL);
                val = new Value(bt.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));*/
                LOGGER.error("ZWave Value Type BUTTON is not implemented yet");
                break;
            case RAW:
                final AtomicReference<short[]> sss = new AtomicReference<>();
                manager.getValueAsRaw(valueId, sss);
                valJson = new JsonArray();
                short[] shorts = sss.get();
                for (int j : shorts) {
                    valJson.addNumber(shorts[j]);
                }
                child.setValueType(ValueType.ARRAY);
                val = new Value(valJson);
                child.setValue(val);
                break;
            default:
                // ToDo
                LOGGER.info("setValue - unknown ValueId type");
                child.setValueType(ValueType.STRING);
                val = new Value("null");
                child.setValue(val);
        }
        //LOGGER.info("Value set - " + valueId.getNodeId());
    }

    //set the user-enetered value of a data point
    private void sendValue(Node kid, ValuePair event) {
        ValueId valId;
        String type = kid.getAttribute("type").getString();
        switch (type) {
            case "BOOL":
                final boolean entryBool = event.getCurrent().getBool();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.BOOL);
                manager.setValueAsBool(valId, entryBool);
                break;
            case "BYTE":
                final byte entryByte = event.getCurrent().getNumber().byteValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.BYTE);
                manager.setValueAsByte(valId, entryByte);
                break;
            case "DECIMAL":
                float entryDecimal = event.getCurrent().getNumber().floatValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.DECIMAL);
                manager.setValueAsFloat(valId, entryDecimal);
                break;
            case "INT":
                final int entryInt = event.getCurrent().getNumber().intValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.INT);
                manager.setValueAsInt(valId, entryInt);
                break;
            case "LIST":
                String entryList = event.getCurrent().getString();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.LIST);
                manager.setValueListSelection(valId, entryList);
                break;
            case "SCHEDULE":
                // ToDo
                //this ZWave data type is not fully implemented (device that uses this type was
                //not available during development)
                /*short hours = event.getCurrent().getNumber().shortValue();
                short minutes = event.getCurrent().getNumber().shortValue();
                byte setback = event.getCurrent().getNumber().byteValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.SCHEDULE);
                manager.setSwitchPoint(valId, hours, minutes, setback);*/
                LOGGER.error("Setting an unimplemented ZWave data type - SCHEDULE");
                break;
            case "SHORT":
                short entryShort = event.getCurrent().getNumber().shortValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.SHORT);
                manager.setValueAsInt(valId, entryShort);
                break;
            case "STRING":
                String entryString = event.getCurrent().getString();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.STRING);
                manager.setValueAsString(valId, entryString);
                break;
            case "BUTTON":
                //this ZWave data type is not implemented (device that uses this data
                //type was not available during development
                /*boolean entryButton = event.getCurrent().getBool();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.BUTTON);
                if (entryButton) {
                    manager.pressButton(valId);
                } else {
                    manager.releaseButton(valId);
                }*/
                LOGGER.error("Setting an unimplemented ZWave data type - BUTTON");
                break;
            case "RAW":
                JsonArray entryJson = event.getCurrent().getArray();
                final short[] shorts = new short[entryJson.size()];
                for (int i = 0; i < entryJson.size(); i++) {
                    shorts[i] = entryJson.get(i);
                }
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.RAW);
                manager.setValueAsRaw(valId, shorts);
                break;
            default:
                LOGGER.info("sendValue - unknown org.zwave4j.ValueType");
        }
        LOGGER.info("Value sent - " + kid.getAttribute("nodeId"));
    }

    //helper method that returns the ValueGenre of a ZWave data point
    protected ValueGenre returnGenre (Node child) {
        LOGGER.info("Returned genre - " + child.getAttribute("nodeId"));
        switch (child.getAttribute("genre").toString()) {
            case "BASIC":
                return ValueGenre.BASIC;
            case "CONFIG":
                return ValueGenre.CONFIG;
            case "COUNT":
                return ValueGenre.COUNT;
            case "SYSTEM":
                return ValueGenre.SYSTEM;
            case "USER":
                return ValueGenre.USER;
            default:
                LOGGER.info("returnGenre - unknown ValueGenre");
                return null;
        }
    }
}
