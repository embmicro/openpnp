package com.embeddedmicro.driver;

import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.machine.openbuilds.OpenBuildsDriver;
import org.openpnp.machine.openbuilds.OpenBuildsDriverWizard;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

public class EmbeddedMicroDriverWizard extends OpenBuildsDriverWizard {
    private final EmbeddedMicroDriver driver;
    
   
    private JComboBox comboBoxAddonPort;
    private JComboBox comboBoxAddonBaud;
    private JTextField textFieldCartActuatorId;
	private JTextField textFieldWheelActuatorId;
	private JTextField textFieldServoActuatorId;
    
    public EmbeddedMicroDriverWizard(EmbeddedMicroDriver driver) {
        super(driver);
        this.driver = driver;
        
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null, "Mojo Settings", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(panel);
        panel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));
        
        JLabel lblPortName = new JLabel("Addon Port");
        panel.add(lblPortName, "2, 2, right, default");
        
        comboBoxAddonPort = new JComboBox();
        panel.add(comboBoxAddonPort, "4, 2, fill, default");
        
        JLabel lblBaudRate = new JLabel("Addon Baud");
        panel.add(lblBaudRate, "2, 4, right, default");
        
        comboBoxAddonBaud = new JComboBox();
        panel.add(comboBoxAddonBaud, "4, 4, fill, default");
        
        comboBoxAddonBaud.addItem(new Integer(110));
        comboBoxAddonBaud.addItem(new Integer(300));
        comboBoxAddonBaud.addItem(new Integer(600));
        comboBoxAddonBaud.addItem(new Integer(1200));
        comboBoxAddonBaud.addItem(new Integer(2400));
        comboBoxAddonBaud.addItem(new Integer(4800));
        comboBoxAddonBaud.addItem(new Integer(9600));
        comboBoxAddonBaud.addItem(new Integer(14400));
        comboBoxAddonBaud.addItem(new Integer(19200));
        comboBoxAddonBaud.addItem(new Integer(38400));
        comboBoxAddonBaud.addItem(new Integer(56000));
        comboBoxAddonBaud.addItem(new Integer(57600));
        comboBoxAddonBaud.addItem(new Integer(115200));
        comboBoxAddonBaud.addItem(new Integer(128000));
        comboBoxAddonBaud.addItem(new Integer(153600));
        comboBoxAddonBaud.addItem(new Integer(230400));
        comboBoxAddonBaud.addItem(new Integer(250000));
        comboBoxAddonBaud.addItem(new Integer(256000));
        comboBoxAddonBaud.addItem(new Integer(460800));
        comboBoxAddonBaud.addItem(new Integer(921600));
        
        comboBoxAddonPort.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            	refreshAddonPortList();
            }
            
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }
            
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        refreshAddonPortList();
        
        JLabel lblActuatorId = new JLabel("Cart Actuator Name");
        panel.add(lblActuatorId, "2, 6, right, default");

		textFieldCartActuatorId = new JTextField();
		panel.add(textFieldCartActuatorId, "4, 6");
		textFieldCartActuatorId.setColumns(5);
		
		lblActuatorId = new JLabel("Wheel Actuator Name");
		panel.add(lblActuatorId, "2, 8, right, default");

		textFieldWheelActuatorId = new JTextField();
		panel.add(textFieldWheelActuatorId, "4, 8");
		textFieldWheelActuatorId.setColumns(5);
		
		lblActuatorId = new JLabel("Servo Actuator Name");
		panel.add(lblActuatorId, "2, 10, right, default");

		textFieldServoActuatorId = new JTextField();
		panel.add(textFieldServoActuatorId, "4, 10");
		textFieldServoActuatorId.setColumns(5);
    }
    
    private void refreshAddonPortList() {
        if (driver != null) {
            comboBoxAddonPort.removeAllItems();
            boolean exists = false;
            String[] portNames = driver.getPortNames();
            for (String portName : portNames) {
                comboBoxAddonPort.addItem(portName);
                if (portName.equals(driver.getAddonPortName())) {
                    exists = true;
                }
            }
            if (!exists && driver.getPortName() != null) {
                comboBoxAddonPort.addItem(driver.getAddonPortName());
            }
        }
    }
    
    @Override
    public void createBindings() {
    	super.createBindings();
    	
        IntegerConverter integerConverter = new IntegerConverter();
        
        addWrappedBinding(driver, "addonPortName", comboBoxAddonPort, "selectedItem");
        addWrappedBinding(driver, "addonBaud", comboBoxAddonBaud, "selectedItem");
        
        addWrappedBinding(driver, "cartActuatorName", textFieldCartActuatorId, "text");
		addWrappedBinding(driver, "wheelActuatorName", textFieldWheelActuatorId, "text");
		addWrappedBinding(driver, "servoActuatorName", textFieldServoActuatorId, "text");
		
		ComponentDecorators.decorateWithAutoSelect(textFieldCartActuatorId);
		ComponentDecorators.decorateWithAutoSelect(textFieldWheelActuatorId);
		ComponentDecorators.decorateWithAutoSelect(textFieldServoActuatorId);
    }
}
