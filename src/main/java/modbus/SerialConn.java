package modbus;

import java.util.HashSet;
import java.util.Set;

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
import com.serotonin.modbus4j.serial.SerialPortWrapper;

public class SerialConn extends ModbusConnection {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SerialConn.class);
	}

	static final String ACTION_ADD_SERIAL_DEVICE = "add serial device";
	static final String ATTR_BAUD_RATE = "baud rate";
	static final String ATTR_DATA_BITS = "data bits";
	static final String ATTR_STOP_BITS = "stop bits";
	static final String ATTR_PARITY = "parity";

	SerialTransportType transType;
	String commPortId;
	int baudRate;
	int dataBits;
	int stopBits;
	int parity;
	String parityString;

	SerialConn(ModbusLink link, Node node) {
		super(link, node);
	}

	String getAddDeviceActionName() {
		return ACTION_ADD_SERIAL_DEVICE;
	}

	@Override
	Handler<ActionResult> getAddDeviceActionHandler() {
		return new AddDeviceHandler(this);

	}

	@Override
	void removeChild() {
		node.removeChild(ACTION_ADD_SERIAL_DEVICE, true);
	}

	Action getEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ATTR_CONNECTION_NAME, ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter(ModbusConnection.ATTR_TRANSPORT_TYPE,
				ValueType.makeEnum(Util.enumNames(SerialTransportType.class)),
				node.getAttribute(ModbusConnection.ATTR_TRANSPORT_TYPE)));

		Set<String> portids = new HashSet<String>();
		try {
			String[] cports = Util.getCommPorts();
			for (String port : cports) {
				portids.add(port);
			}
		} catch (Exception e) {
		}
		if (portids.size() > 0) {
			if (portids.contains(node.getAttribute(ATTR_COMM_PORT_ID).getString())) {
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.makeEnum(portids),
						node.getAttribute(ATTR_COMM_PORT_ID)));
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID_MANUAL, ValueType.STRING));
			} else {
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.makeEnum(portids)));
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID_MANUAL, ValueType.STRING,
						node.getAttribute(ATTR_COMM_PORT_ID)));
			}
		} else {
			act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.STRING, node.getAttribute(ATTR_COMM_PORT_ID)));
		}
		act.addParameter(new Parameter(ATTR_BAUD_RATE, ValueType.NUMBER, node.getAttribute(ATTR_BAUD_RATE)));
		act.addParameter(new Parameter(ATTR_DATA_BITS, ValueType.NUMBER, node.getAttribute(ATTR_DATA_BITS)));
		act.addParameter(new Parameter(ATTR_STOP_BITS, ValueType.NUMBER, node.getAttribute(ATTR_STOP_BITS)));
		act.addParameter(new Parameter(ATTR_PARITY, ValueType.makeEnum(Util.enumNames(ParityType.class)),
				node.getAttribute(ATTR_PARITY)));

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
			readSerialParameters(event);
			writeSerialAttributes();

			readMasterParameters(event);
			writeMasterAttributes();

			stop();

			if (!name.equals(node.getName())) {
				rename(name);
			} else {
				restoreLastSession();
			}
		}
	}

	@Override
	ModbusMaster getMaster() {
		if (this.master != null) {
			return this.master;
		}

		readSerialAttributes();
		readMasterAttributes();

		SerialPortWrapper wrapper = new SerialPortWrapperImpl(commPortId, baudRate, dataBits, stopBits, parity);
		switch (transType) {
		case RTU:
			master = new ModbusFactory().createRtuMaster(wrapper);
			break;
		case ASCII:
			master = new ModbusFactory().createAsciiMaster(wrapper);
			break;
		default:
			return null;
		}

		master.setTimeout(timeout);
		master.setRetries(retries);
		master.setMaxReadBitCount(maxrbc);
		master.setMaxReadRegisterCount(maxrrc);
		master.setMaxWriteRegisterCount(maxwrc);
		master.setDiscardDataDelay(ddd);
		master.setMultipleWritesOnly(mwo);

		try {
			master.init();
		} catch (ModbusInitException e) {
			LOGGER.error("error in initializing master : " + e.getMessage());
			LOGGER.debug("error in initializing master : ", e);
			master = null;
			return null;
		}

		link.masters.add(master);
		return master;
	}

	@Override
	void duplicate(ModbusLink link, Node newnode) {
		ModbusConnection conn = new SerialConn(link, newnode);
		conn.restoreLastSession();
	}

	static class AddDeviceHandler implements Handler<ActionResult> {

		private SerialConn conn;

		AddDeviceHandler(SerialConn conn) {
			this.conn = conn;
		}

		public void handle(ActionResult event) {
			String transtype = null;
			String commPortId = "na";
			String parityString = "NONE";
			int baudRate = 0;
			int dataBits = 0;
			int stopBits = 0;

			int timeout = 0;
			int retries = 0;
			int maxrbc = 0;
			int maxrrc = 0;
			int maxwrc = 0;
			int ddd = 0;
			boolean mwo = false;

			String name = event.getParameter(ATTR_CONNECTION_NAME, ValueType.STRING).getString();
			int slaveid = event.getParameter(ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter(ATTR_POLLING_INTERVAL, ValueType.NUMBER).getNumber()
					.doubleValue() * 1000);
			boolean zerofail = event.getParameter(ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL).getBool();
			long suppressDuration = (long) (event
					.getParameter(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, ValueType.NUMBER).getNumber()
					.doubleValue() * 1000);

			transtype = conn.node.getAttribute(ATTR_TRANSPORT_TYPE).getString();
			commPortId = conn.node.getAttribute(ATTR_COMM_PORT_ID).getString();
			baudRate = conn.node.getAttribute(ATTR_BAUD_RATE).getNumber().intValue();
			dataBits = conn.node.getAttribute(ATTR_DATA_BITS).getNumber().intValue();
			stopBits = conn.node.getAttribute(ATTR_STOP_BITS).getNumber().intValue();
			parityString = conn.node.getAttribute(ATTR_PARITY).getString();

			timeout = conn.node.getAttribute(ATTR_TIMEOUT).getNumber().intValue();
			retries = conn.node.getAttribute(ATTR_RETRIES).getNumber().intValue();
			maxrbc = conn.node.getAttribute(ATTR_MAX_READ_BIT_COUNT).getNumber().intValue();
			maxrrc = conn.node.getAttribute(ATTR_MAX_READ_REGISTER_COUNT).getNumber().intValue();
			maxwrc = conn.node.getAttribute(ATTR_MAX_WRITE_REGISTER_COUNT).getNumber().intValue();
			ddd = conn.node.getAttribute(ATTR_DISCARD_DATA_DELAY).getNumber().intValue();
			mwo = conn.node.getAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY).getBool();

			Node snode = conn.node.createChild(name, true).build();

			snode.setAttribute(ATTR_SLAVE_ID, new Value(slaveid));
			snode.setAttribute(ATTR_POLLING_INTERVAL, new Value(interval));
			snode.setAttribute(ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			snode.setAttribute(ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			snode.setAttribute(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));
			snode.setAttribute(ATTR_SUPPRESS_NON_COV_DURATION, new Value(suppressDuration));

			snode.setAttribute(ATTR_TRANSPORT_TYPE, new Value(transtype));
			snode.setAttribute(ATTR_COMM_PORT_ID, new Value(commPortId));
			snode.setAttribute(ATTR_BAUD_RATE, new Value(baudRate));
			snode.setAttribute(ATTR_DATA_BITS, new Value(dataBits));
			snode.setAttribute(ATTR_STOP_BITS, new Value(stopBits));
			snode.setAttribute(ATTR_PARITY, new Value(parityString));

			snode.setAttribute(ATTR_TIMEOUT, new Value(timeout));
			snode.setAttribute(ATTR_RETRIES, new Value(retries));
			snode.setAttribute(ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			snode.setAttribute(ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			snode.setAttribute(ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			snode.setAttribute(ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			snode.setAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, new Value(mwo));

			new SlaveNode(conn, snode);
		}
	}

	void readSerialParameters(ActionResult event) {
		transType = SerialTransportType
				.valueOf(event.getParameter(ATTR_TRANSPORT_TYPE, ValueType.STRING).getString().toUpperCase());

		Value customPort = event.getParameter(ATTR_COMM_PORT_ID_MANUAL);
		if (customPort != null && customPort.getString() != null && customPort.getString().trim().length() > 0) {
			commPortId = customPort.getString();
		} else {
			commPortId = event.getParameter(ATTR_COMM_PORT_ID).getString();
		}
		baudRate = event.getParameter(ATTR_BAUD_RATE, ValueType.NUMBER).getNumber().intValue();
		dataBits = event.getParameter(ATTR_DATA_BITS, ValueType.NUMBER).getNumber().intValue();
		stopBits = event.getParameter(ATTR_STOP_BITS, ValueType.NUMBER).getNumber().intValue();
		parityString = event.getParameter(ATTR_PARITY).getString();
	}

	void readSerialAttributes() {
		transType = SerialTransportType.valueOf(node.getAttribute(ATTR_TRANSPORT_TYPE).getString().toUpperCase());
		commPortId = node.getAttribute(ATTR_COMM_PORT_ID).getString();
		baudRate = node.getAttribute(ATTR_BAUD_RATE).getNumber().intValue();
		dataBits = node.getAttribute(ATTR_DATA_BITS).getNumber().intValue();
		stopBits = node.getAttribute(ATTR_STOP_BITS).getNumber().intValue();
		parity = ParityType.parseParity(node.getAttribute(ATTR_PARITY).getString());
	}

	void writeSerialAttributes() {
		node.setAttribute(ATTR_TRANSPORT_TYPE, new Value(transType.toString()));
		node.setAttribute(ATTR_COMM_PORT_ID, new Value(commPortId));
		node.setAttribute(ATTR_BAUD_RATE, new Value(baudRate));
		node.setAttribute(ATTR_DATA_BITS, new Value(dataBits));
		node.setAttribute(ATTR_STOP_BITS, new Value(stopBits));
		node.setAttribute(ATTR_PARITY, new Value(parityString));
	}
}
