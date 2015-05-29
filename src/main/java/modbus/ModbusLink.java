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
import org.dsa.iot.dslink.util.Objects;
import org.vertx.java.core.Handler;

public class ModbusLink {
	
	private Node node;
	private final Map<Node, ScheduledFuture<?>> futures;
	
	private ModbusLink(Node node) {
		this.node = node;
		this.futures = new ConcurrentHashMap<>();
	}
	
	public static void start(Node parent) {
		Node node = parent.createChild("MODBUS").build();
		final ModbusLink link = new ModbusLink(node);
		link.init();
	}
	
	private void init() {
		
		restoreLastSession();
		
		Action act = new Action(Permission.READ, new AddDeviceHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("transport type", ValueType.STRING));
		act.addParameter(new Parameter("(ip) host", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("(ip) port", ValueType.NUMBER, new Value(502)));
		act.addParameter(new Parameter("(serial) comm port id", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("(serial) baud rate", ValueType.NUMBER, new Value(9600)));
		act.addParameter(new Parameter("(serial) data bits", ValueType.NUMBER, new Value(8)));
		act.addParameter(new Parameter("(serial) stop bits", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("(serial) parity", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, new Value(500)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, new Value(2000)));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER, new Value(125)));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER, new Value(120)));
		act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, new Value(false)));
		node.createChild("add device").setAction(act).build().setSerializable(false);
		
	}
	
	private void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value transType = child.getAttribute("transport type");
			Value host = child.getAttribute("host");
			Value port = child.getAttribute("port");
			Value commPortId = child.getAttribute("comm port id");
			Value baudRate = child.getAttribute("baud rate");
			Value dataBits = child.getAttribute("data bits");
			Value stopBits = child.getAttribute("stop bits");
			Value parity = child.getAttribute("parity");
			Value slaveId = child.getAttribute("slave id");
			Value interval = child.getAttribute("refresh interval");
			Value timeout = child.getAttribute("timeout");
			Value retries = child.getAttribute("retries");
			Value maxrbc = child.getAttribute("max read bit count");
			Value maxrrc = child.getAttribute("max read register count");
			Value maxwrc = child.getAttribute("max write register count");
			Value ddd = child.getAttribute("discard data delay");
			Value mwo = child.getAttribute("use multiple write commands only");
			if (transType!=null && host!=null && port!=null && commPortId!=null && 
					baudRate!=null && dataBits!=null && stopBits!=null && parity!=null && 
					slaveId!=null && interval!=null && timeout!=null && retries!=null && 
					maxrbc!=null && maxrrc!=null && maxwrc!=null && ddd!=null && mwo!=null) {
				SlaveNode sn = new SlaveNode(getMe(), child);
				sn.restoreLastSession();
			} else {
				node.removeChild(child);
			}
		}
	}
	
	
	private class AddDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String transtype = event.getParameter("transport type", ValueType.STRING).getString();
			String host = event.getParameter("(ip) host", ValueType.STRING).getString();
			int port = event.getParameter("(ip) port", ValueType.NUMBER).getNumber().intValue();
			String commPortId = event.getParameter("(serial) comm port id", ValueType.STRING).getString();
			int baudRate = event.getParameter("(serial) baud rate", ValueType.NUMBER).getNumber().intValue();
			int dataBits = event.getParameter("(serial) data bits", ValueType.NUMBER).getNumber().intValue();
			int stopBits = event.getParameter("(serial) stop bits", ValueType.NUMBER).getNumber().intValue();
			int parity = event.getParameter("(serial) parity", ValueType.NUMBER).getNumber().intValue();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			long interval = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
			
			Node snode = node.createChild(name).build();
			snode.setAttribute("transport type", new Value(transtype));
			snode.setAttribute("host", new Value(host));
			snode.setAttribute("port", new Value(port));
			snode.setAttribute("comm port id", new Value(commPortId));
			snode.setAttribute("baud rate", new Value(baudRate));
			snode.setAttribute("data bits", new Value(dataBits));
			snode.setAttribute("stop bits", new Value(stopBits));
			snode.setAttribute("parity", new Value(parity));
			snode.setAttribute("slave id", new Value(slaveid));
			snode.setAttribute("refresh interval", new Value(interval));
			snode.setAttribute("timeout", new Value(timeout));
			snode.setAttribute("retries", new Value(retries));
			snode.setAttribute("max read bit count", new Value(maxrbc));
			snode.setAttribute("max read register count", new Value(maxrrc));
			snode.setAttribute("max write register count", new Value(maxwrc));
			snode.setAttribute("discard data delay", new Value(ddd));
			snode.setAttribute("use multiple write commands only", new Value(mwo));
			
	        new SlaveNode(getMe(), snode);
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
	                }, 0, slave.interval, TimeUnit.SECONDS);
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

}
