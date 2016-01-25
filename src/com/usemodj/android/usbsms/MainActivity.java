package com.usemodj.android.usbsms;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;



@TargetApi(23)
public class MainActivity extends AppCompatActivity {
	final private static String TAG = "MainActivity";
	//final private int REQUEST_CODE_ASK_PERMISSIONS = 123;
	final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124; 
	final private String SEND_SMS_ACTION = "SEND_SMS_ACTION";
	private static String ACTION_USB_PERMISSION = "com.usemodj.android.usbsms.USB_PERMISSION";
	private static String MANUFACTURER = "NodeSoft";
	private Context mContext;
	private UsbAccessory mAccessory = null;
	private Button mBtSend = null;
	private FileOutputStream mFout = null;
	private FileInputStream mFin = null;
	private PendingIntent mPermissionIntent = null;
	private Button mBtRestart = null;
	private Thread mThread = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Toolbar toolbar = (Toolbar)findViewById(R.id.toolbar);
		//Toolbar will now take on default Action Bar characteristics
		setSupportActionBar(toolbar);

		mContext = getApplicationContext();
		IntentFilter i = new IntentFilter();
		i.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
		i.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		i.addAction(ACTION_USB_PERMISSION);

		registerReceiver(mUsbReceiver, i);
		registerReceiver(mSmsSentReceiver, new IntentFilter("SMS_SENT_ACTION"));
		registerReceiver(mSmsDelivedReceiver, new IntentFilter("SMS_DELIVERED_ACTION"));
		registerReceiver(mSendSmsReceiver, new IntentFilter(SEND_SMS_ACTION));



		if (getIntent().getAction().equals(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
			Log.d(TAG, "Action is Usb Accessory Attached");
			//Toast.makeText(mContext, "1. USB Accessory Attached", Toast.LENGTH_SHORT).show();
			//((TextView) findViewById(R.id.message)).setText("USB Accessory Attached");

			UsbAccessory accessory = UsbManager.getAccessory(getIntent());
			try {
				ParcelFileDescriptor pfd = UsbManager.getInstance( mContext).openAccessory(accessory);
				if(pfd != null) {
					pfd.checkError();
					mAccessory = accessory;
					FileDescriptor fd = pfd.getFileDescriptor();
					mFout = new FileOutputStream(fd);
					mFin = new FileInputStream(fd);

					Log.d(TAG, "Loop Start Read and SMS...");
					synchronized(this){
						if(mThread != null && mThread.isAlive()) mThread.interrupt(); 
						mThread = queueReadAndSMS();	
					}
				} else {
					Toast.makeText(mContext, "Accessory open failed.", Toast.LENGTH_SHORT).show();
				}

			} catch (IllegalArgumentException e) {
				Toast.makeText(mContext, e.getMessage(),  Toast.LENGTH_SHORT).show();
				finish();
			} catch (NullPointerException e) {
				Toast.makeText(mContext, e.getMessage(),  Toast.LENGTH_SHORT).show();
				finish();
			} catch (IOException e) {
				Toast.makeText(mContext, e.getMessage(),  Toast.LENGTH_SHORT).show();
				finish();
			}

		} else {
			//Toast.makeText(mContext, "permission requesting.", Toast.LENGTH_SHORT).show();
			UsbManager manager = UsbManager.getInstance( mContext);
			try {
				UsbAccessory[] accessories = manager.getAccessoryList();
				if (accessories != null) {
					for (UsbAccessory a : accessories) {
						Log.d(TAG, "accessory: " + a.getManufacturer());
						if (a.getManufacturer().equals(MANUFACTURER)) {
							//mAccessory = a;
							mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
							manager.requestPermission(a, mPermissionIntent);
							Log.d(TAG, "permission requested");
							//Toast.makeText(mContext, "Request ACTION_USB_PERMISSION: manufacturer: "+ a.getManufacturer(), Toast.LENGTH_SHORT).show();

							break;
						}
					}
				} else {
					Log.d(TAG, "UsbAccessory is null!");
					//Toast.makeText(mContext, "USB Accessory is Null.", Toast.LENGTH_SHORT).show();
				
				}
			} catch (Exception e) {
				e.printStackTrace();
				Toast.makeText(mContext, e.getMessage(),  Toast.LENGTH_SHORT).show();
				finish();
			}
		}

		mBtRestart = (Button)(findViewById(R.id.restart));
		mBtRestart.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				//  				if(mThread != null && mThread.isAlive()) mThread.interrupt();
				//  				Toast.makeText(mContext, "SMS Sending Ready", Toast.LENGTH_SHORT).show();
				//  				mThread = queueReadAndSMS();
				doRestart(mContext);
			}
		});

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		if(mThread != null) mThread.interrupt();
		unregisterReceiver(mUsbReceiver);
		unregisterReceiver(mSmsSentReceiver);
		unregisterReceiver(mSmsDelivedReceiver);

		super.onDestroy();
	}

