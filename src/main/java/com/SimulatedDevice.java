package com;

import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.stream.JsonReader;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubMessageResult;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.MessageCallback;
import com.microsoft.azure.sdk.iot.device.MessageType;

//import com.Models.CloudCommand;
//import com.Models.DynamicStatus;
//import com.Models.StaticInformation;

import java.io.IOException;
import java.io.RandomAccessFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URL;

import java.util.List;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URISyntaxException;

public class SimulatedDevice {

	private static String connString = "";
	private static int MsgSendInterval = 15 * 1000;
	private static IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;
	private static DeviceClient ModuleTest_Solar_Inverter;
	private static MessageCallback callback;
	private static String devicePublicIp = "";
	private static String chargerIdString = "";
	private static LinkedTreeMap<String, String> staticResult;
	private static String staticFileName = "/home/root/PC-LCD/CloudStaticInformation.json";
	private static String dynamicFileName = "/home/root/PC-LCD/DynamicStatus.json";
	private static String cloudInputPipe = "/home/root/PC-LCD/cloudin";

	private static final Map<String, String> consolCommands = new HashMap<String, String>() {
		{
			put("createTunnel",
					"/home/root/PC-LCD/OpenTunnel.sh enable -d");
			put("closeTunnel", "/home/root/PC-LCD/OpenTunnel.sh disable -d");
		}
	};
	private static Gson gsonTool = new Gson();

	private static class EventCallback implements IotHubEventCallback {
		public void execute(IotHubStatusCode status, Object context) {
			System.out.println("response: " + status.name());

			if (context != null) {
				synchronized (context) {
					context.notify();
				}
			}
		}
	}

	private static class MessageSender extends Models implements Runnable {

		public void run() {
			try {

				StaticInformation staticInfo = new StaticInformation();
				staticInfo.ChargerId = chargerIdString;
				staticInfo.ChargerName = staticResult.get("ChargerName");
				staticInfo.ChargerSerialNumber = staticResult.get("ChargerSerialNumber");
				staticInfo.Model = staticResult.get("Model");
				staticInfo.SoftwareVersion = staticResult.get("SoftwareVersion");
				staticInfo.HardwareVersion = staticResult.get("HardwareVersion");
				staticInfo.DeliveryStatus = staticResult.get("DeliveryStatus");
				staticInfo.Country = staticResult.get("Country");
				staticInfo.City = staticResult.get("City");
				staticInfo.Address = staticResult.get("Address");
				staticInfo.CommissionedDate = staticResult.get("CommissionedDate");
				staticInfo.NumberOfOutlets = staticResult.get("NumberOfOutlets");
				staticInfo.NumberOfPowerModules = staticResult.get("NumberOfPowerModules");
				staticInfo.PaymentTerminalType = staticResult.get("PaymentTerminalType");
				staticInfo.Dealer = staticResult.get("Dealer");
				String msgStr = staticInfo.serialize();
				Message msg = new Message(msgStr);
				msg.setMessageType(MessageType.DEVICE_TELEMETRY);
				msg.setContentTypeFinal("application/json");
				msg.setExpiryTime(1000);
				System.out.println("Sending staticInformation:" + msgStr);
				Object lockobjstatic = new Object();

				EventCallback callback = new EventCallback();
				ModuleTest_Solar_Inverter.sendEventAsync(msg, callback, lockobjstatic);

				while (true) {

					try {
						Gson gson = new Gson();

						DynamicStatus dynamicResult = null;

						try {
							dynamicResult = gson.fromJson(new FileReader(dynamicFileName), DynamicStatus.class);
						} catch (Exception e) {
							System.out.println(e.getMessage());
						}
						if (dynamicResult != null) {
							dynamicResult.IpAddress = devicePublicIp;
							dynamicResult.ChargerId = chargerIdString;
							String msgStrDynamic = dynamicResult.serialize();
							Message msgDynamic = new Message(msgStrDynamic);
							msgDynamic.setMessageType(MessageType.DEVICE_TELEMETRY);
							msgDynamic.setContentTypeFinal("application/json");
							msg.setExpiryTime(1000);
							System.out.println("Sending dynamicInformation:" + msgStrDynamic);
							Object lockobjDynamic = new Object();
							ModuleTest_Solar_Inverter.sendEventAsync(msgDynamic, callback, lockobjDynamic);

							synchronized (lockobjDynamic) {
								lockobjDynamic.wait();
							}
						}
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}

					Thread.sleep(MsgSendInterval);
				}

			} catch (InterruptedException e) {
				System.out.println("Finished.");
			}
		}
	}

