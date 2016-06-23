package modbus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serotonin.modbus4j.BasicProcessImage;
import com.serotonin.modbus4j.ProcessImageListener;
import com.serotonin.modbus4j.ModbusSlaveSet;

import com.serotonin.modbus4j.exception.ModbusInitException;

public class LocalSlaveFolder extends EditableFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalSlaveFolder.class);
	}

	static final String NODE_STATUS = "STATUS";

	static final String REGISTERS_COIL = "coil group";
	static final String REGISTERS_DISCRETE = "discrete input group";
	static final String REGISTERS_HOLDING = "holding register group";
	static final String REGISTERS_INPUT = "input register group";

	static final String ACTION_ADD_POINT = "add point";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_MAKE_COPY = "make copy";
	static final String ACTION_REMOVE = "remove";

	static final String ATTRIBUTE_DATA_TYPE = "data type";
	static final String ATTRIBUTE_TRANSPORT_TYPE = "transport type";
	static final String ATTRIBUTE_PORT = "port";
	static final String ATTRIBUTE_SLAVE_ID = "slave id";
	static final String ATTRIBUTE_STATUS = "point status";
	static final String ATTRIBUTE_NUMBER = "numeric data";
	static final String ATTRIBUTE_SETUP_DEVICE = "Setting up device";
	static final String ATTRIBUTE_START_LISTENING = "Listening started";
	static final String ATTRIBUTE_STOP_LISTENING = "Listening stoppd";

	private ModbusSlaveSet activeListener;
	private final ScheduledThreadPoolExecutor stpe;

	private BasicProcessImage processImage;
	BasicProcessImageListener processImageListener;

	// grouping node for every kind of register
	private Node coilNode;
	private Node discreteNode;
	private Node holdingNode;
	private Node inputNode;

	private Node statusNode;

	Map<Integer, Node> offset2Point = new HashMap<Integer, Node>();

	LocalSlaveFolder(ModbusLink link, Node node) {
		super(link, node);

		this.statusNode = node.createChild(NODE_STATUS).setValueType(ValueType.STRING)
				.setValue(new Value(ATTRIBUTE_SETUP_DEVICE)).build();

		this.coilNode = node.createChild(REGISTERS_COIL).build();
		this.discreteNode = node.createChild(REGISTERS_DISCRETE).build();
		this.holdingNode = node.createChild(REGISTERS_HOLDING).build();
		this.inputNode = node.createChild(REGISTERS_INPUT).build();

		this.stpe = Objects.createDaemonThreadPool();

		this.offset2Point = new HashMap<Integer, Node>();

		this.processImage = getProcessImage();
		this.processImageListener = getProcessImageListener();
		this.processImage.addListener(this.processImageListener);

		this.activeListener = getActiveSlaveSet();
		activeListener.addProcessImage(processImage);
		startListening();

	}

	void startListening() {
		if (stpe != null) {

			stpe.execute(new Runnable() {
				@Override
				public void run() {
					try {
						statusNode.setValue(new Value(ATTRIBUTE_START_LISTENING));
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
						statusNode.setValue(new Value(ATTRIBUTE_STOP_LISTENING));
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

	private BasicProcessImage getProcessImage() {
		int slaveId = node.getAttribute(ATTRIBUTE_SLAVE_ID).getNumber().intValue();
		BasicProcessImage processImage = new BasicProcessImage(slaveId);
		processImage.setInvalidAddressValue(Short.MIN_VALUE);

		return processImage;
	}

	private BasicProcessImageListener getProcessImageListener() {
		return new BasicProcessImageListener();
	}

	@Override
	public void setAddPointAction() {

		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTRIBUTE_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class))));
		act.addParameter(new Parameter(ATTRIBUTE_OFFSET, ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter(ATTRIBUTE_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class))));

		node.createChild(ACTION_ADD_POINT).setAction(act).build().setSerializable(false);

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

	public int getTransportPort(ActionResult event) {
		int port = event.getParameter(ATTRIBUTE_PORT, ValueType.NUMBER).getNumber().intValue();
		return port;
	}

	public TransportType getTransportType(ActionResult event) {
		TransportType transtype;
		try {
			transtype = TransportType
					.valueOf(event.getParameter(ATTRIBUTE_TRANSPORT_TYPE, ValueType.STRING).getString().toUpperCase());
			return transtype;
		} catch (Exception e) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e);
			return null;
		}
	}

	@Override
	public void remove() {
		this.stopListening();
		super.remove();

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

	@Override
	public void duplicate(String name) {

	}

	@Override
	public void addPoint(String name, PointType type, ActionResult event) {
		Node pointNode = null;
		DataType dataType;

		int offset = event.getParameter(ATTRIBUTE_OFFSET, ValueType.NUMBER).getNumber().intValue();
		int range = PointType.getPointTypeInt(type);

		if (type == PointType.COIL || type == PointType.DISCRETE)
			dataType = DataType.BOOLEAN;
		else
			try {
				dataType = DataType
						.valueOf(event.getParameter(ATTRIBUTE_DATA_TYPE, ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				LOGGER.error("invalid data type");
				LOGGER.debug("error: ", e1);
				return;
			}

		boolean defaultStatus = false;
		double defaultNumber = 0;

		switch (type) {
		case COIL:
			processImage.setCoil(offset, defaultStatus);
			pointNode = coilNode.createChild(name).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus))
					.build();

			break;
		case DISCRETE:
			processImage.setInput(offset, defaultStatus);
			pointNode = discreteNode.createChild(name).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus))
					.build();
			break;
		case HOLDING:
			processImage.setNumeric(range, offset, dataType.ordinal(), defaultNumber);
			pointNode = holdingNode.createChild(name).setValueType(ValueType.NUMBER).setValue(new Value(defaultNumber))
					.build();
			break;
		case INPUT:
			processImage.setNumeric(range, offset, dataType.ordinal(), defaultNumber);
			pointNode = inputNode.createChild(name).setValueType(ValueType.NUMBER).setValue(new Value(defaultNumber))
					.build();
			break;
		}

		pointNode.setAttribute(ATTRIBUTE_POINT_TYPE, new Value(type.toString()));
		pointNode.setAttribute(ATTRIBUTE_OFFSET, new Value(offset));
		pointNode.setAttribute(ATTRIBUTE_DATA_TYPE, new Value(dataType.toString()));

		if (!offset2Point.containsKey(offset)) {
			offset2Point.put(offset, pointNode);
		}

		setEditPointActions(pointNode);
	}

	protected void setEditPointActions(Node pointNode) {
		Action act = new Action(Permission.READ, new RemovePointHandler(pointNode));
		Node anode = pointNode.getChild(ACTION_REMOVE);
		if (anode == null)
			pointNode.createChild(ACTION_REMOVE).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new EditPointHandler(pointNode));
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter(ATTRIBUTE_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class)),
				pointNode.getAttribute(ATTRIBUTE_POINT_TYPE)));
		act.addParameter(new Parameter(ATTRIBUTE_OFFSET, ValueType.NUMBER, pointNode.getAttribute(ATTRIBUTE_OFFSET)));

		act.addParameter(new Parameter(ATTRIBUTE_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class)),
				pointNode.getAttribute(ATTRIBUTE_DATA_TYPE)));

		anode = pointNode.getChild(ACTION_EDIT);
		if (anode == null)
			pointNode.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new CopyPointHandler(pointNode));
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		anode = pointNode.getChild(ACTION_MAKE_COPY);
		if (anode == null)
			pointNode.createChild(ACTION_MAKE_COPY).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		pointNode.setWritable(Writable.WRITE);
		pointNode.getListener().setValueHandler(new SetPointHandler(pointNode));

	}

	protected class CopyPointHandler implements Handler<ActionResult> {
		private Node pointNode;

		CopyPointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			if (name.length() > 1 && !name.equals(pointNode.getName())) {
				copyPoint(pointNode, name);
			}
		}
	}

	private Node copyPoint(Node pointNode, String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj).get(node.getName());
		JsonObject pointnodeobj = parentobj.get(pointNode.getName());
		parentobj.put(name, pointnodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getChild(name);
		setEditPointActions(newnode);
		// link.setupPoint(newnode, root);
		return newnode;
	}

	protected class EditPointHandler implements Handler<ActionResult> {
		private Node pointNode;

		EditPointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType
						.valueOf(event.getParameter(ATTRIBUTE_POINT_TYPE, ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				LOGGER.error("invalid type");
				LOGGER.debug("error: ", e);
				return;
			}
			int offset = event.getParameter(ATTRIBUTE_OFFSET, ValueType.NUMBER).getNumber().intValue();

			boolean writable = (type == PointType.COIL || type == PointType.HOLDING)
					&& event.getParameter(ATTRIBUTE_OFFSET, ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE)
				dataType = DataType.BOOLEAN;
			else
				try {
					dataType = DataType.valueOf(
							event.getParameter(ATTRIBUTE_DATA_TYPE, ValueType.STRING).getString().toUpperCase());
				} catch (Exception e1) {
					LOGGER.error("invalid data type");
					LOGGER.debug("error: ", e1);
					return;
				}
			// int bit = event.getParameter("bit", new
			// Value(-1)).getNumber().intValue();
			// double scaling = event.getParameter("scaling",
			// ValueType.NUMBER).getNumber().doubleValue();
			// double addscale = event.getParameter("scaling offset",
			// ValueType.NUMBER).getNumber().doubleValue();

			if (!name.equals(pointNode.getName())) {
				Node newnode = copyPoint(pointNode, name);
				node.removeChild(pointNode);
				pointNode = newnode;
			}
			pointNode.setAttribute("type", new Value(type.toString()));
			pointNode.setAttribute("offset", new Value(offset));
			// pointNode.setAttribute("number of registers", new
			// Value(numRegs));
			pointNode.setAttribute("data type", new Value(dataType.toString()));

			pointNode.setAttribute("writable", new Value(writable));

			setEditPointActions(pointNode);
			// link.setupPoint(pointNode, root);
			pointNode.setAttribute("restoreType", new Value("point"));
		}
	}

	protected class RemovePointHandler implements Handler<ActionResult> {
		private Node pointNode;

		RemovePointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			pointNode.getParent().removeChild(pointNode);
		}
	}

	protected class SetPointHandler implements Handler<ValuePair> {
		private Node pointNode;

		SetPointHandler(Node node) {
			this.pointNode = node;
		}

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource())
				return;

			PointType type = PointType.valueOf(pointNode.getAttribute(ATTRIBUTE_POINT_TYPE).getString());
			int range = PointType.getPointTypeInt(type);
			int offset = pointNode.getAttribute(ATTRIBUTE_OFFSET).getNumber().intValue();
			DataType dataType = DataType.valueOf(pointNode.getAttribute(ATTRIBUTE_DATA_TYPE).getString());

			Value oldValue = event.getPrevious();
			Value newValue = event.getCurrent();
			JsonArray valarr;
			if (newValue.getType() == ValueType.STRING && oldValue.getType() == ValueType.STRING) {
				String valstr = newValue.getString();
				String oldstr = oldValue.getString();
				if (!oldstr.startsWith("["))
					oldstr = "[" + oldstr + "]";
				int numThings = new JsonArray(oldstr).size();
				if (!valstr.startsWith("["))
					valstr = "[" + valstr + "]";
				valarr = new JsonArray(valstr);
				if (valarr.size() != numThings) {
					LOGGER.error("wrong number of values");
					return;
				}
			} else if (newValue.getType().compare(ValueType.BOOL)) {
				valarr = new JsonArray();
				valarr.add(newValue.getBool());
			} else if (newValue.getType() == ValueType.NUMBER) {
				valarr = new JsonArray();
				valarr.add(newValue.getNumber());
			} else {
				LOGGER.error("Unexpected value type");
				return;
			}

			switch (type) {
			case COIL:
				processImage.setCoil(offset, newValue.getBool());

				break;
			case DISCRETE:
				processImage.setInput(offset, newValue.getBool());

				break;
			case HOLDING:
				processImage.setNumeric(range, offset, dataType.ordinal(), newValue.getNumber());

				break;
			case INPUT:
				processImage.setNumeric(range, offset, dataType.ordinal(), newValue.getNumber());

				break;
			}

		}

	}

	private void switchListener(TransportType transtype, Integer port) {
		activeListener = link.getSlaveSet(transtype, port);
		activeListener.addProcessImage(processImage);
	}

	private class BasicProcessImageListener implements ProcessImageListener {

		@Override
		public void coilWrite(int offset, boolean oldValue, boolean newValue) {
			if (oldValue != newValue) {
				Node pointNode = offset2Point.get(offset);
				pointNode.setValue(new Value(newValue));
			}

		}

		@Override
		public void holdingRegisterWrite(int offset, short oldValue, short newValue) {
			if (oldValue != newValue) {
				Node pointNode = offset2Point.get(offset);
				pointNode.setValue(new Value(newValue));
			}
		}

	}
}