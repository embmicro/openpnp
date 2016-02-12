/*
 	Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 	
 	This file is part of OpenPnP.
 	
	OpenPnP is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenPnP is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenPnP.  If not, see <http://www.gnu.org/licenses/>.
 	
 	For more information about OpenPnP visit http://openpnp.org
 */

package com.embeddedmicro.feeder;

import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.swing.Action;

import org.openpnp.ConfigurationListener;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Rectangle;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.spi.VisionProvider;
import org.openpnp.spi.VisionProvider.TemplateMatch;
import org.openpnp.util.VisionUtils;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.core.Persist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.embeddedmicro.feeder.wizards.AutoFeederConfigurationWizard;

/**
 * Vision System Description
 * 
 * The Vision Operation is defined as moving the Camera to the defined Pick Location, performing a template match against the Template Image bound by the Area of Interest and
 * then storing the offsets from the Pick Location to the matched image as Vision Offsets.
 * 
 * The feed operation consists of: 1. Apply the Vision Offsets to the Feed Start Location and Feed End Location. 2. Feed the tape with the modified Locations. 3. Perform the
 * Vision Operation. 4. Apply the new Vision Offsets to the Pick Location and return the Pick Location for Picking.
 * 
 * This leaves the head directly above the Pick Location, which means that when the Feeder is then commanded to pick the Part it only needs to move the distance of the Vision
 * Offsets and do the pick. The Vision Offsets are then used in the next feed operation to be sure to hit the tape at the right position.
 */
public class AutoFeeder extends ReferenceFeeder {
	private final static Logger logger = LoggerFactory.getLogger(AutoFeeder.class);

	private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
	@Element
	protected Location feedLocation = new Location(LengthUnit.Millimeters);
	@Element(required = false)
	protected double feedSpeed = 1.0;
	@Element(required = false)
	protected int mmPerPart = 40;
	@Attribute(required = false)
	protected String cartActuatorName = "Cart";
	@Attribute(required = false)
	protected String wheelActuatorName = "Wheel";
	@Attribute(required = false)
	protected String servoActuatorName = "Servo";
	@Element(required = false)
	protected Vision vision = new Vision();

	protected Location pickLocation;

	private double I = 0.0;

	private static Object actuatorLock = new Object();

	private static class PreFeedJob {
		public AutoFeeder feeder;
		public Nozzle nozzle;

		public PreFeedJob(AutoFeeder feeder, Nozzle nozzle) {
			this.feeder = feeder;
			this.nozzle = nozzle;
		}
	}

	private static BlockingQueue<PreFeedJob> feedJobs = new LinkedBlockingQueue<>(); // queue of pre-feed jobs
	private Exception feedResult; // if the preFeed thows an exception this will be non-null
	private Semaphore waitFeed = new Semaphore(1); // sema for waiting on multiple pre-feed requests to the same feeder
	private Semaphore waitPreFeed = new Semaphore(0); // sema for waiting on the pre-feed operation
	private int queuedFeed = 0; // counting number of queued feeds

	/*
	 * visionOffset contains the difference between where the part was expected to be and where it is. Subtracting these offsets from the pickLocation produces the correct
	 * pick location. Likewise, subtracting the offsets from the feedStart and feedEndLocations should produce the correct feed locations.
	 */
	protected Location visionOffset;

	@Override
	public Location getPickLocation() throws Exception {
		if (pickLocation == null) {
			pickLocation = location;
		}
		return pickLocation;
	}

	private static Thread preFeedThread = new Thread() {
		@Override
		public void run() {
			while (true) { // run forever
				PreFeedJob job = null;
				try {
					job = feedJobs.take(); // get next job, blocks if empty
					logger.debug("waitFeed.acquire() for feeder " + job.feeder.getId());
					job.feeder.waitFeed.acquire(); // wait for feeder to be idle
					logger.debug("waitFeed acquired for feeder " + job.feeder.getId());
				} catch (InterruptedException e) {
					continue;
				}
				Exception exc = null;
				try {
					job.feeder.preFeedAction(job.nozzle); // perform
															// pre-feed
				} catch (Exception e) {
					exc = e;
				}
				job.feeder.feedResult = exc; // save any exception
				logger.debug("waitPreFeed.release() for feeder " + job.feeder.getId());
				job.feeder.waitPreFeed.release(); // signal pre-feed done
			}
		}
	};

	static {
		preFeedThread.start();
	}

