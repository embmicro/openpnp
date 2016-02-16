package com.embeddedmicro.driver;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.openbuilds.OpenBuildsDriver;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceHead;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortTimeoutException;

public class EmbeddedMicroDriver extends OpenBuildsDriver {
	private static final Logger logger = LoggerFactory.getLogger(EmbeddedMicroDriver.class);
	private static final int SETTINGS = 0;
	private static final int WHEEL_STEPS = 1;
	private static final int CART_STEPS = 2;
	private static final int WHEEL_DELAY = 3;
	private static final int CART_DELAY = 4;
	private static final int SERVO_POS = 5;

	public static final int SERVO_MAX = 0x9000;
	public static final int SERVO_MIN = 0x4000;

	@Attribute(required = false)
	protected String addonPortName;
	@Attribute(required = false)
	protected int addonBaud = 115200;

	@Attribute(required = false)
	protected int cartSteps = 4;
	@Attribute(required = false)
	protected int wheelSteps = 32;
	@Attribute(required = false)
	protected double stepsPerMm = 39;
	@Attribute(required = false)
	protected String cartActuatorName = "Cart";
	@Attribute(required = false)
	protected String wheelActuatorName = "Wheel";
	@Attribute(required = false)
	protected String servoActuatorName = "Servo";

	protected SerialPort serialAddonPort;
	protected int currentSettings;
	protected int cartPosition;

	@Override
	public void home(ReferenceHead head) throws Exception {
		// We "home" Z by turning off the steppers, allowing the
        // spring to pull the nozzle back up to home.
        sendCommand("M84");
        // And call that zero
        sendCommand("G92 Z0");
        // And wait a tick just to let things settle down
        Thread.sleep(250);

		super.home(head);

		enableCart(true);
		enableServo(true);

		int oldDelay = read(CART_DELAY);
		write(SERVO_POS, SERVO_MAX); // lift servo
		write(CART_DELAY, 0x10000);
		write(CART_STEPS, Integer.MIN_VALUE); // move left
		waitCart();
		write(CART_STEPS, 100);
		waitCart();
		
		write(CART_DELAY, 0x40000);
		write(CART_STEPS, -400);
		waitCart();
		write(CART_DELAY, oldDelay);
		cartPosition = 0;
	}

	private void waitCart() throws SerialPortException, SerialPortTimeoutException {
		while (read(CART_STEPS) != 0) // hitting the limit switch will set to 0
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
	}

	private void waitWheel() throws SerialPortException, SerialPortTimeoutException {
		while (read(WHEEL_STEPS) != 0)
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
	}
	
	public double getStepsPerMm() {
		return stepsPerMm;
	}

	public void setStepsPerMm(double stepsPerMm) {
		this.stepsPerMm = stepsPerMm;
	}

	@Override
	public void actuate(ReferenceActuator actuator, boolean on) throws Exception {
		super.actuate(actuator, on);
		String name = actuator.getName();
		if (name.equals(cartActuatorName))
			enableCart(on);
		else if (name.equals(wheelActuatorName))
			enableWheel(on);
		else if (name.equals(servoActuatorName))
			enableServo(on);
	}

	@Override
	public void actuate(ReferenceActuator actuator, double value) throws Exception {
		super.actuate(actuator, value);
		String name = actuator.getName();
		if (name.equals(cartActuatorName)) {
			System.out.println("Writing to cart " + (int) (value - cartPosition));
			write(CART_STEPS, (int) (value - cartPosition));
			waitCart();
			cartPosition = (int) value;
		} else if (name.equals(wheelActuatorName)) {
			write(WHEEL_STEPS, (int) (value * stepsPerMm));
			waitWheel();
		} else if (name.equals(servoActuatorName)) {
			int servoPos = (int) (value * (SERVO_MAX - SERVO_MIN) + SERVO_MIN);
			write(SERVO_POS, (int) Math.max(SERVO_MIN, Math.min(SERVO_MAX, servoPos)));
		}
	}

