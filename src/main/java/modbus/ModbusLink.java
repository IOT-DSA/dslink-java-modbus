package modbus;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.ip.tcp.TcpSlave;
import com.serotonin.modbus4j.ip.udp.UdpSlave;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
import org.dsa.iot.dslink.node.actions.EditorType;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModbusLink {
	static private final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(ModbusLink.class);
	}

	static final String ACTION_ADD_LOCAL_SLAVE = "setup local slave";
	static final String ACTION_ADD_IP_DEVICE = "add ip device";
	static final String ACTION_ADD_IP_CONNECTION = "add ip connection";
	static final String ACTION_ADD_SERIAL_CONNECTION = "add serial connection";
	static final String ACTION_SCAN_SERIAL_PORT = "scan for serial ports";

	static final String ACTION_EDIT = "edit";
    static final String ACTION_IMPORT = "import connection";

	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_TRANSPORT_TYPE = "transport type";
	static final String ATTRIBUTE_PORT = "port";
	static final String ATTRIBUTE_SLAVE_ID = "slave id";
	static final String ATTRIBUTE_RESTORE_TYPE = "restoreType";

	Node node;
	Serializer serializer;
	Deserializer deserializer;
	private final Map<SlaveNode, ScheduledFuture<?>> futures;
	final Set<ModbusConnection> connections;
	final Set<ModbusMaster> masters;

	static ModbusLink singleton;

	// modbus listener map: port <-> SlaveSet
	private final Map<Integer, ModbusSlaveSet> tcpListeners;
	private final Map<Integer, ModbusSlaveSet> udpListeners;

	private final Map<String, IpConnectionWithDevice> hostToConnection;
	boolean restoring = true;

	private ModbusLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.serializer = ser;
		this.deserializer = deser;
		this.futures = new ConcurrentHashMap<>();
		this.connections = new HashSet<>();
		this.masters = new HashSet<>();

		this.tcpListeners = new HashMap<>();
		this.udpListeners = new HashMap<>();

		this.hostToConnection = new HashMap<>();
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
		restoring = false;

		Action act = getAddIpConnectionAction();
		node.createChild(ACTION_ADD_IP_CONNECTION, true).setAction(act).build().setSerializable(false);

		act = getAddSerialConnectionAction();
		node.createChild(ACTION_ADD_SERIAL_CONNECTION, true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new PortScanHandler());
		node.createChild(ACTION_SCAN_SERIAL_PORT, true).setAction(act).build().setSerializable(false);

		act = getMakeSlaveAction();
		node.createChild(ACTION_ADD_LOCAL_SLAVE, true).setAction(act).build().setSerializable(false);

		act = getAddIpDeviceAction();
		node.createChild(ACTION_ADD_IP_DEVICE, true).setAction(act).build().setSerializable(false);

		makeImportAction();
	}

	private class PortScanHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			Action act = getAddSerialConnectionAction();
			Node anode = node.getChild(ACTION_ADD_SERIAL_CONNECTION, true);
			if (anode == null) {
				anode = node.createChild(ACTION_ADD_SERIAL_CONNECTION, true).setAction(act).build();
				anode.setSerializable(false);
			} else {
				anode.setAction(act);
			}

			for (ModbusConnection conn : connections) {
				anode = conn.node.getChild(ACTION_EDIT, true);
				if (anode != null) {
					act = conn.getEditAction();
					anode.setAction(act);
				}
			}
		}
	}

	private class MakeSlaveHandler implements Handler<ActionResult> {

		public void handle(ActionResult event) {
			String transtype;
			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			Node slaveNode;

			transtype = event.getParameter(ATTRIBUTE_TRANSPORT_TYPE).getString();
			slaveNode = node.createChild(name, true).build();

			int port = event.getParameter(ATTRIBUTE_PORT, ValueType.NUMBER).getNumber().intValue();
			int slaveid = event.getParameter(ATTRIBUTE_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();

			slaveNode.setAttribute(ATTRIBUTE_TRANSPORT_TYPE, new Value(transtype));
			slaveNode.setAttribute(ATTRIBUTE_PORT, new Value(port));
			slaveNode.setAttribute(ATTRIBUTE_SLAVE_ID, new Value(slaveid));
			slaveNode.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value(EditableFolder.ATTRIBUTE_RESTORE_EDITABLE_FOLDER));

			new LocalSlaveNode(getLink(), slaveNode);
		}
	}

	private Action getAddIpDeviceAction() {
		Action act = new Action(Permission.READ, new AddIpDeviceHandler());
		act.addParameter(new Parameter(SlaveFolder.ATTR_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ModbusConnection.ATTR_TRANSPORT_TYPE,
				ValueType.makeEnum(Util.enumNames(IpTransportType.class))));
		act.addParameter(new Parameter(IpConnection.ATTR_HOST, ValueType.STRING, new Value("")));
		act.addParameter(new Parameter(IpConnection.ATTR_PORT, ValueType.NUMBER, new Value(502)));

		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL, new Value(true)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, ValueType.NUMBER, new Value(60)).setDescription("how many seconds to wait before sending an update for an unchanged value"));

		act.addParameter(new Parameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER, new Value(2000)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER, new Value(125)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER, new Value(0)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, ValueType.makeEnum(ModbusConnection.MULTIPLE_WRITE_COMMAND_OPTIONS), new Value(ModbusConnection.MULTIPLE_WRITE_COMMAND_DEFAULT)));

		return act;
	}

	private Action getAddIpConnectionAction() {
		Action act = new Action(Permission.READ, new AddIpConnectionHandler());
		act.addParameter(new Parameter(ModbusConnection.ATTR_CONNECTION_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ModbusConnection.ATTR_TRANSPORT_TYPE, ValueType.makeEnum("TCP", "UDP")));
		act.addParameter(new Parameter(IpConnection.ATTR_HOST, ValueType.STRING, new Value("")));
		act.addParameter(new Parameter(IpConnection.ATTR_PORT, ValueType.NUMBER, new Value(502)));

		act.addParameter(new Parameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER, new Value(2000)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER, new Value(125)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER, new Value(0)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, ValueType.makeEnum(ModbusConnection.MULTIPLE_WRITE_COMMAND_OPTIONS), new Value(ModbusConnection.MULTIPLE_WRITE_COMMAND_DEFAULT)));

		return act;
	}

	private Action getAddSerialConnectionAction() {
		Action act = new Action(Permission.READ, new AddSerialConnectionHandler());
		act.addParameter(new Parameter(ModbusConnection.ATTR_CONNECTION_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ModbusConnection.ATTR_TRANSPORT_TYPE,
				ValueType.makeEnum(Util.enumNames(SerialTransportType.class))));
		Set<String> portids = new HashSet<>();
		try {
			String[] cports = Util.getCommPorts();
			portids.addAll(Arrays.asList(cports));
		} catch (Exception e) {
			LOGGER.debug("", e);
		}
		if (portids.size() > 0) {
			act.addParameter(new Parameter(SerialConn.ATTR_COMM_PORT_ID, ValueType.makeEnum(portids)));
			act.addParameter(new Parameter(SerialConn.ATTR_COMM_PORT_ID_MANUAL, ValueType.STRING));
		} else {
			act.addParameter(new Parameter(SerialConn.ATTR_COMM_PORT_ID, ValueType.STRING));
		}
		act.addParameter(new Parameter(SerialConn.ATTR_BAUD_RATE, ValueType.NUMBER, new Value(9600)));
		act.addParameter(new Parameter(SerialConn.ATTR_DATA_BITS, ValueType.NUMBER, new Value(8)));
		act.addParameter(new Parameter(SerialConn.ATTR_STOP_BITS, ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter(SerialConn.ATTR_PARITY, ValueType.makeEnum(Util.enumNames(ParityType.class))));

		act.addParameter(new Parameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER, new Value(2000)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER, new Value(125)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER, new Value(0)));
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, ValueType.makeEnum(ModbusConnection.MULTIPLE_WRITE_COMMAND_OPTIONS), new Value(ModbusConnection.MULTIPLE_WRITE_COMMAND_DEFAULT)));
		return act;
	}

	private Action getMakeSlaveAction() {
		Action act = new Action(Permission.READ, new MakeSlaveHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		act.addParameter(
				new Parameter(ATTRIBUTE_TRANSPORT_TYPE, ValueType.makeEnum(Util.enumNames(IpTransportType.class))));
		act.addParameter(new Parameter(ATTRIBUTE_PORT, ValueType.NUMBER, new Value(1025)));
		act.addParameter(new Parameter(ATTRIBUTE_SLAVE_ID, ValueType.NUMBER, new Value(1)));

		return act;
	}

	private void makeImportAction() {
        Action act = new Action(Permission.READ, this::handleImport);
        act.addParameter(new Parameter("Name", ValueType.STRING));
        act.addParameter(new Parameter("JSON", ValueType.STRING).setEditorType(EditorType.TEXT_AREA));
        Node anode = node.getChild(ACTION_IMPORT, true);
        if (anode == null) {
            node.createChild(ACTION_IMPORT, true).setAction(act).build().setSerializable(false);
        } else {
            anode.setAction(act);
        }
    }

    private void handleImport(ActionResult event) {
        String name = event.getParameter("Name", ValueType.STRING).getString();
        String jsonStr = event.getParameter("JSON", ValueType.STRING).getString();
        JsonObject children = new JsonObject(jsonStr);
        Node child = node.createChild(name, true).build();
        try {
            Method deserMethod = Deserializer.class.getDeclaredMethod("deserializeNode", Node.class, JsonObject.class);
            deserMethod.setAccessible(true);
            deserMethod.invoke(deserializer, child, children);
            ModbusConnection mc = null;
            if (child.getAttribute(ModbusConnection.ATTR_HOST) != null) {
                mc = new IpConnection(this, child);
            } else if (child.getAttribute(ModbusConnection.ATTR_COMM_PORT_ID) != null ||
                    child.getAttribute(ModbusConnection.ATTR_COMM_PORT_ID_MANUAL) != null) {
                mc = new SerialConn(this, child);
            } else {
                LOGGER.debug("ERROR: Unknown connection type.");
                //TODO: Throw exception here?
            }
            if (mc != null) {
                mc.restoreLastSession();
            } else {
                child.delete(false);
            }
        } catch (SecurityException | IllegalArgumentException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOGGER.debug("", e);
            child.delete(false);
        }
    }

	public ModbusSlaveSet getSlaveSet(modbus.IpTransportType transtype, int port) {
		ModbusSlaveSet slaveSet;

		switch (transtype) {
		case TCP: {
			if (!tcpListeners.containsKey(port)) {
				slaveSet = new TcpSlave(port, false);
				tcpListeners.put(port, slaveSet);
				return slaveSet;
			} else {
				return tcpListeners.get(port);
			}

		}
		case UDP: {
			if (!udpListeners.containsKey(port)) {
				slaveSet = new UdpSlave(port, false);
				return slaveSet;
			} else {
				return this.udpListeners.get(port);
			}

		}
		default:
			return null;
		}
	}

	private void restoreLastSession() {
		if (node.getChildren() == null)
			return;

		Map<String, Node> children = node.getChildren();
		for (Node child : children.values()) {
			Value restype = child.getAttribute(ATTRIBUTE_RESTORE_TYPE);
			if (restype == null) {
				node.removeChild(child, false);
				continue;
			}

			// common parameters of connection
			Value transType = child.getAttribute(ModbusConnection.ATTR_TRANSPORT_TYPE);
			Value timeout = null;
			Value retries = null;
			Value maxrbc = null;
			Value maxrrc = null;
			Value maxwrc = null;
			Value ddd = null;
			Value mw = null;

			if (ModbusConnection.ATTR_RESTORE_CONNECITON.equals(restype.getString())
					|| SlaveFolder.ATTR_RESTORE_FOLDER.equals(restype.getString())) {
				timeout = child.getAttribute(ModbusConnection.ATTR_TIMEOUT);
				if (timeout == null)
					child.setAttribute(ModbusConnection.ATTR_TIMEOUT, new Value(500));
				retries = child.getAttribute(ModbusConnection.ATTR_RETRIES);
				if (retries == null)
					child.setAttribute(ModbusConnection.ATTR_RETRIES, new Value(2));
				maxrbc = child.getAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT);
				maxrrc = child.getAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT);
				maxwrc = child.getAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT);
				ddd = child.getAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY);
				mw = child.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND);
				if (mw == null) {
					Value mwo = child.getAttribute("use multiple write commands only");
					String useMW = ModbusConnection.MULTIPLE_WRITE_COMMAND_DEFAULT;
					if (mwo != null && mwo.getBool() != null && mwo.getBool()) {
						useMW = ModbusConnection.MULTIPLE_WRITE_COMMAND_ALWAYS;
					}
					child.setAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, new Value(useMW));
					mw = child.getAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND);
				}

			}

			if (ModbusConnection.ATTR_RESTORE_CONNECITON.equals(restype.getString())) {
				// ip connection only
				Value host = child.getAttribute(IpConnection.ATTR_HOST);
				Value port = child.getAttribute(IpConnection.ATTR_PORT);

				// serial connection only
				Value commPortId = child.getAttribute(SerialConn.ATTR_COMM_PORT_ID);
				Value baudRate = child.getAttribute(SerialConn.ATTR_BAUD_RATE);
				Value dataBits = child.getAttribute(SerialConn.ATTR_DATA_BITS);
				Value stopBits = child.getAttribute(SerialConn.ATTR_STOP_BITS);
				Value parity = child.getAttribute(SerialConn.ATTR_PARITY);

				if (host != null && port != null) {
					IpConnection ipConn = new IpConnection(getLink(), child);
					ipConn.restoreLastSession();
				} else if (transType != null && commPortId != null && baudRate != null && dataBits != null
						&& stopBits != null && parity != null && maxrbc != null && maxrrc != null && maxwrc != null
						&& ddd != null && mw != null && timeout != null && retries != null) {
					SerialConn sc = new SerialConn(getLink(), child);
					sc.restoreLastSession();
				} else {
					node.removeChild(child, false);
				}
			} else if (SlaveFolder.ATTR_RESTORE_FOLDER.equals(restype.getString())) {
				// legacy issue - ip device is mixed together with the ip
				// connection
				Value host = child.getAttribute(IpConnection.ATTR_HOST);
				Value port = child.getAttribute(IpConnection.ATTR_PORT);

				Value slaveId = child.getAttribute(ModbusConnection.ATTR_SLAVE_ID);
				Value interval = child.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL);
				Value zerofail = child.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL);
				if (zerofail == null)
					child.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(false));
				Value batchpoll = child.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING);
				if (batchpoll == null)
					child.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(true));
				Value contig = child.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY);
				if (contig == null)
					child.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(true));
				Value suppressDuration = child.getAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION);
				if (suppressDuration == null) {
					child.setAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, new Value(60000));
				}

				if (transType != null && host != null && port != null && maxrbc != null && maxrrc != null
						&& maxwrc != null && ddd != null && mw != null && slaveId != null && interval != null
						&& timeout != null && retries != null) {

					String hostName = host + ":" + port;
					IpConnectionWithDevice conn;
					if (hostToConnection.containsKey(hostName)) {
						conn = hostToConnection.get(hostName);
						conn.addSlave(child);
					} else {
						conn = new IpConnectionWithDevice(getLink(), child);
						hostToConnection.put(hostName, conn);
						conn.restoreLastSession();
						conn.addSlave(child);
					}
				}
			} else if (restype.getString().equals(EditableFolder.ATTRIBUTE_RESTORE_EDITABLE_FOLDER)) {
				Value port = child.getAttribute(ATTRIBUTE_PORT);
				Value slaveId = child.getAttribute(ATTRIBUTE_SLAVE_ID);
				if (transType != null && port != null && slaveId != null) {
					EditableFolder folder = new LocalSlaveNode(getLink(), child);
					folder.restoreLastSession();
				}
			}
		}
	}

	private class AddSerialConnectionHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String commPortId;
			Value customPort = event.getParameter(SerialConn.ATTR_COMM_PORT_ID_MANUAL);
			if (customPort != null && customPort.getString() != null && customPort.getString().trim().length() > 0) {
				commPortId = customPort.getString();
			} else {
				commPortId = event.getParameter(SerialConn.ATTR_COMM_PORT_ID).getString();
			}
			int baudRate = event.getParameter(SerialConn.ATTR_BAUD_RATE, ValueType.NUMBER).getNumber().intValue();
			int dataBits = event.getParameter(SerialConn.ATTR_DATA_BITS, ValueType.NUMBER).getNumber().intValue();
			int stopBits = event.getParameter(SerialConn.ATTR_STOP_BITS, ValueType.NUMBER).getNumber().intValue();
			String parityString = event.getParameter(SerialConn.ATTR_PARITY).getString();

			String name = event.getParameter(ModbusConnection.ATTR_CONNECTION_NAME, ValueType.STRING).getString();
			String transtype = event.getParameter(ModbusConnection.ATTR_TRANSPORT_TYPE).getString();

			int timeout = event.getParameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			int maxrrc = event.getParameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			int maxwrc = event.getParameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER)
					.getNumber().intValue();
			int ddd = event.getParameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER).getNumber()
					.intValue();
			String mw = event.getParameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND).getString();

			Node snode = node.createChild(name, true).build();
			snode.setAttribute(ModbusConnection.ATTR_TRANSPORT_TYPE, new Value(transtype));
			snode.setAttribute(SerialConn.ATTR_COMM_PORT_ID, new Value(commPortId));
			snode.setAttribute(SerialConn.ATTR_BAUD_RATE, new Value(baudRate));
			snode.setAttribute(SerialConn.ATTR_DATA_BITS, new Value(dataBits));
			snode.setAttribute(SerialConn.ATTR_STOP_BITS, new Value(stopBits));
			snode.setAttribute(SerialConn.ATTR_PARITY, new Value(parityString));

			snode.setAttribute(ModbusConnection.ATTR_TIMEOUT, new Value(timeout));
			snode.setAttribute(ModbusConnection.ATTR_RETRIES, new Value(retries));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			snode.setAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			snode.setAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, new Value(mw));

			SerialConn conn = new SerialConn(getLink(), snode);
			conn.init();
		}
	}

	class AddIpConnectionHandler implements Handler<ActionResult> {

		public void handle(ActionResult event) {

			String host;
			int port;

			int timeout, retries, maxrbc, maxrrc, maxwrc, ddd;
			String mw;
			String transtype;
			String name = event.getParameter(ModbusConnection.ATTR_CONNECTION_NAME, ValueType.STRING).getString();
			Node snode;

			transtype = event.getParameter(ModbusConnection.ATTR_TRANSPORT_TYPE).getString();
			host = event.getParameter(IpConnection.ATTR_HOST, ValueType.STRING).getString();
			port = event.getParameter(IpConnection.ATTR_PORT, ValueType.NUMBER).getNumber().intValue();

			timeout = event.getParameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER).getNumber().intValue();
			retries = event.getParameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER).getNumber().intValue();
			maxrbc = event.getParameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			maxrrc = event.getParameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			maxwrc = event.getParameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			ddd = event.getParameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER).getNumber().intValue();
			mw = event.getParameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND).getString();
			snode = node.createChild(name, true).build();

			snode.setAttribute(ModbusConnection.ATTR_TRANSPORT_TYPE, new Value(transtype));
			snode.setAttribute(IpConnection.ATTR_HOST, new Value(host));
			snode.setAttribute(IpConnection.ATTR_PORT, new Value(port));

			snode.setAttribute(ModbusConnection.ATTR_TIMEOUT, new Value(timeout));
			snode.setAttribute(ModbusConnection.ATTR_RETRIES, new Value(retries));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			snode.setAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			snode.setAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, new Value(mw));

			ModbusConnection conn = new IpConnection(getLink(), snode);
			conn.init();
		}
	}

	/*
	 * A legacy handler for the project with the two-tier structure.
	 */
	class AddIpDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String host;
			int port;
			int timeout, retries, maxrbc, maxrrc, maxwrc, ddd;
			String mw;
			String transtype;
			String name = event.getParameter(SlaveFolder.ATTR_NAME, ValueType.STRING).getString();
			Node snode;

			transtype = event.getParameter(ModbusConnection.ATTR_TRANSPORT_TYPE).getString();
			host = event.getParameter(IpConnection.ATTR_HOST, ValueType.STRING).getString();
			port = event.getParameter(IpConnection.ATTR_PORT, ValueType.NUMBER).getNumber().intValue();

			timeout = event.getParameter(ModbusConnection.ATTR_TIMEOUT, ValueType.NUMBER).getNumber().intValue();
			retries = event.getParameter(ModbusConnection.ATTR_RETRIES, ValueType.NUMBER).getNumber().intValue();
			maxrbc = event.getParameter(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			maxrrc = event.getParameter(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			maxwrc = event.getParameter(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER).getNumber()
					.intValue();
			ddd = event.getParameter(ModbusConnection.ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER).getNumber().intValue();
			mw = event.getParameter(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND).getString();
			snode = node.createChild(name, true).build();

			int slaveid = event.getParameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER)
					.getNumber().doubleValue() * 1000);
			boolean zerofail = event.getParameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL)
					.getBool();
			long suppressDuration = (long) (event.getParameter(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, ValueType.NUMBER).getNumber().doubleValue() * 1000);

			snode.setAttribute(ModbusConnection.ATTR_TRANSPORT_TYPE, new Value(transtype));
			snode.setAttribute(IpConnection.ATTR_HOST, new Value(host));
			snode.setAttribute(IpConnection.ATTR_PORT, new Value(port));

			snode.setAttribute(ModbusConnection.ATTR_SLAVE_ID, new Value(slaveid));
			snode.setAttribute(ModbusConnection.ATTR_POLLING_INTERVAL, new Value(interval));
			snode.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			snode.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			snode.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));
			snode.setAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, new Value(suppressDuration));

			snode.setAttribute(ModbusConnection.ATTR_TIMEOUT, new Value(timeout));
			snode.setAttribute(ModbusConnection.ATTR_RETRIES, new Value(retries));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
			snode.setAttribute(ModbusConnection.ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
			snode.setAttribute(ModbusConnection.ATTR_DISCARD_DATA_DELAY, new Value(ddd));
			snode.setAttribute(ModbusConnection.ATTR_USE_MULTIPLE_WRITE_COMMAND, new Value(mw));

			String hostName = host + ":" + port;
			IpConnectionWithDevice conn;
			if (hostToConnection.containsKey(hostName)) {
				conn = hostToConnection.get(hostName);
				conn.addSlave(snode);
			} else {
				conn = new IpConnectionWithDevice(getLink(), snode);
				hostToConnection.put(hostName, conn);
				conn.init();
				conn.addSlave(snode);
			}
		}
	}

	void handleEdit(SlaveFolder slave) {
		Set<Node> set = new HashSet<>(((SlaveNode) slave).getSubscribed());

		for (Node event : set) {
			if (event.getMetaData() == slave) {
				handleUnsub((SlaveNode) slave, event);
				handleSub((SlaveNode) slave, event);
			}
		}
	}

	void handleSlaveTransfer(SlaveNode oldslave, SlaveNode newslave) {
		Set<Node> set = new HashSet<>(oldslave.getSubscribed());

		for (Node event : set) {
			if (event.getMetaData() == newslave) {
				handleUnsub(oldslave, event);
				handleSub(newslave, event);
			}
		}
	}

	private void handleSub(final SlaveNode slave, final Node event) {
		slave.addToSub(event);
		if (futures.containsKey(slave)) {
			return;
		}
		ScheduledThreadPoolExecutor stpe = slave.getDaemonThreadPool();
		ScheduledFuture<?> future = stpe.scheduleWithFixedDelay(slave::readPoints, 0, slave.intervalInMs, TimeUnit.MILLISECONDS);
		futures.put(slave, future);
	}

	private void handleUnsub(SlaveNode slave, Node event) {
		slave.removeFromSub(event);
		if (slave.noneSubscribed()) {
			ScheduledFuture<?> future = futures.remove(slave);
			if (future != null) {
				future.cancel(false);
			}
		}
	}

	void setupPoint(Node child, final SlaveFolder slave) {
		child.setMetaData(slave);
		child.getListener().setOnSubscribeHandler(event -> handleSub((SlaveNode) slave, event));

		child.getListener().setOnUnsubscribeHandler(event -> handleUnsub((SlaveNode) slave, event));
	}

	private ModbusLink getLink() {
		return this;
	}
}
