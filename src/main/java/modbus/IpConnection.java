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

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

import modbus.SlaveNode.TransportType;

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

	IpConnection(ModbusLink link, Node node) {
		super(link, node);
	}

	ModbusMaster getMaster() {
		if (this.master != null) {
			return this.master;
		}

		if (reconnectFuture != null) {
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
		statnode.setValue(new Value("connecting to device"));

		String host = node.getAttribute(ATTR_HOST).getString();
		int port = node.getAttribute(ATTR_PORT).getNumber().intValue();

		getMasterAttributes();

		IpTransportType transtype = null;
		try {
			transtype = IpTransportType.valueOf(node.getAttribute(ATTR_TRANSPORT_TYPE).getString().toUpperCase());
		} catch (Exception e1) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e1);
			statnode.setValue(new Value("invalid transport type"));
			return null;
		}
		switch (transtype) {
		case TCP: {
			IpParameters params = new IpParameters();
			params.setHost(host);
			params.setPort(port);
			master = new ModbusFactory().createTcpMaster(params, true);
			break;
		}
		case UDP: {
			IpParameters params = new IpParameters();
			params.setHost(host);
			params.setPort(port);
			master = new ModbusFactory().createUdpMaster(params);
			break;
		}
		default:
			return null;
		}

		setMasterAttributes();

		try {
			master.init();
			LOGGER.debug("Trying to connect");
		} catch (ModbusInitException e) {
			LOGGER.error("error in initializing master:" + e.getMessage() + " on " + host + ":" + port);
			statnode.setValue(new Value("Could not establish connection"));
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
		act.addParameter(new Parameter(ATTR_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTR_TRANSPORT_TYPE, ValueType.makeEnum(Util.enumNames(IpTransportType.class))));
		act.addParameter(new Parameter(ATTR_HOST, ValueType.STRING, new Value("")));
		act.addParameter(new Parameter(ATTR_PORT, ValueType.NUMBER, new Value(502)));

		act.addParameter(new Parameter(ATTR_TIMEOUT, ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter(ATTR_RETRIES, ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter(ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter(ATTR_MAX_READ_READ_REGISYER_COUNT, ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter(ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter(ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, ValueType.BOOL, new Value(false)));

		return act;
	}

	private class EditHandler implements Handler<ActionResult> {

		public void handle(ActionResult event) {
			TransportType transtype;
			try {
				transtype = TransportType
						.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid transport type");
				LOGGER.debug("error: ", e);
				return;
			}

			readMasterParameters(event);
			node.setAttribute("transport type", new Value(transtype.toString()));
			setMasterAttributes();

			stop();

			if (!name.equals(node.getName())) {
				rename(name);
			}

			restoreLastSession();
		}
	}
	// @Override
	// Handler<ActionResult> getAddDeviceActionHandler() {
	// return new AddIpDeviceHandler(this);
	//
	// }

	class AddDeviceHandler implements Handler<ActionResult> {

		private ModbusConnection conn;

		AddDeviceHandler(ModbusConnection conn) {
			this.conn = conn;
		}

		public void handle(ActionResult event) {

			String name = event.getParameter("name", ValueType.STRING).getString();
			Node deviceNode = node.createChild(name).build();

			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean zerofail = event.getParameter("zero on failed poll", ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();

			deviceNode.setAttribute("slave id", new Value(slaveid));
			deviceNode.setAttribute("polling interval", new Value(interval));
			deviceNode.setAttribute("zero on failed poll", new Value(zerofail));
			deviceNode.setAttribute("use batch polling", new Value(batchpoll));
			deviceNode.setAttribute("contiguous batch requests only", new Value(contig));

			new SlaveNode(getLink(), deviceNode, conn);
		}
	}

}
