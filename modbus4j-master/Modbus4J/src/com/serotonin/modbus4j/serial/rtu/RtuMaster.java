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

import java.io.IOException;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.io.serial.SerialParameters;
import com.serotonin.messaging.MessageControl;
import com.serotonin.messaging.StreamTransport;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.serial.SerialMaster;
import com.serotonin.modbus4j.serial.SerialWaitingRoomKeyFactory;

public class RtuMaster extends SerialMaster {
	
    // Runtime fields.
    private MessageControl conn;
    private long lastSendTime; //Last time sent (Nano-time, not wall clock time)
    private long messageFrameSpacing; //Time in ns
    
    /**
     * For legacy purposes, create RTU Master and
     * compute the character and message frame spacing
     * @param params
     */
    public RtuMaster(SerialParameters params){
    	this(params, true);
    }

    /**
     * Create an RTU Master with specified frame and character spacing times
     * 
     * @param params
     * @param characterSpacingNs
     * @param messageFrameSpacingNs
     */
    public RtuMaster(SerialParameters params, long characterSpacingNs, long messageFrameSpacingNs) {
        super(params);
        this.characterSpacing = characterSpacingNs;
        this.messageFrameSpacing = messageFrameSpacingNs;
    }
    
    /**
     * Create an RTU Master with the option of computing the default spacing based on
     * the SerialParameters or use 0 spacing.
     * @param params
     * @param useDefaultSpacing - true to compute spacing, false to use no spacing.
     */
    public RtuMaster(SerialParameters params, boolean useDefaultSpacing) {
        super(params);

        if(useDefaultSpacing){
        	this.messageFrameSpacing = computeMessageFrameSpacing(params);
        	this.characterSpacing = computeCharacterSpacing(params);
        }else{
        	this.messageFrameSpacing = 0l;
        	this.characterSpacing = 0l;
        }

    }

    @Override
    public void init() throws ModbusInitException {
        super.init();

        RtuMessageParser rtuMessageParser = new RtuMessageParser(true);
        conn = getMessageControl();
        try {
            conn.start(transport, rtuMessageParser, null, new SerialWaitingRoomKeyFactory());
            if (getePoll() == null)
                ((StreamTransport) transport).start("Modbus RTU master");
        }
        catch (IOException e) {
            throw new ModbusInitException(e);
        }
        initialized = true;
    }

    @Override
    public void destroy() {
        closeMessageControl(conn);
        super.close();
    }

    @Override
    public ModbusResponse sendImpl(ModbusRequest request) throws ModbusTransportException {
        // Wrap the modbus request in an rtu request.
        RtuMessageRequest rtuRequest = new RtuMessageRequest(request);

        // Send the request to get the response.
        RtuMessageResponse rtuResponse;
        try {
            //Wait frame spacing time
            long waited = System.nanoTime() - this.lastSendTime;
            if (waited < this.messageFrameSpacing) {
                Thread.sleep(this.messageFrameSpacing / 1000000, (int) (this.messageFrameSpacing % 1000000));
            }
            rtuResponse = (RtuMessageResponse) conn.send(rtuRequest);
            if (rtuResponse == null)
                return null;
            return rtuResponse.getModbusResponse();
        }
        catch (Exception e) {
            throw new ModbusTransportException(e, request.getSlaveId());
        }
        finally {
            //Update our last send time
            this.lastSendTime = System.nanoTime();
        }
    }
    
    /**
     * RTU Spec: 
     * For baud > 19200 
     * Message Spacing: 1.750uS
     * 
     * For baud < 19200
     * Message Spacing: 3.5 * char time
     * 
     * @param params
     * @return
     */
    public static long computeMessageFrameSpacing(SerialParameters params){
        //For Modbus Serial Spec, Message Framing rates at 19200 Baud are fixed
        if (params.getBaudRate() > 19200) {
            return 1750000l; //Nanoseconds
        }
        else {
        	float charTime = computeCharacterTime(params);
            return (long) (charTime * 3.5f);
        }
    }

    /**
     * RTU Spec: 
     * For baud > 19200 
     * Char Spacing: 750uS 
     * 
     * For baud < 19200
     * Char Spacing: 1.5 * char time
     * 
     * @param params
     * @return
     */
    public static long computeCharacterSpacing(SerialParameters params){
        //For Modbus Serial Spec, Message Framing rates at 19200 Baud are fixed
        if (params.getBaudRate() > 19200) {
            return 750000l; //Nanoseconds
        }
        else {
        	float charTime = computeCharacterTime(params);
            return (long) (charTime * 1.5f);
        }
    }

    
    /**
     * Compute the time it takes to transmit 1 character with 
     * the provided Serial Parameters.
     * 
     * RTU Spec: 
     * For baud > 19200 
     * Char Spacing: 750uS 
     * Message Spacing: 1.750uS
     * 
     * For baud < 19200
     * Char Spacing: 1.5 * char time
     * Message Spacing: 3.5 * char time
     * 
     * @param params
     * @return time in nanoseconds
     */
    public static float computeCharacterTime(SerialParameters params){
        //Compute the char size
        float charBits = params.getDataBits();
        switch (params.getStopBits()) {
        case 1:
            //Strangely this results in 0 stop bits.. in JSSC code
            break;
        case 2:
            charBits += 2f;
            break;
        case 3:
            //1.5 stop bits
            charBits += 1.5f;
            break;
        default:
            throw new ShouldNeverHappenException("Unknown stop bit size: " + params.getStopBits());
        }

        if (params.getParity() > 0)
            charBits += 1; //Add another if using parity

        //Compute ns it takes to send one char
        // ((charSize/symbols per second) ) * ns per second
        return (charBits / params.getBaudRate()) * 1000000000f;
    }
}
