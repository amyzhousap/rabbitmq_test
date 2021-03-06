package com.sap.sme.occ.product;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.log.Logger;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

public class MQTest {

	private static Logger firstLogger;

	public static final int MQ_PORT = 5673; // 5672 for CD
	public static final String MQ_HOST = "localhost";// "rabbitmq"; for CD
	public static final int INTERNAL_HTTP_PORT = 58080;

	private static Connection mqConnection;
	private static Channel channel;

	public static Connection getMqConnection() {
		return mqConnection;
	}

	private static Map<String, List<String>> messages = new HashMap<String, List<String>>();

	public static void clearMessages() {
		messages.clear();
	}

	public static List<String> getMessages(String routingKey) {
		if (routingKey == null || routingKey.length() == 0) {
			return Collections.EMPTY_LIST;
		}
		Set<String> keys = messages.keySet();
		for (String key : keys) {
			if (key.toLowerCase().startsWith(routingKey.toLowerCase())) {
				String log = routingKey + " matches " + key;
				if (firstLogger != null) {
					firstLogger.info(log);
				}
				System.out.println(log);
				return messages.get(key);
			}
		}
		String log = routingKey + " has no matches";
		if (firstLogger != null) {
			firstLogger.info(log);
		}
		System.out.println(log);
		return Collections.EMPTY_LIST;
	}

	private static void putMessage(String routingKey, String message) {
		List<String> messageList = messages.get(routingKey);
		if (messageList == null) {
			messageList = new ArrayList<String>();
			messages.put(routingKey, messageList);
		}
		messageList.add(message);
	}

	public static void killAMQConnection() throws IOException {
		channel.close(0, "close amq connection as stopMQ() is called");
		channel.abort();
		mqConnection.close(0, "close amq connection as stopMQ() is called");
		mqConnection.abort();
	}

	/**
	 * by jmeter
	 * 
	 * @param logger
	 * @param vars
	 * @param routingKeys
	 * @return
	 * @throws Exception
	 */
	public static String startMQ(final Logger logger, final org.apache.jmeter.threads.JMeterVariables vars,
			String... routingKeys) throws Exception {
		String ret = "";
		boolean mqStarted = false;
		try {
			LightHttpServer.startHttpServer(INTERNAL_HTTP_PORT, logger);
		} catch (java.net.BindException e) {
			mqStarted = true;
			logger.info(e.getMessage(), e);
			ret = "reusing your last MQ connection, or you can call MQTest.stopMQ(log)/MQTest.clearMessage(log) when you finished one case, "
					+ "otherwise the message generated by your last test is cached.";
			logger.info(ret);
			// now try to clear old messages
			clearMessage(logger);
		} catch (Exception e) {
			logger.warn(e.getMessage(), e);
		}

		if (!mqStarted) {
			try {
				startAMQConnection(logger, vars, routingKeys);
			} catch (IOException e) {
				logger.warn(
						"exception while startAMQ connection, maybe previous connection is shutting down, retrying...",
						e);
				Thread.sleep(100);
				startAMQConnection(logger, vars, routingKeys);
			}
			ret = "MQ is connected successfully, waiting for messages";
			logger.info(ret);
			System.out.println(ret);
		}
		firstLogger = logger;
		return ret;
	}

