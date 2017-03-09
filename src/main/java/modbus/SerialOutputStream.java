package modbus;

import java.io.IOException;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jssc.SerialPort;

public class SerialOutputStream extends OutputStream {
	private final Logger LOGGER = LoggerFactory.getLogger(SerialOutputStream.class);
	private SerialPort port;

	public SerialOutputStream(SerialPort serialPort) {
		this.port = serialPort;
	}

	@Override
	public void write(int arg0) throws IOException {
		try {
			byte b = (byte) arg0;
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Writing byte: " + String.format("%02x", b));
			if ((port != null) && (port.isOpened())) {
				port.writeByte(b);
			}
		} catch (jssc.SerialPortException e) {
			throw new IOException(e);
		}

	}

	@Override
	public void flush() {
		if (LOGGER.isDebugEnabled())
			LOGGER.debug("Called no-op flush...");
		// Nothing yet
	}

}
