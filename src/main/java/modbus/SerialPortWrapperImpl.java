package modbus;

import java.io.InputStream;
import java.io.OutputStream;
import jssc.SerialPort;

import com.serotonin.modbus4j.serial.SerialPortWrapper;

public class SerialPortWrapperImpl implements SerialPortWrapper {
	private final SerialPort port;
	private SerialInputStream is;
	private SerialOutputStream os;
	private int baudRate;
	private int dataBits;
	private int stopBits;
	private int parity;

	public SerialPortWrapperImpl(String portName, int baudRate, int dataBits, int stopBits, int parity) {
		port = new SerialPort(portName);
		this.baudRate = baudRate;
		this.dataBits = dataBits;
		this.stopBits = stopBits;
		this.parity = parity;
	}

	@Override
	public void close() throws Exception {
		port.closePort();
	}

	@Override
	public void open() throws Exception {
		port.openPort();
		port.setParams(this.getBaudRate(), this.getDataBits(), this.getStopBits(), this.getParity());
		port.setFlowControlMode(this.getFlowControlIn() | this.getFlowControlOut());

		this.os = new SerialOutputStream(port);
		this.is = new SerialInputStream(port);
	}

	@Override
	public InputStream getInputStream() {
		return is;
	}

	@Override
	public OutputStream getOutputStream() {
		return os;
	}

	@Override
	public int getBaudRate() {
		return baudRate;
	}

	@Override
	public int getFlowControlIn() {
		return SerialPort.FLOWCONTROL_NONE;
	}

	@Override
	public int getFlowControlOut() {
		return SerialPort.FLOWCONTROL_NONE;
	}

	@Override
	public int getDataBits() {
		return dataBits;
	}

	@Override
	public int getStopBits() {
		return stopBits;
	}

	@Override
	public int getParity() {
		return parity;
	}

}
