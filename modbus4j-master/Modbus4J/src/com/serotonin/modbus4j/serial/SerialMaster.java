/*
 * ============================================================================
 * GNU General Public License
 * ============================================================================
 *
 * Copyright (C) 2006-2011 Serotonin Software Technologies Inc. http://serotoninsoftware.com
 * @author Matthew Lohbihler
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.modbus4j.serial;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.serotonin.io.serial.SerialParameters;
import com.serotonin.io.serial.SerialPortException;
import com.serotonin.io.serial.SerialPortProxy;
import com.serotonin.io.serial.SerialUtils;
import com.serotonin.messaging.EpollStreamTransportCharSpaced;
import com.serotonin.messaging.StreamTransportCharSpaced;
import com.serotonin.messaging.Transport;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;

abstract public class SerialMaster extends ModbusMaster {
	
	
	private final Log LOG = LogFactory.getLog(SerialMaster.class);
	//These options are no longer supported as they were originally a hack that didn't work right anyway
	@Deprecated
    public static final int SYNC_TRANSPORT = 1;
	@Deprecated
    public static final int SYNC_SLAVE = 2;
	@Deprecated
    public static final int SYNC_FUNCTION = 3;

    //
    // Configuration fields.
    private final SerialParameters serialParameters;
    protected long characterSpacing; //Time in ns
    
    // Runtime fields.
    protected SerialPortProxy serialPort;
    protected Transport transport;

    
    
    public SerialMaster(SerialParameters params) {
        serialParameters = params;
    }

    @Override
    public void init() throws ModbusInitException {
        try {
            serialPort = SerialUtils.openSerialPort(serialParameters);
            
            
            if (getePoll() != null)
                transport = new EpollStreamTransportCharSpaced(serialPort.getInputStream(), serialPort.getOutputStream(),
                        getePoll(), this.characterSpacing);
            else
                transport = new StreamTransportCharSpaced(serialPort.getInputStream(), serialPort.getOutputStream(), this.characterSpacing);
        }
        catch (Exception e) {
            throw new ModbusInitException(e);
        }
    }

    public void close() {
        try {
			SerialUtils.close(serialPort);
		} catch (SerialPortException e) {
			LOG.error(e.getMessage(), e);
		}
    }
}
