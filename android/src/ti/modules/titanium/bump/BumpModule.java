/**
 * Ti.Bump Module
 * Copyright (c) 2010-2013 by Appcelerator, Inc. All Rights Reserved.
 * Please see the LICENSE included with this distribution for details.
 */

package ti.bump;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.annotations.Kroll;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.util.TiActivityResultHandler;
import org.appcelerator.titanium.util.TiActivitySupport;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.TiApplication;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;

import com.bumptech.bumpapi.BumpAPI;
import com.bumptech.bumpapi.BumpAPIListener;
import com.bumptech.bumpapi.BumpConnectFailedReason;
import com.bumptech.bumpapi.BumpConnection;
import com.bumptech.bumpapi.BumpDisconnectReason;
import com.bumptech.bumpapi.BumpResources;

import java.util.HashMap;

@Kroll.module(name="Bump", id="ti.bump")
public class BumpModule extends KrollModule implements TiActivityResultHandler, BumpAPIListener {
	
	private static final String LCAT = "BumpModule";
	private static final boolean DBG = false;
	
	private BumpConnection conn;
	
	private String apiKey = null;
	private String username = null;
	private String bumpMessage = null;

	private final Handler baseHandler = new Handler();

	public BumpModule() {
		super();
		// Setup ourselves as the listener for the result of the Activity
	}

	@Kroll.method
	public void connect(HashMap props) {

        KrollDict propsDict = new KrollDict(props);
		// Process the args to the method
		if (props.containsKey("apikey")) {
			apiKey = TiConvert.toString(propsDict.getString("apikey"));
		} else {
			Log.e(LCAT, "Invalid argument - apikey is required");
		}

		if (props.containsKey("username")) {
			username = TiConvert.toString(propsDict.getString("username"));
		}

		if (props.containsKey("message")) {
			bumpMessage = TiConvert.toString(propsDict.getString("message"));
		}

		// A little extra debugging
		if (DBG) {
			Log.d(LCAT, "Bump Connect arguments:");
			Log.d(LCAT, "apikey: "+apiKey);

			if (null != username) {
				Log.d(LCAT, "username: "+username);
			} else {
				Log.d(LCAT, "username not passed");
			}

			if (null != bumpMessage) {
				Log.d(LCAT, "message: "+bumpMessage);
			} else {
				Log.d(LCAT, "No bump message passed");
			}

		}

		// Call the master connect
		this.connectBump();
	}

	protected void connectBump() {

		Activity activity = TiApplication.getAppCurrentActivity();
		TiActivitySupport activitySupport = (TiActivitySupport) activity;
		final int resultCode = activitySupport.getUniqueResultCode();

		try {
			// Work around for the way they implement resource management
			BumpResources bp = new BumpResources();
			if (DBG) {
				Log.d(LCAT, "Bump Connect Called - setting up Intent");
			}

			Intent bump  = new Intent(activity, BumpAPI.class);
			bump.putExtra(BumpAPI.EXTRA_API_KEY, apiKey);

			// Set some extra args if they are defined
			if (null != username) {
				Log.d(LCAT, "Setting Bump Username: "+username);
				bump.putExtra(BumpAPI.EXTRA_USER_NAME, username);
			}

			if (null != bumpMessage) {
				Log.d(LCAT, "Setting Bump message: "+bumpMessage);
				bump.putExtra(BumpAPI.EXTRA_ACTION_MSG, bumpMessage);
			}

			activitySupport.launchActivityForResult(bump, resultCode, this);

			if (DBG) {
				Log.d(LCAT, "Launched Bump Activity");
			}

			// Bubble up the event
			HashMap eventData = new HashMap();
			this.fireEvent("ready", eventData);

		} catch (Exception e) {
			Log.e(LCAT, "--- Exception: "+e.toString());
		}
	}

	@Kroll.method
	public void sendMessage(String message) {
		if (null != this.conn) {
			
			try {
				byte[] chunk = message.getBytes("UTF-8");
				this.conn.send(chunk);
			} catch (Exception e) {
				Log.e(LCAT, "Error Sending data to other party. "+e.getMessage());
			}
		} else {
			HashMap eventArgs = new HashMap();
			eventArgs.put("message", "Not Connected");
			this.fireEvent("error", eventArgs);
			
			Log.i(LCAT, "Not connected");
		}
	}

