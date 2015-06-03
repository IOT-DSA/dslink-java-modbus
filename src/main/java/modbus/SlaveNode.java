package modbus;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import com.serotonin.io.serial.SerialParameters;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.serial.ModSerialParameters;

public class SlaveNode extends SlaveFolder {
	
	ModbusMaster master;
	long interval;
	boolean isSerial;
	
	SlaveNode(ModbusLink link, Node node, boolean isSerial) {
		super(link, node);
		
		this.isSerial = isSerial;
		this.root = this;
		this.master = getMaster();
		if (master == null) {
			node.clearChildren();
			node.getParent().removeChild(node);
			return;
		}
		this.interval = node.getAttribute("refresh interval").getNumber().longValue();
		
		makeEditAction();
	}
	
	enum TransportType {TCP, UDP, RTU, ASCII}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		if (!isSerial) {
			act.addParameter(new Parameter("transport type", ValueType.makeEnum("TCP", "UDP"), node.getAttribute("transport type")));
			act.addParameter(new Parameter("host", ValueType.STRING, node.getAttribute("host")));
			act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));
		} else {
			act.addParameter(new Parameter("transport type", ValueType.makeEnum("RTU", "ASCII"), node.getAttribute("transport type")));
			act.addParameter(new Parameter("comm port id", ValueType.STRING, node.getAttribute("comm port id")));
			act.addParameter(new Parameter("baud rate", ValueType.NUMBER, node.getAttribute("baud rate")));
			act.addParameter(new Parameter("data bits", ValueType.NUMBER, node.getAttribute("data bits")));
			act.addParameter(new Parameter("stop bits", ValueType.NUMBER, node.getAttribute("stop bits")));
			act.addParameter(new Parameter("parity",  ValueType.makeEnum("NONE", "ODD", "EVEN", "MARK", "SPACE"), node.getAttribute("parity")));
		}
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, node.getAttribute("slave id")));
		act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, node.getAttribute("refresh interval")));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, node.getAttribute("timeout")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, node.getAttribute("max read bit count")));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, node.getAttribute("max read register count")));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, node.getAttribute("max write register count")));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, node.getAttribute("discard data delay")));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, node.getAttribute("use multiple write commands only")));
		if (isSerial) {
			act.addParameter(new Parameter("send requests all at once", ValueType.BOOL, node.getAttribute("send requests all at once")));
			act.addParameter(new Parameter("set custom spacing", ValueType.BOOL, node.getAttribute("set custom spacing")));
			act.addParameter(new Parameter("message frame spacing", ValueType.NUMBER, node.getAttribute("message frame spacing")));
			act.addParameter(new Parameter("character spacing", ValueType.NUMBER, node.getAttribute("character spacing")));
		}
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
		int parity = ModbusLink.parseParity(node.getAttribute("parity").getString());
		int timeout = node.getAttribute("timeout").getNumber().intValue();
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
			SerialParameters params;
			if (useMods) params = new ModSerialParameters();
			else params = new SerialParameters();
			params.setCommPortId(commPortId);
			params.setBaudRate(baudRate);
			params.setDataBits(dataBits);
			params.setStopBits(stopBits);
			params.setParity(parity);
			if (useCustomSpacing) {
				master = new ModbusFactory().createRtuMaster(params, charSpacing, msgSpacing);
			} else {
				master = new ModbusFactory().createRtuMaster(params);
			}
			break;
			}
		case ASCII: {
			SerialParameters params;
			if (useMods) params = new ModSerialParameters();
			else params = new SerialParameters();
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
        
        return master;
	}
	
	@Override
	protected void remove() {
		super.remove();
		master.destroy();
		
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (master != null) {
				master.destroy();
			}
			TransportType transtype;
			try {
				transtype = TransportType.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				System.out.println("invalid transport type");
				e.printStackTrace();
				return;
			}
			String host;
			int port;
			if (!isSerial) {
				host = event.getParameter("host", ValueType.STRING).getString();
				port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				node.setAttribute("host", new Value(host));
				node.setAttribute("port", new Value(port));
			} else {
				String commPortId = event.getParameter("comm port id", ValueType.STRING).getString();
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
			}
			String name = event.getParameter("name", ValueType.STRING).getString();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
			
			if (!name.equals(node.getName())) {
				rename(name);
			}
			node.setAttribute("transport type", new Value(transtype.toString()));
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

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject nodeobj = jobj.getObject("MODBUS").getObject(node.getName());
		jobj.getObject("MODBUS").putObject(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		Value transType = newnode.getAttribute("transport type");
		String transString = transType.getString().toUpperCase();
		boolean ser = (transString.equals("RTU") || transString.equals("ASCII"));
		SlaveNode sn = new SlaveNode(link, newnode, ser);
		sn.restoreLastSession();
		return;
		
	}

}
