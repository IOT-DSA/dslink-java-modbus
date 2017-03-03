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

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;

@SuppressWarnings("unused")
public class Main extends DSLinkHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		// args = new String[] { "-b", "http://localhost:8080/conn", "-l",
		// "debug" };
		DSLinkFactory.start(args, new Main());
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

	@Override
	public void stop() {
		ModbusLink ml = ModbusLink.get();
		for (ModbusMaster master : ml.masters) {
			try {
				LOGGER.info("destroying master");
				master.destroy();
			} catch (Exception e) {
				LOGGER.debug("Error destroying master: ", e);
			}
		}
	}
}
