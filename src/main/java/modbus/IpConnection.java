package modbus;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

public class IpConnection extends ModbusConnection {

	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(IpConnection.class);
	}

	static final String ATTR_NAME = "name";
	static final String ATTR_TRANSPORT_TYPE = "transport type";
	static final String ATTR_HOST = "host";
	static final String ATTR_PORT = "port";
	static final String ADD_IP_DEVICE_ACTION = "add ip device";

	IpTransportType transtype;
	String host;
	int port;

	IpConnection(ModbusLink link, Node node) {
		super(link, node);
	}

	ModbusMaster getMaster() {
		if (master != null) {
			return master;
		}

		if (reconnectFuture != null) {
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
		statnode.setValue(new Value(ATTR_STATUS_CONNECTING));

		readIpAttributes();
		readMasterAttributes();

		IpParameters params;
		switch (transtype) {
		case TCP:
			params = new IpParameters();
			params.setHost(host);
			params.setPort(port);
			master = modbusFactory.createTcpMaster(params, true);
			break;
		case UDP:
			params = new IpParameters();
			params.setHost(host);
			params.setPort(port);
			master = modbusFactory.createUdpMaster(params);
			break;
		default:
			return null;
		}

		writeMasterParameters();

		try {
			master.init();
			LOGGER.debug("Trying to connect");
		} catch (ModbusInitException e) {
			LOGGER.error("error in initializing master: " + e.getMessage() + " on " + host + ":" + port);
			statnode.setValue(new Value(ATTR_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
			node.removeChild("stop");
			makeStartAction();
			try {
				master.destroy();
				LOGGER.debug("Close connection");
			} catch (Exception e1) {
				LOGGER.debug(e1.getMessage());
			}
		}

		if (master.isInitialized()) {
			link.masters.add(master);
			return master;
		} else {
			return null;
		}
	}

	String getAddDeviceActionName() {
		return ADD_IP_DEVICE_ACTION;
	}

	@Override
	Handler<ActionResult> getAddDeviceActionHandler() {
		return new AddDeviceHandler(this);
	}

	@Override
	void duplicate(ModbusLink link, Node node) {
		// TBD
	}

	@Override
	void removeChild() {
		node.removeChild(ADD_IP_DEVICE_ACTION);
	}

	@Override
	Action getEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ATTR_NAME, ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter(ATTR_TRANSPORT_TYPE, ValueType.makeEnum(Util.enumNames(IpTransportType.class))));
		act.addParameter(new Parameter(ATTR_HOST, ValueType.STRING, node.getAttribute(ATTR_HOST)));
		act.addParameter(new Parameter(ATTR_PORT, ValueType.NUMBER, node.getAttribute(ATTR_PORT)));

		act.addParameter(new Parameter(ATTR_TIMEOUT, ValueType.NUMBER, node.getAttribute(ATTR_TIMEOUT)));
		act.addParameter(new Parameter(ATTR_RETRIES, ValueType.NUMBER, node.getAttribute(ATTR_RETRIES)));
		act.addParameter(
				new Parameter(ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER, node.getAttribute(ATTR_MAX_READ_BIT_COUNT)));
		act.addParameter(new Parameter(ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER,
				node.getAttribute(ATTR_MAX_READ_REGISTER_COUNT)));
		act.addParameter(new Parameter(ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER,
				node.getAttribute(ATTR_MAX_WRITE_REGISTER_COUNT)));
		act.addParameter(
				new Parameter(ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER, node.getAttribute(ATTR_DISCARD_DATA_DELAY)));
		act.addParameter(new Parameter(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, ValueType.BOOL,
				node.getAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY)));

		return act;
	}

	private class EditHandler implements Handler<ActionResult> {

		public void handle(ActionResult event) {
			readIpParameters(event);
			writeIpAttributes();

			readMasterParameters(event);
			writeMasterAttributes();

			stop();

			if (!name.equals(node.getName())) {
				rename(name);
			}

			restoreLastSession();
		}
	}

	class AddDeviceHandler implements Handler<ActionResult> {
		private ModbusConnection conn;

		AddDeviceHandler(ModbusConnection conn) {
			this.conn = conn;
		}

		public void handle(ActionResult event) {
			String name = event.getParameter(ATTR_SLAVE_NAME, ValueType.STRING).getString();
			Node deviceNode = node.createChild(name).build();

			int slaveid = event.getParameter(ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			long intervalMs = (long) (event.getParameter(ATTR_POLLING_INTERVAL, ValueType.NUMBER).getNumber().intValue()
					* 1000);
			boolean zerofail = event.getParameter(ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL).getBool();

			deviceNode.setAttribute(ATTR_SLAVE_ID, new Value(slaveid));
			deviceNode.setAttribute(ATTR_POLLING_INTERVAL, new Value(intervalMs));
			deviceNode.setAttribute(ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			deviceNode.setAttribute(ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			deviceNode.setAttribute(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));

			new SlaveNode(conn, deviceNode);
		}
	}

	void readIpAttributes() {
		transtype = IpTransportType.valueOf(node.getAttribute(ATTR_TRANSPORT_TYPE).getString().toUpperCase());
		host = node.getAttribute(ATTR_HOST).getString();
		port = node.getAttribute(ATTR_PORT).getNumber().intValue();
	}

	void writeIpAttributes() {
		node.setAttribute(ATTR_TRANSPORT_TYPE, new Value(transtype.toString()));
		node.setAttribute(ATTR_HOST, new Value(host));
		node.setAttribute(ATTR_PORT, new Value(port));
	}

	void readIpParameters(ActionResult event) {
		transtype = IpTransportType
				.valueOf(event.getParameter(ATTR_TRANSPORT_TYPE, ValueType.STRING).getString().toUpperCase());
		host = event.getParameter(ATTR_HOST, ValueType.STRING).getString();
		port = event.getParameter(ATTR_PORT, ValueType.NUMBER).getNumber().intValue();
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

}
