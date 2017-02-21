package modbus;

import java.util.HashSet;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.util.handler.Handler;

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

	IpConnectionWithDevice(ModbusLink link, final Node node) {
		super(link, node);
		node.setAttribute(ATTR_RESTORE_TYPE, new Value(SlaveFolder.ATTR_RESTORE_FOLDER));
		statnode.getListener().setValueHandler(new Handler<ValuePair>(){
			@Override
			public void handle(ValuePair event) {
				Value value = event.getCurrent();
				for (SlaveNode sn: new HashSet<SlaveNode>(slaves)) {
					if (sn.node != node) {
						((SlaveNodeWithConnection) sn).connStatNode.setValue(value);
					}
				}
			}
		});
	}

	@Override
	void restoreLastSession() {
		init();
		
		Set<SlaveNode> slavescopy = new HashSet<SlaveNode>(slaves);
		slaves.clear();
		for (SlaveNode sn: slavescopy) {
			if (sn.node != node) {
				addSlave(sn.node);
			} else {
				slaves.add(sn);
			}
		}
		
	}

	@Override
	void init() {
		makeStopRestartActions(node);

		master = getMaster();
		if (master != null) {
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			SlaveNodeWithConnection sn = new SlaveNodeWithConnection(this, node);
			sn.restoreLastSession();
		} else {
			statnode.setValue(new Value(NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
			scheduleReconnect();
		}
	}
	
	void addSlave(Node slaveNode) {
		makeStopRestartActions(slaveNode);
		
		SlaveNodeWithConnection sn = new SlaveNodeWithConnection(this, slaveNode);
		sn.restoreLastSession();
	}
	
	void makeStopRestartActions(Node slaveNode) {
		Action act = new Action(Permission.READ, new RestartHandler());
		Node anode = slaveNode.getChild(ACTION_RESTART, true);
		if (anode == null) {
			slaveNode.createChild(ACTION_RESTART, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}

		act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				if (reconnectFuture != null) {
					reconnectFuture.cancel(false);
				}
				stop();
			}
		});

		anode = slaveNode.getChild("stop", true);
		if (anode == null)
			slaveNode.createChild("stop", true).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}
	
	void slaveRemoved() {
		if (slaves.isEmpty()) {
			remove();
		} else if (!node.getParent().hasChild(node.getName())) {
			node = slaves.iterator().next().node;
		}
	}
	
}
