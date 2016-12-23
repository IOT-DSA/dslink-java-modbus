package modbus;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.modbus4j.ModbusMaster;

public class SlaveNodeWithConnection extends SlaveNode {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	ModbusLink link;
	long interval;

	SlaveNodeWithConnection(ModbusLink link, final ModbusConnection conn, Node node) {
		super(conn, node);

		ScheduledThreadPoolExecutor stpe = conn.getDaemonThreadPool();
		conn.slaves.add(this);

		this.link = link;
		
        if(master != null && master.isInitialized()){
        	statnode.setValue(new Value("Connected"));
        }
		stpe.execute(new Runnable() {

			@Override
			public void run() {
				conn.checkConnection();
			}

		});

		this.interval = node.getAttribute("polling interval").getNumber().longValue();

		makeEditAction();

		if (master != null) {
			makeStopAction();
		} else {
			makeStartAction();
		}
	}



	private void makeStartAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				master = getMaster();
				if (null != master) {
					conn.checkConnection();
					node.removeChild("start");
					makeStopAction();
				}

			}
		});
		Node anode = node.getChild("start");
		if (anode == null)
			node.createChild("start").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	private void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				if (master != null) {
					master.destroy();
					link.masters.remove(master);
					master = null;
				}
				node.removeChild("stop");
				statnode.setValue(new Value("Stopped"));
				makeStartAction();
			}
		});

		Node anode = node.getChild("stop");
		if (anode == null)
			node.createChild("stop").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));

		act.addParameter(
				new Parameter("transport type", ValueType.makeEnum("TCP", "UDP"), node.getAttribute("transport type")));
		act.addParameter(new Parameter("host", ValueType.STRING, node.getAttribute("host")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));

		// The device specific parameters
		act.addParameter(new Parameter("slave id", ValueType.NUMBER, node.getAttribute("slave id")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
		act.addParameter(
				new Parameter("zero on failed poll", ValueType.BOOL, node.getAttribute("zero on failed poll")));
		act.addParameter(new Parameter("use batch polling", ValueType.BOOL, node.getAttribute("use batch polling")));
		act.addParameter(new Parameter("contiguous batch requests only", ValueType.BOOL,
				node.getAttribute("contiguous batch requests only")));

		// the common parameters for connection
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, node.getAttribute("Timeout")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(
				new Parameter("max read bit count", ValueType.NUMBER, node.getAttribute("max read bit count")));
		act.addParameter(new Parameter("max read register count", ValueType.NUMBER,
				node.getAttribute("max read register count")));
		act.addParameter(new Parameter("max write register count", ValueType.NUMBER,
				node.getAttribute("max write register count")));
		act.addParameter(
				new Parameter("discard data delay", ValueType.NUMBER, node.getAttribute("discard data delay")));
		act.addParameter(new Parameter("use multiple write commands only", ValueType.BOOL,
				node.getAttribute("use multiple write commands only")));

		Node anode = node.getChild("edit");
		if (anode == null)
			node.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();

		if (conn.slaves.isEmpty()) {
			try {
				master.destroy();
				link.masters.remove(master);
				master = null;
			} catch (Exception e) {
				LOGGER.debug("error destroying last master");
			}

			ScheduledThreadPoolExecutor stpe = conn.getDaemonThreadPool();
			stpe.shutdown();
		}

	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			IpTransportType transtype;
			try {
				transtype = IpTransportType
						.valueOf(event.getParameter("transport type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid transport type");
				LOGGER.debug("error: ", e);
				return;
			}

			String host = event.getParameter("host", ValueType.STRING).getString();
			int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();

			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int maxrbc = event.getParameter("max read bit count", ValueType.NUMBER).getNumber().intValue();
			int maxrrc = event.getParameter("max read register count", ValueType.NUMBER).getNumber().intValue();
			int maxwrc = event.getParameter("max write register count", ValueType.NUMBER).getNumber().intValue();
			int ddd = event.getParameter("discard data delay", ValueType.NUMBER).getNumber().intValue();
			boolean mwo = event.getParameter("use multiple write commands only", ValueType.BOOL).getBool();
            
			String currentHost = node.getAttribute("host").getString();
			int currentPort = node.getAttribute("port").getNumber().intValue();
			int currentTimeout = node.getAttribute("Timeout").getNumber().intValue();
			int currentRetries = node.getAttribute("retries").getNumber().intValue();
			int currentMaxrbc = node.getAttribute("max read bit count").getNumber().intValue();
			int currentMaxrrc = node.getAttribute("max read register count").getNumber().intValue();
			int currentMaxwrc = node.getAttribute("max write register count").getNumber().intValue();
			int currentDdd = node.getAttribute("discard data delay").getNumber().intValue();
			boolean currentMwo = node.getAttribute("use multiple write commands only").getBool();
			
			boolean isConnectionChanged = !currentHost.equals(host)
					|| !(currentPort == port)
					|| !(currentTimeout == timeout)
					|| !(currentRetries == retries)
					|| !(currentMaxrbc == maxrbc)
					|| !(currentMaxrrc == maxrrc)
					|| !(currentMaxwrc == maxwrc)
					|| !(currentDdd == ddd)
					|| !(currentMwo == mwo);

			if (isConnectionChanged) {
				node.setAttribute("host", new Value(host));
				node.setAttribute("port", new Value(port));

				node.setAttribute("Timeout", new Value(timeout));
				node.setAttribute("retries", new Value(retries));
				node.setAttribute("max read bit count", new Value(maxrbc));
				node.setAttribute("max read register count", new Value(maxrrc));
				node.setAttribute("max write register count", new Value(maxwrc));
				node.setAttribute("discard data delay", new Value(ddd));
				node.setAttribute("use multiple write commands only", new Value(mwo));
				node.setAttribute("host", new Value(host));
				node.setAttribute("port", new Value(port));

				// Synchronize the connection node
				conn.node.setAttribute("host", new Value(host));
				conn.node.setAttribute("port", new Value(port));

				conn.node.setAttribute("transport type", new Value(transtype.toString()));
				conn.node.setAttribute("Timeout", new Value(timeout));
				conn.node.setAttribute("retries", new Value(retries));
				conn.node.setAttribute("max read bit count", new Value(maxrbc));
				conn.node.setAttribute("max read register count", new Value(maxrrc));
				conn.node.setAttribute("max write register count", new Value(maxwrc));
				conn.node.setAttribute("discard data delay", new Value(ddd));
				conn.node.setAttribute("use multiple write commands only", new Value(mwo));

				if (master != null) {
					try {
						master.destroy();
						link.masters.remove(master);
						master = null;
					} catch (Exception e) {
						LOGGER.debug("error destroying last master");
					}
				}
				master = getMaster();
				conn.checkConnection();
			}

			String name = event.getParameter("name", ValueType.STRING).getString();
			int slaveid = event.getParameter("slave id", ValueType.NUMBER).getNumber().intValue();
			interval = (long) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()
					* 1000);
			boolean zerofail = event.getParameter("zero on failed poll", ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter("use batch polling", ValueType.BOOL).getBool();
			boolean contig = event.getParameter("contiguous batch requests only", ValueType.BOOL).getBool();

			link.handleEdit(root);

			node.setAttribute("slave id", new Value(slaveid));
			node.setAttribute("polling interval", new Value(interval));
			node.setAttribute("zero on failed poll", new Value(zerofail));
			node.setAttribute("use batch polling", new Value(batchpoll));
			node.setAttribute("contiguous batch requests only", new Value(contig));

			if (!name.equals(node.getName())) {
				rename(name);
			}

			makeEditAction();
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = jobj;
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		SlaveNode sn = new SlaveNodeWithConnection(link, conn, newnode);
		sn.restoreLastSession();
	}

	@Override
	public ModbusMaster getMaster() {
		return conn.getMaster();
	}
}
