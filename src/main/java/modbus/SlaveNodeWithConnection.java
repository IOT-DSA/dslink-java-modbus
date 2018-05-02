package modbus;

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
import org.dsa.iot.dslink.util.StringUtils;
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
public class SlaveNodeWithConnection extends SlaveNode {

	private static final Logger LOGGER;

	Node connStatNode;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	SlaveNodeWithConnection(ModbusConnection conn, Node node) {
		super(conn, node);
		connStatNode = node.getChild(ModbusConnection.NODE_STATUS, true);
		if (connStatNode == null) {
			connStatNode = node.createChild(ModbusConnection.NODE_STATUS, true).setValueType(ValueType.STRING)
					.setValue(conn.statnode.getValue()).build();
		} else {
			connStatNode.setValueType(ValueType.STRING);
			connStatNode.setValue(conn.statnode.getValue());
		}
	}

	@Override
	void makeEditAction() {
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
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND,
				ValueType.makeEnum(ModbusConnection.MULTIPLE_WRITE_COMMAND_OPTIONS),
				node.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND)));

		Node anode = node.getChild("edit", true);
		if (anode == null)
			node.createChild("edit", true).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();
		((IpConnectionWithDevice) conn).slaveRemoved();
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
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
			String currentMwo = node.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND).getString();

			boolean isConnectionChanged = !currentHost.equals(((IpConnection) conn).getHost())
					|| !(currentPort == ((IpConnection) conn).getPort()) || !(currentTimeout == conn.getTimeout())
					|| !(currentRetries == conn.getRetries()) || !(currentMaxrbc == conn.getMaxrbc())
					|| !(currentMaxrrc == conn.getMaxrrc()) || !(currentMaxwrc == conn.getMaxwrc())
					|| !(currentDdd == conn.getDdd()) || !(currentMwo.equals(conn.getUseMultipleWrites()));

			if (isConnectionChanged) {
				((IpConnection) conn).writeIpAttributes();

				conn.writeMasterAttributes();

			}

			String name = event.getParameter(ATTR_NAME, ValueType.STRING).getString();

			int slaveid = event.getParameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			intervalInMs = (long) (event.getParameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER)
					.getNumber().doubleValue() * 1000);
			boolean zerofail = event.getParameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL)
					.getBool();

			node.setAttribute(ModbusConnection.ATTR_SLAVE_ID, new Value(slaveid));
			node.setAttribute(ModbusConnection.ATTR_POLLING_INTERVAL, new Value(intervalInMs));
			node.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			node.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			node.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));

			conn.getLink().handleEdit(root);

			if (!name.equals(node.getName())) {
				rename(name);
			} else {

				if (isConnectionChanged) {
					conn.stop();
					conn.restoreLastSession();
				} else {
					checkDeviceConnected();
					makeEditAction();
				}
			}
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.getLink().serializer.serialize();
		JsonObject parentobj = jobj;
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(StringUtils.encodeName(name), nodeobj);
		conn.getLink().deserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name, true);

		ModbusConnection mc = new IpConnectionWithDevice(conn.getLink(), newnode);
		mc.restoreLastSession();
	}
}