	@Override
	public Wizard getConfigurationWizard() {
		return new EmbeddedMicroDriverWizard(this);
	}

	private int stepsToSetting(int steps) {
		switch (steps) {
		case 1:
			return 0;
		case 2:
			return 1;
		case 4:
			return 2;
		case 8:
			return 3;
		case 16:
			return 4;
		case 32:
			return 5;
		default:
			return -1;
		}
	}

	private void enableServo(boolean enable) throws SerialPortException {
		setSettingsBit(enable, 8);
	}

	private void enableWheel(boolean enable) throws SerialPortException {
		setSettingsBit(enable, 0);
	}

	private void enableCart(boolean enable) throws SerialPortException {
		setSettingsBit(enable, 4);
	}

	private void setSettingsBit(boolean set, int bit) throws SerialPortException {
		currentSettings = (currentSettings & ~(1 << bit)) | ((set ? 1 : 0) << bit);
		write(SETTINGS, currentSettings);
	}

	@Override
	public void setEnabled(boolean enabled) throws Exception {
		super.setEnabled(enabled);
		if (serialAddonPort != null && serialAddonPort.isOpened())
			if (enabled) {
				int wStep = Math.max(stepsToSetting(wheelSteps), 0);
				int cStep = Math.max(stepsToSetting(cartSteps), 0);

				currentSettings = cStep << 5 | 1 << 4 | wStep << 1 | 1 << 8; // cart motor on, wheel motor off, servo on
				write(SETTINGS, currentSettings);
				write(WHEEL_DELAY, 0x28000); // default speed
				write(CART_DELAY, 0x2000);
				write(SERVO_POS, SERVO_MAX); // lift wheel
			} else {
				write(SETTINGS, 0); // turn off motors
			}
	}

