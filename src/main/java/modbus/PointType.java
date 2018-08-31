package modbus;

import com.serotonin.modbus4j.code.RegisterRange;

public enum PointType {
	COIL, DISCRETE, HOLDING, INPUT;

	public static int getPointTypeInt(PointType pt) {
		switch (pt) {
		case COIL:
			return RegisterRange.COIL_STATUS;
		case DISCRETE:
			return RegisterRange.INPUT_STATUS;
		case HOLDING:
			return RegisterRange.HOLDING_REGISTER;
		case INPUT:
			return RegisterRange.INPUT_REGISTER;
		default:
			return 0;
		}
	}
}
