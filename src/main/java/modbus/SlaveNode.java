package modbus;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ExceptionResult;
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
	ModbusConnection conn;
	Node statnode;

	private final ConcurrentMap<Node, Boolean> subscribed = new ConcurrentHashMap<Node, Boolean>();

	SlaveNode(ModbusLink link, Node node, ModbusConnection conn) {
		super(link, node);

		this.conn = conn;
		conn.slaves.add(this);
		this.root = this;
		this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING)
				.setValue(new Value("Setting up device")).build();
		this.master = conn.getMaster();
		/*
		 * reject exception stpe.execute(new Runnable() {
		 * 
		 * @Override public void run() { checkConnection(); }
		 * 
		 * });
		 */
		this.interval = node.getAttribute("polling interval").getNumber().longValue();

		makeEditAction();

		if (master != null) {
			makeDisableAction();
		} else {
			makeEnableAction();
		}
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

	private void makeEnableAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				// TBD
				node.removeChild("enable");
				statnode.setValue(new Value("enabled"));
				makeDisableAction();
			}
		});
		Node anode = node.getChild("enable");
		if (anode == null)
			node.createChild("enable").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		this.statnode.setValue(new Value("Disabled"));
	}

	private void makeDisableAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				// TBD
				node.removeChild("disalbe");
				statnode.setValue(new Value("Disabled"));
				makeEnableAction();
			}
		});

		Node anode = node.getChild("diable");
		if (anode == null)
			node.createChild("disable").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		this.statnode.setValue(new Value("Enabled"));
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_NAME, ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_SLAVE_ID)));
		double defint = node.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL).getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER, new Value(defint)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY)));

		Node anode = node.getChild("edit");
		if (anode == null)
			node.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();

		{
			conn.slaves.remove(this);
			return;
		}

	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean zerofail = event.getParameter("zero on failed poll", ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();

			if (!name.equals(node.getName())) {
				rename(name);
			}
			node.setAttribute("slave id", new Value(slaveid));
			node.setAttribute("polling interval", new Value(interval));
			node.setAttribute("zero on failed poll", new Value(zerofail));
			node.setAttribute("use batch polling", new Value(batchpoll));
			node.setAttribute("contiguous batch requests only", new Value(contig));

			link.handleEdit(root);
			master = conn.getMaster();
			// checkConnection();
			makeEditAction();
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = (JsonObject) jobj.get(conn.node.getName());
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveNode sn = new SlaveNode(link, newnode, conn);
		sn.restoreLastSession();
	}

	public void readPoints() {
		if (master == null)
			return;

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
				if ("Device ping failed".equals(statnode.getValue().getString())) {
					// checkConnection();
				}
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
						} else if (obj instanceof ExceptionResult) {
							ExceptionResult result = (ExceptionResult) obj;
							vt = ValueType.STRING;
							v = new Value((String) result.getExceptionMessage());
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
					} else if (node.getAttribute("zero on failed poll").getBool()
							&& pnode.getValueType().compare(ValueType.NUMBER)) {
						pnode.setValue(new Value(0));
					}
				}

			} catch (ModbusTransportException e) {
				LOGGER.debug("", e);
				if ("Enabled".equals(statnode.getValue().getString())) {
					// checkConnection();
				}
				if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
					for (Node pnode : polled) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						}
					}
				}
			} catch (ErrorResponseException e) {
				LOGGER.debug("", e);
				if (node.getAttribute("zero on failed poll").getBool()) {
					for (Node pnode : polled) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						}
					}
				}
			}

		} else {
			for (Node pnode : subscribed.keySet()) {
				readPoint(pnode);
			}
		}
	}

	public ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return conn.getDaemonThreadPool();
	}

	@Override
	public ModbusMaster getMaster() {
		return this.master;
	}

	@Override
	public ModbusConnection getConnection() {
		return this.conn;
	}
}
