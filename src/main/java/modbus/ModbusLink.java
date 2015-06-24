package modbus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.dslink.util.Objects;
import org.vertx.java.core.Handler;

public class ModbusLink {
	
	Node node;
	Serializer copySerializer;
	Deserializer copyDeserializer;
	private final Map<Node, ScheduledFuture<?>> futures;
	
	private ModbusLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.copySerializer = ser;
		this.copyDeserializer = deser;
		this.futures = new ConcurrentHashMap<>();
	}
	
	public static void start(Node parent, Serializer copyser, Deserializer copydeser) {
		Node node = parent;
		final ModbusLink link = new ModbusLink(node, copyser, copydeser);
		link.init();
	}
	
	private void init() {
		
		restoreLastSession();
		
		Action act = new Action(Permission.READ, new AddDeviceHandler(null));
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("transport type", ValueType.makeEnum("TCP", "UDP")));
		act.addParameter(new Parameter("host", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(502)));
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, new Value(false)));
		node.createChild("add ip device").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new AddSerialHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("transport type", ValueType.makeEnum("RTU", "ASCII")));
		act.addParameter(new Parameter("comm port id", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("baud rate", ValueType.NUMBER, new Value(9600)));
		act.addParameter(new Parameter("data bits", ValueType.NUMBER, new Value(8)));
		act.addParameter(new Parameter("stop bits", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("parity", ValueType.makeEnum("NONE", "ODD", "EVEN", "MARK", "SPACE")));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("send requests all at once", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("set custom spacing", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("message frame spacing", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("character spacing", ValueType.NUMBER, new Value(0)));
		node.createChild("add serial connection").setAction(act).build().setSerializable(false);
		
		
	}
	
	private void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value restype = child.getAttribute("restoreType");
			if (restype == null) return;
			Value transType = child.getAttribute("transport type");
			Value timeout = child.getAttribute("Timeout");
			Value retries = child.getAttribute("retries");
			Value maxrbc = child.getAttribute("max read bit count");
			Value maxrrc = child.getAttribute("max read register count");
			Value maxwrc = child.getAttribute("max write register count");
			Value ddd = child.getAttribute("discard data delay");
			Value mwo = child.getAttribute("use multiple write commands only");
			if (restype.getString().equals("conn")) {
				Value commPortId = child.getAttribute("comm port id");
				Value baudRate = child.getAttribute("baud rate");
				Value dataBits = child.getAttribute("data bits");
				Value stopBits = child.getAttribute("stop bits");
				Value parity = child.getAttribute("parity");
				Value useMods = child.getAttribute("send requests all at once");
				Value useCustomSpacing = child.getAttribute("set custom spacing");
				Value msgSpacing = child.getAttribute("message frame spacing");
				Value charSpacing = child.getAttribute("character spacing");
				if (transType!=null && commPortId!=null && baudRate!=null 
						&& dataBits!=null && stopBits!=null && parity!=null && maxrbc!=null && maxrrc!=null 
						&& maxwrc!=null && ddd!=null && mwo!= null && timeout!=null && retries!=null 
						&& useMods!=null && useCustomSpacing!=null && msgSpacing!=null && charSpacing!=null) {
					SerialConn sc = new SerialConn(getMe(), child);
					sc.restoreLastSession();
				} else {
					node.removeChild(child);
				}
			} else if (restype.getString().equals("folder")) {
				Value host = child.getAttribute("host");
				Value port = child.getAttribute("port");
				Value slaveId = child.getAttribute("slave id");
				Value interval = child.getAttribute("polling interval");
				if (transType!=null && host!=null && port!=null && maxrbc!=null && 
						maxrrc!=null && maxwrc!=null && ddd!=null && mwo!= null && slaveId!=null 
						&& interval!=null && timeout!=null && retries!=null) {
					SlaveNode sn = new SlaveNode(getMe(), child, null);
					sn.restoreLastSession();
				} else {
					node.removeChild(child);
				}
			}
		}
	}
	
	
	private class AddSerialHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String commPortId = event.getParameter("comm port id", ValueType.STRING).getString();
			int baudRate = event.getParameter("baud rate", ValueType.NUMBER).getNumber().intValue();
			int dataBits = event.getParameter("data bits", ValueType.NUMBER).getNumber().intValue();
			int stopBits = event.getParameter("stop bits", ValueType.NUMBER).getNumber().intValue();
			String parityString = event.getParameter("parity").getString();
			boolean useMods = event.getParameter("send requests all at once", ValueType.BOOL).getBool();
			boolean useCustomSpacing = event.getParameter("set custom spacing", ValueType.BOOL).getBool();
			long msgSpacing = event.getParameter("message frame spacing", ValueType.NUMBER).getNumber().longValue();
			long charSpacing = event.getParameter("character spacing", ValueType.NUMBER).getNumber().longValue();
			String name = event.getParameter("name", ValueType.STRING).getString();
			String transtype = event.getParameter("transport type").getString();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
		
			Node snode = node.createChild(name).build();
			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("comm port id", new Value(commPortId));
			snode.setAttribute("baud rate", new Value(baudRate));
			snode.setAttribute("data bits", new Value(dataBits));
			snode.setAttribute("stop bits", new Value(stopBits));
			snode.setAttribute("parity", new Value(parityString));
			snode.setAttribute("Timeout", new Value(timeout));
			snode.setAttribute("retries", new Value(retries));
			snode.setAttribute("max read bit count", new Value(maxrbc));
			snode.setAttribute("max read register count", new Value(maxrrc));
			snode.setAttribute("max write register count", new Value(maxwrc));
			snode.setAttribute("discard data delay", new Value(ddd));
			snode.setAttribute("use multiple write commands only", new Value(mwo));
			snode.setAttribute("send requests all at once", new Value(useMods));
			snode.setAttribute("set custom spacing", new Value(useCustomSpacing));
			snode.setAttribute("message frame spacing", new Value(msgSpacing));
			snode.setAttribute("character spacing", new Value(charSpacing));
			
			SerialConn conn = new SerialConn(getMe(), snode);
			conn.init();
		}
	}
	
	class AddDeviceHandler implements Handler<ActionResult> {
		private boolean isSerial;
		private SerialConn conn;
		AddDeviceHandler(SerialConn conn) {
			this.conn = conn;
			this.isSerial = (conn!=null);
		}
		public void handle(ActionResult event) {
			String commPortId = "na"; String host = "na"; String parityString = "NONE";
			int baudRate = 0; int dataBits = 0; int stopBits = 0; int port = 0;
			long msgSpacing = 0; long charSpacing = 0;
			boolean useMods = false; boolean useCustomSpacing = false;
			int timeout, retries, maxrbc, maxrrc, maxwrc, ddd;
			boolean mwo;
			String transtype;
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node snode;
			if (isSerial) {
				commPortId = conn.node.getAttribute("comm port id").getString();
				baudRate = conn.node.getAttribute("baud rate").getNumber().intValue();
				dataBits = conn.node.getAttribute("data bits").getNumber().intValue();
				stopBits = conn.node.getAttribute("stop bits").getNumber().intValue();
				parityString = conn.node.getAttribute("parity").getString();
				useMods = conn.node.getAttribute("send requests all at once").getBool();
				useCustomSpacing = conn.node.getAttribute("set custom spacing").getBool();
				msgSpacing = conn.node.getAttribute("message frame spacing").getNumber().longValue();
				charSpacing = conn.node.getAttribute("character spacing").getNumber().longValue();
				transtype = conn.node.getAttribute("transport type").getString();
				timeout = conn.node.getAttribute("Timeout").getNumber().intValue();
				retries = conn.node.getAttribute("retries").getNumber().intValue();
				maxrbc = conn.node.getAttribute("max read bit count").getNumber().intValue();
				maxrrc = conn.node.getAttribute("max read register count").getNumber().intValue();
				maxwrc = conn.node.getAttribute("max write register count").getNumber().intValue();
				ddd = conn.node.getAttribute("discard data delay").getNumber().intValue();
				mwo = conn.node.getAttribute("use multiple write commands only").getBool();
				snode = conn.node.createChild(name).build();
			} else {
				transtype = event.getParameter("transport type").getString();
				host = event.getParameter("host", ValueType.STRING).getString();
				port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
				retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
				maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
				maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
				maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
				ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
				mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
				snode = node.createChild(name).build();
			}
			
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			long interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()*1000);
			
			
			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("host", new Value(host));
			snode.setAttribute("port", new Value(port));
			snode.setAttribute("comm port id", new Value(commPortId));
			snode.setAttribute("baud rate", new Value(baudRate));
			snode.setAttribute("data bits", new Value(dataBits));
			snode.setAttribute("stop bits", new Value(stopBits));
			snode.setAttribute("parity", new Value(parityString));
			snode.setAttribute("slave id", new Value(slaveid));
			snode.setAttribute("polling interval", new Value(interval));
			snode.setAttribute("Timeout", new Value(timeout));
			snode.setAttribute("retries", new Value(retries));
			snode.setAttribute("max read bit count", new Value(maxrbc));
			snode.setAttribute("max read register count", new Value(maxrrc));
			snode.setAttribute("max write register count", new Value(maxwrc));
			snode.setAttribute("discard data delay", new Value(ddd));
			snode.setAttribute("use multiple write commands only", new Value(mwo));
			snode.setAttribute("send requests all at once", new Value(useMods));
			snode.setAttribute("set custom spacing", new Value(useCustomSpacing));
			snode.setAttribute("message frame spacing", new Value(msgSpacing));
			snode.setAttribute("character spacing", new Value(charSpacing));
			
	        new SlaveNode(getMe(), snode, conn);
		}
	}
	
	 void setupPoint(Node child, final SlaveNode slave) {
	        child.getListener().setOnSubscribeHandler(new Handler<Node>() {
	            public void handle(final Node event) {
	                if (futures.containsKey(event)) {
	                    return;
	                }
	                ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
	                ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
	                    @Override
	                    public void run() {
	                    	if (event.getAttribute("offset") != null) slave.readPoint(event);
	                    }
	                }, 0, slave.interval, TimeUnit.MILLISECONDS);
	                futures.put(event, fut);
	            }
	        });

	        child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
	            @Override
	            public void handle(Node event) {
	                ScheduledFuture<?> fut = futures.remove(event);
	                if (fut != null) {
	                    fut.cancel(false);
	                }
	            }
	        });
	    }
	 private ModbusLink getMe() {
			return this;
		}
	 
	 static int parseParity(String parstr) {
		 int parint = 0;
		 switch(parstr.toUpperCase()) {
		 case "NONE": break;
		 case "ODD": parint=1;break;
		 case "EVEN": parint=2;break;
		 case "MARK": parint=3;break;
		 case "SPACE": parint=4;break;
		 default: break;
		 }
		 return parint;
	 }

}
