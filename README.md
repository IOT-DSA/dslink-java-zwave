# dslink-java-zwave
Mostly functional with the following features still under development:
- The "refresh" feature for the ZWave controller is broken (crashes JVM).
 As a result, if the controller device is removed and inserted again,
 the app must be restarted
- The SCHEDULE, BUTTON data types are not implemented.
- ZWaveLink class requires ARGS0 to have the path to the openzwave-master folder.
 This is currently hard coded, will be implemented to accept user provided path.
- Have not implemented detection of non-setable points.  Currently, all points are
 assumed setable, even if they actually are not.
- Default cases for setValue(), sendValue() in ZWaveDevice,
 and onNotification() in ZWaveConn are not fully implemented