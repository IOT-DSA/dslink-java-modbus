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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(IpConnectionWithDevice.class);
	}

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
		synchronized(masterLock) {
//			LOGGER.info(node.getName() + ": (-1) master is null? " + (master == null));
			init();
			
			Set<SlaveNode> slavescopy = new HashSet<SlaveNode>(slaves);
			//slaves.clear();
//			LOGGER.info(node.getName() + ": (0) master is null? " + (master == null));
			for (SlaveNode sn: slavescopy) {
				//SlaveNode newsn = addSlave(sn.node);
				//link.handleSlaveTransfer(sn, newsn);
//				LOGGER.info(node.getName() + ": calling init on slave " + sn.node.getName());
				sn.init();
			}
		}
		
	}

	@Override
	void init() {
//		LOGGER.info(node.getName() + ": getting Master");
		master = getMaster();
		if (master != null) {
//			LOGGER.info(node.getName() + ": got Master");
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			retryDelay = 1;
		} else {
//			LOGGER.info(node.getName() + ": failed to get Master, calling scheduleReconnect");
			statnode.setValue(new Value(NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
			scheduleReconnect();
		}
	}
	
	synchronized SlaveNode addSlave(Node slaveNode) {
		makeStopRestartActions(slaveNode);
		
		SlaveNodeWithConnection sn = new SlaveNodeWithConnection(this, slaveNode);
		sn.restoreLastSession();
		return sn;
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
	
	@Override
	public void writeMasterAttributes() {
		for (SlaveNode sn: new HashSet<SlaveNode>(slaves)) {
			sn.node.setAttribute(ATTR_TIMEOUT, new Value(timeout));
			sn.node.setAttribute(ATTR_RETRIES, new Value(retries));
			sn.node.setAttribute(ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			sn.node.setAttribute(ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			sn.node.setAttribute(ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			sn.node.setAttribute(ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			sn.node.setAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, new Value(mwo));
		}
	}
	
	@Override
	void writeIpAttributes() {
		for (SlaveNode sn: new HashSet<SlaveNode>(slaves)) {
			sn.node.setAttribute(ATTR_TRANSPORT_TYPE, new Value(transType.toString()));
			sn.node.setAttribute(ATTR_HOST, new Value(host));
			sn.node.setAttribute(ATTR_PORT, new Value(port));
		}
	}
	
	
}
