package modbus;

public enum DataType {
	BOOLEAN, INT16, UINT16, INT16SWAP, UINT16SWAP, INT32, UINT32, INT32SWAP, UINT32SWAP, INT32SWAPSWAP, UINT32SWAPSWAP, FLOAT32, FLOAT32SWAP, INT64, UINT64, INT64SWAP, UINT64SWAP, FLOAT64, FLOAT64SWAP, BCD16, BCD32, BCD32SWAP, CHARSTRING, VARCHARSTRING, INT32M10K, UINT32M10K, INT32M10KSWAP, UINT32M10KSWAP;

	public boolean isFloat() {
		return (this == FLOAT32 || this == FLOAT32SWAP || this == FLOAT64 || this == FLOAT64SWAP);
	}

	public boolean isString() {
		return (this == CHARSTRING || this == VARCHARSTRING);
	}

	protected static Integer getDataTypeInt(DataType dt) {
		switch (dt) {
		case BOOLEAN:
			return com.serotonin.modbus4j.code.DataType.BINARY;
		case INT16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
		case UINT16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED;
		case INT16SWAP:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED_SWAPPED;
		case UINT16SWAP:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_UNSIGNED_SWAPPED;
		case BCD16:
			return com.serotonin.modbus4j.code.DataType.TWO_BYTE_BCD;
		case BCD32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD;
		case BCD32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_BCD_SWAPPED;
		case INT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
		case UINT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED;
		case INT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED;
		case UINT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED;
		case INT32SWAPSWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED_SWAPPED_SWAPPED;
		case UINT32SWAPSWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED_SWAPPED;
		case FLOAT32:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT;
		case FLOAT32SWAP:
			return com.serotonin.modbus4j.code.DataType.FOUR_BYTE_FLOAT_SWAPPED;
		case INT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED;
		case UINT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED;
		case INT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_SIGNED_SWAPPED;
		case UINT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_INT_UNSIGNED_SWAPPED;
		case FLOAT64:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT;
		case FLOAT64SWAP:
			return com.serotonin.modbus4j.code.DataType.EIGHT_BYTE_FLOAT_SWAPPED;
		case CHARSTRING:
			return com.serotonin.modbus4j.code.DataType.CHAR;
		case VARCHARSTRING:
			return com.serotonin.modbus4j.code.DataType.VARCHAR;
		default:
			return null;
		}
	}
}
