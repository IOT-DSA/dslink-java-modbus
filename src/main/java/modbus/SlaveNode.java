package modbus;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;
import com.serotonin.io.serial.SerialParameters;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

public class SlaveNode extends SlaveFolder {
	
	ModbusMaster master;
	long interval;
	
	SlaveNode(ModbusLink link, Node node) {
		super(link, node);
		
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
	
	protected enum TransportType {TCP, UDP, RTU, ASCII}
	
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

}