	private void preFeedAction(Nozzle nozzle) throws Exception {
		Head head = nozzle.getHead();

		Actuator wheelActuator = head.getActuatorByName(wheelActuatorName);
		Actuator servoActuator = head.getActuatorByName(servoActuatorName);
		Actuator cartActuator = head.getActuatorByName(cartActuatorName);

		if (wheelActuator == null || servoActuator == null || cartActuator == null)
			return;

		synchronized (actuatorLock) {
			double steps = mmPerPart;
			if (vision.isEnabled() && visionOffset != null) {
				I += visionOffset.getY() / 5;
				I = Math.min(Math.abs(I), mmPerPart) * Math.signum(I);
				steps += visionOffset.getY() + I; // adjust more as the offset gets worse
			}

			// TODO: use feed speed to adjust speed of the wheel
			if (steps > 0) { // only if we need to actually move stuff
				servoActuator.actuate(1.0); // lift servo
				Thread.sleep(200);
				cartActuator.actuate(feedLocation.getX());
				servoActuator.actuate(feedLocation.getZ());
				Thread.sleep(250);
				wheelActuator.actuate(true);
				wheelActuator.actuate(steps);
				Thread.sleep(100);
				wheelActuator.actuate(false);
				servoActuator.actuate(1.0);
			}
		}
	}

	@Override
	public void preFeed(Nozzle nozzle) {
		logger.debug("preFeed() for " + getId());
		queuedFeed++; // add queued feed
		feedJobs.add(new PreFeedJob(this, nozzle));
	}

	@Override
	public void feed(Nozzle nozzle) throws Exception {
		logger.debug("feed({})", nozzle);

		if (servoActuatorName == null) {
			throw new Exception("No servo actuator name set.");
		}
		if (wheelActuatorName == null) {
			throw new Exception("No wheel actuator name set.");
		}
		if (cartActuatorName == null) {
			throw new Exception("No cart actuator name set.");
		}

		Head head = nozzle.getHead();

		Actuator wheelActuator = head.getActuatorByName(wheelActuatorName);
		Actuator servoActuator = head.getActuatorByName(servoActuatorName);
		Actuator cartActuator = head.getActuatorByName(cartActuatorName);

		if (wheelActuator == null)
			throw new Exception(String.format("No Actuator found with name %s on feed Head %s", wheelActuatorName, head.getName()));
		if (servoActuator == null)
			throw new Exception(String.format("No Actuator found with name %s on feed Head %s", servoActuatorName, head.getName()));
		if (cartActuator == null)
			throw new Exception(String.format("No Actuator found with name %s on feed Head %s", cartActuatorName, head.getName()));

		head.moveToSafeZ(1.0);

		pickLocation = this.location;

		if (queuedFeed > 0) { // was the pre-feed thread started?
			queuedFeed--;
			logger.debug("waitPreFeed.acquire() for " + getId());
			waitPreFeed.acquire();
			logger.debug("waitPreFeed acquired for " + getId());
			if (feedResult != null)
				throw feedResult;
		} else { // pre-feed not called, need to pre-feed
			logger.debug("Performing pre-feed without queue for " + getId());
			preFeedAction(nozzle);
		}

		if (vision.isEnabled()) {
			try {
				updateVision(head, location);
			} catch (Exception e) {
				synchronized (actuatorLock) {
					int attempts = 0;

					servoActuator.actuate(1.0); // lift servo
					Thread.sleep(200);
					cartActuator.actuate(feedLocation.getX());
					servoActuator.actuate(feedLocation.getZ());
					Thread.sleep(250);
					wheelActuator.actuate(true);

					while (true) {
						double steps = mmPerPart;
						if (I > 0)
							steps += I;
						wheelActuator.actuate(steps);

						try {
							getVisionLocation(head, location);
							I += steps/4;
							I = Math.min(Math.abs(I), mmPerPart) * Math.signum(I);
							break;
						} catch (Exception e2) {
							attempts++;
							if (attempts > 5)
								throw e2;
						}
					}

					wheelActuator.actuate(false);
					servoActuator.actuate(1.0);

					updateVision(head, location);
				}
			}

			logger.debug("final visionOffsets " + visionOffset);
		}

		logger.debug("Modified pickLocation {}", pickLocation);

	}

	private void updateVision(Head head, Location location) throws Exception {
		Location visionLoc = getVisionLocation(head, location);
		visionOffset = pickLocation.subtract(visionLoc);
		pickLocation = pickLocation.derive(visionLoc.getX(), visionLoc.getY(), null, pickLocation.getRotation() + visionLoc.getRotation());
	}

