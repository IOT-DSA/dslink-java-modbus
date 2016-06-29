package modbus;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.io.serial.CommPortConfigException;
import com.serotonin.io.serial.CommPortProxy;
import com.serotonin.io.serial.SerialUtils;
import com.serotonin.modbus4j.ModbusMaster;

public class ModbusLink {
	static private final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(ModbusLink.class);
	}

	Node node;
	Serializer copySerializer;
	Deserializer copyDeserializer;
	private final Map<SlaveNode, ScheduledFuture<?>> futures;
	final Set<SerialConn> serialConns;
	final Set<ModbusMaster> masters;

	static ModbusLink singleton;

	private ModbusLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.copySerializer = ser;
		this.copyDeserializer = deser;
		this.futures = new ConcurrentHashMap<>();
		this.serialConns = new HashSet<SerialConn>();
		this.masters = new HashSet<ModbusMaster>();
	}

	public static void start(Node parent, Serializer copyser, Deserializer copydeser) {
		Node node = parent;
		final ModbusLink link = new ModbusLink(node, copyser, copydeser);
		singleton = link;
		link.init();
	}

	public static ModbusLink get() {
		return singleton;
	}

	private void init() {

		restoreLastSession();

		Action act = new Action(Permission.READ, new AddDeviceHandler(null));
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("transport type", ValueType.makeEnum("TCP", "UDP")));
		act.addParameter(new Parameter("host", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(502)));
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("zero on failed poll", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("use batch polling", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("contiguous batch requests only", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, new Value(false)));
		node.createChild("add ip device").setAction(act).build().setSerializable(false);

		act = getAddSerialAction();
		node.createChild("add serial connection").setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new PortScanHandler());
		node.createChild("scan for serial ports").setAction(act).build().setSerializable(false);

		// act = new Action(Permission.READ, new MakeSlaveHandler());
		// act.addParameter(new Parameter("name", ValueType.STRING));
		// act.addParameter(new Parameter("transport type",
		// ValueType.makeEnum("TCP", "UDP")));
		// act.addParameter(new Parameter("slave id", ValueType.NUMBER, new
		// Value(1)));
		// node.createChild("set up ip
		// slave").setAction(act).build().setSerializable(false);
	}

	private class PortScanHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			Action act = getAddSerialAction();
			Node anode = node.getChild("add serial connection");
			if (anode == null) {
				anode = node.createChild("add serial connection").setAction(act).build();
				anode.setSerializable(false);
			} else {
				anode.setAction(act);
			}

			for (SerialConn conn : serialConns) {
				anode = conn.node.getChild("edit");
				if (anode != null) {
					act = conn.getEditAction();
					anode.setAction(act);
				}
			}
		}
	}

	private Action getAddSerialAction() {
		Action act = new Action(Permission.READ, new AddSerialHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("transport type", ValueType.makeEnum("RTU", "ASCII")));
		Set<String> portids = new HashSet<String>();
		try {
			List<CommPortProxy> cports = SerialUtils.getCommPorts();
			for (CommPortProxy port : cports) {
				portids.add(port.getId());
			}
		} catch (CommPortConfigException e) {
			LOGGER.debug("", e);
		}
		if (portids.size() > 0) {
			act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids)));
			act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING));
		} else {
			act.addParameter(new Parameter("comm port id", ValueType.STRING));
		}
		act.addParameter(new Parameter("baud rate", ValueType.NUMBER, new Value(9600)));
		act.addParameter(new Parameter("data bits", ValueType.NUMBER, new Value(8)));
		act.addParameter(new Parameter("stop bits", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("parity", ValueType.makeEnum("NONE", "ODD", "EVEN", "MARK", "SPACE")));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("send requests all at once", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("set custom spacing", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("message frame spacing", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("character spacing", ValueType.NUMBER, new Value(0)));
		return act;
	}

	private void restoreLastSession() {
		if (node.getChildren() == null)
			return;
		for (Node child : node.getChildren().values()) {
			Value restype = child.getAttribute("restoreType");
			if (restype == null) {
				node.removeChild(child);
				continue;
			}
			Value transType = child.getAttribute("transport type");
			Value timeout = child.getAttribute("Timeout");
			if (timeout == null) child.setAttribute("Timeout", new Value(500));
			Value retries = child.getAttribute("retries");
			if (retries == null) child.setAttribute("retries", new Value(2));
			Value maxrbc = child.getAttribute("max read bit count");
			Value maxrrc = child.getAttribute("max read register count");
			Value maxwrc = child.getAttribute("max write register count");
			Value ddd = child.getAttribute("discard data delay");
			Value mwo = child.getAttribute("use multiple write commands only");
			if (restype.getString().equals("conn")) {
				Value commPortId = child.getAttribute("comm port id");
				Value baudRate = child.getAttribute("baud rate");
				Value dataBits = child.getAttribute("data bits");
				Value stopBits = child.getAttribute("stop bits");
				Value parity = child.getAttribute("parity");
				Value useMods = child.getAttribute("send requests all at once");
				Value useCustomSpacing = child.getAttribute("set custom spacing");
				Value msgSpacing = child.getAttribute("message frame spacing");
				Value charSpacing = child.getAttribute("character spacing");
				if (transType != null && commPortId != null && baudRate != null && dataBits != null && stopBits != null
						&& parity != null && maxrbc != null && maxrrc != null && maxwrc != null && ddd != null
						&& mwo != null && timeout != null && retries != null && useMods != null
						&& useCustomSpacing != null && msgSpacing != null && charSpacing != null) {
					SerialConn sc = new SerialConn(getMe(), child);
					sc.restoreLastSession();
				} else {
					node.removeChild(child);
				}
			} else if (restype.getString().equals("folder")) {
				Value host = child.getAttribute("host");
				Value port = child.getAttribute("port");
				Value slaveId = child.getAttribute("slave id");
				Value interval = child.getAttribute("polling interval");
				Value zerofail = child.getAttribute("zero on failed poll");
				if (zerofail == null) child.setAttribute("zero on failed poll", new Value(false));
				Value batchpoll = child.getAttribute("use batch polling");
				if (batchpoll == null)
					child.setAttribute("use batch polling", new Value(true));
				Value contig = child.getAttribute("contiguous batch requests only");
				if (contig == null)
					child.setAttribute("contiguous batch requests only", new Value(true));
				if (transType != null && host != null && port != null && maxrbc != null && maxrrc != null
						&& maxwrc != null && ddd != null && mwo != null && slaveId != null && interval != null
						&& timeout != null && retries != null) {
					SlaveNode sn = new SlaveNode(getMe(), child, null);
					sn.restoreLastSession();
				} else {
					node.removeChild(child);
				}
			}
		}
	}

	private class AddSerialHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
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
			String name = event.getParameter("name", ValueType.STRING).getString();
			String transtype = event.getParameter("transport type").getString();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();

			Node snode = node.createChild(name).build();
			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("comm port id", new Value(commPortId));
			snode.setAttribute("baud rate", new Value(baudRate));
			snode.setAttribute("data bits", new Value(dataBits));
			snode.setAttribute("stop bits", new Value(stopBits));
			snode.setAttribute("parity", new Value(parityString));
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

			SerialConn conn = new SerialConn(getMe(), snode);
			conn.init();
		}
	}

	// private class MakeSlaveHandler implements Handler<ActionResult> {
	// public void handle(ActionResult event) {
	// String name = event.getParameter("name", ValueType.STRING).getString();
	// String transtype = event.getParameter("transport type").getString();
	// int slaveid = event.getParameter("slave id",
	// ValueType.NUMBER).getNumber().intValue();
	//
	// Node child = node.createChild(name).build();
	// child.setAttribute("transport type", new Value(transtype));
	// child.setAttribute("slave id", new Value(slaveid));
	// child.createChild("Coils").setValueType(ValueType.MAP).setValue(new
	// Value(new JsonObject())).build();
	// child.createChild("Discrete
	// Inputs").setValueType(ValueType.MAP).setValue(new Value(new
	// JsonObject())).build();
	// child.createChild("Input
	// Registers").setValueType(ValueType.MAP).setValue(new Value(new
	// JsonObject())).build();
	// child.createChild("Holding
	// Registers").setValueType(ValueType.MAP).setValue(new Value(new
	// JsonObject())).build();
	//
	// new IpSlave(getMe(), child);
	//
	// }
	// }

	class AddDeviceHandler implements Handler<ActionResult> {
		private boolean isSerial;
		private SerialConn conn;

		AddDeviceHandler(SerialConn conn) {
			this.conn = conn;
			this.isSerial = (conn != null);
		}

		public void handle(ActionResult event) {
			String commPortId = "na";
			String host = "na";
			String parityString = "NONE";
			int baudRate = 0;
			int dataBits = 0;
			int stopBits = 0;
			int port = 0;
			long msgSpacing = 0;
			long charSpacing = 0;
			boolean useMods = false;
			boolean useCustomSpacing = false;
			int timeout, retries, maxrbc, maxrrc, maxwrc, ddd;
			boolean mwo;
			String transtype;
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node snode;
			if (isSerial) {
				commPortId = conn.node.getAttribute("comm port id").getString();
				baudRate = conn.node.getAttribute("baud rate").getNumber().intValue();
				dataBits = conn.node.getAttribute("data bits").getNumber().intValue();
				stopBits = conn.node.getAttribute("stop bits").getNumber().intValue();
				parityString = conn.node.getAttribute("parity").getString();
				useMods = conn.node.getAttribute("send requests all at once").getBool();
				useCustomSpacing = conn.node.getAttribute("set custom spacing").getBool();
				msgSpacing = conn.node.getAttribute("message frame spacing").getNumber().longValue();
				charSpacing = conn.node.getAttribute("character spacing").getNumber().longValue();
				transtype = conn.node.getAttribute("transport type").getString();
				timeout = conn.node.getAttribute("Timeout").getNumber().intValue();
				retries = conn.node.getAttribute("retries").getNumber().intValue();
				maxrbc = conn.node.getAttribute("max read bit count").getNumber().intValue();
				maxrrc = conn.node.getAttribute("max read register count").getNumber().intValue();
				maxwrc = conn.node.getAttribute("max write register count").getNumber().intValue();
				ddd = conn.node.getAttribute("discard data delay").getNumber().intValue();
				mwo = conn.node.getAttribute("use multiple write commands only").getBool();
				snode = conn.node.createChild(name).build();
			} else {
				transtype = event.getParameter("transport type").getString();
				host = event.getParameter("host", ValueType.STRING).getString();
				port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
				retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
				maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
				maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
				maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
				ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
				mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
				snode = node.createChild(name).build();
			}

			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean zerofail = event.getParameter("zero on failed poll", ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();

			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("host", new Value(host));
			snode.setAttribute("port", new Value(port));
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

			new SlaveNode(getMe(), snode, conn);
		}
	}

	void handleEdit(SlaveNode slave) {
		for (Node event : slave.getSubscribed()) {
			if (event.getMetaData() == slave) {
				handleUnsub(slave, event);
				handleSub(slave, event);
			}
		}
	}

	private void handleSub(final SlaveNode slave, final Node event) {
		slave.addToSub(event);
		if (futures.containsKey(slave)) {
			return;
		}
		ScheduledThreadPoolExecutor stpe = slave.getDaemonThreadPool();
		ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				slave.readPoints();
			}
		}, 0, slave.interval, TimeUnit.MILLISECONDS);
		futures.put(slave, fut);
		LOGGER.debug("subscribed to " + slave.node.getName());
	}

	private void handleUnsub(SlaveNode slave, Node event) {
		slave.removeFromSub(event);
		if (slave.noneSubscribed()) {
			ScheduledFuture<?> fut = futures.remove(slave);
			if (fut != null) {
				fut.cancel(false);
			}
			LOGGER.debug("unsubscribed from " + slave.node.getName());
		}
	}

	void setupPoint(Node child, final SlaveNode slave) {
		child.setMetaData(slave);
		child.getListener().setOnSubscribeHandler(new Handler<Node>() {
			public void handle(final Node event) {
				handleSub(slave, event);
			}
		});

		child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			@Override
			public void handle(Node event) {
				handleUnsub(slave, event);
			}
		});
	}

	private ModbusLink getMe() {
		return this;
	}

	static int parseParity(String parstr) {
		int parint = 0;
		switch (parstr.toUpperCase()) {
		case "NONE":
			break;
		case "ODD":
			parint = 1;
			break;
		case "EVEN":
			parint = 2;
			break;
		case "MARK":
			parint = 3;
			break;
		case "SPACE":
			parint = 4;
			break;
		default:
			break;
		}
		return parint;
	}

}
