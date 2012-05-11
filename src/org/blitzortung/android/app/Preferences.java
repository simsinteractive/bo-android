package org.blitzortung.android.app;

import android.os.Bundle;
import android.preference.PreferenceActivity;

public class Preferences extends PreferenceActivity {
	
	public static final String USERNAME_KEY = "username";
	public static final String PASSWORD_KEY = "password";
	public static final String RASTER_SIZE_KEY = "raster_size";
	final static String MAP_TYPE_KEY = "map_mode";
	final static String QUERY_PERIOD_KEY = "query_period";
	final static String BACKGROUND_QUERY_PERIOD_KEY = "background_query_period";
	final static String SHOW_LOCATION_KEY = "location";
	final static String ALARM_ENABLED_KEY = "alarm_enabled";

	
	@Override
	public void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    
	    addPreferencesFromResource(R.xml.preferences);
	}

}