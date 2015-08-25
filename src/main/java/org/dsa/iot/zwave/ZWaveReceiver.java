package org.dsa.iot.zwave;

import org.dsa.iot.commons.GuaranteedReceiver;
import org.dsa.iot.dslink.node.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zwave4j.Manager;
import org.zwave4j.Notification;
import org.zwave4j.NotificationWatcher;

import java.io.IOException;

/**
 * Created by Peter Weise on 8/18/15.
 */

/*public class ZWaveReceiver extends GuaranteedReceiver<Manager> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZWaveReceiver.class);
    private Manager manager;
    private ZWaveConn conn;

    public ZWaveReceiver(ZWaveConn conn) {
        super(1);
        if (conn == null) {
            throw new NullPointerException("zwd");
        }
        this.conn = conn;
    }

    @Override
    protected Manager instantiate() throws Exception {
        manager = Manager.create();
        conn.controllerPort = conn.node.getAttribute("comm port id").getString();// "/dev/cu.SLAB_USBtoUART"; //
        manager.addDriver(conn.controllerPort);;
        manager.addWatcher(conn, null);
        return manager;
    }

    @Override
    protected boolean invalidateInstance(Exception e) {
        LOGGER.info("ZWaveReceiver - invalidateInstance: " + e);
        Throwable cause = e.getCause();
        return cause instanceof IOException;
    }
}*/
