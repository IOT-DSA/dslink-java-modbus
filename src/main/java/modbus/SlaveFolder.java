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

	static final String MSG_STRING_SIZE_NOT_MATCHING = "new string size is not the same as the old one";
	protected ModbusLink link;
	protected Node node;
	protected SlaveFolder root;

	SlaveFolder(ModbusLink link, Node node) {
		this.link = link;
		this.node = node;
		node.setAttribute("restoreType", new Value("folder"));

		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("data type",
				ValueType.makeEnum("BOOLEAN", "INT16", "UINT16", "INT16SWAP", "UINT16SWAP", "INT32", "UINT32",
						"INT32SWAP", "UINT32SWAP", "INT32SWAPSWAP", "UINT32SWAPSWAP", "FLOAT32", "FLOAT32SWAP", "INT64",
						"UINT64", "INT64SWAP", "UINT64SWAP", "FLOAT64", "FLOAT64SWAP", "BCD16", "BCD32", "BCD32SWAP",
						"CHARSTRING", "VARCHARSTRING", "INT32M10K", "UINT32M10K", "INT32M10KSWAP", "UINT32M10KSWAP")));
		act.addParameter(new Parameter("bit", ValueType.NUMBER));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("scaling offset", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("writable", ValueType.BOOL, new Value(false)));
		node.createChild("add point").setAction(act).build().setSerializable(false);

		if (!(this instanceof SlaveNode)) {
			act = new Action(Permission.READ, new RenameHandler());
			act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
			node.createChild("rename").setAction(act).build().setSerializable(false);
		}

		act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("make copy").setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("add folder").setAction(act).build().setSerializable(false);
	}

	SlaveFolder(ModbusLink link, Node node, SlaveFolder root) {
		this(link, node);
		this.root = root;

	}

	void restoreLastSession() {
		if (node.getChildren() == null)
			return;
		for (Node child : node.getChildren().values()) {
			Value restoreType = child.getAttribute("restoreType");
			if (restoreType != null) {
				if (restoreType.getString().equals("folder")) {
					SlaveFolder sf = new SlaveFolder(link, child, root);
					sf.restoreLastSession();
				} else if (restoreType.getString().equals("point")) {
					Value type = child.getAttribute("type");
					Value offset = child.getAttribute("offset");
					Value numRegs = child.getAttribute("number of registers");
					Value dataType = child.getAttribute("data type");
					Value bit = child.getAttribute("bit");
					if (bit == null)
						child.setAttribute("bit", new Value(-1));
					Value scaling = child.getAttribute("scaling");
					Value addScale = child.getAttribute("scaling offset");
					Value writable = child.getAttribute("writable");
					if (type != null && offset != null && numRegs != null && dataType != null && scaling != null
							&& addScale != null && writable != null) {
						setupPointActions(child);
						link.setupPoint(child, root);
					} else {
						node.removeChild(child);
					}
				}
			} else if (child.getAction() == null && !(root == this && child.getName().equals("STATUS"))) {
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
			String newname = event.getParameter("name", ValueType.STRING).getString();
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
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj);
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveFolder sf = new SlaveFolder(link, newnode, root);
		sf.restoreLastSession();
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
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			new SlaveFolder(link, child, root);
		}
	}

	protected enum PointType {
		COIL, DISCRETE, HOLDING, INPUT
	}

	protected enum DataType {
		BOOLEAN, INT16, UINT16, INT16SWAP, UINT16SWAP, INT32, UINT32, INT32SWAP, UINT32SWAP, INT32SWAPSWAP, UINT32SWAPSWAP, FLOAT32, FLOAT32SWAP, INT64, UINT64, INT64SWAP, UINT64SWAP, FLOAT64, FLOAT64SWAP, BCD16, BCD32, BCD32SWAP, CHARSTRING, VARCHARSTRING, INT32M10K, UINT32M10K, INT32M10KSWAP, UINT32M10KSWAP;

		public boolean isFloat() {
			return (this == FLOAT32 || this == FLOAT32SWAP || this == FLOAT64 || this == FLOAT64SWAP);
		}

		public boolean isString() {
			return (this == CHARSTRING || this == VARCHARSTRING);
		}
	}

	protected class AddPointHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType.valueOf(event.getParameter("type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid type");
				LOGGER.debug("error: ", e);
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING)
					&& event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE)
				dataType = DataType.BOOLEAN;
			else
				try {
					dataType = DataType
							.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
				} catch (Exception e1) {
					LOGGER.error("invalid data type");
					LOGGER.debug("error: ", e1);
					return;
				}
			int bit = event.getParameter("bit", new Value(-1)).getNumber().intValue();
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter("scaling offset", ValueType.NUMBER).getNumber().doubleValue();
			Node pnode = node.createChild(name).setValueType(ValueType.STRING).build();
			pnode.setAttribute("type", new Value(type.toString()));
			pnode.setAttribute("offset", new Value(offset));
			pnode.setAttribute("number of registers", new Value(numRegs));
			pnode.setAttribute("data type", new Value(dataType.toString()));
			pnode.setAttribute("bit", new Value(bit));
			pnode.setAttribute("scaling", new Value(scaling));
			pnode.setAttribute("scaling offset", new Value(addscale));
			pnode.setAttribute("writable", new Value(writable));
			setupPointActions(pnode);
			link.setupPoint(pnode, root);
			pnode.setAttribute("restoreType", new Value("point"));
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
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT"),
				pointNode.getAttribute("type")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER, pointNode.getAttribute("offset")));
		act.addParameter(
				new Parameter("number of registers", ValueType.NUMBER, pointNode.getAttribute("number of registers")));
		act.addParameter(new Parameter("data type",
				ValueType.makeEnum("BOOLEAN", "INT16", "UINT16", "INT16SWAP", "UINT16SWAP", "INT32", "UINT32",
						"INT32SWAP", "UINT32SWAP", "INT32SWAPSWAP", "UINT32SWAPSWAP", "FLOAT32", "FLOAT32SWAP", "INT64",
						"UINT64", "INT64SWAP", "UINT64SWAP", "FLOAT64", "FLOAT64SWAP", "BCD16", "BCD32", "BCD32SWAP",
						"CHARSTRING", "VARCHARSTRING", "INT32M10K", "UINT32M10K", "INT32M10KSWAP", "UINT32M10KSWAP"),
				pointNode.getAttribute("data type")));
		act.addParameter(new Parameter("bit", ValueType.NUMBER, pointNode.getAttribute("bit")));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, pointNode.getAttribute("scaling")));
		act.addParameter(new Parameter("scaling offset", ValueType.NUMBER, pointNode.getAttribute("scaling offset")));
		act.addParameter(new Parameter("writable", ValueType.BOOL, pointNode.getAttribute("writable")));
		anode = pointNode.getChild("edit");
		if (anode == null)
			pointNode.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new CopyPointHandler(pointNode));
		act.addParameter(new Parameter("name", ValueType.STRING));
		anode = pointNode.getChild("make copy");
		if (anode == null)
			pointNode.createChild("make copy").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		boolean writable = pointNode.getAttribute("writable").getBool();
		if (writable) {

			pointNode.setWritable(Writable.WRITE);
			pointNode.getListener().setValueHandler(new SetHandler(pointNode));

			// act = new Action(Permission.READ, new SetHandler(pointNode));
			// act.addParameter(new Parameter("value", ValueType.STRING));
			// pointNode.createChild("set").setAction(act).build().setSerializable(false);
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
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj).get(node.getName());
		JsonObject pointnodeobj = parentobj.get(pointNode.getName());
		parentobj.put(name, pointnodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getChild(name);
		setupPointActions(newnode);
		link.setupPoint(newnode, root);
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
				type = PointType.valueOf(event.getParameter("type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid type");
				LOGGER.debug("error: ", e);
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING)
					&& event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE)
				dataType = DataType.BOOLEAN;
			else
				try {
					dataType = DataType
							.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
				} catch (Exception e1) {
					LOGGER.error("invalid data type");
					LOGGER.debug("error: ", e1);
					return;
				}
			int bit = event.getParameter("bit", new Value(-1)).getNumber().intValue();
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter("scaling offset", ValueType.NUMBER).getNumber().doubleValue();

			if (!name.equals(pointNode.getName())) {
				Node newnode = copyPoint(pointNode, name);
				node.removeChild(pointNode);
				pointNode = newnode;
			}
			pointNode.setAttribute("type", new Value(type.toString()));
			pointNode.setAttribute("offset", new Value(offset));
			pointNode.setAttribute("number of registers", new Value(numRegs));
			pointNode.setAttribute("data type", new Value(dataType.toString()));
			pointNode.setAttribute("bit", new Value(bit));
			pointNode.setAttribute("scaling", new Value(scaling));
			pointNode.setAttribute("scaling offset", new Value(addscale));
			pointNode.setAttribute("writable", new Value(writable));
			setupPointActions(pointNode);
			link.setupPoint(pointNode, root);
			pointNode.setAttribute("restoreType", new Value("point"));
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

	protected Double getDoubleValue(Value val) {
		double ret = 0.0;
		{
			if (val.getType() == ValueType.STRING) {
				ret = Double.parseDouble(val.getString());
			} else if (val.getType() == ValueType.NUMBER) {
				ret = val.getNumber().doubleValue();
			}
		}
		return ret;
	}

	protected int getIntValue(Value val) {
		int ret = 0;
		{
			if (val.getType() == ValueType.STRING) {
				ret = Integer.parseInt(val.getString());
			} else if (val.getType() == ValueType.NUMBER) {
				ret = val.getNumber().intValue();
			}
		}
		return ret;
	}

	private final Map<String, Boolean> polledNodes = new ConcurrentHashMap<>();

	protected void readPoint(Node pointNode) {
		if (pointNode.getAttribute("offset") == null)
			return;

		if (root.getMaster() == null) {
			root.getConnection().stop();
			return;
		}

		PointType type = PointType.valueOf(pointNode.getAttribute("type").getString());
		int offset = getIntValue(pointNode.getAttribute("offset"));
		int numRegs = getIntValue(pointNode.getAttribute("number of registers"));
		int id = getIntValue(root.node.getAttribute("slave id"));

		int bit = getIntValue(pointNode.getAttribute("bit"));
		double scaling = getDoubleValue(pointNode.getAttribute("scaling"));
		double addscale = getDoubleValue(pointNode.getAttribute("scaling offset"));
		DataType dataType = DataType.valueOf(pointNode.getAttribute("data type").getString());
		ModbusRequest request = null;
		JsonArray val = new JsonArray();
		// try {
		// root.master.init();
		// } catch (ModbusInitException e) {
		// // TODO Auto-generated catch block
		// LOGGER.debug("error: ", e);
		// }
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
		} catch (Exception e) {
			LOGGER.debug("error: ", e);
			polledNodes.remove(requestString);
		} finally {
			// try {
			// root.master.destroy();
			// } catch (Exception e) {
			// LOGGER.debug("error destroying last master");
			// }
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
		pointNode.setValueType(vt);
		pointNode.setValue(v);
		LOGGER.debug("read and updated " + pointNode.getName());
	}

	private Node getStatusNode() {
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

			PointType type = PointType.valueOf(vnode.getAttribute("type").getString());
			int offset = vnode.getAttribute("offset").getNumber().intValue();
			int id = root.node.getAttribute("slave id").getNumber().intValue();
			int numRegs = vnode.getAttribute("number of registers").getNumber().intValue();
			DataType dataType = DataType.valueOf(vnode.getAttribute("data type").getString());
			int bit = vnode.getAttribute("bit").getNumber().intValue();
			double scaling = vnode.getAttribute("scaling").getNumber().doubleValue();
			double addscale = vnode.getAttribute("scaling offset").getNumber().doubleValue();
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
			// try {
			// root.master.init();
			// } catch (ModbusInitException e) {
			// // TODO Auto-generated catch block
			// LOGGER.debug("error: ", e);
			// }
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
				// System.out.println(response.getExceptionMessage());
			} catch (ModbusTransportException e) {
				// TODO Auto-generated catch block
				LOGGER.error("Modbus transport exception");
				LOGGER.debug("error: ", e);
				return;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				LOGGER.error("make arr exception");
				LOGGER.debug("error: ", e);
				return;
			} // finally {
				// root.master.destroy();
				// }

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
		return null;
	}

	protected static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling, double addscaling, PointType pt,
			int slaveid, int offset, int numRegisters, int bitnum) throws Exception {
		short[] retval = {};
		Integer dtint = getDataTypeInt(dt);
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

	protected static Integer getDataTypeInt(DataType dt) {
		switch (dt) {
		case BOOLEAN:
			return com.serotonin.modbus4j.code.DataType.BINARY;
		case INT16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
		case UINT16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED;
		case INT16SWAP:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED_SWAPPED;
		case UINT16SWAP:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED_SWAPPED;
		case BCD16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_BCD;
		case BCD32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD;
		case BCD32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD_SWAPPED;
		case INT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
		case UINT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED;
		case INT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED;
		case UINT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED;
		case INT32SWAPSWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED_SWAPPED;
		case UINT32SWAPSWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED_SWAPPED;
		case FLOAT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT;
		case FLOAT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT_SWAPPED;
		case INT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED;
		case UINT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED;
		case INT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED_SWAPPED;
		case UINT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED_SWAPPED;
		case FLOAT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT;
		case FLOAT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT_SWAPPED;
		case CHARSTRING:
			return com.serotonin.modbus4j.code.DataType.CHAR;
		case VARCHARSTRING:
			return com.serotonin.modbus4j.code.DataType.VARCHAR;
		default:
			return null;
		}
	}

	protected JsonArray parseResponse(ReadResponse response, DataType dataType, double scaling, double addscaling,
			PointType pointType, int slaveid, int offset, int bit) {
		short[] responseData = response.getShortData();
		byte[] byteData = response.getData();
		JsonArray retval = new JsonArray();
		Integer dt = getDataTypeInt(dataType);
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
