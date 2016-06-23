package modbus;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.locator.BinaryLocator;

public class SlaveNode extends SlaveFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	ModbusMaster master;
	long interval;
	boolean isSerial;
	SerialConn conn;
	Node statnode;
	private final ScheduledThreadPoolExecutor stpe;
	private final ConcurrentMap<Node, Boolean> subscribed = new ConcurrentHashMap<Node, Boolean>();

	SlaveNode(ModbusLink link, Node node, SerialConn conn) {
		super(link, node);

		this.conn = conn;
		this.isSerial = (conn != null);
		if (isSerial) {
			conn.slaves.add(this);
			stpe = conn.getDaemonThreadPool();
		} else {
			stpe = Objects.createDaemonThreadPool();
		}
		this.root = this;
		this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING)
				.setValue(new Value("Setting up device")).build();
		this.master = getMaster();
		stpe.execute(new Runnable() {
			@Override
			public void run() {
				checkConnection();
			}
		});

		this.interval = node.getAttribute("polling interval").getNumber().longValue();

		makeEditAction();
	}

	void checkConnection() {
		if (master != null) {
			boolean connected = false;
			try {
				LOGGER.debug("pinging device to test connectivity");
				connected = master.testSlaveNode(node.getAttribute("slave id").getNumber().intValue());
			} catch (Exception e) {
				LOGGER.debug("error during device ping: ", e);
			}
			if (connected)
				statnode.setValue(new Value("Ready"));
			else
				statnode.setValue(new Value("Device ping failed"));
		}
	}

	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}

	void addToSub(Node event) {
		subscribed.put(event, true);
	}

	void removeFromSub(Node event) {
		subscribed.remove(event);
	}

	Set<Node> getSubscribed() {
		return subscribed.keySet();
	}

	boolean noneSubscribed() {
		return subscribed.isEmpty();
	}

	enum TransportType {
		TCP, UDP, RTU, ASCII
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		if (!isSerial) {
			act.addParameter(new Parameter("transport type", ValueType.makeEnum("TCP", "UDP"),
					node.getAttribute("transport type")));
			act.addParameter(new Parameter("host", ValueType.STRING, node.getAttribute("host")));
			act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));
		}
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, node.getAttribute("slave id")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
		act.addParameter(new Parameter("use batch polling", ValueType.BOOL, node.getAttribute("use batch polling")));
		act.addParameter(new Parameter("contiguous batch requests only", ValueType.BOOL,
				node.getAttribute("contiguous batch requests only")));
		if (!isSerial) {
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
		}
		Node anode = node.getChild("edit");
		if (anode == null)
			node.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	ModbusMaster getMaster() {
		if (isSerial)
			return conn.master;
		statnode.setValue(new Value("connecting to device"));
		TransportType transtype = null;
		try {
			transtype = TransportType.valueOf(node.getAttribute("transport type").getString().toUpperCase());
		} catch (Exception e1) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e1);
			statnode.setValue(new Value("invalid transport type"));
			return null;
		}
		String host = node.getAttribute("host").getString();
		int port = node.getAttribute("port").getNumber().intValue();
		int timeout = node.getAttribute("Timeout").getNumber().intValue();
		int retries = node.getAttribute("retries").getNumber().intValue();
		int maxrbc = node.getAttribute("max read bit count").getNumber().intValue();
		int maxrrc = node.getAttribute("max read register count").getNumber().intValue();
		int maxwrc = node.getAttribute("max write register count").getNumber().intValue();
		int ddd = node.getAttribute("discard data delay").getNumber().intValue();
		boolean mwo = node.getAttribute("use multiple write commands only").getBool();
		ModbusMaster master = null;
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

		master.setTimeout(timeout);
		master.setRetries(retries);
		master.setMaxReadBitCount(maxrbc);
		master.setMaxReadRegisterCount(maxrrc);
		master.setMaxWriteRegisterCount(maxwrc);
		master.setDiscardDataDelay(ddd);
		master.setMultipleWritesOnly(mwo);

		try {
			master.init();
			LOGGER.debug("Trying to connect");
		} catch (ModbusInitException e) {
			LOGGER.error("error initializing master");
			LOGGER.debug("error initializing master", e);
			statnode.setValue(new Value("Could not establish connection - ModbusInitException"));
			try {
				master.destroy();
			} catch (Exception e1) {
			}
			return null;
		}

		link.masters.add(master);
		return master;
	}

	@Override
	protected void remove() {
		super.remove();
		if (isSerial) {
			conn.slaves.remove(this);
			return;
		}
		try {
			master.destroy();
			link.masters.remove(master);
		} catch (Exception e) {
			LOGGER.debug("error destroying last master");
		}
		if (!isSerial)
			stpe.shutdown();

	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (!isSerial) {
				if (master != null) {
					try {
						master.destroy();
						link.masters.remove(master);
					} catch (Exception e) {
						LOGGER.debug("error destroying last master");
					}
				}
				TransportType transtype;
				try {
					transtype = TransportType
							.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
				} catch (Exception e) {
					LOGGER.error("invalid transport type");
					LOGGER.debug("error: ", e);
					return;
				}
				String host = event.getParameter("host", ValueType.STRING).getString();
				int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				node.setAttribute("host", new Value(host));
				node.setAttribute("port", new Value(port));
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
			}
			String name = event.getParameter("name", ValueType.STRING).getString();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();
			link.handleEdit(root);
			node.setAttribute("slave id", new Value(slaveid));
			node.setAttribute("polling interval", new Value(interval));
			node.setAttribute("use batch polling", new Value(batchpoll));
			node.setAttribute("contiguous batch requests only", new Value(contig));

			if (!name.equals(node.getName())) {
				rename(name);
			}

			master = getMaster();
			checkConnection();

			makeEditAction();
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = isSerial ? (JsonObject) jobj.get(conn.node.getName()) : jobj;
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveNode sn = new SlaveNode(link, newnode, conn);
		sn.restoreLastSession();
	}

	public void readPoints() {
		if (node.getAttribute("use batch polling").getBool()) {
			LOGGER.debug("batch polling " + node.getName() + " :");
			int id = getIntValue(node.getAttribute("slave id"));
			BatchRead<Node> batch = new BatchRead<Node>();
			batch.setContiguousRequests(node.getAttribute("contiguous batch requests only").getBool());
			batch.setErrorsInResults(true);
			Set<Node> polled = new HashSet<Node>();
			for (Node pnode : subscribed.keySet()) {
				if (pnode.getAttribute("offset") == null)
					continue;
				PointType type = PointType.valueOf(pnode.getAttribute("type").getString());
				int offset = getIntValue(pnode.getAttribute("offset"));
				int numRegs = getIntValue(pnode.getAttribute("number of registers"));
				int bit = getIntValue(pnode.getAttribute("bit"));
				DataType dataType = DataType.valueOf(pnode.getAttribute("data type").getString());

				Integer dt = getDataTypeInt(dataType);
				if (dt == null)
					dt = com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
				int range = getPointTypeInt(type);

				if (dataType == DataType.BOOLEAN && !BinaryLocator.isBinaryRange(range) && bit < 0) {
					dt = com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
				}

				BaseLocator<?> locator = BaseLocator.createLocator(id, range, offset, dt, bit, numRegs);

				batch.addLocator(pnode, locator);
				polled.add(pnode);
			}

			try {
				BatchResults<Node> response = master.send(batch);
				for (Node pnode : polled) {
					Object obj = response.getValue(pnode);
					LOGGER.debug(pnode.getName() + " : " + obj.toString());

					DataType dataType = DataType.valueOf(pnode.getAttribute("data type").getString());
					double scaling = getDoubleValue(pnode.getAttribute("scaling"));
					double addscale = getDoubleValue(pnode.getAttribute("scaling offset"));

					ValueType vt = null;
					Value v = null;
					if (getDataTypeInt(dataType) != null) {
						if (dataType == DataType.BOOLEAN && obj instanceof Boolean) {
							vt = ValueType.BOOL;
							v = new Value((Boolean) obj);
						} else if (dataType == DataType.BOOLEAN && obj instanceof Number) {
							vt = ValueType.ARRAY;
							JsonArray jarr = new JsonArray();
							for (int i = 0; i < 16; i++) {
								jarr.add(((((Number) obj).intValue() >> i) & 1) == 1);
							}
							v = new Value(jarr);
						} else if (dataType.isString() && obj instanceof String) {
							vt = ValueType.STRING;
							v = new Value((String) obj);
						} else if (obj instanceof Number) {
							vt = ValueType.NUMBER;
							Number num = (Number) obj;
							v = new Value(num.doubleValue() / scaling + addscale);
						}
					} else {
						switch (dataType) {
						case INT32M10KSWAP:
						case INT32M10K: {
							short shi = (short) (((Number) obj).intValue() >>> 16);
							short slo = (short) (((Number) obj).intValue() & 0xffff);
							boolean swap = (dataType == DataType.INT32M10KSWAP);
							int num;
							if (swap)
								num = ((int) slo) * 10000 + (int) shi;
							else
								num = ((int) shi) * 10000 + (int) slo;
							vt = ValueType.NUMBER;
							v = new Value(num / scaling + addscale);
							break;
						}
						case UINT32M10KSWAP:
						case UINT32M10K: {
							short shi = (short) (((Number) obj).intValue() >>> 16);
							short slo = (short) (((Number) obj).intValue() & 0xffff);
							boolean swap = (dataType == DataType.INT32M10KSWAP);
							long num;
							if (swap)
								num = toUnsignedLong(toUnsignedInt(slo) * 10000 + toUnsignedInt(shi));
							else
								num = toUnsignedLong(toUnsignedInt(shi) * 10000 + toUnsignedInt(slo));
							vt = ValueType.NUMBER;
							v = new Value(num / scaling + addscale);
							break;
						}
						default:
							vt = null;
							v = null;
							break;
						}
					}
					if (v != null) {
						pnode.setValueType(vt);
						pnode.setValue(v);
					}
				}

			} catch (ModbusTransportException e) {
				LOGGER.debug("", e);
			} catch (ErrorResponseException e) {
				LOGGER.debug("", e);
			}

		} else {
			for (Node pnode : subscribed.keySet()) {
				readPoint(pnode);
			}
		}
	}

}
