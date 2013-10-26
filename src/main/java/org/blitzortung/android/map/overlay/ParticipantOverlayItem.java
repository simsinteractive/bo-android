package org.blitzortung.android.map.overlay;

import com.google.android.maps.OverlayItem;
import org.blitzortung.android.data.Coordsys;
import org.blitzortung.android.data.beans.Station;
import org.blitzortung.android.data.beans.Station.State;

public class ParticipantOverlayItem extends OverlayItem {

	private final long lastDataTime;
	
	private final State state;
	
	public ParticipantOverlayItem(Station station) {
		super(Coordsys.toMapCoords(station.getLongitude(), station.getLatitude()), station.getName(), "");

		lastDataTime = station.getOfflineSince();
		state = station.getState();
	}
	
	public long getLastDataTime() {
		return lastDataTime;
	}
	
	public State getState() {
		return state;
	}

}