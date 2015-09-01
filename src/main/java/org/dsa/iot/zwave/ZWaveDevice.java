package org.dsa.iot.zwave;

import org.dsa.iot.dslink.node.Node;
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
import org.zwave4j.Notification;
import org.zwave4j.ValueGenre;
import org.zwave4j.ValueId;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by Peter Weise on 8/12/15.
 */

public class ZWaveDevice {

    private static final Logger LOGGER;

    static {
        LOGGER = LoggerFactory.getLogger(ZWaveDevice.class);
    }

    protected Node node, parent;
    protected long homeId;
    private ZWaveLink link;
    private ZWaveConn conn;

    public ZWaveDevice(Node parent, Node child, long homeId, ZWaveLink link, ZWaveConn conn) {
        this.node = child;
        this.homeId = homeId;
        this.parent = parent;
        this.link = link;
        this.conn = conn;
    }

    protected void addValue (Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before it is stored as a child
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(conn.manager.getValueLabel(notification.getValueId()).replace("(%)", "(Percent)"));
            if (name.equals("Unknown")) {
                LOGGER.info("This is the problem - ZWaveDevice - addValue");
                name = "Energy";
            }
            if (!name.isEmpty()) {
                Node child = node.createChild(name).build();
                Value val = new Value(notification.getNodeId());
                child.setAttribute("nodeId", val);
                val = new Value(notification.getValueId().getCommandClassId());
                child.setAttribute("cc", val);
                val = new Value(notification.getGroupIdx());
                child.setAttribute("group", val);
                val = new Value(notification.getValueId().getInstance());
                child.setAttribute("instance", val);
                val = new Value((notification.getValueId().getGenre().name()));
                child.setAttribute("genre", val);
                val = new Value(notification.getValueId().getIndex());
                child.setAttribute("index", val);
                val = new Value(notification.getSceneId());
                child.setAttribute("scene", val);
                val = new Value(notification.getButtonId());
                child.setAttribute("button", val);
                val = new Value(conn.manager.getValueUnits(notification.getValueId()));
                child.setAttribute("unit", val);
                val = new Value(notification.getValueId().getType().name());
                child.setAttribute("type", val);
                setValue(notification.getValueId(), child);

                addActions(child);
            }
        }
        LOGGER.info("Value added - " + notification.getNodeId());
    }

    protected void addAllOnOff() {
        Action actOn = setAllOnAction();
        node.createChild("All on").setAction(actOn).build().setSerializable(false);

        Action actOff = setAllOffAction();
        node.createChild("All off").setAction(actOff).build().setSerializable(false);

        Action actRefresh = controllerRefreshAction();
        node.createChild("Refresh").setAction(actRefresh).build().setSerializable(false);
    }

    protected Action controllerRefreshAction() {
        Action act = new Action(Permission.READ, new ControllerRefreshHandler());
        return act;
    }

    private class ControllerRefreshHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            conn.restart();
        }
    }

    private Action setAllOnAction() {
        Action act = new Action(Permission.READ, new SetOnHandler());
        return act;
    }

    private class SetOnHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            conn.manager.switchAllOn(homeId);
        }
    }

    private Action setAllOffAction() {
        Action act = new Action(Permission.READ, new SetOffHandler());
        return act;
    }

    private class SetOffHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            conn.manager.switchAllOff(homeId);
        }
    }

    private Action setNodeAction() {
        Action act = new Action(Permission.READ, new SetNodeHandler());
        act.addParameter(new Parameter("nodeID", org.dsa.iot.dslink.node.value.ValueType.STRING, node.getAttribute("nodeId")));
        return act;
    }

    private class SetNodeHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            String name = event.getParameter("nodeID", org.dsa.iot.dslink.node.value.ValueType.STRING).getString();
            Value val = new Value(name);
            parent.setAttribute("nodeId", val);
            Short newId = new Short(name);
            LOGGER.info("New node ID: {}", newId);
        }
    }

    private void addActions(Node child) {
        child.setWritable(Writable.WRITE);
        child.getListener().setValueHandler(new SetPointHandler(child));

        Action refresh = refreshAction(child);
        node.createChild("Refresh").setAction(refresh).build().setSerializable(false);

        Action request = setNodeAction();
        node.createChild("Change nodeId").setAction(request).build().setSerializable(false);
    }

    private Action refreshAction(Node child) {
        Action act = new Action(Permission.READ, new RefreshHandler(child));
        return act;
    }

    private class RefreshHandler implements Handler<ActionResult> {
        private Node kid;
        public RefreshHandler(Node kid) {
            this.kid = kid;
        }
        public void handle(ActionResult event) {
            final short val = kid.getAttribute("nodeId").getNumber().shortValue();
            conn.manager.refreshNodeInfo(homeId, val);
        }
    }

    private class SetPointHandler implements Handler<ValuePair> {
        private Node kid;
        public SetPointHandler(Node kid) {
            this.kid = kid;
        }
        public void handle(ValuePair event) {
            if (!event.isFromExternalSource()) return;
            sendValue(kid, event);
        }
    }

    protected Action setNameAction() {
        Action act = new Action(Permission.READ, new SetNameHandler());
        act.addParameter(new Parameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING, new Value(node.getDisplayName())));
        return act;
    }

    private class SetNameHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            String name = event.getParameter("name", org.dsa.iot.dslink.node.value.ValueType.STRING).getString();
            rename(name);
        }
    }

    protected void rename(String newName) {
        ZWaveDevice zwd = duplicate(newName);
        removeAndReplace(zwd);
    }

    protected ZWaveDevice duplicate(String newName) {
        Node newNode = parent.createChild(newName).build();
        newNode.setAttribute("nodeId", node.getAttribute("nodeId"));
        ZWaveDevice zwd = new ZWaveDevice(parent, newNode, homeId, /*manager,*/ link, conn);
        Action act = setNameAction();
        newNode.createChild("rename").setAction(act).build().setSerializable(false);
        moveAttrib(newNode);
        return zwd;
    }

    private void moveAttrib(Node newNode) {
        for (Node kid : node.getChildren().values()) {
            newNode.addChild(kid);
            addActions(newNode.getChild(kid.getName()));
        }
    }

    private void removeAndReplace(ZWaveDevice zwd) {
        conn.devices.remove(node.getAttribute("nodeId").getString());
        conn.devices.put(zwd.node.getAttribute("nodeId").getString(), zwd);
        parent.removeChild(node);
    }

    protected void changeValue(Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before it is stored as a child
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(conn.manager.getValueLabel(notification.getValueId()).replace("(%)", "(Percent)"));
            Node child = node.getChild(name);
            setValue(notification.getValueId(), child);
        }
        //LOGGER.info("Value changed - " + notification.getNodeId());
    }

    protected void removeValue(Notification notification) {
        short validClass = notification.getValueId().getCommandClassId();
        // ignore COMMAND_CLASS_BASIC (0x20) as it currently not used for USER access
        // ignored because the value is removed by ZWave before it is stored as a child
        if (validClass > (short) 0x20) {
            String name = StringUtils.encodeName(conn.manager.getValueLabel(notification.getValueId()).replace("(%)", "(Percent)"));
            node.removeChild(name);
        }
        LOGGER.info("Value removed - " + notification.getNodeId());
    }

    private void setValue(final ValueId valueId, Node child) {
        Value val;
        JsonArray valJson;
        switch (valueId.getType()) {
            case BOOL:
                final AtomicReference<Boolean> b = new AtomicReference<>();
                conn.manager.getValueAsBool(valueId, b);
                child.setValueType(ValueType.BOOL);
                val = new Value(b.get());
                child.setValue(val);
                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case BYTE:
                final AtomicReference<Short> bb = new AtomicReference<>();
                conn.manager.getValueAsByte(valueId, bb);
                child.setValueType(ValueType.NUMBER);
                val = new Value(bb.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case DECIMAL:
                if (child == null) {

                }
                final AtomicReference<Float> f = new AtomicReference<>();
                conn.manager.getValueAsFloat(valueId, f);
                child.setValueType(ValueType.NUMBER);
                val = new Value(f.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case INT:
                final AtomicReference<Integer> i = new AtomicReference<>();
                conn.manager.getValueAsInt(valueId, i);
                child.setValueType(ValueType.NUMBER);
                val = new Value(i.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case LIST:
                AtomicReference<String> l = new AtomicReference<>();
                conn.manager.getValueListSelectionString(valueId, l);
                List<String> ll = new ArrayList<>();
                conn.manager.getValueListItems(valueId, ll);
                Set<String> ls = new HashSet<>(ll);
                child.setValueType(ValueType.makeEnum(ls));
                val = new Value(l.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case SCHEDULE:
                // ToDo
                AtomicReference<Short> hrs = new AtomicReference<>();
                AtomicReference<Short> min = new AtomicReference<>();
                AtomicReference<Byte> sec = new AtomicReference<>();
                short numPoints = conn.manager.getNumSwitchPoints(valueId);
                Short[] hours = new Short[numPoints];
                Short[] minutes = new Short[numPoints];
                Byte[] setback = new Byte[numPoints];
                for (short n = 0; n < numPoints; n++) {
                    conn.manager.getSwitchPoint(valueId, n, hrs, min, sec);
                    hours[n] = hrs.get();
                    minutes[n] = min.get();
                    setback[n] = sec.get();
                }

                child.setValueType(ValueType.STRING);
                val = new Value("null");
                child.setValue(val);
                break;
            case SHORT:
                AtomicReference<Short> s = new AtomicReference<>();
                conn.manager.getValueAsShort(valueId, s);
                child.setValueType(ValueType.NUMBER);
                val = new Value(s.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case STRING:
                final AtomicReference<String> ss = new AtomicReference<>();
                conn.manager.getValueAsString(valueId, ss);
                child.setValueType(ValueType.STRING);
                val = new Value(ss.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case BUTTON:
                // ToDo
                AtomicReference<Boolean> bt = new AtomicReference<>();
                conn.manager.getValueAsBool(valueId, bt);
                child.setValueType(ValueType.BOOL);
                val = new Value(bt.get());
                child.setValue(val);

                child.setWritable(Writable.WRITE);
                child.getListener().setValueHandler(new SetPointHandler(child));
                break;
            case RAW:
                final AtomicReference<short[]> sss = new AtomicReference<>();
                conn.manager.getValueAsRaw(valueId, sss);
                valJson = new JsonArray();
                short[] shorts = sss.get();
                for (int j = 0; j < shorts.length; j++) {
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
                conn.manager.setValueAsBool(valId, entryBool);
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
                conn.manager.setValueAsByte(valId, entryByte);
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
                conn.manager.setValueAsFloat(valId, entryDecimal);
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
                conn.manager.setValueAsInt(valId, entryInt);
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
                conn.manager.setValueListSelection(valId, entryList);
                break;
            case "SCHEDULE":
                // ToDo
                short hours = event.getCurrent().getNumber().shortValue();
                short minutes = event.getCurrent().getNumber().shortValue();
                byte setback = event.getCurrent().getNumber().byteValue();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.SCHEDULE);
                conn.manager.setSwitchPoint(valId, hours, minutes, setback);
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
                conn.manager.setValueAsInt(valId, entryShort);
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
                conn.manager.setValueAsString(valId, entryString);
                break;
            case "BUTTON":
                boolean entryButton = event.getCurrent().getBool();
                valId = new ValueId(homeId,
                        kid.getAttribute("nodeId").getNumber().shortValue(),
                        returnGenre(kid),
                        kid.getAttribute("cc").getNumber().shortValue(),
                        kid.getAttribute("instance").getNumber().shortValue(),
                        kid.getAttribute("index").getNumber().shortValue(),
                        org.zwave4j.ValueType.BUTTON);
                if (entryButton) {
                    conn.manager.pressButton(valId);
                } else {
                    conn.manager.releaseButton(valId);
                }
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
                conn.manager.setValueAsRaw(valId, shorts);
                break;
            default:
                LOGGER.info("sendValue - unknown org.zwave4j.ValueType");
        }
        LOGGER.info("Value sent - " + kid.getAttribute("nodeId"));
    }

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

    private ZWaveDevice getMe() { return this; }
}
