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
package com.serotonin.modbus4j.serial.rtu;

import com.serotonin.messaging.IncomingRequestMessage;
import com.serotonin.messaging.OutgoingRequestMessage;
import com.serotonin.modbus4j.base.ModbusUtils;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.util.queue.ByteQueue;

/**
 * Handles the RTU enveloping of modbus requests.
 * 
 * @author mlohbihler
 */
public class RtuMessageRequest extends RtuMessage implements OutgoingRequestMessage, IncomingRequestMessage {
    static RtuMessageRequest createRtuMessageRequest(ByteQueue queue) throws ModbusTransportException {
        ModbusRequest request = ModbusRequest.createModbusRequest(queue);
        RtuMessageRequest rtuRequest = new RtuMessageRequest(request);

        // Check the CRC
        ModbusUtils.checkCRC(rtuRequest.modbusMessage, queue);

        // Return the data.
        return rtuRequest;
    }

    public RtuMessageRequest(ModbusRequest modbusRequest) {
        super(modbusRequest);
    }

    @Override
    public boolean expectsResponse() {
        return modbusMessage.getSlaveId() != 0;
    }

    public ModbusRequest getModbusRequest() {
        return (ModbusRequest) modbusMessage;
    }
}
