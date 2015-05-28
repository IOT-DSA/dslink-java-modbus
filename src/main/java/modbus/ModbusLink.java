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

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

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
		
		Action act = new Action(Permission.READ, new AddDeviceHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("host", ValueType.STRING));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(502)));
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
	
	private class AddDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String host = event.getParameter("host", ValueType.STRING).getString();
			int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
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
			snode.setSerializable(false);
			
			IpParameters params = new IpParameters();
			params.setHost(host);
	        params.setPort(port);
	        
	        ModbusMaster master = new ModbusFactory().createTcpMaster(params, false);
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
	        new SlaveNode(getMe(), snode, slaveid, master, interval);
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