//	@Override
//	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
//	    switch (requestCode) {
//	        case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS:
//	            {
//	            Map<String, Integer> perms = new HashMap<String, Integer>();
//	            // Initial
//	            perms.put(Manifest.permission.RECEIVE_SMS, PackageManager.PERMISSION_GRANTED);
//	            perms.put(Manifest.permission.SEND_SMS, PackageManager.PERMISSION_GRANTED);
//	            perms.put(Manifest.permission.READ_PHONE_STATE, PackageManager.PERMISSION_GRANTED);
//	            // Fill with results
//	            for (int i = 0; i < permissions.length; i++)
//	                perms.put(permissions[i], grantResults[i]);
//	            // Check for ACCESS_FINE_LOCATION
//	            if (perms.get(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
//	                    && perms.get(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
//	                    && perms.get(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
//	                // All Permissions Granted
//	                //insertDummyContact();
//	            } else {
//	                // Permission Denied
//	                Toast.makeText(MainActivity.this, "Some Permission is Denied", Toast.LENGTH_SHORT)
//	                        .show();
//	            }
//	            }
//	            break;
//	        default:
//	            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//	    }
//	}
	
	public void queueWrite(final String data) {
		if (mAccessory == null || mFout == null) {
			//Toast.makeText(mContext, "Accessary or FileOutputStream is  null.", Toast.LENGTH_SHORT).show();
			return;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					synchronized(this){
//						runOnUiThread(new Runnable(){
//							@Override
//							public void run() {
//								Toast.makeText(mContext, data, Toast.LENGTH_SHORT).show();
//							}
//						});
						Log.d(TAG, "Writing data: " + data);
						mFout.write(data.getBytes());
						Log.d(TAG, "Done writing");
					}
				} catch (Exception e) {
					e.printStackTrace();
					//Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
				}
			}
		}).start();
	}

	public Thread queueReadAndSMS() {
		//TODO: DEBUG
		//Toast.makeText(mContext, "Run queueReadAndSMS()  ", Toast.LENGTH_SHORT).show();
		if (mAccessory == null || mFin == null) {
			//Toast.makeText(mContext, "Accessary or FileInputStream is Null.", Toast.LENGTH_SHORT).show();
			return null;
		}

		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				for (;;) {
					Log.d(TAG,  "loop");
					try {
						byte[] buff = new byte[1024];
						int len = 0;
						synchronized(this){
							len = mFin.read(buff);
						}
						String msg = new String(buff, 0, len, "UTF-8");
						Log.d(TAG, ">>> Read: " + msg);
						//TODO:DEBUG
						// ((TextView)findViewById(R.id.textView2)).setText(msg);
						// Message Format: "smsNumber#smsText"
						// "01012341234#Hello Message"

						String smsNumber = null;
						String smsText = null;
						StringTokenizer st = new StringTokenizer(msg, "#");
						if (st.hasMoreTokens()) {
							smsNumber = st.nextToken();
							if (st.hasMoreTokens()) {
								smsText = st.nextToken();
							}
						}

						if (smsNumber != null && smsText != null) {
							//sendSMS(smsNumber, smsText);
							//sendSMSWrapper(smsNumber, smsText);
							Intent i = new Intent();
							i.setAction(SEND_SMS_ACTION);
							i.putExtra("smsNumber", smsNumber);
							i.putExtra("smsText", smsText);
							sendBroadcast(i);
							
						} else {
							// Result Response to Usb Host
							queueWrite("Invalid Message Format.");
						}
						
						if(Thread.currentThread().isInterrupted())
							throw new InterruptedException();

						//Thread.yield();
						Thread.sleep(10);
					} catch (InterruptedException e) {
						return;
					} catch (Exception e) {
						Log.d(TAG, e.getMessage());
					}

				} //for loop end
			}
		});
		//thread.setDaemon(true);
		thread.start();
		return thread;

	}

	public void sendSMSWrapper(String smsNumber, String smsText) {
		// the only way we insert the dummy contact if if we are below M.
	    // Else we continue on and prompt the user for permissions
	    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
	    	sendSMS(smsNumber, smsText);
	        return;
	    }
	    
	    List<String> permissionsNeeded = new ArrayList<String>();
	    
	    final List<String> permissionsList = new ArrayList<String>();
	    if (!addPermission(permissionsList, Manifest.permission.RECEIVE_SMS))
	        permissionsNeeded.add("Receive SMS");
	    if (!addPermission(permissionsList, Manifest.permission.SEND_SMS))
	        permissionsNeeded.add("Send SMS");
	    if (!addPermission(permissionsList, Manifest.permission.READ_PHONE_STATE))
	        permissionsNeeded.add("Read Phone State");
	 
	    if (permissionsList.size() > 0) {
	        if (permissionsNeeded.size() > 0) {
	            // Need Rationale
	            String message = "You need to grant access to " + permissionsNeeded.get(0);
	            for (int i = 1; i < permissionsNeeded.size(); i++)
	                message = message + ", " + permissionsNeeded.get(i);
	            
	            showMessageOKCancel(message,
	                    new DialogInterface.OnClickListener() {
	                        @Override
	                        public void onClick(DialogInterface dialog, int which) {
	                            requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
	                                    REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
	                        }
	                    });
	            return;
	        }
	        requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
	                REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
	        return;
	    }
	 
	    sendSMS(smsNumber, smsText);
	}
	
	private boolean addPermission(List<String> permissionsList, String permission) {
	    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
	        permissionsList.add(permission);
	        // Check for Rationale Option
	        if (!shouldShowRequestPermissionRationale(permission))
	            return false;
	    }
	    return true;
	}

	private void showMessageOKCancel(String message, DialogInterface.OnClickListener okListener) {
	    new AlertDialog.Builder(MainActivity.this)
	            .setMessage(message)
	            .setPositiveButton("OK", okListener)
	            .setNegativeButton("Cancel", null)
	            .create()
	            .show();
	}
	
	public void sendSMS(String smsNumber, String smsText) {
		//Toast.makeText(mContext, smsText, Toast.LENGTH_SHORT).show();

		PendingIntent sentIntent = PendingIntent.getBroadcast(this, 0, new Intent("SMS_SENT_ACTION"), 0);
		PendingIntent deliveredIntent = PendingIntent.getBroadcast(this, 0, new Intent("SMS_DELIVERED_ACTION"), 0);
		SmsManager mSmsManager = SmsManager.getDefault();
		mSmsManager.sendTextMessage(smsNumber, null, smsText, sentIntent, deliveredIntent);

	}

	BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				Log.d(TAG, "USB Accessory Attached!");
				//((TextView) findViewById(R.id.message)).setText("USB Accessory Attached");
				//Toast.makeText(mContext, "USB Accessory Attached", Toast.LENGTH_SHORT).show();

