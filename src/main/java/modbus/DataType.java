package modbus;

public enum DataType {
	BOOLEAN(null, null),
	INT16(Short.MIN_VALUE, Short.MAX_VALUE), 
	UINT16(0, 65535),
	INT16SWAP(Short.MIN_VALUE, Short.MAX_VALUE),
	UINT16SWAP(0, 65535),
	INT32(Integer.MIN_VALUE, Integer.MAX_VALUE), 
	UINT32(0L, 4294967295L),
	INT32SWAP(Integer.MIN_VALUE, Integer.MAX_VALUE),
	UINT32SWAP(0L, 4294967295L),
	INT32SWAPSWAP(Integer.MIN_VALUE, Integer.MAX_VALUE),
	UINT32SWAPSWAP(0L, 4294967295L),
	FLOAT32(null, null),
	FLOAT32SWAP(null, null),
	INT64(Long.MIN_VALUE, Long.MAX_VALUE),
	UINT64(0L, null),
	INT64SWAP(Long.MIN_VALUE, Long.MAX_VALUE),
	UINT64SWAP(0L, null),
	FLOAT64(null, null),
	FLOAT64SWAP(null, null),
	BCD16(0, 9999),
	BCD32(0, 99999999),
	BCD32SWAP(0, 99999999),
	CHARSTRING(null, null),
	VARCHARSTRING(null, null),
	INT32M10K(-327680000, 327670000),
	UINT32M10K(0, 655350000), 
	INT32M10KSWAP(-327680000, 327670000), 
	UINT32M10KSWAP(0, 655350000);

	
	private Long lowerBound;
	private Long upperBound;
	
	private DataType(int lowerBound, int upperBound) {
		this.lowerBound = (long) lowerBound;
		this.upperBound = (long) upperBound;
	}
	
	private DataType(Long lowerBound, Long upperBound) {
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
	}
	
	
	public boolean isFloat() {
		return (this == FLOAT32 || this == FLOAT32SWAP || this == FLOAT64 || this == FLOAT64SWAP);
	}

	public boolean isString() {
		return (this == CHARSTRING || this == VARCHARSTRING);
	}
	
	public boolean checkBounds(Number n) {
		return (lowerBound == null || n.longValue() >= lowerBound) && (upperBound == null || n.longValue() <= upperBound);
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
