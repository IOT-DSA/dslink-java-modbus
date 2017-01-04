package modbus;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.ModbusMaster;

/*
 * A special class to handle the legal project based on the two-tier design.
 * 
 * The Device Node and its connection  share the same node.
 * 
 * */
public class SlaveNodeWithConnection extends SlaveNode {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	ModbusLink link;

	SlaveNodeWithConnection(ModbusLink link, final ModbusConnection conn, Node node) {
		super(conn, node);

		this.link = link;
		statnode.setValue(conn.statnode.getValue());

		ScheduledThreadPoolExecutor stpe = conn.getDaemonThreadPool();
		stpe.execute(new Runnable() {
			@Override
			public void run() {
				conn.checkConnection();
			}
		});

		makeEditAction();

		if (master != null && master.isInitialized()) {
			makeStopAction();
		} else {
			makeStartAction();
		}
	}

	private void makeStartAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				master = getMaster();
				if (null != master) {
					conn.checkConnection();
					node.removeChild("start");
					makeStopAction();
				}

			}
		});

		Node anode = node.getChild("start");
		if (anode == null)
			node.createChild("start").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	private void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				if (master != null) {
					master.destroy();
					link.masters.remove(master);
					master = null;
				}
				node.removeChild("stop");
				statnode.setValue(new Value("Stopped"));
				makeStartAction();
			}
		});

		Node anode = node.getChild("stop");
		if (anode == null)
			node.createChild("stop").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));

		act.addParameter(
				new Parameter("transport type", ValueType.makeEnum("TCP", "UDP"), node.getAttribute("transport type")));
		act.addParameter(new Parameter("host", ValueType.STRING, node.getAttribute("host")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));

		// The device specific parameters
		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_SLAVE_ID)));
		int interval = node.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL).getNumber().intValue() / 1000;
		act.addParameter(new Parameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER, new Value(interval)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY)));

		// the common parameters for connection
		act.addParameter(new Parameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_TIMEOUT)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_RETRIES)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY)));

		Node anode = node.getChild("edit");
		if (anode == null)
			node.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();

		if (conn.slaves.isEmpty()) {
			try {
				master.destroy();
				link.masters.remove(master);
				master = null;
			} catch (Exception e) {
				LOGGER.debug("error destroying last master");
			}

			ScheduledThreadPoolExecutor stpe = conn.getDaemonThreadPool();
			stpe.shutdown();
		}

	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			IpTransportType transtype;
			try {
				transtype = IpTransportType
						.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid transport type");
				LOGGER.debug("error: ", e);
				return;
			}

			((IpConnection) conn).readIpParameters(event);
			conn.readMasterParameters(event);

			String currentHost = node.getAttribute(IpConnection.ATTR_HOST).getString();
			int currentPort = node.getAttribute(IpConnection.ATTR_PORT).getNumber().intValue();

			int currentTimeout = node.getAttribute(ModbusConnection.ATTR_TIMEOUT).getNumber().intValue();
			int currentRetries = node.getAttribute(ModbusConnection.ATTR_RETRIES).getNumber().intValue();
			int currentMaxrbc = node.getAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT).getNumber().intValue();
			int currentMaxrrc = node.getAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT).getNumber().intValue();
			int currentMaxwrc = node.getAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT).getNumber()
					.intValue();
			int currentDdd = node.getAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY).getNumber().intValue();
			boolean currentMwo = node.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY).getBool();

			boolean isConnectionChanged = !currentHost.equals(((IpConnection) conn).getHost())
					|| !(currentPort == ((IpConnection) conn).getPort()) || !(currentTimeout == conn.getTimeout())
					|| !(currentRetries == conn.getRetries()) || !(currentMaxrbc == conn.getMaxrbc())
					|| !(currentMaxrrc == conn.getMaxrrc()) || !(currentMaxwrc == conn.getMaxwrc())
					|| !(currentDdd == conn.getDdd()) || !(currentMwo == conn.isMwo());

			if (isConnectionChanged) {
				((IpConnection) conn).writeIpAttributes();

				conn.writeMasterAttributes();

				if (master != null) {
					try {
						master.destroy();
						link.masters.remove(master);
						master = null;
					} catch (Exception e) {
						LOGGER.debug("error destroying last master");
					}
				}
				master = getMaster();
				conn.checkConnection();
			}

			String name = event.getParameter(ATTR_NAME, ValueType.STRING).getString();

			int slaveid = event.getParameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			intervalInMs = (long) (event.getParameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER)
					.getNumber().doubleValue() * 1000);
			boolean zerofail = event.getParameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL)
					.getBool();

			link.handleEdit(root);

			node.setAttribute(ModbusConnection.ATTR_SLAVE_ID, new Value(slaveid));
			node.setAttribute(ModbusConnection.ATTR_POLLING_INTERVAL, new Value(intervalInMs));
			node.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			node.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			node.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));

			if (!name.equals(node.getName())) {
				rename(name);
			}

			makeEditAction();
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = jobj;
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);

		SlaveNode sn = new SlaveNodeWithConnection(link, conn, newnode);
		sn.restoreLastSession();
	}

	@Override
	public ModbusMaster getMaster() {
		return conn.getMaster();
	}
}