	@Override
	public void postFeed() {
		logger.debug("Releasing waitFeed for " + getId());
		waitFeed.release();
	}

	// TODO: Throw an Exception if vision fails.
	private Location getVisionLocation(Head head, Location pickLocation) throws Exception {
		logger.debug("getVisionOffsets({}, {})", head.getName(), pickLocation);
		// Find the Camera to be used for vision
		// TODO: Consider caching this
		Camera camera = null;
		for (Camera c : head.getCameras()) {
			if (c.getVisionProvider() != null) {
				camera = c;
			}
		}

		if (camera == null) {
			throw new Exception("No vision capable camera found on head.");
		}

		head.moveToSafeZ(1.0);

		// Position the camera over the pick location.
		logger.debug("Move camera to pick location.");
		camera.moveTo(pickLocation, 1.0);

		// Move the camera to be in focus over the pick location.
		// head.moveTo(head.getX(), head.getY(), z, head.getC());

		// Settle the camera
		Thread.sleep(camera.getSettleTimeMs());

		VisionProvider visionProvider = camera.getVisionProvider();

		Rectangle aoi = getVision().getAreaOfInterest();

		// Perform the template match
		logger.debug("Perform template match.");

		List<TemplateMatch> matches = visionProvider.getTemplateMatches(vision.getTemplateImage());

		System.out.println("Matches: " + matches.size());

		Location aoiLoc = VisionUtils.getPixelLocation(camera, aoi.getX(), aoi.getY());
		Location aoiBounds = VisionUtils.getPixelLocation(camera, aoi.getX() + aoi.getWidth(), aoi.getY() + aoi.getHeight());

		double xMin = Math.min(aoiLoc.getX(), aoiBounds.getX());
		double xMax = Math.max(aoiLoc.getX(), aoiBounds.getX());
		double yMin = Math.min(aoiLoc.getY(), aoiBounds.getY());
		double yMax = Math.max(aoiLoc.getY(), aoiBounds.getY());

		logger.debug("AOI: {} < x < {} : {} < y < {}", new Object[] { xMin, xMax, yMin, yMax });

		if (matches.size() == 0)
			throw new Exception("No matchs found!");

		TemplateMatch bestMatch = matches.get(0);

		// if (bestMatch.location.getX() < xMin || bestMatch.location.getX() > xMax || bestMatch.location.getY() < yMin || bestMatch.location.getY() > yMax)
		// throw new Exception("Best match not in AOI! Best Match:" + bestMatch + " AOI: x:" + xMin + "-" + xMax + " y:" + yMin + "-" + yMax);

		for (Iterator<TemplateMatch> i = matches.iterator(); i.hasNext();) {
			TemplateMatch m = i.next();
			double space = (Math.abs(bestMatch.location.getY() - m.location.getY()) / mmPerPart) % 1;
			if (m.location.getX() > xMin && m.location.getX() < xMax && m.location.getY() > yMin && m.location.getY() < yMax && (space < 0.05 || space > .95)) {
				logger.debug("Keeping matchX {}, matchY {}, quality {}", new Object[] { m.location.getX(), m.location.getY(), m.score });
			} else {
				i.remove();
				logger.debug("Removed matchX {}, matchY {}, quality {}", new Object[] { m.location.getX(), m.location.getY(), m.score });
			}
		}

		if (matches.size() == 0)
			throw new Exception("No matchs found in AOI!");

		TemplateMatch yMost = bestMatch;

		for (TemplateMatch m : matches)
			if (m.location.getY() > yMost.location.getY())
				yMost = m;

		// return new Location(LengthUnit.Millimeters, 0.0, 0.0, 0.0, 0.0);

		logger.debug("Using matchX {}, matchY {}, quality {}", new Object[] { yMost.location.getX(), yMost.location.getY(), yMost.score });

		return yMost.location;
	}

	@Override
	public String toString() {
		return String.format("ReferenceTapeFeeder id %s", id);
	}

	public Location getFeedLocation() {
		return feedLocation;
	}

	public void setFeedLocation(Location feedLocation) {
		this.feedLocation = feedLocation;
	}

	public Double getFeedSpeed() {
		return feedSpeed;
	}

	public void setFeedSpeed(Double feedSpeed) {
		this.feedSpeed = feedSpeed;
	}

	public int getMmPerPart() {
		return mmPerPart;
	}

	public void setMmPerPart(int mmPerPart) {
		this.mmPerPart = mmPerPart;
	}