	public synchronized void addonConnect() throws Exception {
		addonDisconnect();
		serialAddonPort = new SerialPort(addonPortName);
		serialAddonPort.openPort();
		serialAddonPort.setParams(addonBaud, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
		serialAddonPort.readBytes(); // flush read cache

	}

	public synchronized void addonDisconnect() {
		if (serialAddonPort != null && serialAddonPort.isOpened()) {
			try {
				write(SETTINGS, 0); // turn off motors
			} catch (SerialPortException e1) {
				logger.error("addonDisconnect()", e1);
			} // motors off, 1/8th step
			try {
				serialAddonPort.closePort();
			} catch (SerialPortException e) {
				logger.error("addonDisconnect()", e);
			}
			serialAddonPort = null;
		}
	}

	@Override
	public synchronized void connect() throws Exception {
		// first connect to the main board
		super.connect();
		// then connect to the addon board
		addonConnect();
	}

	@Override
	public synchronized void disconnect() {
		// first disconnect the main board
		super.disconnect();
		// then disconnect the addon board
		addonDisconnect();
	}

	public String getAddonPortName() {
		return addonPortName;
	}

	public void setAddonPortName(String addonPortName) {
		this.addonPortName = addonPortName;
	}

	public int getAddonBaud() {
		return addonBaud;
	}

	public void setAddonBaud(int addonBaud) {
		this.addonBaud = addonBaud;
	}

	public String getCartActuatorName() {
		return cartActuatorName;
	}

	public void setCartActuatorName(String cartActuatorName) {
		this.cartActuatorName = cartActuatorName;
	}

	public String getWheelActuatorName() {
		return wheelActuatorName;
	}

	public void setWheelActuatorName(String wheelActuatorName) {
		this.wheelActuatorName = wheelActuatorName;
	}

	public String getServoActuatorName() {
		return servoActuatorName;
	}

	public void setServoActuatorName(String servoActuatorName) {
		this.servoActuatorName = servoActuatorName;
	}

	private boolean write(int address, int data) throws SerialPortException {
		byte[] buff = new byte[9];
		buff[0] = (byte) (1 << 7);
		buff[1] = (byte) (address & 0xff);
		buff[2] = (byte) ((address >> 8) & 0xff);
		buff[3] = (byte) ((address >> 16) & 0xff);
		buff[4] = (byte) ((address >> 24) & 0xff);
		buff[5] = (byte) (data & 0xff);
		buff[6] = (byte) ((data >> 8) & 0xff);
		buff[7] = (byte) ((data >> 16) & 0xff);
		buff[8] = (byte) ((data >> 24) & 0xff);
		return serialAddonPort.writeBytes(buff);
	}

	private boolean write(int address, boolean increment, int[] data) throws SerialPortException {
		for (int i = 0; i < data.length; i += 64) {
			int length = Math.min(data.length - i, 64);
			if (!write64(address, increment, data, i, length))
				return false;
			if (increment)
				address += length;
		}
		return true;
	}

	private boolean write64(int address, boolean increment, int[] data, int start, int length) throws SerialPortException {
		byte[] buff = new byte[5 + length * 4];
		buff[0] = (byte) ((1 << 7) | (length - 1));
		if (increment)
			buff[0] |= (1 << 6);
		buff[1] = (byte) (address & 0xff);
		buff[2] = (byte) ((address >> 8) & 0xff);
		buff[3] = (byte) ((address >> 16) & 0xff);
		buff[4] = (byte) ((address >> 24) & 0xff);
		for (int i = 0; i < length; i++) {
			buff[i * 4 + 5] = (byte) (data[i + start] & 0xff);
			buff[i * 4 + 6] = (byte) ((data[i + start] >> 8) & 0xff);
			buff[i * 4 + 7] = (byte) ((data[i + start] >> 16) & 0xff);
			buff[i * 4 + 8] = (byte) ((data[i + start] >> 24) & 0xff);
		}

		return serialAddonPort.writeBytes(buff);
	}

	private int read(int address) throws SerialPortException, SerialPortTimeoutException {
		byte[] buff = new byte[5];
		buff[0] = (byte) (0 << 7);
		buff[1] = (byte) (address & 0xff);
		buff[2] = (byte) ((address >> 8) & 0xff);
		buff[3] = (byte) ((address >> 16) & 0xff);
		buff[4] = (byte) ((address >> 24) & 0xff);
		if (!serialAddonPort.writeBytes(buff))
			throw new SerialPortException(serialAddonPort.getPortName(), "readReg", "Failed to write address");
		buff = serialAddonPort.readBytes(4, 1000);
		return (buff[0] & 0xff) | (buff[1] & 0xff) << 8 | (buff[2] & 0xff) << 16 | (buff[3] & 0xff) << 24;
	}

	private void read(int address, boolean increment, int[] data) throws SerialPortException, SerialPortTimeoutException {
		for (int i = 0; i < data.length; i += 64) {
			int length = Math.min(data.length - i, 64);
			read64(address, increment, data, i, length);
			if (increment)
				address += length;
		}
	}

	private void read64(int address, boolean increment, int[] data, int start, int length) throws SerialPortException, SerialPortTimeoutException {
		byte[] buff = new byte[5];
		buff[0] = (byte) ((0 << 7) | (length - 1));
		if (increment)
			buff[0] |= (1 << 6);
		buff[1] = (byte) (address & 0xff);
		buff[2] = (byte) ((address >> 8) & 0xff);
		buff[3] = (byte) ((address >> 16) & 0xff);
		buff[4] = (byte) ((address >> 24) & 0xff);

		if (!serialAddonPort.writeBytes(buff))
			throw new SerialPortException(serialAddonPort.getPortName(), "readReg", "Failed to write address");

		buff = serialAddonPort.readBytes(length * 4, 3000);

		for (int i = 0; i < buff.length; i += 4) {
			data[i / 4 + start] = (buff[i] & 0xff) | (buff[i + 1] & 0xff) << 8 | (buff[i + 2] & 0xff) << 16 | (buff[i + 3] & 0xff) << 24;
		}
	}
}