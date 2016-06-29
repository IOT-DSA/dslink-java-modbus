package modbus;

import java.util.HashMap;
import java.util.Map;
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
import com.serotonin.modbus4j.BasicProcessImage;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.ProcessImageListener;
import com.serotonin.modbus4j.exception.ModbusInitException;

/*
 * This is a special folder which represents the virtual device node
 * This folder has an one-to-one relationship with process image 
 */
public class LocalSlaveNode extends LocalSlaveFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalSlaveNode.class);
	}

	static final String NODE_STATUS = "STATUS";

	private Node statusNode;

	private ModbusSlaveSet activeListener;
	private final ScheduledThreadPoolExecutor stpe;

	protected BasicProcessImage processImage;
	protected BasicProcessImageListener processImageListener;
	Map<Integer, Node> offsetToPoint;

	LocalSlaveNode(ModbusLink link, Node node) {
		super(link, node);

		this.root = this;
		this.statusNode = getStatusNode();

		this.stpe = Objects.createDaemonThreadPool();

		this.processImage = getProcessImage();
		this.processImageListener = getProcessImageListener();
		this.processImage.addListener(this.processImageListener);
		this.offsetToPoint = getOffsetToPoint();
		this.activeListener = getActiveSlaveSet();
		activeListener.addProcessImage(processImage);

		startListening();
	}

	private Node getStatusNode() {
		Node child = node.createChild(NODE_STATUS).setValueType(ValueType.STRING)
				.setValue(new Value(STATUS_SETUP_DEVICE)).build();

		return child;
	}

	protected Map<Integer, Node> getOffsetToPoint() {
		if (null == offsetToPoint) {
			offsetToPoint = new HashMap<Integer, Node>();
		}

		return offsetToPoint;
	}

	protected BasicProcessImage getProcessImage() {
		int slaveId = node.getAttribute(ATTRIBUTE_SLAVE_ID).getNumber().intValue();
		if (null == processImage) {
			processImage = new BasicProcessImage(slaveId);
		}
		processImage.setInvalidAddressValue(Short.MIN_VALUE);

		return processImage;
	}

	private BasicProcessImageListener getProcessImageListener() {
		return new BasicProcessImageListener();
	}

	public void setEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());

		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(node.getName())));

		act.addParameter(new Parameter(ATTRIBUTE_TRANSPORT_TYPE,
				ValueType.makeEnum(Util.enumNames(TransportType.class)), node.getAttribute(ATTRIBUTE_TRANSPORT_TYPE)));
		act.addParameter(new Parameter(ATTRIBUTE_PORT, ValueType.NUMBER, node.getAttribute(ATTRIBUTE_PORT)));
		act.addParameter(new Parameter(ATTRIBUTE_SLAVE_ID, ValueType.NUMBER, node.getAttribute(ATTRIBUTE_SLAVE_ID)));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	@Override
	public void edit(ActionResult event) {

		TransportType transtype = getTransportType(event);
		int port = getTransportPort(event);

		String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
		int slaveid = event.getParameter(ATTRIBUTE_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();

		node.setAttribute(ATTRIBUTE_TRANSPORT_TYPE, new Value(transtype.toString()));
		node.setAttribute(ATTRIBUTE_PORT, new Value(port));
		node.setAttribute(ATTRIBUTE_SLAVE_ID, new Value(slaveid));

		if (!name.equals(node.getName())) {
			rename(name);
		}

		if (!transtype.toString().equals(node.getAttribute(transtype.toString()))) {
			switchListener(transtype, port);
		}

		this.setEditAction();
	}

	void startListening() {
		if (stpe != null) {

			stpe.execute(new Runnable() {
				@Override
				public void run() {
					try {
						statusNode.setValue(new Value(STATUS_START_LISTENING));
						activeListener.start();
					} catch (ModbusInitException e) {
						e.printStackTrace();
					}
				}
			});

		}
	}

	void stopListening() {
		if (stpe != null) {

			stpe.execute(new Runnable() {
				@Override
				public void run() {
					try {
						statusNode.setValue(new Value(STATUS_STOP_LISTENING));
						activeListener.stop();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});

		}
	}

	private ModbusSlaveSet getActiveSlaveSet() {
		TransportType transtype = null;

		try {
			transtype = TransportType.valueOf(node.getAttribute(ATTRIBUTE_TRANSPORT_TYPE).getString().toUpperCase());
		} catch (Exception e1) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e1);
			return null;
		}
		int port = node.getAttribute(ATTRIBUTE_PORT).getNumber().intValue();

		return link.getSlaveSet(transtype, port);
	}

	@Override
	public void remove() {
		this.stopListening();
		super.remove();
	}

	protected void switchListener(TransportType transtype, Integer port) {
		activeListener = link.getSlaveSet(transtype, port);
		activeListener.addProcessImage(processImage);
	}

	private class BasicProcessImageListener implements ProcessImageListener {

		@Override
		public void coilWrite(int offset, boolean oldValue, boolean newValue) {
			if (oldValue != newValue) {
				Node pointNode = offsetToPoint.get(offset);
				pointNode.setValue(new Value(newValue));
			}

		}

		@Override
		public void holdingRegisterWrite(int offset, short oldValue, short newValue) {
			if (oldValue != newValue) {
				Node pointNode = offsetToPoint.get(offset);
				pointNode.setValue(new Value(newValue));
			}
		}

	}
}