	public static Object JsonReader(String fileName) throws IOException {

		try {

			JsonReader reader = new JsonReader(new FileReader(fileName));
			Object info = gsonTool.fromJson(reader, Object.class);
			return info;

		} catch (Exception e) {

			return "";
		}

	}

	private static class AppMessageCallback extends Models implements MessageCallback {
		//Processing message sent from cloud(Tom).
		public IotHubMessageResult execute(Message msg, Object context) {
			String msgString = new String(msg.getBytes());
			System.out.println("Received message from cloud: " + msgString);
		//Invoking the .sh script to open port.
			CloudCommand cmd = gsonTool.fromJson(msgString, CloudCommand.class);
			if (consolCommands.containsKey(cmd.Command)) {
				System.out.println("executing commands" + cmd.Command);
				String threadName = Thread.currentThread().getName();
				System.out.println(cmd.Command + " " + threadName);
				String command = consolCommands.get(cmd.Command);
				if (!command.isEmpty()) {
					try {
						String line;
						Process process = Runtime.getRuntime().exec(command);
						BufferedReader in = new BufferedReader(
								new InputStreamReader(process.getInputStream()) );
						while ((line = in.readLine()) != null) {
							System.out.println(line);
						}
						int exitCode = process.waitFor();
						assert exitCode == 0;
//						System.out.println("executing..." + command);
//						Runtime.getRuntime().exec(command);

					} catch (IOException | InterruptedException e) {
						System.out.println("failed executing..." + command);
						e.printStackTrace();
					}
					return IotHubMessageResult.COMPLETE;
				}
				return IotHubMessageResult.COMPLETE;
			} else {
				try {
					System.out.println("ReceivedMsg" + msgString);
					System.out.println("Writing message to cloudin pipe" + msgString);
					boolean result = WriteToPipe.Write(msgString, cloudInputPipe);
					if (result) {
						System.out.println("Successfully executed cloud command:" + msgString);
						return IotHubMessageResult.COMPLETE;
					} else {
						System.out.println("Failed to executed cloud command:" + msgString);
						return IotHubMessageResult.REJECT;
					}
				} catch (IOException e) {
					System.out.println("Failed to executed cloud command:" + msgString);
					e.printStackTrace();
					return IotHubMessageResult.REJECT;
				}
			}

		}
	}

	public static void Execute(Boolean allowshutdown) throws IOException, URISyntaxException, InterruptedException {
		//Check the data in file CloudStaticInformation.json to confirm if this is the right charger.
		System.out.println("Initilizing...");
		String iotConnectionString = connString;
		try {
			staticResult = (LinkedTreeMap<String, String>) JsonReader(staticFileName);
		} catch (Exception e) {
			System.out.println("Static file is missing or in wrong format. Failed to continue.");
			System.exit(0);
		}

		if (staticResult != null) {
			String fetchedConnectionString = staticResult.get("ConnectionString");
			String chargerId = staticResult.get("ChargerId");

			if (chargerId == null || chargerId.isEmpty()) {
				chargerId = staticResult.get("ChargerSerialNumber");
			}

			if (fetchedConnectionString != null && fetchedConnectionString != "") {
				iotConnectionString = fetchedConnectionString;
				chargerIdString = chargerId;
			}

			String fetchedId = StringUtils.substringBetween(fetchedConnectionString, "DeviceId=", ";SharedAccessKey");
			if (!fetchedId.equals(chargerId)) {
				System.out.println("Self shutdown due to wrong serialnumber");
				System.exit(0);
			}
		}

		else {
			System.out.println("Static file is missing or in wrong format. Failed to continue.");
			System.exit(0);
		}
		System.out.println("Waiting for internet connection...");
		//Check if network is available, if not, retry in 3000ms.
		while (true) {
			if (IpService.NetIsAvailable()) {
				break;
			} else {
				System.out.println("No connection yet.., retry...");
				Thread.sleep(3000);
			}
		}
		//Get the ip address of this charger.
		devicePublicIp = IpService.GetPublicIp();
		//Connect to the Iot hub.
		ModuleTest_Solar_Inverter = new DeviceClient(iotConnectionString, protocol);
		callback = new AppMessageCallback();
		ModuleTest_Solar_Inverter.setMessageCallback(callback, null);
		ModuleTest_Solar_Inverter.open();

		// Create new thread and start sending messages.
		System.out.println("Starting client...");
		MessageSender sender = new MessageSender();
		ExecutorService executor = Executors.newFixedThreadPool(1);
		executor.execute(sender);

		System.out.println("allow shut down is " + allowshutdown);
		//Stop the application
		if (allowshutdown) {
			System.out.println("Press ENTER to exit.");
			System.in.read();
			System.out.println("shutting down...");
			executor.shutdownNow();
			ModuleTest_Solar_Inverter.closeNow();
			System.exit(0);
		}

	}