	public String getCartActuatorName() {
		return cartActuatorName;
	}

	public void setCartActuatorName(String cartActuatorName) {
		String oldValue = this.cartActuatorName;
		this.cartActuatorName = cartActuatorName;
		propertyChangeSupport.firePropertyChange("cartActuatorName", oldValue, cartActuatorName);
	}

	public String getWheelActuatorName() {
		return wheelActuatorName;
	}

	public void setWheelActuatorName(String wheelActuatorName) {
		String oldValue = this.wheelActuatorName;
		this.wheelActuatorName = wheelActuatorName;
		propertyChangeSupport.firePropertyChange("wheelActuatorName", oldValue, wheelActuatorName);
	}

	public String getServoActuatorName() {
		return servoActuatorName;
	}

	public void setServoActuatorName(String servoActuatorName) {
		String oldValue = this.servoActuatorName;
		this.servoActuatorName = servoActuatorName;
		propertyChangeSupport.firePropertyChange("servoActuatorName", oldValue, servoActuatorName);
	}

	public Vision getVision() {
		return vision;
	}

	public void setVision(Vision vision) {
		this.vision = vision;
	}

	public void addPropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.addPropertyChangeListener(listener);
	}

	public void addPropertyChangeListener(String propertyName, PropertyChangeListener listener) {
		propertyChangeSupport.addPropertyChangeListener(propertyName, listener);
	}

	public void removePropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.removePropertyChangeListener(listener);
	}

	public void removePropertyChangeListener(String propertyName, PropertyChangeListener listener) {
		propertyChangeSupport.removePropertyChangeListener(propertyName, listener);
	}

	@Override
	public Wizard getConfigurationWizard() {
		return new AutoFeederConfigurationWizard(this);
	}

	@Override
	public String getPropertySheetHolderTitle() {
		return getClass().getSimpleName() + " " + getName();
	}

	@Override
	public PropertySheetHolder[] getChildPropertySheetHolders() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PropertySheet[] getPropertySheets() {
		return new PropertySheet[] { new PropertySheetWizardAdapter(getConfigurationWizard()) };
	}

	@Override
	public Action[] getPropertySheetHolderActions() {
		// TODO Auto-generated method stub
		return null;
	}

	public static class Vision {
		@Attribute(required = false)
		private boolean enabled;
		@Attribute(required = false)
		private String templateImageName;
		@Element(required = false)
		private Rectangle areaOfInterest = new Rectangle();
		@Element(required = false)
		private Location templateImageTopLeft = new Location(LengthUnit.Millimeters);
		@Element(required = false)
		private Location templateImageBottomRight = new Location(LengthUnit.Millimeters);

		private BufferedImage templateImage;
		private boolean templateImageDirty;

		public Vision() {
			Configuration.get().addListener(new ConfigurationListener.Adapter() {
				@Override
				public void configurationComplete(Configuration configuration) throws Exception {
					if (templateImageName != null) {
						File file = configuration.getResourceFile(Vision.this.getClass(), templateImageName);
						templateImage = ImageIO.read(file);
					}
				}
			});
		}

		@Persist
		private void persist() throws IOException {
			if (templateImageDirty) {
				File file = null;
				if (templateImageName != null) {
					file = Configuration.get().getResourceFile(this.getClass(), templateImageName);
				} else {
					file = Configuration.get().createResourceFile(this.getClass(), "tmpl_", ".png");
					templateImageName = file.getName();
				}
				ImageIO.write(templateImage, "png", file);
				templateImageDirty = false;
			}
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public BufferedImage getTemplateImage() {
			return templateImage;
		}

		public void setTemplateImage(BufferedImage templateImage) {
			if (templateImage != this.templateImage) {
				this.templateImage = templateImage;
				templateImageDirty = true;
			}
		}

		public Rectangle getAreaOfInterest() {
			return areaOfInterest;
		}

		public void setAreaOfInterest(Rectangle areaOfInterest) {
			this.areaOfInterest = areaOfInterest;
		}

		public Location getTemplateImageTopLeft() {
			return templateImageTopLeft;
		}

		public void setTemplateImageTopLeft(Location templateImageTopLeft) {
			this.templateImageTopLeft = templateImageTopLeft;
		}

		public Location getTemplateImageBottomRight() {
			return templateImageBottomRight;
		}

		public void setTemplateImageBottomRight(Location templateImageBottomRight) {
			this.templateImageBottomRight = templateImageBottomRight;
		}
	}
}
