package com.serotonin.io.serial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import jssc.SerialNativeInterface;
import jssc.SerialPortList;

import com.serotonin.modbus4j.serial.ModJsscSerialPortProxy;

public class ModSerialUtils {
	//Port List 
    private static List<String> ownedPortIdentifiers = Collections.synchronizedList(new ArrayList<String>());

    /**
     * Get a list of all available COMM Ports
     * 
     * @return
     * @throws CommPortConfigException
     */
    public static List<CommPortProxy> getCommPorts() throws CommPortConfigException {
        try {
            List<CommPortProxy> ports = new LinkedList<CommPortProxy>();
            String[] portNames;
            
            switch(SerialNativeInterface.getOsType()){
            	case SerialNativeInterface.OS_LINUX:
            		portNames = SerialPortList.getPortNames(Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
                break;
            	case SerialNativeInterface.OS_MAC_OS_X:
                    portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*")); //Was "tty.(serial|usbserial|usbmodem).*")
                break;
                default:
                	 portNames = SerialPortList.getPortNames();
                break;
            }
            
            for (String portName : portNames) {
                CommPortIdentifier id = new CommPortIdentifier(portName, false);
                ports.add(new CommPortProxy(id));
            }

            return ports;
        }
        catch (UnsatisfiedLinkError e) {
            throw new CommPortConfigException(e.getMessage());
        }
        catch (NoClassDefFoundError e) {
            throw new CommPortConfigException(
                    "Comm configuration error. Check that rxtx DLL or libraries have been correctly installed.");
        }
    }

    /**
     * Safely open a serial port ensuring no one else already has (All open methods MUST use this call for this to work)
     * 
     * @param serialParameters
     * @return
     * @throws SerialPortException
     */
    public static SerialPortProxy openSerialPort(SerialParameters serialParameters) throws SerialPortException {
        SerialPortProxy serialPort = null;
        System.out.println("Hi from modded serial utils");
        try {
            //Check to see if this port is already owned
            if (portOwned(serialParameters.getCommPortId()))
                throw new SerialPortException("Port In Use: " + serialParameters.getCommPortId());

            // Open the serial port.
            serialPort = new ModJsscSerialPortProxy(serialParameters);
            serialPort.open();

            synchronized (ownedPortIdentifiers) {
                ownedPortIdentifiers.add(serialPort.getParameters().getCommPortId());
            }
        }
        catch (Exception e) {
            // Wrap all exceptions
            if (e instanceof SerialPortException)
                throw (SerialPortException) e;
            throw new SerialPortException(e);
        }

        return serialPort;
    }

    /**
     * Check our list to see if the port is open
     * 
     * @param commPortId
     * @return
     */
    public static boolean portOwned(String commPortId) {
        synchronized (ownedPortIdentifiers) {
            for (String identifier : ownedPortIdentifiers) {
                if (identifier.equals(commPortId))
                    return true;
            }
            return false;
        }
    }

    /**
     * Safely close a COMM Port
     * 
     * @param serialPort
     * @throws SerialPortException
     */
    public static void close(SerialPortProxy serialPort) throws SerialPortException {
        if (serialPort != null) {
            serialPort.close();

            synchronized (ownedPortIdentifiers) {
                ownedPortIdentifiers.remove(serialPort.getParameters().getCommPortId());
            }
        }
    }
	
}
