package com.usemodj.android.usbsms;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.StringTokenizer;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {
	private static String TAG = "MainActivity";
	private static String ACTION_USB_PERMISSION = "com.usemodj.android.usbsms.USB_PERMISSION";
	private static String MANUFACTURER = "NodeSoft";
	Context mContext;
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
		  
		mContext = this;
		IntentFilter i = new IntentFilter();
		i.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
		i.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		i.addAction(ACTION_USB_PERMISSION);
		registerReceiver(mUsbReceiver, i);

		if (getIntent().getAction().equals(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
			Log.d(TAG, "Action is Usb Accessory Attached");
			((TextView) findViewById(R.id.message)).setText("USB Accessory Attached");
			Toast.makeText(mContext, "USB Accessory Attached", Toast.LENGTH_SHORT).show();

			UsbAccessory accessory = UsbManager.getAccessory(getIntent());
			mAccessory = accessory;
			FileDescriptor fd = null;
			try {
				fd = UsbManager.getInstance(this).openAccessory(accessory).getFileDescriptor();
			} catch (IllegalArgumentException e) {
				finish();
			} catch (NullPointerException e) {
				finish();
			}
			mFout = new FileOutputStream(fd);
			mFin = new FileInputStream(fd);
			
			Log.d(TAG, "Loop Start Read and SMS...");
			if(mThread != null && mThread.isAlive()) mThread.interrupt();
			mThread = queueReadAndSMS();

		} else {
			UsbAccessory[] accessories = UsbManager.getInstance(this).getAccessoryList();
			if (accessories != null) {
				for (UsbAccessory a : accessories) {
					Log.d(TAG, "accessory: " + a.getManufacturer());
					if (a.getManufacturer().equals(MANUFACTURER)) {
						mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
						UsbManager.getInstance(this).requestPermission(a, mPermissionIntent);
						Log.d(TAG, "permission requested");
						break;
					}
				}
			} else {
				Log.d(TAG, "UsbAccessory is null!");
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
		unregisterReceiver(mUsbReceiver);
		super.onDestroy();
	}

	public void queueWrite(final String data) {
		if (mAccessory == null) {
			return;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Log.d(TAG, "Writing data: " + data);
					mFout.write(data.getBytes());
					Log.d(TAG, "Done writing");

				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

	public Thread queueReadAndSMS() {
		if (mAccessory == null) {
			return null;
		}
		
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				for (;;) {
					Log.d(TAG,  "loop");
					try {
						byte[] buff = new byte[1024];
						int len = mFin.read(buff);
						String msg = new String(buff, 0, len, "UTF-8");
						Log.d(TAG, ">>> Read: " + msg);
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
							sendSMS(smsNumber, smsText);
						} else {
							// Result Response to Usb Host
							queueWrite("Invalid Message Format.");
						}

						if(Thread.currentThread().isInterrupted())
							throw new InterruptedException();

					} catch (IOException e) {
						e.printStackTrace();
					} catch (InterruptedException e) {
						return;
					}

					
				} //for loop end
			}
		});
		thread.start();
		return thread;
		
	}

	public void sendSMS(String smsNumber, String smsText) {
		PendingIntent sentIntent = PendingIntent.getBroadcast(this, 0, new Intent("SMS_SENT_ACTION"), 0);
		PendingIntent deliveredIntent = PendingIntent.getBroadcast(this, 0, new Intent("SMS_DELIVERED_ACTION"), 0);

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String result = "";
				switch (getResultCode()) {
				case Activity.RESULT_OK:
					// ���ۼ���
					result = "���� �Ϸ�";
					break;
				case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
					// ���� ����
					result = "���� ����";
					break;
				case SmsManager.RESULT_ERROR_NO_SERVICE:
					// ���� ���� �ƴ�
					result = "���� ������ �ƴմϴ�";
					break;
				case SmsManager.RESULT_ERROR_RADIO_OFF:
					// ���� ����
					result = "����(Radio)�� �����ֽ��ϴ�";
					break;
				case SmsManager.RESULT_ERROR_NULL_PDU:
					// PDU ����
					result = "PDU Null";
					break;

				}

				Toast.makeText(mContext, result, Toast.LENGTH_SHORT).show();
				// Result Response to Usb Host
				queueWrite(result);
			}
		}, new IntentFilter("SMS_SENT_ACTION"));

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				switch (getResultCode()) {
				case Activity.RESULT_OK:
					// ���� �Ϸ�
					Toast.makeText(mContext, "SMS ���� �Ϸ�", Toast.LENGTH_SHORT).show();
					break;
				case Activity.RESULT_CANCELED:
					// ���� �ȵ�
					Toast.makeText(mContext, "SMS ���� ����", Toast.LENGTH_SHORT).show();
					break;
				}
			}
		}, new IntentFilter("SMS_DELIVERED_ACTION"));

		SmsManager mSmsManager = SmsManager.getDefault();
		mSmsManager.sendTextMessage(smsNumber, null, smsText, sentIntent, deliveredIntent);
	}

	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				Log.d(TAG, "USB Accessory Attached!");
				((TextView) findViewById(R.id.message)).setText("USB Accessory Attached");
				Toast.makeText(mContext, "USB Accessory Attached", Toast.LENGTH_SHORT).show();

				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					// openAccessory(accessory);
					mAccessory = accessory;
					FileDescriptor fd = null;
					try {
						fd = UsbManager.getInstance(getApplicationContext()).openAccessory(accessory)
								.getFileDescriptor();
					} catch (IllegalArgumentException e) {
						finish();
					} catch (NullPointerException e) {
						finish();
					}

					mFout = new FileOutputStream(fd);
					mFin = new FileInputStream(fd);

					Log.d(TAG, "Loop Start Read and SMS...");
					if(mThread != null && mThread.isAlive()) mThread.interrupt();
					mThread = queueReadAndSMS();

				} else {
					Log.d("USB", "permission denied for accessory " + accessory);
				}
			} else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				((TextView) findViewById(R.id.message)).setText("USB Accessory Detached");
				Toast.makeText(mContext, "USB Accessory Detached", Toast.LENGTH_SHORT).show();

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
			} else if (ACTION_USB_PERMISSION.equals(action)) {
				Log.d(TAG, "permission answered");
				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					UsbAccessory[] accessories = UsbManager.getInstance(getApplicationContext()).getAccessoryList();
					for (UsbAccessory a : accessories) {
						Log.d(TAG, "accessory: " + a.getManufacturer());
						if (a.getManufacturer().equals(MANUFACTURER)) {
							mAccessory = a;
							FileDescriptor fd = null;
							try {
								fd = UsbManager.getInstance(getApplicationContext()).openAccessory(a)
										.getFileDescriptor();
							} catch (IllegalArgumentException e) {
								finish();
							} catch (NullPointerException e) {
								finish();
							}

							mFout = new FileOutputStream(fd);
							mFin = new FileInputStream(fd);
							
							Log.d(TAG, "added accessory");
							
							Log.d(TAG, "Loop Start Read and SMS...");
							if(mThread != null && mThread.isAlive()) mThread.interrupt();
							mThread = queueReadAndSMS();

							break;
						}
					}
				}
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
