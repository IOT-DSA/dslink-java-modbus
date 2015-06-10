package modbus;

import java.math.BigDecimal;
import java.util.Arrays;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import com.serotonin.modbus4j.code.RegisterRange;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.NumericLocator;
import com.serotonin.modbus4j.locator.StringLocator;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;

public class SlaveFolder {
	protected ModbusLink link;
	protected Node node;
	protected SlaveNode root;
	
	SlaveFolder(ModbusLink link, Node node) {
		this.link = link;
		this.node = node;
		node.setAttribute("restoreType", new Value("folder"));
		
		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("data type", ValueType.makeEnum("BOOLEAN", "INT16", "UINT16", "INT16SWAP", "UINT16SWAP", "INT32", "UINT32", "INT32SWAP", "UINT32SWAP", "INT32SWAPSWAP", "UINT32SWAPSWAP", "FLOAT32", "FLOAT32SWAP", "INT64", "UINT64", "INT64SWAP", "UINT64SWAP", "FLOAT64", "FLOAT64SWAP", "BCD16", "BCD32", "BCD32SWAP", "CHARSTRING", "VARCHARSTRING", "INT32M10K", "UINT32M10K")));
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
	
	SlaveFolder(ModbusLink link, Node node, SlaveNode root) {
		this(link, node);
		this.root = root;
		
	}
	
