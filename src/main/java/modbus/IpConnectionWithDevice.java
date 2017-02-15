package modbus;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.value.Value;

/*
 * A special class to handle the legacy project based on the two-tier design.
 * 
 * Link
 *     |
 *     ->Device Node
 * 
 * The Device Node and its connection  share the same node.
 * 
 * */

public class IpConnectionWithDevice extends IpConnection {

	IpConnectionWithDevice(ModbusLink link, Node node) {
		super(link, node);
	}

	@Override
	void restoreLastSession() {
		init();
	}

	@Override
	void init() {
		Action act = new Action(Permission.READ, new RestartHandler());
		Node anode = node.getChild(ACTION_RESTART, true);
		if (anode == null) {
			node.createChild(ACTION_RESTART, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}

		makeStopAction();

		master = getMaster();
		if (master != null) {
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			slaves.clear();
			SlaveNodeWithConnection sn = new SlaveNodeWithConnection(this, node);
			sn.restoreLastSession();
		} else {
			statnode.setValue(new Value(NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
			scheduleReconnect();
		}
	}
}
