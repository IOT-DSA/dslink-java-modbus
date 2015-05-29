package modbus;

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

import com.serotonin.io.serial.SerialParameters;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;

public class SlaveNode {
	
	private ModbusLink link;
	private Node node;
	private ModbusMaster master;
	long interval;
	
	SlaveNode(ModbusLink link, Node node) {
		this.link = link;
		this.node = node;
		this.master = getMaster();
		if (master == null) {
			node.clearChildren();
			node.getParent().removeChild(node);
			return;
		}
		this.interval = node.getAttribute("refresh interval").getNumber().longValue();
		
		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("type", ValueType.STRING));
		act.addParameter(new Parameter("offset", ValueType.NUMBER));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("data type", ValueType.STRING));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("writable", ValueType.BOOL, new Value(false)));
		node.createChild("add point").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);
		
		makeEditAction();
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("transport type", ValueType.STRING, node.getAttribute("transport type")));
		act.addParameter(new Parameter("(ip) host", ValueType.STRING, node.getAttribute("host")));
		act.addParameter(new Parameter("(ip) port", ValueType.NUMBER, node.getAttribute("port")));
		act.addParameter(new Parameter("(serial) comm port id", ValueType.STRING, node.getAttribute("comm port id")));
		act.addParameter(new Parameter("(serial) baud rate", ValueType.NUMBER, node.getAttribute("baud rate")));
		act.addParameter(new Parameter("(serial) data bits", ValueType.NUMBER, node.getAttribute("data bits")));
		act.addParameter(new Parameter("(serial) stop bits", ValueType.NUMBER, node.getAttribute("stop bits")));
		act.addParameter(new Parameter("(serial) parity", ValueType.NUMBER, node.getAttribute("parity")));
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, node.getAttribute("slave id")));
		act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, node.getAttribute("refresh interval")));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, node.getAttribute("timeout")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, node.getAttribute("max read bit count")));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, node.getAttribute("max read register count")));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, node.getAttribute("max write register count")));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, node.getAttribute("discard data delay")));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, node.getAttribute("use multiple write commands only")));
		node.createChild("edit").setAction(act).build().setSerializable(false);
	}
	
	ModbusMaster getMaster() {
		TransportType transtype = null;
		try {
			transtype = TransportType.valueOf(node.getAttribute("transport type").getString().toUpperCase());
		} catch (Exception e1) {
			System.out.println("invalid transport type");
			e1.printStackTrace();
			return null;
		}
		String host = node.getAttribute("host").getString();
		int port = node.getAttribute("port").getNumber().intValue();
		String commPortId = node.getAttribute("comm port id").getString();
		int baudRate = node.getAttribute("baud rate").getNumber().intValue();
		int dataBits = node.getAttribute("data bits").getNumber().intValue();
		int stopBits = node.getAttribute("stop bits").getNumber().intValue();
		int parity = node.getAttribute("parity").getNumber().intValue();
		int timeout = node.getAttribute("timeout").getNumber().intValue();
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
	        master = new ModbusFactory().createTcpMaster(params, false);
	        break;
			}
		case UDP: {
			IpParameters params = new IpParameters();
			params.setHost(host);
	        params.setPort(port);
	        master = new ModbusFactory().createUdpMaster(params);
	        break;
			}
		case RTU: {
			SerialParameters params = new SerialParameters();
			params.setCommPortId(commPortId);
			params.setBaudRate(baudRate);
			params.setDataBits(dataBits);
			params.setStopBits(stopBits);
			params.setParity(parity);
			master = new ModbusFactory().createRtuMaster(params);
			break;
			}
		case ASCII: {
			SerialParameters params = new SerialParameters();
			params.setCommPortId(commPortId);
			params.setBaudRate(baudRate);
			params.setDataBits(dataBits);
			params.setStopBits(stopBits);
			params.setParity(parity);
			master = new ModbusFactory().createAsciiMaster(params);
			break;
		}
		default: return null;
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
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        return master;
	}
	
	private enum TransportType {TCP, UDP, RTU, ASCII}
	private enum PointType {COIL, DISCRETE, HOLDING, INPUT}
	private enum DataType {INT32, INT16, UINT32, UINT16, INT32M10K, UINT32M10K, BOOLEAN }
	
	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			node.clearChildren();
			node.getParent().removeChild(node);
		}
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			TransportType transtype;
			try {
				transtype = TransportType.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				System.out.println("invalid transport type");
				e.printStackTrace();
				return;
			}
			String host = event.getParameter("(ip) host", ValueType.STRING).getString();
			int port = event.getParameter("(ip) port", ValueType.NUMBER).getNumber().intValue();
			String commPortId = event.getParameter("(serial) comm port id", ValueType.STRING).getString();
			int baudRate = event.getParameter("(serial) baud rate", ValueType.NUMBER).getNumber().intValue();
			int dataBits = event.getParameter("(serial) data bits", ValueType.NUMBER).getNumber().intValue();
			int stopBits = event.getParameter("(serial) stop bits", ValueType.NUMBER).getNumber().intValue();
			int parity = event.getParameter("(serial) parity", ValueType.NUMBER).getNumber().intValue();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
			
			node.setAttribute("transport type", new Value(transtype.toString()));
			node.setAttribute("host", new Value(host));
			node.setAttribute("port", new Value(port));
			node.setAttribute("comm port id", new Value(commPortId));
			node.setAttribute("baud rate", new Value(baudRate));
			node.setAttribute("data bits", new Value(dataBits));
			node.setAttribute("stop bits", new Value(stopBits));
			node.setAttribute("parity", new Value(parity));
			node.setAttribute("slave id", new Value(slaveid));
			node.setAttribute("refresh interval", new Value(interval));
			node.setAttribute("timeout", new Value(timeout));
			node.setAttribute("retries", new Value(retries));
			node.setAttribute("max read bit count", new Value(maxrbc));
			node.setAttribute("max read register count", new Value(maxrrc));
			node.setAttribute("max write register count", new Value(maxwrc));
			node.setAttribute("discard data delay", new Value(ddd));
			node.setAttribute("use multiple write commands only", new Value(mwo));
			
			master = getMaster();
			
			node.removeChild("edit");
			makeEditAction();
		}
	}
	
	private class AddPointHandler implements Handler<ActionResult> {
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
			System.out.println(scaling);
			Node pnode = node.createChild(name).setValueType(ValueType.STRING).build();
			pnode.setAttribute("type", new Value(type.toString()));
			pnode.setAttribute("offset", new Value(offset));
			pnode.setAttribute("number of registers", new Value(numRegs));
			pnode.setAttribute("data type", new Value(dataType.toString()));
			pnode.setAttribute("scaling", new Value(scaling));
			pnode.setAttribute("writable", new Value(writable));
			setupPointActions(pnode);
			link.setupPoint(pnode, getMe());
		}
	}
	
	void setupPointActions(Node pointNode) {
		Action act = new Action(Permission.READ, new RemovePointHandler(pointNode));
		pointNode.createChild("remove").setAction(act).build().setSerializable(false);
		boolean writable = pointNode.getAttribute("writable").getBool();
		if (writable) {
			act = new Action(Permission.READ, new SetHandler(pointNode));
			act.addParameter(new Parameter("value", ValueType.STRING));
			pointNode.createChild("set").setAction(act).build().setSerializable(false);
		}
	}
	
	private class RemovePointHandler implements Handler<ActionResult> {
		private Node toRemove;
		RemovePointHandler(Node pnode){
			toRemove = pnode;
		}
		public void handle(ActionResult event) {
			node.removeChild(toRemove);
		}
	}
	
	void readPoint(Node pointNode) {
		PointType type = PointType.valueOf(pointNode.getAttribute("type").getString());
		int offset = pointNode.getAttribute("offset").getNumber().intValue();
		int numRegs = pointNode.getAttribute("number of registers").getNumber().intValue();
		int id = node.getAttribute("slave id").getNumber().intValue();
		double scaling = pointNode.getAttribute("scaling").getNumber().doubleValue();
		DataType dataType = DataType.valueOf(pointNode.getAttribute("data type").getString());
		ModbusRequest request=null;
		JsonArray val = new JsonArray();
		try {
			switch (type) {
			case COIL: request = new ReadCoilsRequest(id, offset, numRegs);break;
			case DISCRETE: request = new ReadDiscreteInputsRequest(id, offset, numRegs);break;
			case HOLDING: request = new ReadHoldingRegistersRequest(id, offset, numRegs);break;
			case INPUT: request = new ReadInputRegistersRequest(id, offset, numRegs);break;
			}
			ReadResponse response = (ReadResponse) master.send(request);
			if (type == PointType.COIL || type == PointType.DISCRETE) {
				System.out.println(Arrays.toString(response.getBooleanData()));
				for (boolean b: response.getBooleanData()) {
					val.addBoolean(b);
				}
			} else {
				System.out.println(Arrays.toString(response.getShortData()));
				val = parseResponse(response.getShortData(), dataType, scaling);
			}
		} catch (ModbusTransportException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		pointNode.setValue(new Value(val.toString()));
	}
	
	private class SetHandler implements Handler<ActionResult> {
		private Node vnode;
		SetHandler(Node vnode) {
			this.vnode = vnode;
		}
		public void handle(ActionResult event) {
			PointType type = PointType.valueOf(vnode.getAttribute("type").getString());
			int offset = vnode.getAttribute("offset").getNumber().intValue();
			int id = node.getAttribute("slave id").getNumber().intValue();
			DataType dataType = DataType.valueOf(vnode.getAttribute("data type").getString());
			double scaling = vnode.getAttribute("scaling").getNumber().doubleValue();
			int numThings = new JsonArray(vnode.getValue().getString()).size();
			String valstr = event.getParameter("value", ValueType.STRING).getString();
			if (!valstr.startsWith("[")) valstr = "["+valstr+"]";
			JsonArray valarr = new JsonArray(valstr);
			if (valarr.size() != numThings) {
				System.out.println("wrong number of values");
				return;
			}
			ModbusRequest request = null;
			try {
				switch (type) {
				case COIL: request = new WriteCoilsRequest(id, offset, makeBoolArr(valarr));break;
				case HOLDING: request = new WriteRegistersRequest(id, offset, makeShortArr(valarr, dataType, scaling));break;
				default:break;
				}
				ModbusResponse response = master.send(request);
				System.out.println(response.getExceptionMessage());
			} catch (ModbusTransportException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return;
			}
			
		}
	}
	
	private static boolean[] makeBoolArr(JsonArray jarr) throws Exception {
		boolean[] retval = new boolean[jarr.size()];
		for (int i=0;i<jarr.size();i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Boolean)) throw new Exception("not a boolean array");
			else retval[i] = (boolean) o;
		}
		return retval;
	}
	
	private static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling) throws Exception {
		short[] retval = null;
		if (dt == DataType.BOOLEAN) {
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
		}
		if (dt == DataType.INT16 || dt == DataType.UINT16) retval = new short[jarr.size()];
		else retval = new short[2*jarr.size()];
		for (int i=0;i<jarr.size();i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Number)) throw new Exception("not an int array");
			Number n = ((Number) o).doubleValue()*scaling;
			switch (dt) {
			case INT16:
			case UINT16: retval[i] =n.shortValue(); break;
			case INT32:
			case UINT32: { 
				long aslong = n.longValue();
				retval[i/2] = (short) (aslong/65536);
				retval[(i/2)+1] = (short) (aslong%65536); break;
			}
			case INT32M10K:
			case UINT32M10K: { 
				long aslong = n.longValue();
				retval[i/2] = (short) (aslong/10000);
				retval[(i/2)+1] = (short) (aslong%10000); break;
			}
			default: break;
			}
			
		}
		return retval;
	}
	
	private JsonArray parseResponse(short[] responseData, DataType dataType, double scaling) {
		JsonArray retval = new JsonArray();
		int last = 0;
		int regnum = 0;
		for (short s: responseData) {
			switch (dataType) {
			case INT16: retval.addNumber(s/scaling);break;
			case UINT16: retval.addNumber(Short.toUnsignedInt(s)/scaling);break;
			case INT32: if (regnum == 0) {
					regnum += 1;
					last = s;
				} else {
					regnum = 0;
					int num = last*65536 + Short.toUnsignedInt(s);
					retval.addNumber(num/scaling);
				}
				break;
			case UINT32:  if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(last*65536 + Short.toUnsignedInt(s));
					retval.addNumber(num/scaling);
				}
				break;
			case INT32M10K: if (regnum == 0) {
					regnum += 1;
					last = s;
				} else {
					regnum = 0;
					int num = last*10000 + s;
					retval.addNumber(num/scaling);
				}
				break;
			case UINT32M10K: if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(last*10000 + Short.toUnsignedInt(s));
					retval.addNumber(num/scaling);
				}
				break;
			case BOOLEAN: for (int i=0; i<16; i++) {
					retval.addBoolean(((s >> i) & 1) != 0);
				}
				break;
			default: retval.addString("oops");break;
			}
		}
		return retval;
	}
	
	private SlaveNode getMe() {
		return this;
	}
	

}