	void restoreLastSession() {
		if (node.getChildren() == null) return;
		for  (Node child: node.getChildren().values()) {
			Value restoreType = child.getAttribute("restoreType");
			if (restoreType != null) {
				if (restoreType.getString().equals("folder")) {
					SlaveFolder sf = new SlaveFolder(link, child, root);
					sf.restoreLastSession();
				} else if (restoreType.getString().equals("point")) {
					setupPointActions(child);
					link.setupPoint(child, root);
				}
			} else if (child.getAction() == null) {
				node.removeChild(child);
			}
		}
	}
	
	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName())) duplicate(newname);
		}
	}
	
	protected class RenameHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName())) rename(newname);
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
		JsonObject nodeobj = parentobj.getObject(node.getName());
		parentobj.putObject(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveFolder sf = new SlaveFolder(link, newnode, root);
		sf.restoreLastSession();
	}

	protected JsonObject getParentJson(JsonObject jobj) {
		return getParentJson(jobj, node);
	}

	private JsonObject getParentJson(JsonObject jobj, Node n) {
		if (n == root.node) return jobj.getObject("MODBUS");
		else return getParentJson(jobj, n.getParent()).getObject(n.getParent().getName());
	}

	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			new SlaveFolder(link, child, root);
		}
	}
	
	protected enum PointType {COIL, DISCRETE, HOLDING, INPUT}
	protected enum DataType {BOOLEAN, INT16, UINT16, INT16SWAP, UINT16SWAP, 
		INT32, UINT32, INT32SWAP, UINT32SWAP, INT32SWAPSWAP, UINT32SWAPSWAP, 
		FLOAT32, FLOAT32SWAP, INT64, UINT64, INT64SWAP, UINT64SWAP, 
		FLOAT64, FLOAT64SWAP, BCD16, BCD32, BCD32SWAP, CHARSTRING,
		VARCHARSTRING, INT32M10K, UINT32M10K;

		public boolean isFloat() {
			return (this == FLOAT32 || this == FLOAT32SWAP || this == FLOAT64 || this == FLOAT64SWAP);
		}

		public boolean isString() {
			return (this == CHARSTRING || this == VARCHARSTRING);
		}}
	
	protected class AddPointHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType.valueOf(event.getParameter("type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				System.out.println("invalid type");
				e.printStackTrace();
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING) && event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE) dataType = DataType.BOOLEAN;
			else try {
				dataType = DataType.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				System.out.println("invalid data type");
				e1.printStackTrace();
				return;
			}
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter("scaling offset", ValueType.NUMBER).getNumber().doubleValue();
			Node pnode = node.createChild(name).setValueType(ValueType.STRING).build();
			pnode.setAttribute("type", new Value(type.toString()));
			pnode.setAttribute("offset", new Value(offset));
			pnode.setAttribute("number of registers", new Value(numRegs));
			pnode.setAttribute("data type", new Value(dataType.toString()));
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
		pointNode.createChild("remove").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new EditPointHandler(pointNode));
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT"), pointNode.getAttribute("type")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER, pointNode.getAttribute("offset")));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, pointNode.getAttribute("number of registers")));
		act.addParameter(new Parameter("data type", ValueType.makeEnum("BOOLEAN", "INT16", "UINT16", "INT16SWAP", "UINT16SWAP", "INT32", "UINT32", "INT32SWAP", "UINT32SWAP", "INT32SWAPSWAP", "UINT32SWAPSWAP", "FLOAT32", "FLOAT32SWAP", "INT64", "UINT64", "INT64SWAP", "UINT64SWAP", "FLOAT64", "FLOAT64SWAP", "BCD16", "BCD32", "BCD32SWAP", "CHARSTRING", "VARCHARSTRING", "INT32M10K", "UINT32M10K"), pointNode.getAttribute("data type")));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, pointNode.getAttribute("scaling")));
		act.addParameter(new Parameter("scaling offset", ValueType.NUMBER, pointNode.getAttribute("scaling offset")));
		act.addParameter(new Parameter("writable", ValueType.BOOL, pointNode.getAttribute("writable")));
		pointNode.createChild("edit").setAction(act).build().setSerializable(false);
		
		boolean writable = pointNode.getAttribute("writable").getBool();
		if (writable) {
			act = new Action(Permission.READ, new SetHandler(pointNode));
			act.addParameter(new Parameter("value", ValueType.STRING));
			pointNode.createChild("set").setAction(act).build().setSerializable(false);
		}
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
				System.out.println("invalid type");
				e.printStackTrace();
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING) && event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE) dataType = DataType.BOOLEAN;
			else try {
				dataType = DataType.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				System.out.println("invalid data type");
				e1.printStackTrace();
				return;
			}
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			double addscale = event.getParameter("scaling offset", ValueType.NUMBER).getNumber().doubleValue();
			
			if (!name.equals(pointNode.getName())) {
				Node newnode = node.createChild(name).build();
				node.removeChild(pointNode);
				pointNode = newnode;
			}
			pointNode.setAttribute("type", new Value(type.toString()));
			pointNode.setAttribute("offset", new Value(offset));
			pointNode.setAttribute("number of registers", new Value(numRegs));
			pointNode.setAttribute("data type", new Value(dataType.toString()));
			pointNode.setAttribute("scaling", new Value(scaling));
			pointNode.setAttribute("scaling offset", new Value(addscale));
			pointNode.setAttribute("writable", new Value(writable));
			pointNode.clearChildren();
			setupPointActions(pointNode);
			link.setupPoint(pointNode, root);
			pointNode.setAttribute("restoreType", new Value("point"));
		}
	}
	
	protected class RemovePointHandler implements Handler<ActionResult> {
		private Node toRemove;
		RemovePointHandler(Node pnode){
			toRemove = pnode;
		}
		public void handle(ActionResult event) {
			node.removeChild(toRemove);
		}
	}
	
	protected void readPoint(Node pointNode) {
		PointType type = PointType.valueOf(pointNode.getAttribute("type").getString());
		int offset = pointNode.getAttribute("offset").getNumber().intValue();
		int numRegs = pointNode.getAttribute("number of registers").getNumber().intValue();
		int id = root.node.getAttribute("slave id").getNumber().intValue();
		double scaling = pointNode.getAttribute("scaling").getNumber().doubleValue();
		double addscale = pointNode.getAttribute("scaling offset").getNumber().doubleValue();
		DataType dataType = DataType.valueOf(pointNode.getAttribute("data type").getString());
		ModbusRequest request=null;
		JsonArray val = new JsonArray();
		try {
			root.master.init();
		} catch (ModbusInitException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			switch (type) {
			case COIL: request = new ReadCoilsRequest(id, offset, numRegs);break;
			case DISCRETE: request = new ReadDiscreteInputsRequest(id, offset, numRegs);break;
			case HOLDING: request = new ReadHoldingRegistersRequest(id, offset, numRegs);break;
			case INPUT: request = new ReadInputRegistersRequest(id, offset, numRegs);break;
			}
			ReadResponse response = (ReadResponse) root.master.send(request);
			if (response.getExceptionCode()!=-1) {
				//System.out.println("errorresponse "+response.getExceptionMessage());
				return;
			}
			if (type == PointType.COIL || type == PointType.DISCRETE) {
				boolean[] booldat = response.getBooleanData();
				System.out.println(Arrays.toString(booldat));
				for (int j=0;j<numRegs;j++) {
					boolean b = booldat[j];
					val.addBoolean(b);
				}
			} else {
				System.out.println(Arrays.toString(response.getShortData()));
				val = parseResponse(response, dataType, scaling, addscale, type, id, offset);
			}
		} catch (ModbusTransportException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			root.master.destroy();
		}
		String valString = val.toString();
		if (val.size() == 1) valString = val.get(0).toString();
		pointNode.setValue(new Value(valString));
	}
	
	protected class SetHandler implements Handler<ActionResult> {
		private Node vnode;
		SetHandler(Node vnode) {
			this.vnode = vnode;
		}
		public void handle(ActionResult event) {
			PointType type = PointType.valueOf(vnode.getAttribute("type").getString());
			int offset = vnode.getAttribute("offset").getNumber().intValue();
			int id = root.node.getAttribute("slave id").getNumber().intValue();
			int numRegs = vnode.getAttribute("number of registers").getNumber().intValue();
			DataType dataType = DataType.valueOf(vnode.getAttribute("data type").getString());
			double scaling = vnode.getAttribute("scaling").getNumber().doubleValue();
			double addscale = vnode.getAttribute("scaling offset").getNumber().doubleValue();
			String oldstr = vnode.getValue().getString();
			if (!oldstr.startsWith("[")) oldstr = "["+oldstr+"]";
			int numThings = new JsonArray(oldstr).size();
			String valstr = event.getParameter("value", ValueType.STRING).getString();
			if (!valstr.startsWith("[")) valstr = "["+valstr+"]";
			JsonArray valarr = new JsonArray(valstr);
			if (valarr.size() != numThings) {
				System.out.println("wrong number of values");
				return;
			}
			try {
				root.master.init();
			} catch (ModbusInitException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			ModbusRequest request = null;
			try {
				switch (type) {
				case COIL: request = new WriteCoilsRequest(id, offset, makeBoolArr(valarr));break;
				case HOLDING: request = new WriteRegistersRequest(id, offset, makeShortArr(valarr, dataType, scaling, addscale, type, id, offset, numRegs));break;
				default:break;
				}
				if (request!=null) System.out.println("set request: " + request.toString());
				ModbusResponse response = root.master.send(request);
				//System.out.println(response.getExceptionMessage());
			} catch (ModbusTransportException e) {
				// TODO Auto-generated catch block
				System.out.println("Modbus transpot exception");
				e.printStackTrace();
				return;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				System.out.println("make arr exception");
				e.printStackTrace();
				return;
			} finally {
				root.master.destroy();
			}
			
		}
	}
	
	protected static boolean[] makeBoolArr(JsonArray jarr) throws Exception {
		boolean[] retval = new boolean[jarr.size()];
		for (int i=0;i<jarr.size();i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Boolean)) throw new Exception("not a boolean array");
			else retval[i] = (boolean) o;
		}
		return retval;
	}
	
	protected static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling, double addscaling, PointType pt, int slaveid, int offset, int numRegisters) throws Exception {
		short[] retval = {};
		Integer dtint = getDataTypeInt(dt);
		if (dtint != null) {
			if (!dt.isString()) {
				NumericLocator nloc = new NumericLocator(slaveid, getPointTypeInt(pt), offset, dtint);
				for (int i=0;i<jarr.size();i++) {
					Object o = jarr.get(i);
					if (!(o instanceof Number)) throw new Exception("not a numeric array");
					Number n = ((Number) o).doubleValue()*scaling - addscaling;
					retval = concatArrays(retval, nloc.valueToShorts(n));
				}
			} else {
				Object o = jarr.get(0);
				if (!(o instanceof String)) throw new Exception("not a string");
				String str = (String) o;
				StringLocator sloc = new StringLocator(slaveid, getPointTypeInt(pt), offset, dtint, numRegisters);
				retval = sloc.valueToShorts(str);
			}
		} else if (dt == DataType.BOOLEAN) {
			retval = new short[(int)Math.ceil((double)jarr.size()/16)];
			for (int i=0;i<retval.length;i++) {
				short element = 0;
				for (int j=0;j<16;j++) {
					int bit = 0;
					if (i+j < jarr.size()) {
						Object o = jarr.get(i+j);
						if (!(o instanceof Boolean)) throw new Exception("not a boolean array");
						if ((boolean) o ) bit = 1;
					}
					element = (short) (element & (bit << (15 - j)));
					jarr.get(i+j);
				}
				retval[i] = element;
			}
			return retval;
		} else if (dt == DataType.INT32M10K || dt == DataType.UINT32M10K) {
			retval = new short[2*jarr.size()];
			for (int i=0;i<jarr.size();i++) {
				Object o = jarr.get(i);
				if (!(o instanceof Number)) throw new Exception("not an int array");
				Number n = ((Number) o).doubleValue()*scaling - addscaling; 
				long aslong = n.longValue();
				retval[i*2] = (short) (aslong/10000);
				retval[(i*2)+1] = (short) (aslong%10000); break;
			}
		}
		return retval;
	}
	
	private static short[] concatArrays(short[] arr1, short[] arr2) {
		int len1 = arr1.length; 
		int len2 = arr2.length;
		short[] retval = new short[len1+len2];
		System.arraycopy(arr1, 0, retval, 0, len1);
		System.arraycopy(arr2, 0, retval, len1, len2);
		return retval;
	}

	private static Integer getDataTypeInt(DataType dt) {
		switch(dt) {
		case INT16: return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
		case UINT16: return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED;
		case INT16SWAP: return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED_SWAPPED;
		case UINT16SWAP: return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED_SWAPPED;
		case BCD16: return com.serotonin.modbus4j.code.DataType.TWO_BYTE_BCD;
		case BCD32: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD;
		case BCD32SWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD_SWAPPED;
		case INT32: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
		case UINT32: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED;
		case INT32SWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED;
		case UINT32SWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED;
		case INT32SWAPSWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED_SWAPPED;
		case UINT32SWAPSWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED_SWAPPED;
		case FLOAT32: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT;
		case FLOAT32SWAP: return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT_SWAPPED;
		case INT64: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED;
		case UINT64: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED;
		case INT64SWAP: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED_SWAPPED;
		case UINT64SWAP: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED_SWAPPED;
		case FLOAT64: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT;
		case FLOAT64SWAP: return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT_SWAPPED;
		case CHARSTRING: return com.serotonin.modbus4j.code.DataType.CHAR;
		case VARCHARSTRING: return com.serotonin.modbus4j.code.DataType.VARCHAR;
		default: return null;
		}
	}
	
	protected JsonArray parseResponse(ReadResponse response, DataType dataType, double scaling, double addscaling, PointType pointType, int slaveid, int offset) {
		short[] responseData = response.getShortData();
		JsonArray retval = new JsonArray();
		Integer dt = getDataTypeInt(dataType);
		if (dt != null) {
			byte[] byteData = response.getData();
			if (!dataType.isString()) {
				NumericLocator nloc = new NumericLocator(slaveid, getPointTypeInt(pointType), offset, dt);
				int regsPerVal = nloc.getRegisterCount();
				for (int i=0;i<responseData.length;i+=regsPerVal) {
					Number num = nloc.bytesToValueRealOffset(byteData, i);
					if (dataType.isFloat()) retval.addNumber(new BigDecimal(num.doubleValue()/scaling + addscaling));
					else retval.addNumber((new BigDecimal(num.doubleValue()/scaling + addscaling)).toBigInteger());
				}
			} else {
				StringLocator sloc = new StringLocator(slaveid, getPointTypeInt(pointType), offset, dt, responseData.length);
				retval.addString(sloc.bytesToValueRealOffset(byteData, 0));
			}
		} else {
			int last = 0;
			int regnum = 0;
			switch (dataType) {
			case BOOLEAN: for (short s: responseData) {
				for (int i=0; i<16; i++) {
					retval.addBoolean(((s >> i) & 1) != 0);
				}
				}
				break;
			
			case INT32M10K: for (short s: responseData) {
				if (regnum == 0) {
					regnum += 1;
					last = s;
				} else {
					regnum = 0;
					int num = last*10000 + s;
					retval.addNumber(num/scaling + addscaling);
				}
				}
				break;
			case UINT32M10K: for (short s: responseData) {
				if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(last*10000 + Short.toUnsignedInt(s));
					retval.addNumber(num/scaling + addscaling);
				}
				}
				break; 
			default: break;
			}
		}
		return retval;
	}
	
	private static int getPointTypeInt(PointType pt) {
		switch (pt) {
		case COIL: return RegisterRange.COIL_STATUS;
		case DISCRETE: return RegisterRange.INPUT_STATUS;
		case HOLDING: return RegisterRange.HOLDING_REGISTER;
		case INPUT: return RegisterRange.INPUT_REGISTER;
		default: return 0;
		}
	}
	
//	private int getDigitFromBcd(int bcd, int position) {
//		return (bcd >>> (position*4)) & 15;
//	}
	

}
