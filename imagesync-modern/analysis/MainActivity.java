package com.ricohimaging.imagesync;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.tabs.TabLayout;
import com.ricoh.camera.sdk.wireless.api.CameraDevice;
import com.ricoh.camera.sdk.wireless.api.CameraDeviceDetector;
import com.ricoh.camera.sdk.wireless.api.CameraEventListener;
import com.ricoh.camera.sdk.wireless.api.DetectState;
import com.ricoh.camera.sdk.wireless.api.DeviceInterface;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import k1.t;
import m.b1;
import m.d2;
import m.d3;
import m.e2;
import m.f2;
import m.m3;
import n.e;
import o.f;
import s.g;
import s.j;
import s.o;

/* compiled from: r8-map-id-c9b316a91c3bd4bbe1b6558d2d0e21dc8cc48c6b20222ca4031b2a9ce3f49e32 */
/* loaded from: classes2.dex */
public class MainActivity extends b1 {

    /* renamed from: s, reason: collision with root package name */
    public static CameraDevice f679s = null;

    /* renamed from: t, reason: collision with root package name */
    public static boolean f680t = false;

    /* renamed from: u, reason: collision with root package name */
    public static boolean f681u = false;

    /* renamed from: a, reason: collision with root package name */
    public final List f682a = Arrays.asList("deviceImages", "cameraImages", "setting");
    public String b;

    /* renamed from: c, reason: collision with root package name */
    public MainActivity f683c;
    public SvApplication d;
    public TabLayout f;

    /* renamed from: g, reason: collision with root package name */
    public CameraDevice f684g;

    /* renamed from: h, reason: collision with root package name */
    public f f685h;

    /* renamed from: i, reason: collision with root package name */
    public Timer f686i;
    public e j;
    public s.e k;
    public d3 l;

    /* renamed from: m, reason: collision with root package name */
    public int f687m;

    /* renamed from: n, reason: collision with root package name */
    public int f688n;

    /* renamed from: o, reason: collision with root package name */
    public volatile boolean f689o;

    /* renamed from: p, reason: collision with root package name */
    public String f690p;

    /* renamed from: q, reason: collision with root package name */
    public t f691q;

    /* renamed from: r, reason: collision with root package name */
    public String f692r;

    public MainActivity() {
        new LinkedHashMap();
        new LinkedHashMap();
        this.f686i = null;
        this.j = null;
        this.k = null;
        this.l = null;
        this.f687m = 0;
        this.f688n = 0;
        new HashMap();
        new HashMap();
        this.f689o = false;
        this.f690p = null;
        this.f691q = null;
        this.f692r = null;
    }

    public static boolean l(CameraDevice cameraDevice) {
        if (!g.S(cameraDevice)) {
            return true;
        }
        String firmwareVersion = cameraDevice.getFirmwareVersion();
        return firmwareVersion.isEmpty() || ((double) Float.parseFloat(firmwareVersion)) > 1.0d;
    }

    public final void m(SvApplication svApplication) {
        if (svApplication.f783a == null && this.f686i == null) {
            this.f686i = new Timer();
            e eVar = new e(this);
            this.j = eVar;
            this.f686i.schedule(eVar, 3000L, 1000L);
        }
    }

    public final String n() {
        return (String) this.f682a.get(this.f.getSelectedTabPosition());
    }

