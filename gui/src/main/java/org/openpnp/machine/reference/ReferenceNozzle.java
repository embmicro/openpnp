package org.openpnp.machine.reference;

import org.openpnp.ConfigurationListener;
import org.openpnp.gui.support.Wizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.base.AbstractNozzle;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReferenceNozzle extends AbstractNozzle implements
        ReferenceHeadMountable {
    private final static Logger logger = LoggerFactory
            .getLogger(ReferenceNozzle.class);

    @Element
    private Location headOffsets;

    @Attribute(required = false)
    private int pickDwellMilliseconds;

    @Attribute(required = false)
    private int placeDwellMilliseconds;

    @Element
    private NozzleTip nozzleTip;

    private ReferenceMachine machine;
    private ReferenceDriver driver;

    public ReferenceNozzle() {
        Configuration.get().addListener(new ConfigurationListener() {
            @Override
            public void configurationLoaded(Configuration configuration)
                    throws Exception {
                machine = (ReferenceMachine) configuration.getMachine();
                driver = machine.getDriver();
            }
        });
    }

    public int getPickDwellMilliseconds() {
        return pickDwellMilliseconds;
    }

    public void setPickDwellMilliseconds(int pickDwellMilliseconds) {
        this.pickDwellMilliseconds = pickDwellMilliseconds;
    }

    public int getPlaceDwellMilliseconds() {
        return placeDwellMilliseconds;
    }

    public void setPlaceDwellMilliseconds(int placeDwellMilliseconds) {
        this.placeDwellMilliseconds = placeDwellMilliseconds;
    }

    @Override
    public Location getHeadOffsets() {
        return headOffsets;
    }

    @Override
    public NozzleTip getNozzleTip() {
        return nozzleTip;
    }

    @Override
    public boolean canPickAndPlace(Feeder feeder, Location placeLocation) {
        return nozzleTip.canHandle(feeder.getPart());
    }

    @Override
    public void pick() throws Exception {
        logger.debug("pick()");
        driver.pick(this);
        machine.fireMachineHeadActivity(head);
        Thread.sleep(pickDwellMilliseconds);
    }

    @Override
    public void place() throws Exception {
        logger.debug("place()");
        driver.place(this);
        machine.fireMachineHeadActivity(head);
        Thread.sleep(placeDwellMilliseconds);
    }

    @Override
    public void moveTo(Location location, double speed) throws Exception {
        logger.debug("moveTo({}, {})", new Object[] { location, speed } );
        driver.moveTo(this, location, speed);
    }

    @Override
    public void moveToSafeZ(double speed) throws Exception {
        logger.debug("moveToSafeZ({})", new Object[] { speed } );
        Location l = new Location(getLocation().getUnits(), Double.NaN,
                Double.NaN, 0, Double.NaN);
        driver.moveTo(this, l, speed);
    }

    @Override
    public Location getLocation() {
        return driver.getLocation(this);
    }

    @Override
    public Wizard getConfigurationWizard() {
        // TODO Auto-generated method stub
        return null;
    }
}