//				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					// openAccessory(accessory);
					try {

						ParcelFileDescriptor pfd = UsbManager.getInstance(getApplicationContext()).openAccessory(accessory);
						if(pfd != null){
							pfd.checkError();
							mAccessory = accessory;
							FileDescriptor fd = pfd.getFileDescriptor();
							mFout = new FileOutputStream(fd);
							mFin = new FileInputStream(fd);

							Log.d(TAG, "Loop Start Read and SMS...");
							synchronized(this){
								if(mThread != null && mThread.isAlive()) mThread.interrupt();
								mThread = queueReadAndSMS();	
							}
						} else {
							Toast.makeText(mContext, "Accessory open failed.", Toast.LENGTH_SHORT).show();
						}


					} catch (IllegalArgumentException e) {
						Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
						finish();

					} catch (NullPointerException e) {
						Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
						finish();

					} catch (Exception e) {
						Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
						finish();
					}

//				} else {
//					Log.d("USB", "permission denied for accessory " + accessory);
//					//Toast.makeText(mContext, "permission denied for accessory " + accessory, Toast.LENGTH_SHORT).show();
//				}
			} else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				((TextView) findViewById(R.id.message)).setText("USB Accessory Detached");
				Toast.makeText(mContext, "USB Accessory Detached", Toast.LENGTH_SHORT).show();

				if(mThread != null && mThread.isAlive()) mThread.interrupt();
				
				if (accessory != null && accessory.equals(mAccessory)) {
					if (mFout != null)
						try {
							mFout.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					if (mFin != null) {
						try {
							mFin.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					mAccessory = null;
				}
				
				finish();
			} 
			else if (ACTION_USB_PERMISSION.equals(action)) {
				Log.d(TAG, "permission answered");
				//Toast.makeText(mContext, "permission answered", Toast.LENGTH_SHORT).show();

				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					UsbAccessory[] accessories = UsbManager.getInstance( mContext).getAccessoryList();
					for (UsbAccessory a : accessories) {
						Log.d(TAG, "accessory: " + a.getManufacturer());
						if (a.getManufacturer().equals(MANUFACTURER)) {
							try {
								ParcelFileDescriptor pfd = UsbManager.getInstance( mContext).openAccessory(a);
								if(pfd != null){
									pfd.checkError();
									mAccessory = a;
									FileDescriptor fd = pfd.getFileDescriptor();
									mFout = new FileOutputStream(fd);
									mFin = new FileInputStream(fd);

									synchronized(this){
										if(mThread != null && mThread.isAlive()) mThread.interrupt(); 
										mThread = queueReadAndSMS();	
									}
								} else {
									Toast.makeText(mContext, "Accessory open failed", Toast.LENGTH_SHORT).show();
								}

							} catch (IllegalArgumentException e) {
								Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
								finish();

							} 
							catch (NullPointerException e) {
								Toast.makeText(mContext, "permission answered: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
								finish();
							
							} catch (Exception e) {
								Toast.makeText(mContext, "permission answered: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
								finish();
							
							}

							Log.d(TAG, "added accessory");
							//Toast.makeText(mContext, "added accessory", Toast.LENGTH_SHORT).show();

							break;
						}
					}
				}
			}
		}
	};

	BroadcastReceiver mSmsSentReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String result = "";
			switch (getResultCode()) {
			case Activity.RESULT_OK:
				//
				result = "SMS Sent OK";
				break;
			case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
				// 
				result = "SMS Sent Failure";
				break;
			case SmsManager.RESULT_ERROR_NO_SERVICE:
				// 
				result = "SMS Sent No Service";
				break;
			case SmsManager.RESULT_ERROR_RADIO_OFF:
				//
				result = "SMS Sent Radio Off";
				break;
			case SmsManager.RESULT_ERROR_NULL_PDU:
				// 
				result = "SMS Sent Null PDU";
				break;

			}

			Toast.makeText(mContext, result, Toast.LENGTH_SHORT).show();
			// Result Response to Usb Host
			queueWrite(result);
		}
	};


	BroadcastReceiver mSmsDelivedReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			switch (getResultCode()) {
			case Activity.RESULT_OK:
				//SMS sending sucess
				Toast.makeText(mContext, "SMS Sending Sucess.", Toast.LENGTH_SHORT).show();
				break;
			case Activity.RESULT_CANCELED:
				// SMS sending fail
				Toast.makeText(mContext, "SMS Sending Fail.", Toast.LENGTH_SHORT).show();
				break;
			}
		}
	};

	BroadcastReceiver mSendSmsReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(SEND_SMS_ACTION.equals(action)){
				String smsNumber = intent.getStringExtra("smsNumber");
				String smsText = intent.getStringExtra("smsText");
				sendSMSWrapper(smsNumber, smsText);
			}
		}
	};


	public static void doRestart(Context c) {
		try {
			//check if the context is given
			if (c != null) {
				//fetch the packagemanager so we can get the default launch activity 
				// (you can replace this intent with any other activity if you want
				PackageManager pm = c.getPackageManager();
				//check if we got the PackageManager
				if (pm != null) {
					//create the intent with the default start activity for your application
					Intent mStartActivity = pm.getLaunchIntentForPackage(
							c.getPackageName()
							);
					if (mStartActivity != null) {
						mStartActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
						//create a pending intent so the application is restarted after System.exit(0) was called. 
						// We use an AlarmManager to call this intent in 100ms
						int mPendingIntentId = 223344;
						PendingIntent mPendingIntent = PendingIntent
								.getActivity(c, mPendingIntentId, mStartActivity,
										PendingIntent.FLAG_CANCEL_CURRENT);
						AlarmManager mgr = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
						mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent);
						//kill the application
						System.exit(0);
					} else {
						Log.e(TAG, "Was not able to restart application, mStartActivity null");
					}
				} else {
					Log.e(TAG, "Was not able to restart application, PM null");
				}
			} else {
				Log.e(TAG, "Was not able to restart application, Context null");
			}
		} catch (Exception ex) {
			Log.e(TAG, "Was not able to restart application");
		}
	}
}
