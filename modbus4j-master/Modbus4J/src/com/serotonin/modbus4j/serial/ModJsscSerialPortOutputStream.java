package com.serotonin.modbus4j.serial;

import java.io.IOException;

import jssc.SerialPort;

import com.serotonin.io.StreamUtils;
import com.serotonin.io.serial.JsscSerialPortOutputStream;
import com.serotonin.io.serial.SerialPortOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ModJsscSerialPortOutputStream extends SerialPortOutputStream {
	private final Log LOG = LogFactory.getLog(JsscSerialPortOutputStream.class);
    private SerialPort port;

    public ModJsscSerialPortOutputStream(SerialPort port) {
        this.port = port;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.io.OutputStream#write(int)
     */
    @Override
    public void write(int arg0) throws IOException {
        try {
            byte b = (byte) arg0;
        	if (LOG.isDebugEnabled())
                LOG.debug("Writing byte: " + String.format("%02x", b) );
            if ((port != null) && (port.isOpened())) {
                port.writeByte(b);
            }
        }
        catch (jssc.SerialPortException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void flush() {
    	if (LOG.isDebugEnabled())
            LOG.debug("Called no-op flush...");
        //Nothing yet
    }
	

	@Override
	public void write(byte[] b) throws IOException {
		System.out.println("Hi from inside modded jssc output stream");
	    try {
	        if (LOG.isDebugEnabled()) {
	            LOG.debug("Writing bytes: " + StreamUtils.dumpHex(b, 0, b.length));
	        }
	        if ((port != null) && (port.isOpened())) {
	        	System.out.println("writing, theoretically");
	        	this.port.purgePort(SerialPort.PURGE_TXCLEAR | SerialPort.PURGE_RXCLEAR);
	            port.writeBytes(b);
	        }
	    } catch (jssc.SerialPortException e) {
	        throw new IOException(e);
	    }
	}
	
}
