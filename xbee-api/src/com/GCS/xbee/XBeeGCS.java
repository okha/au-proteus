package com.GCS.xbee;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.InputMismatchException;
import java.util.NoSuchElementException;
import java.util.Scanner;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import com.rapplogic.xbee.api.ApiId;
import com.rapplogic.xbee.api.PacketListener;
import com.rapplogic.xbee.api.XBee;
import com.rapplogic.xbee.api.XBeeException;
import com.rapplogic.xbee.api.XBeeResponse;
import com.rapplogic.xbee.api.zigbee.ZNetRxResponse;
import com.rapplogic.xbee.util.ByteUtils;

public class XBeeGCS {

	private final static Logger log = Logger.getLogger(XBeeGCS.class);
	private static XBee xbee;
	private static CollisionAvoidance ca;

	public XBeeGCS () {
		PropertyConfigurator.configure("log4j.properties");
		xbee = new XBee();
		ca = new CollisionAvoidance(xbee, log);
		xbee.addPacketListener(new GCSPacketListener());

		new GUI();
	}

	public static void main(String[] args) throws InterruptedException {
		new XBeeGCS();
		// set up the coordinator XBee Serial communication
		try {
			xbee.open("/dev/ttyUSB0", 115200);
			// hackish way of making the thread sleep forever.  open to other suggestions...
			Thread.sleep(Long.MAX_VALUE);
		}
		catch (XBeeException e) {
			e.printStackTrace();
		}
		finally {
			xbee.close();
		}
	}

	private class GUI extends JFrame implements ActionListener {
		private JTextField text;

		public GUI() {
			super ("Load Arbitrary Waypoint");

			text = new JTextField(50);
			text.addActionListener(this);

			JButton button = new JButton("Load");
			button.addActionListener(this);

			JPanel panel = new JPanel();
			panel.add(text);
			panel.add(button);

			this.add(panel);
			this.pack();
			this.setVisible(true);

		}

		@Override
		public void actionPerformed(ActionEvent arg0) {
			String string = text.getText();
			Scanner scanner = new Scanner(string);
			scanner.useDelimiter(", ");
			CollisionAvoidance.Coordinate wp = new CollisionAvoidance.Coordinate();
			try {
				wp.x = (double) scanner.nextInt();
				wp.y = (double) scanner.nextInt();
				wp.z = (double) scanner.nextInt();
			}
			catch (InputMismatchException e) {
				log.error("input mismatch exception caught");
				return;
			}
			catch (NoSuchElementException e) {
				log.error("3D point not typed in");
				return;
			}
			log.info("GUI has initiated transmit of " + wp);
			ca.transmit(ca.latest, wp);
		}

	}

	private class GCSPacketListener implements PacketListener {

		// keep track of time between packets
		private long next;
		private long prev;

		// receive packet and add the parsed data from it to the CA object's hash map
		@Override
		public void processResponse(XBeeResponse response) {
			if (response.getApiId() == ApiId.ZNET_RX_RESPONSE) {
				ZNetRxResponse rx = (ZNetRxResponse) response;

				log.debug("Received RX packet, option is " + rx.getOption() + 
						", sender 64 address is " + ByteUtils.toBase16(rx.getRemoteAddress64().getAddress()) + 
						", remote 16-bit address is " + ByteUtils.toBase16(rx.getRemoteAddress16().getAddress()) + 
						", data is " + ByteUtils.toBase16(rx.getData()));

				ca.addData(rx.getRemoteAddress64(), packetParser(rx));
			}
		}

		// parse packet payload and calculate communication
		// return null if not a PlaneData packet
		private PlaneData packetParser(ZNetRxResponse response) {
			next = System.currentTimeMillis();
			log.debug("Time elapsed is " + (next-prev)+"ms, response length is "+response.getData().length);
			prev = next;	

			// If response isn't a telemetry packet that we sent, just print it out
			if (response.getData().length != 40) {
				StringBuffer sb = new StringBuffer();
				for (int i = 0; i < response.getData().length; i++)
					sb.append((char)response.getData()[i]);
				log.info(sb.toString());
				return null;
			}

			// flip data in packet to little endian and store it in an int array
			int planeDataArray[] = new int[10];
			byte data[] = new byte[response.getData().length];
			for (int i = 0; i < data.length; i++)
				data[i] = (byte) response.getData()[i];
			for (int i = 0; i < data.length; i += 4) {
				ByteBuffer bb = ByteBuffer.allocate(32);
				bb.order(ByteOrder.BIG_ENDIAN);
				bb.put(data, i, 4);
				bb.order(ByteOrder.LITTLE_ENDIAN);
				planeDataArray[i / 4] = bb.getInt(0);
			}

			// store data in new PlaneData object
			PlaneData pd = new PlaneData();
			pd.currLat = planeDataArray[0];
			pd.currLng = planeDataArray[1];
			pd.currAlt = planeDataArray[2];
			pd.nextLat = planeDataArray[3];
			pd.nextLng = planeDataArray[4];
			pd.nextAlt = planeDataArray[5];
			pd.ground_speed = planeDataArray[6];
			pd.target_bearing = planeDataArray[7];
			pd.currWP = planeDataArray[8];
			pd.WPdistance = planeDataArray[9];

			return pd;
		}
	}


}