package modbus;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

public class SlaveNode extends SlaveFolder {
	private static final Logger LOGGER;
	
	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}
	
	ModbusMaster master;
	long interval;
	boolean isSerial;
	SerialConn conn;
	Node statnode;
	private final ScheduledThreadPoolExecutor stpe;
	
	SlaveNode(ModbusLink link, Node node, SerialConn conn) {
		super(link, node);
		
		this.conn = conn;
		this.isSerial = (conn!=null);
		if (isSerial) {
			conn.slaves.add(this);
			stpe = conn.getDaemonThreadPool();
		} else {
			stpe = Objects.createDaemonThreadPool();
		}
		this.root = this;
		this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING).setValue(new Value("Setting up device")).build();
		this.master = getMaster();
		stpe.execute(new Runnable() {
			@Override
			public void run() {
				checkConnection();
			}
		});
		
		this.interval = node.getAttribute("polling interval").getNumber().longValue();
		
		makeEditAction();
	}
	
	void checkConnection() {
		if (master != null) {
			boolean connected = false;
			try {
				connected = master.testSlaveNode(node.getAttribute("slave id") .getNumber().intValue());
			} catch (Exception e) {
				LOGGER.debug("error: ", e);
			}
			if (connected) statnode.setValue(new Value("Ready"));
			else statnode.setValue(new Value("Device ping failed"));
		}
	}
	
	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}
	
	enum TransportType {TCP, UDP, RTU, ASCII}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		if (!isSerial) {
			act.addParameter(new Parameter("transport type", ValueType.makeEnum("TCP", "UDP"), node.getAttribute("transport type")));
			act.addParameter(new Parameter("host", ValueType.STRING, node.getAttribute("host")));
			act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));
		}
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, node.getAttribute("slave id")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue()/1000;
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
		if (!isSerial) {
			act.addParameter(new Parameter("Timeout", ValueType.NUMBER, node.getAttribute("Timeout")));
			act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
			act.addParameter(new Parameter("max read bit count", ValueType.NUMBER, node.getAttribute("max read bit count")));
			act.addParameter(new Parameter("max read register count", ValueType.NUMBER, node.getAttribute("max read register count")));
			act.addParameter(new Parameter("max write register count", ValueType.NUMBER, node.getAttribute("max write register count")));
			act.addParameter(new Parameter("discard data delay", ValueType.NUMBER, node.getAttribute("discard data delay")));
			act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL, node.getAttribute("use multiple write commands only")));
		}
		Node anode = node.getChild("edit");
		if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	ModbusMaster getMaster() {
		if (isSerial) return conn.master;
		statnode.setValue(new Value("connecting to device"));
		TransportType transtype = null;
		try {
			transtype = TransportType.valueOf(node.getAttribute("transport type").getString().toUpperCase());
		} catch (Exception e1) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e1);
			statnode.setValue(new Value("invalid transport type"));
			return null;
		}
		String host = node.getAttribute("host").getString();
		int port = node.getAttribute("port").getNumber().intValue();
		int timeout = node.getAttribute("Timeout").getNumber().intValue();
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
			LOGGER.error("error initializing master");
			statnode.setValue(new Value("Could not establish connection - ModbusInitException"));
			return null;
		}
        
        return master;
	}
	
	@Override
	protected void remove() {
		super.remove();
		if (isSerial) {
			conn.slaves.remove(this);
			return;
		}
		try {
			master.destroy();
		} catch (Exception e) {
			LOGGER.debug("error destroying last master");
		}
		if (!isSerial) stpe.shutdown();
		
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (!isSerial) {
				if (master != null) {
					try {
						master.destroy();
					} catch (Exception e) {
						LOGGER.debug("error destroying last master");
					}
				}
				TransportType transtype;
				try {
					transtype = TransportType.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
				} catch (Exception e) {
					LOGGER.error("invalid transport type");
					LOGGER.debug("error: ", e);
					return;
				}
				String host = event.getParameter("host", ValueType.STRING).getString();
				int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				node.setAttribute("host", new Value(host));
				node.setAttribute("port", new Value(port));
				int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
				int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
				int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
				int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
				int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
				int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
				boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
				node.setAttribute("transport type", new Value(transtype.toString()));
				node.setAttribute("Timeout", new Value(timeout));
				node.setAttribute("retries", new Value(retries));
				node.setAttribute("max read bit count", new Value(maxrbc));
				node.setAttribute("max read register count", new Value(maxrrc));
				node.setAttribute("max write register count", new Value(maxwrc));
				node.setAttribute("discard data delay", new Value(ddd));
				node.setAttribute("use multiple write commands only", new Value(mwo));
			} 
			String name = event.getParameter("name", ValueType.STRING).getString();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()*1000);
			link.handleEdit(root);
			node.setAttribute("slave id", new Value(slaveid));
			node.setAttribute("polling interval", new Value(interval));
			
			if (!name.equals(node.getName())) {
				rename(name);
			}
			
			master = getMaster();
			checkConnection();
			
			makeEditAction();
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = isSerial ? jobj.getObject(conn.node.getName()) : jobj;
		JsonObject nodeobj = parentobj.getObject(node.getName());
		parentobj.putObject(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveNode sn = new SlaveNode(link, newnode, conn);
		sn.restoreLastSession();
		return;
		
	}

}
