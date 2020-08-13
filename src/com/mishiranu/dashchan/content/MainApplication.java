package com.mishiranu.dashchan.content;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Process;
import androidx.annotation.NonNull;
import chan.content.ChanManager;
import chan.http.HttpClient;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.content.storage.DatabaseHelper;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.Log;
import java.io.File;
import java.util.Collections;
import java.util.List;

public class MainApplication extends Application {
	private static MainApplication instance;

	public MainApplication() {
		instance = this;
	}

	private boolean checkProcess(String suffix) {
		return StringUtils.equals(suffix, processSuffix);
	}

	public boolean isMainProcess() {
		return checkProcess(null);
	}

	private String processSuffix;

	@Override
	public void onCreate() {
		super.onCreate();

		ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
		if (processes == null) {
			processes = Collections.emptyList();
		}
		int pid = Process.myPid();
		for (ActivityManager.RunningAppProcessInfo process : processes) {
			if (process.pid == pid) {
				int index = process.processName.indexOf(':');
				if (index >= 0) {
					processSuffix = StringUtils.nullIfEmpty(process.processName.substring(index + 1));
				}
				break;
			}
		}

		if (isMainProcess()) {
			Log.init(this);
			// Init
			ChanManager.getInstance();
			HttpClient.getInstance();
			DatabaseHelper.getInstance();
			CacheManager.getInstance();
			LocaleManager.getInstance().apply(this, false);
			ChanManager.getInstance().loadLibraries();
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		if (checkProcess(null)) {
			LocaleManager.getInstance().apply(this, true);
		}
	}

	public static MainApplication getInstance() {
		return instance;
	}

	@TargetApi(Build.VERSION_CODES.KITKAT)
	public boolean isLowRam() {
		if (C.API_KITKAT) {
			ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
			return activityManager != null && activityManager.isLowRamDevice();
		} else {
			return Runtime.getRuntime().maxMemory() <= 64 * 1024 * 1024;
		}
	}

	@Override
	public File getDir(String name, int mode) {
		String suffix = "webview";
		if (checkProcess(suffix)) {
			File dir = super.getCacheDir();
			dir = new File(dir, suffix.equals(name) ? name : suffix + "-" + name);
			IOUtils.deleteRecursive(dir);
			dir.mkdirs();
			return dir;
		} else {
			return super.getDir(name, mode);
		}
	}
}
