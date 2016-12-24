package modbus;

import java.util.HashSet;
import java.util.List;
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

import com.serotonin.io.serial.CommPortConfigException;
import com.serotonin.io.serial.CommPortProxy;
import com.serotonin.io.serial.SerialParameters;
import com.serotonin.io.serial.SerialUtils;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.serial.ModSerialParameters;

public class SerialConn extends ModbusConnection {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SerialConn.class);
	}

	static final String ADD_SERIAL_DEVICE_ACTION = "add serial device";

	SerialConn(ModbusLink link, Node node) {
		super(link, node);
	}

	String getAddDeviceActionName() {
		return ADD_SERIAL_DEVICE_ACTION;
	}

	@Override
	Handler<ActionResult> getAddDeviceActionHandler() {
		return new AddDeviceHandler(this);

	}

	@Override
	void removeChild() {
		node.removeChild(ADD_SERIAL_DEVICE_ACTION);
	}

	Action getEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("transport type", ValueType.makeEnum(Util.enumNames(SerialTransportType.class)),
				node.getAttribute("transport type")));

		Set<String> portids = new HashSet<String>();
		try {
			List<CommPortProxy> cports = SerialUtils.getCommPorts();
			for (CommPortProxy port : cports) {
				portids.add(port.getId());
			}
		} catch (CommPortConfigException e) {
			// TODO Auto-generated catch block
		}
		if (portids.size() > 0) {
			if (portids.contains(node.getAttribute("comm port id").getString())) {
				act.addParameter(
						new Parameter("comm port id", ValueType.makeEnum(portids), node.getAttribute("comm port id")));
				act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING));
			} else {
				act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids)));
				act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING,
						node.getAttribute("comm port id")));
			}
		} else {
			act.addParameter(new Parameter("comm port id", ValueType.STRING, node.getAttribute("comm port id")));
		}
		act.addParameter(new Parameter("baud rate", ValueType.NUMBER, node.getAttribute("baud rate")));
		act.addParameter(new Parameter("data bits", ValueType.NUMBER, node.getAttribute("data bits")));
		act.addParameter(new Parameter("stop bits", ValueType.NUMBER, node.getAttribute("stop bits")));
		act.addParameter(new Parameter("parity", ValueType.makeEnum("NONE", "ODD", "EVEN", "MARK", "SPACE"),
				node.getAttribute("parity")));

		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, node.getAttribute("Timeout")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(
				new Parameter("max read bit count", ValueType.NUMBER, node.getAttribute("max read bit count")));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER,
				node.getAttribute("max read register count")));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER,
				node.getAttribute("max write register count")));
		act.addParameter(
				new Parameter("discard data delay", ValueType.NUMBER, node.getAttribute("discard data delay")));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL,
				node.getAttribute("use multiple write commands only")));

		act.addParameter(new Parameter("send requests all at once", ValueType.BOOL,
				node.getAttribute("send requests all at once")));
		act.addParameter(new Parameter("set custom spacing", ValueType.BOOL, node.getAttribute("set custom spacing")));
		act.addParameter(
				new Parameter("message frame spacing", ValueType.NUMBER, node.getAttribute("message frame spacing")));
		act.addParameter(new Parameter("character spacing", ValueType.NUMBER, node.getAttribute("character spacing")));

		return act;
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			SerialTransportType transtype;
			try {
				transtype = SerialTransportType
						.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid transport type");
				LOGGER.debug("error: ", e);
				return;
			}
			String commPortId;
			Value customPort = event.getParameter("comm port id (manual entry)");
			if (customPort != null && customPort.getString() != null && customPort.getString().trim().length() > 0) {
				commPortId = customPort.getString();
			} else {
				commPortId = event.getParameter("comm port id").getString();
			}
			int baudRate = event.getParameter("baud rate", ValueType.NUMBER).getNumber().intValue();
			int dataBits = event.getParameter("data bits", ValueType.NUMBER).getNumber().intValue();
			int stopBits = event.getParameter("stop bits", ValueType.NUMBER).getNumber().intValue();
			String parityString = event.getParameter("parity").getString();

			boolean useMods = event.getParameter("send requests all at once", ValueType.BOOL).getBool();
			boolean useCustomSpacing = event.getParameter("set custom spacing", ValueType.BOOL).getBool();
			long msgSpacing = event.getParameter("message frame spacing", ValueType.NUMBER).getNumber().longValue();
			long charSpacing = event.getParameter("character spacing", ValueType.NUMBER).getNumber().longValue();

			node.setAttribute("comm port id", new Value(commPortId));
			node.setAttribute("baud rate", new Value(baudRate));
			node.setAttribute("data bits", new Value(dataBits));
			node.setAttribute("stop bits", new Value(stopBits));
			node.setAttribute("parity", new Value(parityString));

			node.setAttribute("send requests all at once", new Value(useMods));
			node.setAttribute("set custom spacing", new Value(useCustomSpacing));
			node.setAttribute("message frame spacing", new Value(msgSpacing));
			node.setAttribute("character spacing", new Value(charSpacing));

			String name = event.getParameter("name", ValueType.STRING).getString();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();

			node.setAttribute("transport type", new Value(transtype.toString()));
			node.setAttribute("Timeout", new Value(timeout));
			node.setAttribute("retries", new Value(retries));
			node.setAttribute("max read bit count", new Value(maxrbc));
			node.setAttribute("max read register count", new Value(maxrrc));
			node.setAttribute("max write register count", new Value(maxwrc));
			node.setAttribute("discard data delay", new Value(ddd));
			node.setAttribute("use multiple write commands only", new Value(mwo));

			stop();

			if (!name.equals(node.getName())) {
				rename(name);
			}

			restoreLastSession();
		}
	}

	@Override
	ModbusMaster getMaster() {
		if (this.master != null) {
			return this.master;
		}

		SerialTransportType transtype = null;
		try {
			transtype = SerialTransportType.valueOf(node.getAttribute("transport type").getString().toUpperCase());
		} catch (Exception e1) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e1);
			statnode.setValue(new Value("invalid transport type"));
			return null;
		}

		String commPortId = node.getAttribute("comm port id").getString();
		int baudRate = node.getAttribute("baud rate").getNumber().intValue();
		int dataBits = node.getAttribute("data bits").getNumber().intValue();
		int stopBits = node.getAttribute("stop bits").getNumber().intValue();
		int parity = ModbusLink.parseParity(node.getAttribute("parity").getString());

		int timeout = node.getAttribute("Timeout").getNumber().intValue();
		int retries = node.getAttribute("retries").getNumber().intValue();
		int maxrbc = node.getAttribute("max read bit count").getNumber().intValue();
		int maxrrc = node.getAttribute("max read register count").getNumber().intValue();
		int maxwrc = node.getAttribute("max write register count").getNumber().intValue();
		int ddd = node.getAttribute("discard data delay").getNumber().intValue();
		boolean mwo = node.getAttribute("use multiple write commands only").getBool();

		boolean useMods = node.getAttribute("send requests all at once").getBool();
		boolean useCustomSpacing = node.getAttribute("set custom spacing").getBool();
		long msgSpacing = node.getAttribute("message frame spacing").getNumber().longValue();
		long charSpacing = node.getAttribute("character spacing").getNumber().longValue();

		SerialParameters params;
		switch (transtype) {
		case RTU:
			if (useMods) {
				params = new ModSerialParameters();
			} else {
				params = new SerialParameters();
			}
			params.setCommPortId(commPortId);
			params.setBaudRate(baudRate);
			params.setDataBits(dataBits);
			params.setStopBits(stopBits);
			params.setParity(parity);

			LOGGER.debug("Getting RTU master");
			if (useCustomSpacing) {
				master = new ModbusFactory().createRtuMaster(params, charSpacing, msgSpacing);
			} else {
				master = new ModbusFactory().createRtuMaster(params);
			}
			break;
		case ASCII:
			if (useMods) {
				params = new ModSerialParameters();
			} else {
				params = new SerialParameters();
			}
			params.setCommPortId(commPortId);
			params.setBaudRate(baudRate);
			params.setDataBits(dataBits);
			params.setStopBits(stopBits);
			params.setParity(parity);

			master = new ModbusFactory().createAsciiMaster(params);
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
			LOGGER.error("error initializing master");
			LOGGER.debug("error: ", e);
			statnode.setValue(new Value("Could not establish connection - ModbusInitException"));
			stop();
			return null;
		}
		for (SlaveNode sn : slaves) {
			sn.master = master;
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

			long msgSpacing = 0;
			long charSpacing = 0;
			boolean useMods = false;
			boolean useCustomSpacing = false;

			int timeout = 0;
			int retries = 0;
			int maxrbc = 0;
			int maxrrc = 0;
			int maxwrc = 0;
			int ddd = 0;
			boolean mwo = false;

			String name = event.getParameter("name", ValueType.STRING).getString();
			transtype = conn.node.getAttribute("transport type").getString();
			commPortId = conn.node.getAttribute("comm port id").getString();
			baudRate = conn.node.getAttribute("baud rate").getNumber().intValue();
			dataBits = conn.node.getAttribute("data bits").getNumber().intValue();
			stopBits = conn.node.getAttribute("stop bits").getNumber().intValue();
			parityString = conn.node.getAttribute("parity").getString();

			useMods = conn.node.getAttribute("send requests all at once").getBool();
			useCustomSpacing = conn.node.getAttribute("set custom spacing").getBool();
			msgSpacing = conn.node.getAttribute("message frame spacing").getNumber().longValue();
			charSpacing = conn.node.getAttribute("character spacing").getNumber().longValue();

			timeout = conn.node.getAttribute("Timeout").getNumber().intValue();
			retries = conn.node.getAttribute("retries").getNumber().intValue();
			maxrbc = conn.node.getAttribute("max read bit count").getNumber().intValue();
			maxrrc = conn.node.getAttribute("max read register count").getNumber().intValue();
			maxwrc = conn.node.getAttribute("max write register count").getNumber().intValue();
			ddd = conn.node.getAttribute("discard data delay").getNumber().intValue();
			mwo = conn.node.getAttribute("use multiple write commands only").getBool();

			Node snode = conn.node.createChild(name).build();

			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean zerofail = event.getParameter("zero on failed poll", ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();

			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("comm port id", new Value(commPortId));
			snode.setAttribute("baud rate", new Value(baudRate));
			snode.setAttribute("data bits", new Value(dataBits));
			snode.setAttribute("stop bits", new Value(stopBits));
			snode.setAttribute("parity", new Value(parityString));

			snode.setAttribute("slave id", new Value(slaveid));
			snode.setAttribute("polling interval", new Value(interval));
			snode.setAttribute("zero on failed poll", new Value(zerofail));
			snode.setAttribute("use batch polling", new Value(batchpoll));
			snode.setAttribute("contiguous batch requests only", new Value(contig));

			snode.setAttribute("Timeout", new Value(timeout));
			snode.setAttribute("retries", new Value(retries));
			snode.setAttribute("max read bit count", new Value(maxrbc));
			snode.setAttribute("max read register count", new Value(maxrrc));
			snode.setAttribute("max write register count", new Value(maxwrc));
			snode.setAttribute("discard data delay", new Value(ddd));

			snode.setAttribute("use multiple write commands only", new Value(mwo));
			snode.setAttribute("send requests all at once", new Value(useMods));
			snode.setAttribute("set custom spacing", new Value(useCustomSpacing));
			snode.setAttribute("message frame spacing", new Value(msgSpacing));
			snode.setAttribute("character spacing", new Value(charSpacing));

			new SlaveNode(conn, snode);
		}
	}
}
