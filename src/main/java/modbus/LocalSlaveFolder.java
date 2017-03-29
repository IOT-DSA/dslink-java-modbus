package modbus;

import java.util.Map;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serotonin.modbus4j.BasicProcessImage;
import com.serotonin.modbus4j.ProcessImage;

/*
 * The implementation of Editable Folder 
 */
public class LocalSlaveFolder extends EditableFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalSlaveFolder.class);
	}

	static final String ACTION_ADD_POINT = "add point";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_MAKE_COPY = "make copy";
	static final String ACTION_REMOVE = "remove";

	static final String ATTRIBUTE_DATA_TYPE = "data type";
	static final String ATTRIBUTE_TRANSPORT_TYPE = "transport type";
	static final String ATTRIBUTE_PORT = "port";
	static final String ATTRIBUTE_SLAVE_ID = "slave id";
	static final String ATTRIBUTE_OFFSET = "offset";
	static final String ATTRIBUTE_REGISTER_COUNT = "number of registers";

	static final String ATTRIBUTE_RESTORE_TYPE = "restoreType";
	static final String ATTRIBUTE_RESTORE_POINT = "point";
	static final String ATTRIBUTE_RESTORE_FOLDER = "editable folder";
	static final String ATTRIBUTE_RESTORE_GROUP = "register group";

	static final String NODE_STATUS = "Status";

	LocalSlaveFolder(ModbusLink link, EditableFolder root, Node node) {
		this(link, node);

		this.root = root;
	}

	LocalSlaveFolder(ModbusLink link, Node node) {
		super(link, node);

	}

	@Override
	public void setAddPointAction() {

		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTRIBUTE_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class))));

		act.addParameter(new Parameter(ATTRIBUTE_OFFSET, ValueType.NUMBER));
		act.addParameter(new Parameter(ATTRIBUTE_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class))));
		act.addParameter(new Parameter(ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER));

		node.createChild(ACTION_ADD_POINT, true).setAction(act).build().setSerializable(false);

	}

	public void setEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());

		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(node.getName())));

		Node editNode = node.getChild(ACTION_EDIT, true);
		if (editNode == null)
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	public int getTransportPort(ActionResult event) {
		int port = event.getParameter(ATTRIBUTE_PORT, ValueType.NUMBER).getNumber().intValue();
		return port;
	}

	public IpTransportType getTransportType(ActionResult event) {
		IpTransportType transtype;
		try {
			transtype = IpTransportType
					.valueOf(event.getParameter(ATTRIBUTE_TRANSPORT_TYPE, ValueType.STRING).getString().toUpperCase());
			return transtype;
		} catch (Exception e) {
			LOGGER.error("invalid transport type");
			LOGGER.debug("error: ", e);
			return null;
		}
	}

	@Override
	public void edit(ActionResult event) {

		String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();

		if (!name.equals(node.getName())) {
			rename(name);
		}

		this.setEditAction();
	}

	@Override
	public void duplicate(String name) {
		super.duplicate(name);

		Node newnode = node.getParent().getChild(name, true);
		LocalSlaveFolder folder = new LocalSlaveFolder(link, root, newnode);
		folder.restoreLastSession();
	}

	@Override
	public void addPoint(String name, PointType type, ActionResult event) {
		Node pointNode = null;
		DataType dataType;

		int registerCount = 0;
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
		String defaultString = " ";

		BasicProcessImage processImage = (BasicProcessImage) root.getProcessImage();
		Map<Integer, Node> offsetToPoint = root.getOffsetToPoint();

		switch (type) {
		case COIL:
			processImage.setCoil(offset, defaultStatus);
			pointNode = node.createChild(name, true).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus)).build();
			break;
		case DISCRETE:
			processImage.setInput(offset, defaultStatus);
			pointNode = node.createChild(name, true).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus)).build();
			break;
		case HOLDING:
			if (dataType.isString()) {
				registerCount = event.getParameter(ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
				processImage.setString(range, offset, DataType.getDataTypeInt(dataType), registerCount, defaultString);
				pointNode = node.createChild(name, true).setValueType(ValueType.STRING).setValue(new Value(defaultString))
						.build();
			} else {
				processImage.setNumeric(range, offset, DataType.getDataTypeInt(dataType), defaultNumber);
				pointNode = node.createChild(name, true).setValueType(ValueType.NUMBER).setValue(new Value(defaultNumber))
						.build();
			}
			break;
		case INPUT:
			if (dataType.isString()) {
				registerCount = event.getParameter(ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
				processImage.setString(range, offset, dataType.ordinal(), registerCount, defaultString);
				pointNode = node.createChild(name, true).setValueType(ValueType.STRING).setValue(new Value(defaultString))
						.build();
			} else {
				processImage.setNumeric(range, offset, dataType.ordinal(), defaultNumber);
				pointNode = node.createChild(name, true).setValueType(ValueType.NUMBER).setValue(new Value(defaultNumber))
						.build();
			}
			break;
		default:
			break;
		}

		if (null != pointNode) {
			pointNode.setAttribute(ATTRIBUTE_POINT_TYPE, new Value(type.toString()));
			pointNode.setAttribute(ATTRIBUTE_OFFSET, new Value(offset));
			pointNode.setAttribute(ATTRIBUTE_DATA_TYPE, new Value(dataType.toString()));

			if (dataType.isString()) {
				pointNode.setAttribute(ATTRIBUTE_REGISTER_COUNT, new Value(registerCount));
			}
			pointNode.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value(ATTRIBUTE_RESTORE_POINT));

			if (!offsetToPoint.containsKey(offset)) {
				offsetToPoint.put(offset, pointNode);
			}

			setEditPointActions(pointNode);
		}

	}

	protected void setEditPointActions(Node pointNode) {
		Action act = new Action(Permission.READ, new RemovePointHandler(pointNode));

		Node child = pointNode.getChild(ACTION_REMOVE, true);
		if (child == null)
			pointNode.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		else
			child.setAction(act);

		act = new Action(Permission.READ, new EditPointHandler(pointNode));
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter(ATTRIBUTE_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class)),
				pointNode.getAttribute(ATTRIBUTE_POINT_TYPE)));
		act.addParameter(new Parameter(ATTRIBUTE_OFFSET, ValueType.NUMBER, pointNode.getAttribute(ATTRIBUTE_OFFSET)));

		act.addParameter(new Parameter(ATTRIBUTE_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class)),
				pointNode.getAttribute(ATTRIBUTE_DATA_TYPE)));

		act.addParameter(new Parameter(ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER,
				pointNode.getAttribute(ATTRIBUTE_REGISTER_COUNT)));
		child = pointNode.getChild(ACTION_EDIT, true);
		if (child == null)
			pointNode.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		else
			child.setAction(act);

		act = new Action(Permission.READ, new CopyPointHandler(pointNode));
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		child = pointNode.getChild(ACTION_MAKE_COPY, true);
		if (child == null)
			pointNode.createChild(ACTION_MAKE_COPY, true).setAction(act).build().setSerializable(false);
		else
			child.setAction(act);

		pointNode.setWritable(Writable.WRITE);
		pointNode.getListener().setValueHandler(new SetValueHandler(pointNode));

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
		parentobj.put(StringUtils.encodeName(name), pointnodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getChild(name, true);
		setEditPointActions(newnode);

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
			int registerCount = event.getParameter(ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();

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

			if (!name.equals(pointNode.getName())) {
				Node newnode = copyPoint(pointNode, name);
				node.removeChild(pointNode, false);
				pointNode = newnode;
			}

			pointNode.setAttribute(ATTRIBUTE_POINT_TYPE, new Value(type.toString()));
			pointNode.setAttribute(ATTRIBUTE_OFFSET, new Value(offset));
			pointNode.setAttribute(ATTRIBUTE_REGISTER_COUNT, new Value(registerCount));
			pointNode.setAttribute(ATTRIBUTE_DATA_TYPE, new Value(dataType.toString()));
			pointNode.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value(ATTRIBUTE_RESTORE_POINT));

			setEditPointActions(pointNode);
		}
	}

	protected class RemovePointHandler implements Handler<ActionResult> {
		private Node pointNode;

		RemovePointHandler(Node pnode) {
			pointNode = pnode;
		}

		public void handle(ActionResult event) {
			node.removeChild(pointNode, false);
		}
	}

	protected class SetValueHandler implements Handler<ValuePair> {
		private Node pointNode;

		SetValueHandler(Node node) {
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

			Value newValue = event.getCurrent();
			BasicProcessImage processImage = (BasicProcessImage) root.getProcessImage();

			switch (type) {
			case COIL:
				processImage.setCoil(offset, newValue.getBool());
				break;
			case DISCRETE:
				processImage.setInput(offset, newValue.getBool());
				break;
			case HOLDING:
				if (dataType.isString()) {
					int registerCount = pointNode.getAttribute(ATTRIBUTE_REGISTER_COUNT).getNumber().intValue();
					processImage.setString(range, offset, DataType.getDataTypeInt(dataType), registerCount,
							newValue.getString());
				} else {
					processImage.setNumeric(range, offset, DataType.getDataTypeInt(dataType), newValue.getNumber());
				}
				break;
			case INPUT:
				if (dataType.isString()) {
					int registerCount = pointNode.getAttribute(ATTRIBUTE_REGISTER_COUNT).getNumber().intValue();
					processImage.setString(range, offset, DataType.getDataTypeInt(dataType), registerCount,
							newValue.getString());
				} else {
					processImage.setNumeric(range, offset, DataType.getDataTypeInt(dataType), newValue.getNumber());
				}
				break;
			}

		}

	}

	public void restoreLastSession() {
		restoreLastSession(this.node);
	}

	private void restoreLastSession(Node node) {
		if (node.getChildren() == null)
			return;

		for (Node child : node.getChildren().values()) {
			Value restoreType = child.getAttribute(ATTRIBUTE_RESTORE_TYPE);

			if (restoreType != null) {
				if (restoreType.getString().equals(ATTRIBUTE_RESTORE_FOLDER)) {
					EditableFolder folder = new LocalSlaveFolder(link, root, child);
					folder.restoreLastSession();
				}

				else if (restoreType.getString().equals(ATTRIBUTE_RESTORE_POINT)) {
					Value type = child.getAttribute(ATTRIBUTE_POINT_TYPE);
					Value offset = child.getAttribute(ATTRIBUTE_OFFSET);
					Value dataType = child.getAttribute(ATTRIBUTE_DATA_TYPE);
					Value value = child.getValue();
					if (type != null && offset != null && dataType != null) {
						setEditPointActions(child);
						// remote slave fetches value remotely, while local
						// slave loads the value from the local data source
						child.setValue(value, true);
					} else {
						node.removeChild(child, false);
					}
				}
			} else if (child.getAction() == null && !(NODE_STATUS.equals(child.getName()))) {
				node.removeChild(child, false);
			}
		}
	}

	@Override
	protected void addFolder(String name) {
		Node child = node.createChild(name, true).build();
		new LocalSlaveFolder(link, root, child);

	}

	@Override
	protected ProcessImage getProcessImage() {
		return root.getProcessImage();
	}

	@Override
	protected Map<Integer, Node> getOffsetToPoint() {
		return null;
	}
}