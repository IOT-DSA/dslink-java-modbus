package modbus;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
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
	
	final ConnectionRestorer restorer = new ConnectionRestorer(this);

	IpConnectionWithDevice(ModbusLink link, final Node node) {
		super(link, node);
		node.setAttribute(ATTR_RESTORE_TYPE, new Value(SlaveFolder.ATTR_RESTORE_FOLDER));
		statnode.getListener().setValueHandler(new Handler<ValuePair>() {
			@Override
			public void handle(ValuePair event) {
				Value value = event.getCurrent();
				LOGGER.info("(!) Status listener got update with value <" + value.toString() + "> : " + node.getName());
				for (SlaveNode sn : new HashSet<SlaveNode>(slaves)) {
					if (sn.node != node && sn instanceof SlaveNodeWithConnection
							&& ((SlaveNodeWithConnection) sn).connStatNode != null) {
						((SlaveNodeWithConnection) sn).connStatNode.setValue(value);
					}
					if (ModbusConnection.NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED.equals(value.getString()) 
							|| ModbusConnection.NODE_STATUS_CONNECTION_STOPPED.equals(value.getString())) {
						sn.getStatusNode().setValue(new Value(SlaveNode.NODE_STATUS_CONN_DOWN));
					}
				}
			}
		});
		LOGGER.info("(!) Set up status listener: " + node.getName());
	}

	@Override
	void restoreLastSession() {
		synchronized (masterLock) {
			LOGGER.info("(!) Restoring: " + node.getName());
			init();

			Set<SlaveNode> slavescopy = new HashSet<SlaveNode>(slaves);
			for (SlaveNode sn : slavescopy) {
				sn.init();
			}
		}

	}

	@Override
	void init() {
		master = getMaster();
		if (master != null) {
			LOGGER.info("(!) Got master: " + node.getName());
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			retryDelay = 1;
		} else {
			LOGGER.info("(!) Failed to get master: " + node.getName());
			statnode.setValue(new Value(NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
			scheduleReconnect();
		}
	}

	SlaveNode addSlave(Node slaveNode) {
		makeStopRestartActions(slaveNode);

		SlaveNodeWithConnection sn = new SlaveNodeWithConnection(this, slaveNode);
		sn.restoreLastSession();
		return sn;
	}

	void makeStopRestartActions(final Node slaveNode) {
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
		
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				event.getTable().addRow(Row.make(slaveNode.getChild("Connection Status", false).getValue()));
			}
		});
		act.addResult(new Parameter("Connection Status", ValueType.STRING));
		slaveNode.createChild("get conn status", true).setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				Node statnode = slaveNode.getChild("Connection Status", false);
				Value statval = statnode.getValue();
				statnode.setValue(statval);
			}
		});
		slaveNode.createChild("reupdate conn status", true).setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				event.getTable().addRow(Row.make(slaveNode.getChild("Device Status", false).getValue()));
			}
		});
		act.addResult(new Parameter("Device Status", ValueType.STRING));
		slaveNode.createChild("get dev status", true).setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				Node statnode = slaveNode.getChild("Device Status", false);
				Value statval = statnode.getValue();
				statnode.setValue(statval);
			}
		});
		slaveNode.createChild("reupdate dev status", true).setAction(act).build().setSerializable(false);
	}

	void slaveRemoved() {
		if (slaves.isEmpty()) {
			remove();
		} else if (!node.getParent().hasChild(node.getName(), false)) {
			node = slaves.iterator().next().node;
		}
	}

	@Override
	public void writeMasterAttributes() {
		for (SlaveNode sn : new HashSet<SlaveNode>(slaves)) {
			sn.node.setAttribute(ATTR_TIMEOUT, new Value(timeout));
			sn.node.setAttribute(ATTR_RETRIES, new Value(retries));
			sn.node.setAttribute(ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			sn.node.setAttribute(ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			sn.node.setAttribute(ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			sn.node.setAttribute(ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			sn.node.setAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND, new Value(mw));
		}
	}

	@Override
	void writeIpAttributes() {
		for (SlaveNode sn : new HashSet<SlaveNode>(slaves)) {
			sn.node.setAttribute(ATTR_TRANSPORT_TYPE, new Value(transType.toString()));
			sn.node.setAttribute(ATTR_HOST, new Value(host));
			sn.node.setAttribute(ATTR_PORT, new Value(port));
		}
	}
	
	static class ConnectionRestorer {
		AtomicBoolean started = new AtomicBoolean(false);
		boolean done = false;
		IpConnectionWithDevice conn;
		ConnectionRestorer(IpConnectionWithDevice conn) {
			this.conn = conn;
		}
		
		public void restore() {
			if (started.compareAndSet(false, true)) {
				conn.restoreLastSession();
				synchronized (this) {
					done = true;
					notifyAll();
				}
			} else {
				synchronized(this) {
					while(!done) {
						try {
							wait();
						} catch (InterruptedException e) {
							LOGGER.error("", e);
						}
					}
				}
			}
		}
	}

}
