package modbus;

import java.util.Map;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.modbus4j.ProcessImage;

public class VirtualDeviceFolder extends EditableFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(VirtualDeviceFolder.class);
	}
	
	static final String ACTION_EDIT = "edit";

	public VirtualDeviceFolder(ModbusLink link, Node node) {
		super(link, node);
		node.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value("virtual"));
	}

	@Override
	protected void edit(ActionResult event) {
		// No-op

	}
	
	@Override
	public void setMakeCopyAction() {
		// No-op
	}
	
	@Override
	public void setAddPointAction() {
		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTRIBUTE_POINT_TYPE, ValueType.makeEnum(Util.enumNames(PointType.class))));

		act.addParameter(new Parameter(ATTRIBUTE_OFFSET, ValueType.NUMBER));
		act.addParameter(new Parameter(LocalSlaveFolder.ATTRIBUTE_DATA_TYPE, ValueType.makeEnum(Util.enumNames(DataType.class))));
		act.addParameter(new Parameter(LocalSlaveFolder.ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER));

		node.createChild(LocalSlaveFolder.ACTION_ADD_POINT, true).setAction(act).build().setSerializable(false);
	}

	@Override
	protected void addPoint(String name, PointType type, ActionResult event) {
		Node pointNode = null;
		DataType dataType;

		int registerCount = 0;
		int offset = event.getParameter(ATTRIBUTE_OFFSET, ValueType.NUMBER).getNumber().intValue();

		if (type == PointType.COIL || type == PointType.DISCRETE)
			dataType = DataType.BOOLEAN;
		else
			try {
				dataType = DataType
						.valueOf(event.getParameter(LocalSlaveFolder.ATTRIBUTE_DATA_TYPE, ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				LOGGER.error("invalid data type");
				LOGGER.debug("error: ", e1);
				return;
			}

		boolean defaultStatus = false;
		double defaultNumber = 0;
		String defaultString = " ";

		switch (type) {
		case COIL:
			pointNode = node.createChild(name, true).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus))
					.build();
			break;
		case DISCRETE:
			pointNode = node.createChild(name, true).setValueType(ValueType.BOOL).setValue(new Value(defaultStatus))
					.build();
			break;
		case HOLDING:
			if (dataType.isString()) {
				registerCount = event.getParameter(LocalSlaveFolder.ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
				pointNode = node.createChild(name, true).setValueType(ValueType.STRING)
						.setValue(new Value(defaultString)).build();
			} else {
				pointNode = node.createChild(name, true).setValueType(ValueType.NUMBER)
						.setValue(new Value(defaultNumber)).build();
			}
			break;
		case INPUT:
			if (dataType.isString()) {
				registerCount = event.getParameter(LocalSlaveFolder.ATTRIBUTE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
				pointNode = node.createChild(name, true).setValueType(ValueType.STRING)
						.setValue(new Value(defaultString)).build();
			} else {
				pointNode = node.createChild(name, true).setValueType(ValueType.NUMBER)
						.setValue(new Value(defaultNumber)).build();
			}
			break;
		default:
			break;
		}

		if (null != pointNode) {
			pointNode.setAttribute(ATTRIBUTE_POINT_TYPE, new Value(type.toString()));
			pointNode.setAttribute(ATTRIBUTE_OFFSET, new Value(offset));
			pointNode.setAttribute(LocalSlaveFolder.ATTRIBUTE_DATA_TYPE, new Value(dataType.toString()));

			if (dataType.isString()) {
				pointNode.setAttribute(LocalSlaveFolder.ATTRIBUTE_REGISTER_COUNT, new Value(registerCount));
			}
			pointNode.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value(LocalSlaveFolder.ATTRIBUTE_RESTORE_POINT));

			setEditPointActions(pointNode);
		}

	}
	
	protected void setEditPointActions(final Node pointNode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				node.removeChild(pointNode, false);
			}
		});

		Node child = pointNode.getChild(ACTION_REMOVE, true);
		if (child == null)
			pointNode.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		else
			child.setAction(act);

		pointNode.setWritable(Writable.WRITE);
	}

	@Override
	protected void addFolder(String name) {
		Node child = node.createChild(name, true).build();
		new VirtualDeviceFolder(link, child);

	}

	@Override
	public void setEditAction() {
		// no-op
	}

	@Override
	protected ProcessImage getProcessImage() {
		return null;
	}

	@Override
	protected Map<Integer, Node> getOffsetToPoint() {
		return null;
	}
	
	@Override
	void restoreLastSession() {
		if (node.getChildren() == null) {
			return;
		}
		for (Node child: node.getChildren().values()) {
			Value restype = child.getAttribute(ATTRIBUTE_RESTORE_TYPE);
			if (restype != null) {
				if ("virtual".equals(restype.getString())) {
					VirtualDeviceFolder vdf = new VirtualDeviceFolder(link, child);
					vdf.restoreLastSession();
				} else if (restype.getString().equals(LocalSlaveFolder.ATTRIBUTE_RESTORE_POINT)) {
					Value type = child.getAttribute(ATTRIBUTE_POINT_TYPE);
					Value offset = child.getAttribute(ATTRIBUTE_OFFSET);
					Value dataType = child.getAttribute(LocalSlaveFolder.ATTRIBUTE_DATA_TYPE);
					if (type != null && offset != null && dataType != null) {
						setEditPointActions(child);
					} else {
						node.removeChild(child, false);
					}
				}
			} else if (child.getAction() == null) {
				node.removeChild(child, false);
			}
		}
	}

}
