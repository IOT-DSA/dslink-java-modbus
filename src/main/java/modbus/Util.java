package modbus;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonArray;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.NumericLocator;
import com.serotonin.modbus4j.locator.StringLocator;
import com.serotonin.modbus4j.msg.ReportSlaveIdRequest;

import jssc.SerialNativeInterface;
import jssc.SerialPortList;

public class Util {
	
	public static boolean pingModbusSlave(ModbusMaster master, int slaveId) {
		if (master.testSlaveNode(slaveId)) {
			return true;
		}
		try {
			master.send(new ReportSlaveIdRequest(slaveId));
		} catch (ModbusTransportException e) {
			return false;
		}
		return true;
	}

	public static String[] getCommPorts() {
		String[] portNames;

		switch (SerialNativeInterface.getOsType()) {
		case SerialNativeInterface.OS_LINUX:
			portNames = SerialPortList
					.getPortNames(Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
			break;
		case SerialNativeInterface.OS_MAC_OS_X:
			portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*")); // Was
																						// "tty.(serial|usbserial|usbmodem).*")
			break;
		default:
			portNames = SerialPortList.getPortNames();
			break;
		}

		return portNames;

	}

	public static <E> String[] enumNames(Class<E> enumData) {
		String valuesStr = Arrays.toString(enumData.getEnumConstants());
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}

	private static short[] concatArrays(short[] arr1, short[] arr2) {
		int len1 = arr1.length;
		int len2 = arr2.length;
		short[] retval = new short[len1 + len2];
		System.arraycopy(arr1, 0, retval, 0, len1);
		System.arraycopy(arr2, 0, retval, len1, len2);
		return retval;
	}

	protected static boolean[] makeBoolArr(JsonArray jarr) throws Exception {
		boolean[] retval = new boolean[jarr.size()];
		for (int i = 0; i < jarr.size(); i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Boolean))
				throw new Exception("not a boolean array");
			else
				retval[i] = (Boolean) o;
		}
		return retval;
	}

	protected static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling, double addscaling, PointType pt,
			int slaveid, int offset, int numRegisters, int bitnum) throws Exception {
		short[] retval = {};
		Integer dtint = DataType.getDataTypeInt(dt);
		if (dtint != null) {
			if (!dt.isString()) {
				NumericLocator nloc = new NumericLocator(slaveid, PointType.getPointTypeInt(pt), offset, dtint);
				for (int i = 0; i < jarr.size(); i++) {
					Object o = jarr.get(i);
					if (!(o instanceof Number))
						throw new Exception("not a numeric array");
					Number n = ((Number) o).doubleValue() * scaling - addscaling;
					retval = concatArrays(retval, nloc.valueToShorts(n));
				}
			} else {
				Object o = jarr.get(0);
				if (!(o instanceof String))
					throw new Exception("not a string");
				String str = (String) o;
				StringLocator sloc = new StringLocator(slaveid, PointType.getPointTypeInt(pt), offset, dtint,
						numRegisters);
				retval = sloc.valueToShorts(str);
			}
		} else if (dt == DataType.BOOLEAN) {
			retval = new short[(int) Math.ceil((double) jarr.size() / 16)];
			for (int i = 0; i < retval.length; i++) {
				short element = 0;
				for (int j = 0; j < 16; j++) {
					int bit = 0;
					if (j == bitnum) {
						Object o = jarr.get(i);
						if (!(o instanceof Boolean))
							throw new Exception("not a boolean array");
						if ((Boolean) o)
							bit = 1;
					} else if (bitnum == -1 && i + j < jarr.size()) {
						Object o = jarr.get(i + j);
						if (!(o instanceof Boolean))
							throw new Exception("not a boolean array");
						if ((Boolean) o)
							bit = 1;
					}
					element = (short) (element & (bit << (15 - j)));
					jarr.get(i + j);
				}
				retval[i] = element;
			}
			return retval;
		} else if (dt == DataType.INT32M10K || dt == DataType.UINT32M10K || dt == DataType.INT32M10KSWAP
				|| dt == DataType.UINT32M10KSWAP) {
			retval = new short[2 * jarr.size()];
			for (int i = 0; i < jarr.size(); i++) {
				Object o = jarr.get(i);
				if (!(o instanceof Number))
					throw new Exception("not an int array");
				Number n = ((Number) o).doubleValue() * scaling - addscaling;
				long aslong = n.longValue();
				if (dt == DataType.INT32M10K || dt == DataType.UINT32M10K) {
					retval[i * 2] = (short) (aslong / 10000);
					retval[(i * 2) + 1] = (short) (aslong % 10000);
				} else {
					retval[i * 2] = (short) (aslong % 10000);
					retval[(i * 2) + 1] = (short) (aslong / 10000);
				}

			}
		}
		return retval;
	}

	static int toUnsignedInt(short x) {
		return ((int) x) & 0xffff;
	}

	static long toUnsignedLong(int x) {
		return ((long) x) & 0xffffffffL;
	}

	protected static Double getDoubleValue(Value val) {
		double ret = 0.0;
		{
			if (val.getType() == ValueType.STRING) {
				ret = Double.parseDouble(val.getString());
			} else if (val.getType() == ValueType.NUMBER) {
				ret = val.getNumber().doubleValue();
			}
		}
		return ret;
	}

	protected static int getIntValue(Value val) {
		int ret = 0;
		{
			if (val.getType() == ValueType.STRING) {
				ret = Integer.parseInt(val.getString());
			} else if (val.getType() == ValueType.NUMBER) {
				ret = val.getNumber().intValue();
			}
		}
		return ret;
	}
}
