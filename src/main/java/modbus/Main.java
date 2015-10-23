package modbus;

import java.io.File;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.SerializationManager;
import org.dsa.iot.dslink.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.io.serial.SerialParameters;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.serial.ModSerialParameters;

@SuppressWarnings("unused")
public class Main extends DSLinkHandler {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
	
	public static void main(String[] args) {
			//args = new String[] { "-b", "http://localhost:8080/conn", "-l", "debug" };
			DSLinkFactory.start(args, new Main());
			
			
//		SerialParameters serialParameters = new SerialParameters();
//        serialParameters.setCommPortId("COM3");
//        serialParameters.setBaudRate(19200);
//        serialParameters.setParity(2);
//
//        ModbusMaster master = new ModbusFactory().createRtuMaster(serialParameters);
//        master.setTimeout(5000);
//        master.setRetries(0);
//        try {
//			master.init();
//		} catch (ModbusInitException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//
//
//            long start = System.currentTimeMillis();
//            //System.out.print("Testing " + "117" + "... ");
//            //System.out.println(master.testSlaveNode(117));
//            try {
//				master.send(new ReadCoilsRequest(117, 2000, 1));
//			} catch (ModbusTransportException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//            System.out.println("Time: " + (System.currentTimeMillis() - start));
//            
//
//        // try {
//        // System.out.println(master.send(new ReadHoldingRegistersRequest(1, 0, 1)));
//        // }
//        // catch (Exception e) {
//        // e.printStackTrace();
//        // }
//
//        // try {
//        // // ReadCoilsRequest request = new ReadCoilsRequest(2, 65534, 1);
//        // ReadHoldingRegistersResponse response = (ReadHoldingRegistersResponse) master
//        // .send(new ReadHoldingRegistersRequest(2, 0, 1));
//        // System.out.println(response);
//        // }
//        // catch (Exception e) {
//        // e.printStackTrace();
//        // }
//
//        // System.out.println(master.scanForSlaveNodes());
//
//        master.destroy();
//        try {
//			master.init();
//		} catch (ModbusInitException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//
//
//            start = System.currentTimeMillis();
//            //System.out.print("Testing " + "117" + "... ");
//            //System.out.println(master.testSlaveNode(117));
//            try {
//				master.send(new ReadCoilsRequest(117, 2000, 1));
//			} catch (ModbusTransportException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//            System.out.println("Time: " + (System.currentTimeMillis() - start));
//            master.destroy();
		}
	
	@Override
	public boolean isResponder() {
		return true;
	}
	
	@Override
	public void onResponderInitialized(DSLink link) {
		LOGGER.info("Initialized");
		
		NodeManager manager = link.getNodeManager();
		Serializer copyser = new Serializer(manager);
		Deserializer copydeser = new Deserializer(manager);
        Node superRoot = manager.getNode("/").getNode();
		ModbusLink.start(superRoot, copyser, copydeser);
	}
}