	@Override
	public void onResult(Activity activity, int requestCode, int resultCode, Intent data) {
		
		if (DBG) {
			Log.d(LCAT, "Activity onResult with Result: "+resultCode);
		}

		if (resultCode == Activity.RESULT_OK) {
			// Bump connected successfully, set its listener			
			try {
				this.conn = (BumpConnection) data.getParcelableExtra(BumpAPI.EXTRA_CONNECTION);
				conn.setListener(this, baseHandler);
				
				// Fan out the event to the app
				HashMap eventData = new HashMap();
				eventData.put("username", conn.getOtherUserName());
				this.fireEvent("connected", eventData);
				
				if (DBG) {
					Log.i(LCAT, "--- Successfully connected to " + conn.getOtherUserName()+ " ---");				
				}

			} catch (Exception e) {
				Log.e(LCAT, "--- Error: " + e.getMessage() + " ---");				
			}
			
		} else {
			// Failed to connect, obtain the reason
			if (DBG) {
				Log.d(LCAT, "onConnect Fail");
			}

			try {
				BumpConnectFailedReason reason = (BumpConnectFailedReason) data.getSerializableExtra(BumpAPI.EXTRA_REASON);
				
				// Notify the app about the failure
				HashMap eventData = new HashMap();
				eventData.put("message", reason.toString());

				if (reason == BumpConnectFailedReason.FAIL_USER_CANCELED) {
					this.fireEvent("cancel", eventData);
				} else {
					// Notify the app about the failure
					this.fireEvent("error", eventData);
				}
				
				Log.e(LCAT, "--- Failed to connect (" + reason.toString() + ")---");
			} catch (Exception e) {
				// TODO: handle exception
				Log.e(LCAT, "--- Error: " + e.getMessage() + " ---");
			}
		}
	}

	@Override
	public void bumpDataReceived(byte[] chunk) {
		try {
			String data = new String(chunk, "UTF-8");

			if (DBG) {
				Log.d(LCAT,"Received Data from other party: "+data);
			}

			if (DBG) {
				dataReceived(conn.getOtherUserName() + " said: " + data);
			} else {
				dataReceived(data);
			}
		} catch (Exception e) {
			Log.e(LCAT, "Failed to parse incoming data");
		}
	}

	public String dataReceived(String data) {
		// Float up the event to the app
		HashMap eventData = new HashMap();
		eventData.put("data", data);
		this.fireEvent("data",eventData);

		Log.e(LCAT, "Data: "+data);
		return data;
	}

	@Kroll.method
	public void disconnect() {

        if (conn != null) {
			conn.disconnect();
			conn = null;
		}

		if (DBG) {
			Log.d(LCAT, "Bump Disconnect Called ");
		}

		// Float the event to the app
		HashMap eventData = new HashMap();
		eventData.put("message", "END_USER_QUIT");
		this.fireEvent("disconnected", eventData);
		
	}
	
	@Override
	public void onStop(Activity activity) {
		
		if (conn != null) {
			conn.disconnect();
			conn = null;
		}

		super.onStop(activity);
		
		if (DBG) {
			Log.i(LCAT, "--- onStop ");			
		}
		
	}

	@Override
	public void onError(Activity activity, int requestCode, Exception e) {
		if (DBG) {
			Log.e(LCAT, "--- onError "+e.getMessage());
		}
	}

	@Override
	public void bumpDisconnect(BumpDisconnectReason reason) {
		String disconnectDueTo = null;
		
		switch (reason) {
            case END_OTHER_USER_QUIT:
                disconnectDueTo = "END_OTHER_USER_QUIT";
                if (DBG) {
                    dataReceived("--- " + conn.getOtherUserName() + " QUIT ---");
                }
            break;
            case END_OTHER_USER_LOST:
                disconnectDueTo = "END_OTHER_USER_LOST";
                if (DBG) {
                    dataReceived("--- " + conn.getOtherUserName() + " LOST ---");
                }
            break;
            default:
                disconnectDueTo = "UNKNOWN";
            break;
		}
		
		// Float the event to the app
		HashMap eventData = new HashMap();
		eventData.put("message", disconnectDueTo);
		this.fireEvent("disconnected", eventData);
		
	}
	
	
}