    public final boolean o() {
        View viewFindViewById = findViewById(R.id.overlay_fragment_container);
        boolean z2 = viewFindViewById != null && viewFindViewById.getVisibility() == 0;
        Fragment fragmentFindFragmentByTag = getSupportFragmentManager().findFragmentByTag("TAG_OVERLAY");
        return z2 || (fragmentFindFragmentByTag != null && fragmentFindFragmentByTag.isAdded() && !fragmentFindFragmentByTag.isHidden());
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public final void onCreate(Bundle bundle) {
        if (f681u) {
            super.onCreate(null);
        } else {
            super.onCreate(bundle);
            f681u = true;
        }
        setContentView(R.layout.activity_main);
        this.f683c = this;
        SvApplication svApplication = (SvApplication) getApplication();
        this.d = svApplication;
        this.f684g = svApplication.f783a;
        MainActivity mainActivity = this.f683c;
        f fVar = new f();
        ConnectivityManager connectivityManager = (ConnectivityManager) mainActivity.getSystemService("connectivity");
        fVar.f1667a = connectivityManager;
        this.f685h = fVar;
        if (connectivityManager != null) {
            NetworkRequest networkRequestBuild = new NetworkRequest.Builder().addTransportType(1).build();
            j jVar = new j(fVar, 0);
            fVar.b = jVar;
            connectivityManager.registerNetworkCallback(networkRequestBuild, jVar);
        }
        this.f = (TabLayout) findViewById(R.id.tabLayout);
        View viewInflate = LayoutInflater.from(this).inflate(R.layout.app_album_tab_layout, (ViewGroup) null);
        TabLayout tabLayout = this.f;
        tabLayout.addTab(tabLayout.newTab().setCustomView(viewInflate), false);
        View viewInflate2 = LayoutInflater.from(this).inflate(R.layout.camera_album_tab_layout, (ViewGroup) null);
        TabLayout tabLayout2 = this.f;
        tabLayout2.addTab(tabLayout2.newTab().setCustomView(viewInflate2), false);
        View viewInflate3 = LayoutInflater.from(this).inflate(R.layout.setting_tab_layout, (ViewGroup) null);
        TabLayout tabLayout3 = this.f;
        tabLayout3.addTab(tabLayout3.newTab().setCustomView(viewInflate3), false);
        this.f.addOnTabSelectedListener((TabLayout.OnTabSelectedListener) new d2(this));
        this.f.getViewTreeObserver().addOnGlobalLayoutListener(new e2(this));
        getSupportActionBar().setElevation(0.0f);
        boolean z2 = this.d.f789n;
        List list = this.f682a;
        if (z2) {
            TabLayout tabLayout4 = this.f;
            tabLayout4.selectTab(tabLayout4.getTabAt(list.indexOf("setting")));
            this.d.f789n = false;
        } else {
            CameraDevice cameraDevice = this.f684g;
            if (cameraDevice == null || !cameraDevice.isConnected(DeviceInterface.WLAN) || !l(this.f684g)) {
                TabLayout tabLayout5 = this.f;
                tabLayout5.selectTab(tabLayout5.getTabAt(list.indexOf("deviceImages")));
                m(this.d);
            } else if (g.F(this.f684g.getModel())) {
                TabLayout tabLayout6 = this.f;
                tabLayout6.selectTab(tabLayout6.getTabAt(list.indexOf("cameraImages")));
            } else {
                TabLayout tabLayout7 = this.f;
                tabLayout7.selectTab(tabLayout7.getTabAt(list.indexOf("deviceImages")));
                new AlertDialog.Builder(this).setMessage(R.string.error_msg_camera_not_supported).setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
            }
        }
        this.b = (String) list.get(this.f.getSelectedTabPosition());
        this.d.c(this);
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onDestroy() {
        j jVar;
        super.onDestroy();
        f fVar = this.f685h;
        ConnectivityManager connectivityManager = (ConnectivityManager) fVar.f1667a;
        if (connectivityManager != null && (jVar = (j) fVar.b) != null) {
            connectivityManager.unregisterNetworkCallback(jVar);
        }
        s.e eVar = this.k;
        if (eVar != null) {
            eVar.a(true);
            this.k = null;
        }
        CameraDevice cameraDevice = this.f684g;
        if (cameraDevice != null) {
            for (CameraEventListener cameraEventListener : cameraDevice.getEventListeners()) {
                if (cameraEventListener instanceof f2) {
                    this.f684g.removeEventListener(cameraEventListener);
                    return;
                }
            }
        }
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public final void onNewIntent(Intent intent) {
        String str;
        CameraDevice cameraDevice;
        super.onNewIntent(intent);
        String stringExtra = intent.getStringExtra("cameraImages");
        if (intent.getBooleanExtra("MOVE_TO_PREVIOUS_TAB", false) && (str = this.b) != null) {
            int iHashCode = str.hashCode();
            if (iHashCode != -1226735107) {
                if (iHashCode == -1221331858) {
                    str.equals("deviceImages");
                } else if (iHashCode == 1985941072) {
                    str.equals("setting");
                }
            } else if (str.equals("cameraImages") && ((cameraDevice = this.d.f783a) == null || !cameraDevice.isConnected(DeviceInterface.WLAN))) {
                String str2 = this.f692r;
                stringExtra = (str2 == null || str2.equals("cameraImages")) ? "deviceImages" : this.f692r;
            }
        }
        if (stringExtra != null) {
            r(stringExtra);
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onPause() {
        super.onPause();
        this.f689o = true;
        t();
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onResume() {
        super.onResume();
        int i3 = 0;
        this.f689o = false;
        f2 f2Var = new f2(this);
        CameraDevice cameraDevice = this.d.f783a;
        this.f684g = cameraDevice;
        if (cameraDevice != null) {
            CameraEventListener[] eventListeners = cameraDevice.getEventListeners();
            int length = eventListeners.length;
            while (true) {
                if (i3 >= length) {
                    this.f684g.addEventListener(f2Var);
                    break;
                } else if (eventListeners[i3] instanceof f2) {
                    break;
                } else {
                    i3++;
                }
            }
        }
        t tVar = this.f691q;
        if (tVar != null) {
            this.f691q = null;
            q(tVar.b);
        } else {
            String str = this.f690p;
            if (str != null) {
                this.f690p = null;
            } else {
                str = this.b;
            }
            this.f.getTabAt(this.f682a.indexOf(str)).select();
        }
        if (this.f684g == null) {
            m(this.d);
        }
    }

    public final void p() {
        FragmentManager supportFragmentManager = getSupportFragmentManager();
        Fragment fragmentFindFragmentByTag = supportFragmentManager.findFragmentByTag("TAG_OVERLAY");
        if (fragmentFindFragmentByTag != null) {
            FragmentTransaction fragmentTransactionBeginTransaction = supportFragmentManager.beginTransaction();
            try {
                fragmentTransactionBeginTransaction.remove(fragmentFindFragmentByTag).commit();
            } catch (IllegalStateException e) {
                e.toString();
                int i3 = o.f1842a;
                fragmentTransactionBeginTransaction.remove(fragmentFindFragmentByTag).commitAllowingStateLoss();
            }
        }
        findViewById(R.id.overlay_fragment_container).setVisibility(8);
    }

    public final void q(String str) {
        String str2;
        FragmentManager supportFragmentManager = getSupportFragmentManager();
        if (supportFragmentManager.isStateSaved() || this.f689o) {
            this.f691q = new t(str, 1);
            return;
        }
        Fragment fragmentFindFragmentByTag = supportFragmentManager.findFragmentByTag("cameraImages");
        if (fragmentFindFragmentByTag != null) {
            FragmentTransaction fragmentTransactionBeginTransaction = supportFragmentManager.beginTransaction();
            fragmentTransactionBeginTransaction.remove(fragmentFindFragmentByTag);
            try {
                fragmentTransactionBeginTransaction.commit();
            } catch (IllegalStateException e) {
                e.toString();
                int i3 = o.f1842a;
                try {
                    supportFragmentManager.beginTransaction().remove(fragmentFindFragmentByTag).commitAllowingStateLoss();
                } catch (Exception unused) {
                }
            }
        }
        if (str.equals("deviceImages") && (str2 = this.b) != null && str2.equals("setting")) {
            return;
        }
        r(str);
    }

    public final void r(String str) {
        if (getSupportFragmentManager().isStateSaved()) {
            if (this.f689o) {
                this.f690p = str;
            }
        } else {
            TabLayout.Tab tabAt = this.f.getTabAt(this.f682a.indexOf(str));
            Objects.requireNonNull(tabAt);
            tabAt.select();
        }
    }

    public final void s(m3 m3Var) {
        FragmentManager supportFragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransactionBeginTransaction = supportFragmentManager.beginTransaction();
        Fragment fragmentFindFragmentByTag = supportFragmentManager.findFragmentByTag("TAG_OVERLAY");
        if (fragmentFindFragmentByTag == null) {
            fragmentTransactionBeginTransaction.add(R.id.overlay_fragment_container, m3Var, "TAG_OVERLAY");
        } else if (fragmentFindFragmentByTag != m3Var) {
            fragmentTransactionBeginTransaction.replace(R.id.overlay_fragment_container, m3Var, "TAG_OVERLAY");
        }
        try {
            fragmentTransactionBeginTransaction.commit();
        } catch (IllegalStateException e) {
            e.toString();
            int i3 = o.f1842a;
            fragmentTransactionBeginTransaction.commitAllowingStateLoss();
        }
        View viewFindViewById = findViewById(R.id.overlay_fragment_container);
        viewFindViewById.setVisibility(0);
        viewFindViewById.bringToFront();
    }

    public final void t() {
        if (this.f686i != null) {
            DeviceInterface deviceInterface = DeviceInterface.BLE;
            if (CameraDeviceDetector.getDetectState(deviceInterface) == DetectState.DETECTING) {
                CameraDeviceDetector.stopDetect(deviceInterface);
            }
            this.f686i.cancel();
            this.j.cancel();
            this.f686i = null;
            this.j = null;
        }
    }
}