	private static class WriteToPipe {
		public static boolean Write(String input, String path) throws IOException {
			try {
				if (input != null && !input.trim().isEmpty() && path != null && !path.trim().isEmpty()) {
					RandomAccessFile pipe = new RandomAccessFile(path, "rw");
					pipe.write(input.getBytes());
					pipe.close();
					System.out.println("successfully pushed message to pipe");
					return true;
				}
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
			return false;
		}

	}

	private static class IpService {

		public static String GetPublicIp() throws IOException {
			URL whatismyip = new URL("http://checkip.amazonaws.com");
			BufferedReader in = null;
			try {
				in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
				String ip = in.readLine();
				return ip;
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}

		public static boolean NetIsAvailable() throws IOException {
			Socket socket = null;
			boolean reachable = false;
			String hostname = "www.google.com";
			try {
				socket = new Socket(hostname, 443);
				if (socket.isConnected()) {
					socket.close();
					reachable = true;
				}

			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (!socket.isClosed()) {
					socket.close();
				}
			}

			return reachable;
		}
	}

	public static class Models {

		public static class TelemetryDataPoint {
			public double current;
			public double temperature;
			public double voltage;
			public String deviceId;

			// Serialize object to JSON format.
			public String serialize() {
				Gson gson = new Gson();
				return gson.toJson(this);
			}
		}

		public static class StaticInformation {

			public String MsgType = "static";

			public String ChargerId;

			public String Model;

			public String SoftwareVersion;

			public String HardwareVersion;

			public String DeliveryStatus;

			public String Country;

			public String City;

			public String Address;

			public String CommissionedDate;

			public String NumberOfOutlets;

			public String NumberOfPowerModules;

			public String PaymentTerminalType;

			public String PaymentTerminalSoftwareVersion;

			public String PaymentTerminalHardwareVersion;

			public String TimeStamp;

			public String ConnectionString;

			public String Dealer;

			public String ChargerSerialNumber;

			public String ChargerName;

			public String serialize() {
				Gson gson = new Gson();
				return gson.toJson(this);
			}
		}

		public static class DynamicInformation {

			public String MsgType = "dynamic";
			public String ChargerId;
			public String SystemStatus;
			public String TimeStamp;
			public List<Plug> Plugs;
			public String IpAddress;

			public String serialize() {
				Gson gson = new Gson();
				return gson.toJson(this);
			}
		}

		public static class DynamicStatus {
			public String MsgType = "dynamic";
			public String ChargerId;
			public String SystemStatus;
			public String TimeStamp;
			public String IpAddress;
			public List<PlugStatus> Plugs;

			public String serialize() {
				Gson gson = new Gson();
				return gson.toJson(this);
			}
		}

		public static class PlugStatus {
			public String PlugId;

			public String Status;

			public String ErrorCode;

			public String MaxActivePower;

			public List<MeterValueSet> MeterValue;

		}

		public static class MeterValueSet {
			public String value;

			public String format;

			public String measurand;

			public String unit;

		}

		public static class Plug {
			public String PlugId;

			public String Status;

			public String ErrorCode;

			public String OutputCurrent;

			public String OutputVoltage;

			public String SOC;

		}

		public static class CloudCommand {
			public String ChargerId;

			public String Command;

			public String TimeStamp;
		}
	}



	public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
//			Boolean allowShutdown = false;
			//Enter "debug" to make allowShutdown true.
//			if (args.length == 1 && args[0].equals("debug")) {
//				allowShutdown = true;
			Boolean allowShutdown = true;
//			}
			SimulatedDevice.Execute(allowShutdown);
	}



}

