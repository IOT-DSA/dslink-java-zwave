# dslink-java-zwave

A DSLink that wotks with ZWave. More information about ZWave can be located at www.z-wave.com

## Current bugs and features still under development

Upon startup, the controller node takes a few moments (possibly a few minutes) to
completely load all the node information.  This is standard for ZWave, and is device
dependent.  Full functionality of features are available once loading is completed
(specifically, the controller node actions).

You are able to remove the controller device during runtime (so that you can add or
remove other devices to/from it).  However, once you reinsert the controller, you
MUST use the refresh action from the controller node.

Mostly functional with the following features still under development:
- The SCHEDULE and BUTTON data types are not implemented.
- Have not implemented detection of non-setable points.  Currently, all points are
 assumed setable, even if they actually are not.
 
A local test run requires a broker to be actively running. 