	private static void startAMQConnection(final Logger logger, final org.apache.jmeter.threads.JMeterVariables vars,
			String... routingKeys) throws Exception {
		String host = vars.get("MQTest.mqhost");
		if (host != null && host.length() > 0) {
			logger.info("got host " + host + " from user defined variable...");
		} else {
			logger.info("NO host " + host + " from user defined variable, user localhost as default");
			host = MQ_HOST;
		}

		String port = vars.get("MQTest.mqport");
		int intPort = 0;
		try {
			intPort = Integer.parseInt(port);
		} catch (NumberFormatException e) {
			logger.warn(e.getMessage(), e);
		}

		if (intPort <= 0) {
			logger.info("incorrect port from user defined variable, use 5673 as default");
			intPort = MQ_PORT;
		} else {
			logger.info("got port " + intPort + " from user defined variable...");
		}

		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(host);
		factory.setPort(intPort);
		factory.setUsername("root");
		factory.setPassword("Initial0");
		mqConnection = factory.newConnection();
		channel = mqConnection.createChannel();

		channel.exchangeDeclare("SharedExchange", "topic", true);
		String queueName = channel.queueDeclare().getQueue();
		if (routingKeys != null && routingKeys.length > 0) {
			for (int i = 0; i < routingKeys.length; i++) {
				String log = "binding routing key: " + routingKeys[i];
				if (logger != null) {
					logger.info(log);
				}
				System.out.println(log);
				channel.queueBind(queueName, "SharedExchange", routingKeys[i]);
			}
		} else {
			String log = "the param routingKeys is not set, you may not get any messages.";
			if (logger != null) {
				logger.info(log);
			}
			System.out.println(log);
		}
		// channel.queueBind(queueName, "SharedExchange", "Product.UPDATE.*.#");

		messages.clear();

		Consumer consumer = new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) throws IOException {
				String routingKey = envelope.getRoutingKey();
				String message = new String(body, "UTF-8");
				MQTest.putMessage(routingKey, message);
				String output = " [x] Received, routingKey:" + routingKey + " '" + message + "'";
				System.out.println(output);
				if (logger != null) {
					logger.info(output);
				}
			}
		};
		channel.basicConsume(queueName, true, consumer);
	}

	/**
	 * by jmeter
	 * 
	 * @param logger
	 * @throws Exception
	 */
	public static void stopMQ(final Logger logger) throws Exception {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		try {
			HttpGet httpget = new HttpGet("http://localhost:" + INTERNAL_HTTP_PORT + "/exit");
			logger.info("Executing request " + httpget.getRequestLine());
			// Create a custom response handler
			ResponseHandler<String> responseHandler = new ResponseHandler<String>() {
				public String handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
					int status = response.getStatusLine().getStatusCode();
					if (status >= 200 && status < 300) {
						HttpEntity entity = response.getEntity();
						return entity != null ? EntityUtils.toString(entity) : null;
					} else {
						logger.warn("Unexpected response status: " + status);
						throw new ClientProtocolException("Unexpected response status: " + status);
					}
				}
			};
			String responseBody = httpclient.execute(httpget, responseHandler);
			logger.info("----------------------------------------");
			logger.info(responseBody);
		} finally {
			httpclient.close();
		}
	}

	/**
	 * by jmeter
	 * 
	 * @param logger
	 * @return
	 * @throws Exception
	 */
	public static String getMessage(final Logger logger, String routingKey, int expectedCount) throws Exception {
		AMessage message = new AMessage();
		long sleep = 100;
		int sleepCount = 0;
		while (message == null || message.getMessage().equals("") || message.getMessage().equals("[]")
				|| message.getCount() < expectedCount) {
			if (sleepCount < 3) {
				Thread.sleep(sleep);
				try {
					message = getAMessage(logger, routingKey);
				} catch (Exception e) {
					logger.warn(e.getMessage(), e);
					logger.warn("trying again ...");
				}
				sleepCount++;
				sleep = sleep + sleepCount * 100;
			} else {
				logger.info("still can not get message after trying " + sleepCount + " times...");
				break;
			}
		}
		return message.getMessage();
	}

	static class AMessage {
		private int count = 0;

		public int getCount() {
			return count;
		}

		public void setCount(int count) {
			this.count = count;
		}

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}

		private String message = "";
	}

	private static AMessage getAMessage(final Logger logger, String routingKey) throws Exception {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		try {
			HttpGet httpget = new HttpGet(
					"http://localhost:" + INTERNAL_HTTP_PORT + "/messages?routingKey=" + routingKey);
			// org.apache.commons.httpclient.params.HttpClientParams params =
			// new org.apache.commons.httpclient.params.HttpClientParams();
			// params.setParameter("routingKey", routingKey);
			// httpclient.
			// HttpParams params = new BasicHttpParams();
			// params.setParameter("routingKey", routingKey);
			// httpget.setParams(params);
			logger.info("Executing request " + httpget.getRequestLine());
			// Create a custom response handler
			ResponseHandler<AMessage> responseHandler = new ResponseHandler<AMessage>() {
				public AMessage handleResponse(final HttpResponse response)
						throws ClientProtocolException, IOException {
					org.apache.http.Header[] headers = response.getHeaders("X-Count");
					String countStr = headers[0].getValue();
					int count = Integer.parseInt(countStr);

					String message = "";
					int status = response.getStatusLine().getStatusCode();
					if (status >= 200 && status < 300) {
						HttpEntity entity = response.getEntity();
						message = entity != null ? EntityUtils.toString(entity) : null;
					} else {
						logger.warn("Unexpected response status: " + status);
						throw new ClientProtocolException("Unexpected response status: " + status);
					}
					AMessage aMessage = new AMessage();
					aMessage.setMessage(message);
					aMessage.setCount(count);
					return aMessage;
				}
			};
			AMessage aMessage = httpclient.execute(httpget, responseHandler);
			logger.info("----------------------------------------");
			logger.info(aMessage.getMessage());
			return aMessage;
		} finally {
			httpclient.close();
		}
	}

	/**
	 * by jmeter
	 * 
	 * @param logger
	 * @return
	 * @throws Exception
	 */
	public static String clearMessage(final Logger logger) throws Exception {
		String message = "";
		boolean success = true;
		long sleep = 100;
		int sleepCount = 0;
		while (message == null || message.equals("")) {
			if (sleepCount < 3) {
				Thread.sleep(sleep);
				try {
					message = clearAMessage(logger);
				} catch (Exception e) {
					logger.warn(e.getMessage(), e);
					logger.warn("trying again ...");
					success = false;
				}
				sleepCount++;
				sleep = sleep + sleepCount * 100;
			} else {
				success = false;
				logger.info("still can not clear message after trying " + sleepCount + " times...");
				break;
			}
		}
		if (success) {
			return "clear message successfully, you can start a new test.";
		} else {
			return "clear message failed, please call MQTest.startMQ(log) to start test.";
		}
	}

	private static String clearAMessage(final Logger logger) throws Exception {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		try {
			HttpGet httpget = new HttpGet("http://localhost:" + INTERNAL_HTTP_PORT + "/clear");
			logger.info("Executing request " + httpget.getRequestLine());
			// Create a custom response handler
			ResponseHandler<String> responseHandler = new ResponseHandler<String>() {
				public String handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
					int status = response.getStatusLine().getStatusCode();
					if (status >= 200 && status < 300) {
						HttpEntity entity = response.getEntity();
						return entity != null ? EntityUtils.toString(entity) : null;
					} else {
						logger.warn("Unexpected response status: " + status);
						throw new ClientProtocolException("Unexpected response status: " + status);
					}
				}
			};
			String responseBody = httpclient.execute(httpget, responseHandler);
			logger.info("----------------------------------------");
			logger.info("clear messages successfully.");
			return "clear messages successfully.";
		} finally {
			httpclient.close();
		}
	}

	///////////////////////////////// Assertion Utils, all below are used by
	///////////////////////////////// jmeter
	///////////////////////////////// /////////////////////////////////////////
	public static boolean assertCount(final SampleResult result, final Logger logger, String message, int count)
			throws Exception {
		JsonArray array = Json.parse(message).asArray();
		int size = array.size();
		if (size != count) {
			result.setSuccessful(false);
			result.setResponseCode("400");
			String info = "expecting " + count + " messages, but got only " + size;
			result.setResponseMessage(info);
			logger.info(info);
			return false;
		} else {
			result.setSuccessful(true);
			result.setResponseCode("200");
			String info = "there are " + count + " messages.";
			logger.info(info);
			return true;
		}
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, String message,
			String propertyName, String expectedValue) throws Exception {
		return assertProperty_Internal2(result, logger, message, propertyName, expectedValue, 1);
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, String message,
			String propertyName, Integer expectedValue) throws Exception {
		return assertProperty_Internal2(result, logger, message, propertyName, expectedValue, 2);
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, String message,
			String propertyName, Long expectedValue) throws Exception {
		return assertProperty_Internal2(result, logger, message, propertyName, expectedValue, 3);
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, String message,
			String propertyName, Boolean expectedValue) throws Exception {
		return assertProperty_Internal2(result, logger, message, propertyName, expectedValue, 4);
	}

	public static JsonObject parseMessage(final SampleResult result, final Logger logger, String message,
			String selectProp) throws Exception {
		if (result == null || logger == null || StringUtils_isEmpty(message)) {
			String info = "incorrect null parameters passed, the first 3 parameters are mandatory!!!";
			if (logger != null) {
				logger.warn(info);
			}
			if (result != null) {
				result.setSuccessful(false);
				result.setResponseCode("400");
				result.setResponseMessage(info);
			}
			return null;
		}
		JsonArray array = Json.parse(message).asArray();
		if (StringUtils_isEmpty(selectProp)) {
			logger.info("no selectProp is set, so just return first message");
			return array.get(0).asObject();
		} else {
			for (int i = 0; i < array.size(); i++) {
				JsonObject object = array.get(i).asObject();
				if (object.names().contains(selectProp)) {
					return object;
				}
			}
			String info = "cannot find json object by the property name: " + selectProp;
			logger.warn(info);
			if (result != null) {
				result.setSuccessful(false);
				result.setResponseCode("400");
				result.setResponseMessage(info);
			}
			return null;
		}
	}

	////////////////////// another higher efficiency assert utils
	////////////////////// //////////////////
	static class AssertResult {
		public AssertResult(boolean found, Object actualValue) {
			this.found = found;
			this.actualValue = actualValue;
		}

		public boolean isFound() {
			return found;
		}

		public void setFound(boolean found) {
			this.found = found;
		}

		public Object getActualValue() {
			return actualValue;
		}

		public void setActualValue(Object actualValue) {
			this.actualValue = actualValue;
		}

		private boolean found;
		private Object actualValue;
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, JsonObject message,
			String propertyName, String expectedValue) throws Exception {
		return assertProperty_Internal(result, logger, message, propertyName, expectedValue, 1).isFound();
	}

	// more advanced, multi expected values
	public static boolean assertProperty(final SampleResult result, final Logger logger, JsonObject message,
			String propertyName, String... expectedValues) throws Exception {

		boolean found = false;
		List<String> values = new ArrayList<String>();
		String actualValue = "";
		for (String expectedValue : expectedValues) {
			values.add(expectedValue);
			AssertResult assertResult = assertProperty_Internal(result, logger, message, propertyName, expectedValue,
					1);
			found = assertResult.isFound();
			actualValue = assertResult.getActualValue().toString();
			if (found) {
				break;
			}
		}
		if (!found) {
			String info = "expect the " + propertyName + " as " + String.join(", ", values) + ", but got "
					+ actualValue;
			result.setSuccessful(false);
			result.setResponseCode("400");
			result.setResponseMessage(info);
		} else {
			result.setSuccessful(true);
			result.setResponseCode("200");
		}
		return found;
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, JsonObject message,
			String propertyName, Integer expectedValue) throws Exception {
		return assertProperty_Internal(result, logger, message, propertyName, expectedValue, 2).isFound();
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, JsonObject message,
			String propertyName, Long expectedValue) throws Exception {
		return assertProperty_Internal(result, logger, message, propertyName, expectedValue, 3).isFound();
	}

	public static boolean assertProperty(final SampleResult result, final Logger logger, JsonObject message,
			String propertyName, Boolean expectedValue) throws Exception {
		return assertProperty_Internal(result, logger, message, propertyName, expectedValue, 4).isFound();
	}

	private static AssertResult assertProperty_Internal(final SampleResult result, final Logger logger,
			JsonObject message, String propertyName, Object expectedValue, int type) throws Exception {
		if (StringUtils_isEmpty(propertyName) || result == null || logger == null || message == null) {
			String info = "incorrect null parameters passed, the first 4 parameters are mandatory!!!";
			if (logger != null) {
				logger.warn(info);
			}
			if (result != null) {
				result.setSuccessful(false);
				result.setResponseCode("400");
				result.setResponseMessage(info);
			}
			return new AssertResult(false, "");
		}
		boolean found = false;
		boolean foundProp = false;
		Object actualValue = null;
		JsonObject object = message;
		if (object.names().contains(propertyName)) {
			foundProp = true;
			switch (type) {
			case 1:// String
				actualValue = object.get(propertyName).asString();
				break;
			case 2:// Integer
				actualValue = object.get(propertyName).asInt();
				break;
			case 3:// Long
				actualValue = object.get(propertyName).asLong();
				break;
			case 4:// Boolean
				actualValue = object.get(propertyName).asBoolean();
				break;
			}
			if (actualValue == null) {
				if (expectedValue == null) {
					logger.info("asseting " + propertyName + ", and both are null.");
					found = true;
				} else {
					logger.info("asseting " + propertyName + ", but got null.");
				}
			} else {
				if (actualValue.equals(expectedValue)) {
					found = true;
				}
			}
		}
		if (found) {
			result.setSuccessful(true);
			result.setResponseCode("200");
		} else {
			result.setSuccessful(false);
			result.setResponseCode("400");
			String info;
			if (foundProp) {
				info = "expect the " + propertyName + " as " + expectedValue + ", but got " + actualValue;
			} else {
				info = "expect the " + propertyName + " as " + expectedValue + ", but not found the property";
			}
			logger.info(info);
			result.setResponseMessage(info);
		}
		return new AssertResult(found, actualValue);
	}

	private static boolean StringUtils_isEmpty(String str) {
		return str == null || str.length() == 0;
	}

	private static boolean assertProperty_Internal2(final SampleResult result, final Logger logger, String message,
			String propertyName, Object expectedValue, int type) throws Exception {
		if (StringUtils_isEmpty(propertyName) || result == null || logger == null || StringUtils_isEmpty(message)) {
			String info = "incorrect null parameters passed, the first 4 parameters are mandatory!!!";
			if (logger != null) {
				logger.warn(info);
			}
			if (result != null) {
				result.setSuccessful(false);
				result.setResponseCode("400");
				result.setResponseMessage(info);
			}
			return false;
		}
		boolean found = false;
		boolean foundProp = false;
		Object actualValue = null;
		JsonArray array = Json.parse(message).asArray();
		for (int i = 0; i < array.size(); i++) {
			JsonObject object = array.get(i).asObject();
			if (object.names().contains(propertyName)) {
				foundProp = true;
				switch (type) {
				case 1:// String
					actualValue = object.get(propertyName).asString();
					break;
				case 2:// Integer
					actualValue = object.get(propertyName).asInt();
					break;
				case 3:// Long
					actualValue = object.get(propertyName).asLong();
					break;
				case 4:// Boolean
					actualValue = object.get(propertyName).asBoolean();
					break;
				}
				if (actualValue == null) {
					if (expectedValue == null) {
						logger.info("asseting " + propertyName + ", and both are null.");
						found = true;
					} else {
						logger.info("asseting " + propertyName + ", but got null.");
					}
				} else {
					if (actualValue.equals(expectedValue)) {
						found = true;
					}
				}
			} else {
				if (!found) {
					continue;
				} else {
					break;
				}
			}
		}
		if (found) {
			result.setSuccessful(true);
			result.setResponseCode("200");
		} else {
			result.setSuccessful(false);
			result.setResponseCode("400");
			String info;
			if (foundProp) {
				info = "expect the " + propertyName + " as " + expectedValue + ", but got " + actualValue;
			} else {
				info = "expect the " + propertyName + " as " + expectedValue + ", but not found the property";
			}
			logger.info(info);
			result.setResponseMessage(info);
		}
		return found;
	}

}
