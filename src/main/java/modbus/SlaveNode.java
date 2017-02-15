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
import org.dsa.iot.dslink.util.json.JsonArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ExceptionResult;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.locator.BinaryLocator;

/*
 * A regular class for the multiple tier design.
 * 
 * Link
 *     |
 *     ->Connection
 *                 |
 *                 ->Device Node
 * 
 * The Device Node and its connection  share the same node.
 * 
 * */

public class SlaveNode extends SlaveFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	// ModbusMaster master;
	long intervalInMs;

	Node statnode;

	private final ConcurrentMap<Node, Boolean> subscribed = new ConcurrentHashMap<Node, Boolean>();

	SlaveNode(ModbusConnection conn, Node node) {
		super(conn, node);

		conn.slaves.add(this);
		root = this;

		statnode = node.getChild(NODE_STATUS, true);
		if (statnode == null) {
			statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING)
					.setValue(new Value(NODE_STATUS_SETTING_UP)).build();
		}

		checkDeviceConnected();

		this.intervalInMs = node.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL).getNumber().longValue();

		makeEditAction();

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

	void makeEditAction() {
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

		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null)
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();

		conn.slaves.remove(this);
		return;
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter(ATTR_NAME, ValueType.STRING).getString();
			int slaveid = event.getParameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			intervalInMs = (long) (event.getParameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER)
					.getNumber().doubleValue() * 1000);
			boolean zerofail = event.getParameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL)
					.getBool();

			if (!name.equals(node.getName())) {
				rename(name);
			}
			node.setAttribute(ModbusConnection.ATTR_SLAVE_ID, new Value(slaveid));
			node.setAttribute(ModbusConnection.ATTR_POLLING_INTERVAL, new Value(intervalInMs));
			node.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			node.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			node.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));

			conn.getLink().handleEdit(root);

			checkDeviceConnected();

			makeEditAction();
		}
	}

	public void readPoints() {
		if (getMaster() == null) {
			return;
		}
		if (!NODE_STATUS_READY.equals(statnode.getValue().getString())) {
			checkDeviceConnected();
			if (!NODE_STATUS_READY.equals(statnode.getValue().getString())) {
				return;
			}
		}

		if (node.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING).getBool()) {
			LOGGER.debug("batch polling " + node.getName() + " :");
			int id = Util.getIntValue(node.getAttribute(ModbusConnection.ATTR_SLAVE_ID));
			BatchRead<Node> batch = new BatchRead<Node>();
			batch.setContiguousRequests(
					node.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY).getBool());
			batch.setErrorsInResults(true);
			Set<Node> polled = new HashSet<Node>();
			for (Node pnode : subscribed.keySet()) {
				if (pnode.getAttribute(ATTR_OFFSET) == null)
					continue;
				PointType type = PointType.valueOf(pnode.getAttribute(ATTR_POINT_TYPE).getString());
				int offset = Util.getIntValue(pnode.getAttribute(ATTR_OFFSET));
				int numRegs = Util.getIntValue(pnode.getAttribute(ATTR_NUMBER_OF_REGISTERS));
				int bit = Util.getIntValue(pnode.getAttribute(ATTR_BIT));
				DataType dataType = DataType.valueOf(pnode.getAttribute(ATTR_DATA_TYPE).getString());

				Integer dt = DataType.getDataTypeInt(dataType);
				if (dt == null)
					dt = com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
				int range = PointType.getPointTypeInt(type);

				if (dataType == DataType.BOOLEAN && !BinaryLocator.isBinaryRange(range) && bit < 0) {
					dt = com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
				}

				BaseLocator<?> locator = BaseLocator.createLocator(id, range, offset, dt, bit, numRegs);

				batch.addLocator(pnode, locator);
				polled.add(pnode);
			}

			try {
				BatchResults<Node> response = getMaster().send(batch);

				for (Node pnode : polled) {
					Object obj = response.getValue(pnode);
					LOGGER.debug(pnode.getName() + " : " + obj.toString());

					DataType dataType = DataType.valueOf(pnode.getAttribute(ATTR_DATA_TYPE).getString());
					double scaling = Util.getDoubleValue(pnode.getAttribute(ATTR_SCALING));
					double addscale = Util.getDoubleValue(pnode.getAttribute(ATTR_SCALING_OFFSET));

					ValueType vt = null;
					Value v = null;
					if (DataType.getDataTypeInt(dataType) != null) {
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
							LOGGER.error(pnode.getName() + " : " + result.getExceptionMessage());
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
								num = Util.toUnsignedLong(Util.toUnsignedInt(slo) * 10000 + Util.toUnsignedInt(shi));
							else
								num = Util.toUnsignedLong(Util.toUnsignedInt(shi) * 10000 + Util.toUnsignedInt(slo));
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
					} else if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						} else if (pnode.getValueType().compare(ValueType.BOOL)) {
							pnode.setValue(new Value(false));
						}
					}
				}

			} catch (ModbusTransportException | ErrorResponseException e) {
				LOGGER.debug("error during batch poll: ", e);
				checkDeviceConnected();
				if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
					for (Node pnode : polled) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						} else if (pnode.getValueType().compare(ValueType.BOOL)) {
							pnode.setValue(new Value(false));
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
		return conn.master;
	}

	@Override
	public Node getStatusNode() {
		return this.statnode;
	}

	@Override
	void checkDeviceConnected() {
		int slaveId = node.getAttribute(ATTR_SLAVE_ID).getNumber().intValue();

		boolean connected = false;
		if (conn.master != null) {
			try {
				LOGGER.debug("pinging device to test connectivity");
				connected = conn.master.testSlaveNode(slaveId);
			} catch (Exception e) {
				LOGGER.debug("error during device ping: ", e);
			}
			if (connected) {
				statnode.setValue(new Value(NODE_STATUS_READY));
			} else {
				statnode.setValue(new Value(NODE_STATUS_PING_FAILED));
				conn.checkConnection();
			}
		} else {
			statnode.setValue(new Value(NODE_STATUS_CONN_DOWN));
		}
		return;
	}
}
