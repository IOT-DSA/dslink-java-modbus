package com.serotonin.modbus4j.serial;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jssc.SerialPort;

import com.serotonin.io.serial.JsscSerialPortInputStream;
import com.serotonin.io.serial.JsscSerialPortOutputStream;
import com.serotonin.io.serial.JsscSerialPortProxy;
import com.serotonin.io.serial.SerialParameters;
import com.serotonin.io.serial.SerialPortException;
import com.serotonin.io.serial.SerialPortInputStream;
import com.serotonin.io.serial.SerialPortOutputStream;
import com.serotonin.io.serial.SerialPortProxy;

@SuppressWarnings("unused")
public class ModJsscSerialPortProxy extends SerialPortProxy {
	Log LOG = LogFactory.getLog(JsscSerialPortProxy.class);

    private SerialPort port;
    private SerialPortOutputStream os;
    private SerialPortInputStream is;

    /**
     * @param serialParameters
     */
    public ModJsscSerialPortProxy(SerialParameters serialParameters) {
        super(serialParameters.getCommPortId());
        this.parameters = serialParameters;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#readBytes(int)
     */
    @Override
    public byte[] readBytes(int i) throws SerialPortException {
        try {
            return this.port.readBytes(i);
        }
        catch (jssc.SerialPortException e) {
            throw new SerialPortException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#writeInt(int)
     */
    @Override
    public void writeInt(int arg0) throws SerialPortException {
        try {
            this.port.writeInt(arg0);
        }
        catch (jssc.SerialPortException e) {
            throw new SerialPortException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#close()
     */
    @Override
    public void closeImpl() throws SerialPortException {
        Throwable ex = null;

        try {
            this.is.close();
        }
        catch (IOException e) {
            LOG.error(e);
            ex = e;
        }
        try {
            this.os.close();
        }
        catch (IOException e) {
            LOG.error(e);
            ex = e;
        }
        try {
            this.port.closePort();
        }
        catch (jssc.SerialPortException e) {
            LOG.error(e);
            ex = e;
        }

        if (ex != null)
            throw new SerialPortException(ex); //May miss some errors if > 1, but hey we get something back. 

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#open()
     */
    @Override
    public void openImpl() throws SerialPortException {

        try {
        	if (LOG.isDebugEnabled())
                LOG.debug("Opening Serial Port: " + this.parameters.getCommPortId());
        	
            this.port = new SerialPort(this.parameters.getCommPortId());

            this.port.openPort();
            this.port.setFlowControlMode(this.parameters.getFlowControlIn() | this.parameters.getFlowControlOut());
            this.port.setParams(parameters.getBaudRate(), parameters.getDataBits(), parameters.getStopBits(),
                    parameters.getParity());
            this.is = new JsscSerialPortInputStream(this.port, this.listeners);
            this.os = new ModJsscSerialPortOutputStream(this.port);

        }
        catch (jssc.SerialPortException e) {
            throw new SerialPortException(e);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#getInputStream()
     */
    @Override
    public SerialPortInputStream getInputStream() {
        return this.is;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.serotonin.io.serial.SerialPortProxy#getOutputStream()
     */
    @Override
    public SerialPortOutputStream getOutputStream() {
        return this.os;
    }

}
