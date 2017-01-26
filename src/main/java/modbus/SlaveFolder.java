package modbus;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.code.RegisterRange;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.BinaryLocator;
import com.serotonin.modbus4j.locator.NumericLocator;
import com.serotonin.modbus4j.locator.StringLocator;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;

public class SlaveFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveFolder.class);
	}

	static final String ACTION_ADD_POINT = "add point";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_MAKE_COPY = "make copy";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_RENAME = "rename";
	static final String ACTION_ADD_FOLDER = "add folder";

	static final String ATTR_NAME = "name";
	static final String ATTR_POINT_TYPE = "type";
	static final String ATTR_OFFSET = "offset";
	static final String ATTR_NUMBER_OF_REGISTERS = "number of registers";
	static final String ATTR_DATA_TYPE = "data type";
	static final String ATTR_BIT = "bit";
	static final String ATTR_SCALING = "scaling";
	static final String ATTR_SCALING_OFFSET = "scaling offset";
	static final String ATTR_WRITBLE = "writable";

	static final String ATTR_RESTORE_TYPE = "restoreType";
	static final String ATTR_RESTORE_FOLDER = "folder";
	static final String ATTR_RESTORE_POINT = "point";

	static final String NODE_STATUS = "Status";
	static final String NODE_STATUS_SETTING_UP = "Setting up device";
	static final String NODE_STATUS_READY = "Ready";

	static final String MSG_STRING_SIZE_NOT_MATCHING = "new string size is not the same as the old one";

	ModbusConnection conn;
	protected Node node;
	protected SlaveFolder root;

	SlaveFolder(ModbusConnection conn, Node node) {
		this.conn = conn;
		this.node = node;

		node.setAttribute(ATTR_RESTORE_TYPE, new Value("folder"));

		Action act = getAddPointAction();
		node.createChild(ACTION_ADD_POINT).setAction(act).build().setSerializable(false);

		makeEditAction();

		act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild(ACTION_MAKE_COPY).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new RemoveHandler());
		node.createChild(ACTION_REMOVE).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild(ACTION_ADD_FOLDER).setAction(act).build().setSerializable(false);
	}

	SlaveFolder(ModbusConnection conn, Node node, SlaveFolder root) {
		this(conn, node);
		this.root = root;

	}

	Action getAddPointAction() {
		Action act = new Action(Permission.READ, new AddPointHandler());

		act.addParameter(new Parameter(ATTR_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTR_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class))));
		act.addParameter(new Parameter(ATTR_OFFSET, ValueType.NUMBER));
		act.addParameter(new Parameter(ATTR_NUMBER_OF_REGISTERS, ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter(ATTR_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class))));
		act.addParameter(new Parameter(ATTR_BIT, ValueType.NUMBER));
		act.addParameter(new Parameter(ATTR_SCALING, ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter(ATTR_SCALING_OFFSET, ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter(ATTR_WRITBLE, ValueType.BOOL, new Value(false)));

		return act;
	}

	void restoreLastSession() {
		if (node.getChildren() == null)
			return;

		Map<String, Node> children = node.getChildren();
		for (Node child : children.values()) {
			Value restoreType = child.getAttribute(ATTR_RESTORE_TYPE);
			if (restoreType != null) {
				if (ATTR_RESTORE_FOLDER.equals(restoreType.getString())) {
					SlaveFolder sf = new SlaveFolder(conn, child, root);
					sf.restoreLastSession();
				} else if (ATTR_RESTORE_POINT.equals(restoreType.getString())) {
					Value type = child.getAttribute(ATTR_POINT_TYPE);
					Value offset = child.getAttribute(ATTR_OFFSET);
					Value numRegs = child.getAttribute(ATTR_NUMBER_OF_REGISTERS);
					Value dataType = child.getAttribute(ATTR_DATA_TYPE);
					Value bit = child.getAttribute(ATTR_BIT);
					if (bit == null)
						child.setAttribute(ATTR_BIT, new Value(-1));
					Value scaling = child.getAttribute(ATTR_SCALING);
					Value addScale = child.getAttribute(ATTR_SCALING_OFFSET);
					Value writable = child.getAttribute(ATTR_WRITBLE);
					if (type != null && offset != null && numRegs != null && dataType != null && scaling != null
							&& addScale != null && writable != null) {
						setupPointActions(child);
						conn.getLink().setupPoint(child, root);
					} else {
						node.removeChild(child);
					}
				}
			} else if (child.getAction() == null && !(root == this && NODE_STATUS.equals(child.getName()))) {
				node.removeChild(child);
			}
		}
	}

	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				duplicate(newname);
		}
	}

	protected class RenameHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter(ATTR_NAME, ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				rename(newname);
		}
	}

	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}

	protected void remove() {
		node.clearChildren();
		node.getParent().removeChild(node);
	}

	protected void rename(String newname) {
		duplicate(newname);
		remove();
	}

	protected void duplicate(String name) {
		JsonObject jobj = conn.getLink().copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj);
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		conn.getLink().copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);

		SlaveFolder sf = new SlaveFolder(conn, newnode, root);
		sf.restoreLastSession();
	}

	void makeEditAction() {
		Action act = new Action(Permission.READ, new RenameHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
	}

	protected JsonObject getParentJson(JsonObject jobj) {
		return getParentJson(jobj, node);
	}

	private JsonObject getParentJson(JsonObject jobj, Node node) {
		if ((node == root.getConnection().node))
			return jobj;
		else
			return getParentJson(jobj, node.getParent()).get(node.getParent().getName());
	}

	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter(ATTR_NAME, ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			new SlaveFolder(conn, child, root);
		}
	}

	protected class AddPointHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type = null;
			ValueType valType = null;
			try {
				type = PointType
						.valueOf(event.getParameter(ATTR_POINT_TYPE, ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid type");
				LOGGER.debug("error: ", e);
				return;
			}
			int offset = event.getParameter(ATTR_OFFSET, ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter(ATTR_NUMBER_OF_REGISTERS, ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING)
					&& event.getParameter(ATTR_WRITBLE, ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE) {
				dataType = DataType.BOOLEAN;
				valType = ValueType.BOOL;
			} else
				try {
					dataType = DataType
							.valueOf(event.getParameter(ATTR_DATA_TYPE, ValueType.STRING).getString().toUpperCase());
					if (dataType.isString()) {
						valType = ValueType.STRING;
					} else {
						valType = ValueType.NUMBER;
					}
				} catch (Exception e1) {
					LOGGER.error("invalid data type");
					LOGGER.debug("error: ", e1);
					return;
				}
			int bit = event.getParameter(ATTR_BIT, new Value(-1)).getNumber().intValue();
			double scaling = event.getParameter(ATTR_SCALING, ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter(ATTR_SCALING_OFFSET, ValueType.NUMBER).getNumber().doubleValue();

			Node pnode = node.createChild(name).setValueType(valType).build();
			pnode.setAttribute(ATTR_POINT_TYPE, new Value(type.toString()));
			pnode.setAttribute(ATTR_OFFSET, new Value(offset));
			pnode.setAttribute(ATTR_NUMBER_OF_REGISTERS, new Value(numRegs));
			pnode.setAttribute(ATTR_DATA_TYPE, new Value(dataType.toString()));
			pnode.setAttribute(ATTR_BIT, new Value(bit));
			pnode.setAttribute(ATTR_SCALING, new Value(scaling));
			pnode.setAttribute(ATTR_SCALING_OFFSET, new Value(addscale));
			pnode.setAttribute(ATTR_WRITBLE, new Value(writable));
			setupPointActions(pnode);
			conn.getLink().setupPoint(pnode, root);
			pnode.setAttribute(ATTR_RESTORE_TYPE, new Value("point"));
		}
	}

	protected void setupPointActions(Node pointNode) {
		Action act = new Action(Permission.READ, new RemovePointHandler(pointNode));
		Node anode = pointNode.getChild("remove");
		if (anode == null)
			pointNode.createChild("remove").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new EditPointHandler(pointNode));
		act.addParameter(new Parameter(ATTR_NAME, ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter(ATTR_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class)),
				pointNode.getAttribute(ATTR_POINT_TYPE)));
		act.addParameter(new Parameter(ATTR_OFFSET, ValueType.NUMBER, pointNode.getAttribute(ATTR_OFFSET)));
		act.addParameter(new Parameter(ATTR_NUMBER_OF_REGISTERS, ValueType.NUMBER,
				pointNode.getAttribute(ATTR_NUMBER_OF_REGISTERS)));
		act.addParameter(new Parameter(ATTR_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class)),
				pointNode.getAttribute(ATTR_DATA_TYPE)));
		act.addParameter(new Parameter(ATTR_BIT, ValueType.NUMBER, pointNode.getAttribute(ATTR_BIT)));
		act.addParameter(new Parameter(ATTR_SCALING, ValueType.NUMBER, pointNode.getAttribute(ATTR_SCALING)));
		act.addParameter(
				new Parameter(ATTR_SCALING_OFFSET, ValueType.NUMBER, pointNode.getAttribute(ATTR_SCALING_OFFSET)));
		act.addParameter(new Parameter(ATTR_WRITBLE, ValueType.BOOL, pointNode.getAttribute(ATTR_WRITBLE)));
		anode = pointNode.getChild(ACTION_EDIT);
		if (anode == null)
			pointNode.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new CopyPointHandler(pointNode));
		act.addParameter(new Parameter(ATTR_NAME, ValueType.STRING));
		anode = pointNode.getChild(ACTION_MAKE_COPY);
		if (anode == null)
			pointNode.createChild(ACTION_MAKE_COPY).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		boolean writable = pointNode.getAttribute(ATTR_WRITBLE).getBool();
		if (writable) {

			pointNode.setWritable(Writable.WRITE);
			pointNode.getListener().setValueHandler(new SetHandler(pointNode));
		}
	}

	protected class CopyPointHandler implements Handler<ActionResult> {
		private Node pointNode;

		CopyPointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			if (name.length() > 1 && !name.equals(pointNode.getName())) {
				copyPoint(pointNode, name);
			}
		}
	}

	private Node copyPoint(Node pointNode, String name) {
		JsonObject jobj = conn.getLink().copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj).get(node.getName());
		JsonObject pointnodeobj = parentobj.get(pointNode.getName());
		parentobj.put(name, pointnodeobj);
		conn.getLink().copyDeserializer.deserialize(jobj);
		Node newnode = node.getChild(name);
		setupPointActions(newnode);
		conn.getLink().setupPoint(newnode, root);
		return newnode;
	}

	protected class EditPointHandler implements Handler<ActionResult> {
		private Node pointNode;

		EditPointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType
						.valueOf(event.getParameter(ATTR_POINT_TYPE, ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid type");
				LOGGER.debug("error: ", e);
				return;
			}
			int offset = event.getParameter(ATTR_OFFSET, ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter(ATTR_NUMBER_OF_REGISTERS, ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING)
					&& event.getParameter(ATTR_WRITBLE, ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE)
				dataType = DataType.BOOLEAN;
			else
				try {
					dataType = DataType
							.valueOf(event.getParameter(ATTR_DATA_TYPE, ValueType.STRING).getString().toUpperCase());
				} catch (Exception e1) {
					LOGGER.error("invalid data type");
					LOGGER.debug("error: ", e1);
					return;
				}
			int bit = event.getParameter(ATTR_BIT, new Value(-1)).getNumber().intValue();
			double scaling = event.getParameter(ATTR_SCALING, ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter(ATTR_SCALING_OFFSET, ValueType.NUMBER).getNumber().doubleValue();

			if (!name.equals(pointNode.getName())) {
				Node newnode = copyPoint(pointNode, name);
				node.removeChild(pointNode);
				pointNode = newnode;
			}
			pointNode.setAttribute(ATTR_POINT_TYPE, new Value(type.toString()));
			pointNode.setAttribute(ATTR_OFFSET, new Value(offset));
			pointNode.setAttribute(ATTR_NUMBER_OF_REGISTERS, new Value(numRegs));
			pointNode.setAttribute(ATTR_DATA_TYPE, new Value(dataType.toString()));
			pointNode.setAttribute(ATTR_BIT, new Value(bit));
			pointNode.setAttribute(ATTR_SCALING, new Value(scaling));
			pointNode.setAttribute(ATTR_SCALING_OFFSET, new Value(addscale));
			pointNode.setAttribute(ATTR_WRITBLE, new Value(writable));
			setupPointActions(pointNode);
			conn.getLink().setupPoint(pointNode, root);
			pointNode.setAttribute(ATTR_RESTORE_TYPE, new Value("point"));
		}
	}

	protected class RemovePointHandler implements Handler<ActionResult> {
		private Node toRemove;

		RemovePointHandler(Node pnode) {
			toRemove = pnode;
		}

		public void handle(ActionResult event) {
			node.removeChild(toRemove);
		}
	}

	private final Map<String, Boolean> polledNodes = new ConcurrentHashMap<>();

	protected void readPoint(Node pointNode) {
		if (pointNode.getAttribute(ATTR_OFFSET) == null)
			return;

		if (root.getMaster() == null) {
			root.getConnection().stop();
			return;
		}

		if (!ModbusConnection.NODE_STATUS_CONNECTED.equals(conn.statnode.getValue().getString())) {
			conn.checkConnection();
			return;
		}

		PointType type = PointType.valueOf(pointNode.getAttribute(ATTR_POINT_TYPE).getString());
		int offset = Util.getIntValue(pointNode.getAttribute(ATTR_OFFSET));
		int numRegs = Util.getIntValue(pointNode.getAttribute(ATTR_NUMBER_OF_REGISTERS));
		int id = Util.getIntValue(root.node.getAttribute(ModbusConnection.ATTR_SLAVE_ID));

		int bit = Util.getIntValue(pointNode.getAttribute(ATTR_BIT));
		double scaling = Util.getDoubleValue(pointNode.getAttribute(ATTR_SCALING));
		double addscale = Util.getDoubleValue(pointNode.getAttribute(ATTR_SCALING_OFFSET));
		DataType dataType = DataType.valueOf(pointNode.getAttribute(ATTR_DATA_TYPE).getString());
		ModbusRequest request = null;
		JsonArray val = new JsonArray();

		String requestString = "";
		try {
			switch (type) {
			case COIL:
				request = new ReadCoilsRequest(id, offset, numRegs);
				break;
			case DISCRETE:
				request = new ReadDiscreteInputsRequest(id, offset, numRegs);
				break;
			case HOLDING:
				request = new ReadHoldingRegistersRequest(id, offset, numRegs);
				break;
			case INPUT:
				request = new ReadInputRegistersRequest(id, offset, numRegs);
				break;
			}
			if (request != null)
				LOGGER.debug("Sending request: " + request.toString());
			requestString = ":" + id + ":" + offset + ":" + numRegs + ":";
			if (polledNodes.containsKey(requestString)) {
				// LOGGER.info("Skipping already currently polling request: " +
				// requestString);
				return;
			}
			polledNodes.put(requestString, true);
			ReadResponse response = (ReadResponse) root.getMaster().send(request);
			polledNodes.remove(requestString);
			LOGGER.debug("Got response: " + response.toString());
			root.getStatusNode().setValue(new Value("Ready"));
			if (response.getExceptionCode() != -1) {
				LOGGER.debug("error response: " + response.getExceptionMessage());
				return;
			}
			if (type == PointType.COIL || type == PointType.DISCRETE) {
				boolean[] booldat = response.getBooleanData();
				LOGGER.debug(Arrays.toString(booldat));
				for (int j = 0; j < numRegs; j++) {
					boolean b = booldat[j];
					val.add(b);
				}
			} else {
				LOGGER.debug(Arrays.toString(response.getShortData()));
				val = parseResponse(response, dataType, scaling, addscale, type, id, offset, bit);
			}
		} catch (ModbusTransportException e) {
			LOGGER.debug("ModbusTransportException: ", e);

			polledNodes.remove(requestString);
			conn.checkConnection();
			if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
				if (pointNode.getValueType().compare(ValueType.NUMBER)) {
					pointNode.setValue(new Value(0));
				} else if (pointNode.getValueType().compare(ValueType.BOOL)) {
					pointNode.setValue(new Value(false));
				}
			}
		} finally {
			try {
				root.getMaster().destroy();
			} catch (Exception e) {
				LOGGER.debug("error destroying last master");
			}
		}
		String valString = val.toString();
		Value v = new Value(valString);
		ValueType vt = ValueType.STRING;
		if (val.size() == 0) {
			vt = pointNode.getValueType();
			v = null;
		}
		if (val.size() == 1) {
			valString = val.get(0).toString();
			if (dataType == DataType.BOOLEAN) {
				vt = ValueType.BOOL;
				v = new Value(Boolean.parseBoolean(valString));
			} else if (dataType.isString()) {
				vt = ValueType.STRING;
				v = new Value(valString);
			} else {
				try {
					vt = ValueType.NUMBER;
					v = new Value(Double.parseDouble(valString));
				} catch (Exception e) {
					vt = ValueType.STRING;
					v = new Value(valString);
				}
			}
		}

		if (v != null) {
			pointNode.setValueType(vt);
			pointNode.setValue(v);
			LOGGER.debug("read and updated " + pointNode.getName());
		}
	}

	public Node getStatusNode() {
		return null;
	}

	protected class SetHandler implements Handler<ValuePair> {
		private Node vnode;

		SetHandler(Node vnode) {
			this.vnode = vnode;
		}

		public void handle(ValuePair event) {
			if (root.getMaster() == null) {
				root.getConnection().stop();
				return;
			}

			if (!event.isFromExternalSource())
				return;

			PointType type = PointType.valueOf(vnode.getAttribute(ATTR_POINT_TYPE).getString());
			int offset = vnode.getAttribute(ATTR_OFFSET).getNumber().intValue();
			int id = root.node.getAttribute(ModbusConnection.ATTR_SLAVE_ID).getNumber().intValue();
			int numRegs = vnode.getAttribute(ATTR_NUMBER_OF_REGISTERS).getNumber().intValue();
			DataType dataType = DataType.valueOf(vnode.getAttribute(ATTR_DATA_TYPE).getString());
			int bit = vnode.getAttribute(ATTR_BIT).getNumber().intValue();
			double scaling = vnode.getAttribute(ATTR_SCALING).getNumber().doubleValue();
			double addscale = vnode.getAttribute(ATTR_SCALING_OFFSET).getNumber().doubleValue();
			Value oldval = event.getPrevious();
			Value newval = event.getCurrent();
			JsonArray newValArr = new JsonArray();
			JsonArray oldValArr = new JsonArray();

			if (newval.getType() == ValueType.STRING && oldval.getType() == ValueType.STRING) {
				String valstr = newval.getString();
				String oldstr = oldval.getString();

				if (!valstr.startsWith("[")) {
					newValArr.add(valstr);
				}

				if (!oldstr.startsWith("[")) {
					oldValArr.add(oldstr);
				}

				if (newValArr.size() != oldValArr.size()) {
					LOGGER.error(MSG_STRING_SIZE_NOT_MATCHING);
					return;
				}
			} else if (newval.getType().compare(ValueType.BOOL)) {
				newValArr = new JsonArray();
				newValArr.add(newval.getBool());
			} else if (newval.getType() == ValueType.NUMBER) {
				newValArr = new JsonArray();
				newValArr.add(newval.getNumber());
			} else {
				LOGGER.error("Unexpected value type");
				return;
			}

			ModbusRequest request = null;
			try {
				switch (type) {
				case COIL:
					request = new WriteCoilsRequest(id, offset, makeBoolArr(newValArr));
					break;
				case HOLDING:
					request = new WriteRegistersRequest(id, offset,
							makeShortArr(newValArr, dataType, scaling, addscale, type, id, offset, numRegs, bit));
					break;
				default:
					break;
				}
				if (request != null)
					LOGGER.debug("set request: " + request.toString());
				root.getMaster().send(request);
			} catch (ModbusTransportException e) {
				LOGGER.error("Modbus transport exception");
				LOGGER.debug("error: ", e);
				return;
			} catch (Exception e) {
				LOGGER.error("make arr exception");
				LOGGER.debug("error: ", e);
				return;
			} finally {
				try {
					root.getMaster().destroy();
				} catch (Exception e) {
					LOGGER.debug("error destroying last master");
				}
			}
		}
	}

	protected static boolean[] makeBoolArr(JsonArray jarr) throws Exception {
		boolean[] retval = new boolean[jarr.size()];
		for (int i = 0; i < jarr.size(); i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Boolean))
				throw new Exception("not a boolean array");
			else
				retval[i] = (Boolean) o;
		}
		return retval;
	}

	public ModbusConnection getConnection() {
		return null;
	}

	public ModbusMaster getMaster() {
		return root.getMaster();
	}

	protected static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling, double addscaling, PointType pt,
			int slaveid, int offset, int numRegisters, int bitnum) throws Exception {
		short[] retval = {};
		Integer dtint = DataType.getDataTypeInt(dt);
		if (dtint != null) {
			if (!dt.isString()) {
				NumericLocator nloc = new NumericLocator(slaveid, getPointTypeInt(pt), offset, dtint);
				for (int i = 0; i < jarr.size(); i++) {
					Object o = jarr.get(i);
					if (!(o instanceof Number))
						throw new Exception("not a numeric array");
					Number n = ((Number) o).doubleValue() * scaling - addscaling;
					retval = concatArrays(retval, nloc.valueToShorts(n));
				}
			} else {
				Object o = jarr.get(0);
				if (!(o instanceof String))
					throw new Exception("not a string");
				String str = (String) o;
				StringLocator sloc = new StringLocator(slaveid, getPointTypeInt(pt), offset, dtint, numRegisters);
				retval = sloc.valueToShorts(str);
			}
		} else if (dt == DataType.BOOLEAN) {
			retval = new short[(int) Math.ceil((double) jarr.size() / 16)];
			for (int i = 0; i < retval.length; i++) {
				short element = 0;
				for (int j = 0; j < 16; j++) {
					int bit = 0;
					if (j == bitnum) {
						Object o = jarr.get(i);
						if (!(o instanceof Boolean))
							throw new Exception("not a boolean array");
						if ((Boolean) o)
							bit = 1;
					} else if (bitnum == -1 && i + j < jarr.size()) {
						Object o = jarr.get(i + j);
						if (!(o instanceof Boolean))
							throw new Exception("not a boolean array");
						if ((Boolean) o)
							bit = 1;
					}
					element = (short) (element & (bit << (15 - j)));
					jarr.get(i + j);
				}
				retval[i] = element;
			}
			return retval;
		} else if (dt == DataType.INT32M10K || dt == DataType.UINT32M10K || dt == DataType.INT32M10KSWAP
				|| dt == DataType.UINT32M10KSWAP) {
			retval = new short[2 * jarr.size()];
			for (int i = 0; i < jarr.size(); i++) {
				Object o = jarr.get(i);
				if (!(o instanceof Number))
					throw new Exception("not an int array");
				Number n = ((Number) o).doubleValue() * scaling - addscaling;
				long aslong = n.longValue();
				if (dt == DataType.INT32M10K || dt == DataType.UINT32M10K) {
					retval[i * 2] = (short) (aslong / 10000);
					retval[(i * 2) + 1] = (short) (aslong % 10000);
				} else {
					retval[i * 2] = (short) (aslong % 10000);
					retval[(i * 2) + 1] = (short) (aslong / 10000);
				}

			}
		}
		return retval;
	}

	private static short[] concatArrays(short[] arr1, short[] arr2) {
		int len1 = arr1.length;
		int len2 = arr2.length;
		short[] retval = new short[len1 + len2];
		System.arraycopy(arr1, 0, retval, 0, len1);
		System.arraycopy(arr2, 0, retval, len1, len2);
		return retval;
	}

	protected JsonArray parseResponse(ReadResponse response, DataType dataType, double scaling, double addscaling,
			PointType pointType, int slaveid, int offset, int bit) {
		short[] responseData = response.getShortData();
		byte[] byteData = response.getData();
		JsonArray retval = new JsonArray();
		Integer dt = DataType.getDataTypeInt(dataType);
		if (dt != null && dataType != DataType.BOOLEAN) {
			if (!dataType.isString()) {
				NumericLocator nloc = new NumericLocator(slaveid, getPointTypeInt(pointType), offset, dt);
				int regsPerVal = nloc.getRegisterCount();
				for (int i = 0; i < responseData.length; i += regsPerVal) {
					try {
						Number num = nloc.bytesToValueRealOffset(byteData, i);
						retval.add(num.doubleValue() / scaling + addscaling);
					} catch (Exception e) {
						LOGGER.debug("Error retrieving numeric value", e);
					}
				}
			} else {
				StringLocator sloc = new StringLocator(slaveid, getPointTypeInt(pointType), offset, dt,
						responseData.length);
				retval.add(sloc.bytesToValueRealOffset(byteData, 0));
			}
		} else {
			int last = 0;
			int regnum = 0;
			switch (dataType) {
			case BOOLEAN:
				if (bit != -1 && responseData.length > 0) {
					BinaryLocator bloc = new BinaryLocator(slaveid, getPointTypeInt(pointType), offset, bit);
					retval.add(bloc.bytesToValueRealOffset(byteData, 0));
				} else {
					for (short s : responseData) {
						for (int i = 0; i < 16; i++) {
							retval.add(((s >> i) & 1) != 0);
						}
					}
				}
				break;
			case INT32M10KSWAP:
			case INT32M10K:
				for (short s : responseData) {
					if (regnum == 0) {
						regnum += 1;
						last = s;
					} else {
						regnum = 0;
						int num;
						boolean swap = (dataType == DataType.INT32M10KSWAP);
						if (swap)
							num = s * 10000 + last;
						else
							num = last * 10000 + s;
						retval.add(num / scaling + addscaling);
					}
				}
				break;
			case UINT32M10KSWAP:
			case UINT32M10K:
				for (short s : responseData) {
					if (regnum == 0) {
						regnum += 1;
						last = toUnsignedInt(s);
					} else {
						regnum = 0;
						long num;
						boolean swap = (dataType == DataType.UINT32M10KSWAP);
						if (swap)
							num = toUnsignedLong(toUnsignedInt(s) * 10000 + last);
						else
							num = toUnsignedLong(last * 10000 + toUnsignedInt(s));
						retval.add(num / scaling + addscaling);
					}
				}
				break;
			default:
				break;
			}
		}
		return retval;
	}

	static int toUnsignedInt(short x) {
		return ((int) x) & 0xffff;
	}

	static long toUnsignedLong(int x) {
		return ((long) x) & 0xffffffffL;
	}

	protected static int getPointTypeInt(PointType pt) {
		switch (pt) {
		case COIL:
			return RegisterRange.COIL_STATUS;
		case DISCRETE:
			return RegisterRange.INPUT_STATUS;
		case HOLDING:
			return RegisterRange.HOLDING_REGISTER;
		case INPUT:
			return RegisterRange.INPUT_REGISTER;
		default:
			return 0;
		}
	}

	// private int getDigitFromBcd(int bcd, int position) {
	// return (bcd >>> (position*4)) & 15;
	// }

}
