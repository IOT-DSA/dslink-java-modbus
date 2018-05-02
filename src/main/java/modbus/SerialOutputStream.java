package modbus;

import java.io.IOException;
import java.io.OutputStream;

import jssc.SerialPort;

public class SerialOutputStream extends OutputStream {
	private SerialPort port;

	public SerialOutputStream(SerialPort serialPort) {
		this.port = serialPort;
	}

	@Override
	public void write(int arg0) throws IOException {
		try {
			byte b = (byte) arg0;
			if ((port != null) && (port.isOpened())) {
				port.writeByte(b);
			}
		} catch (jssc.SerialPortException e) {
			throw new IOException(e);
		}

	}

	@Override
	public void flush() {
		// Nothing yet
	}

}
