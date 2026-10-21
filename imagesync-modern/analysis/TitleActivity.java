package com.ricohimaging.imagesync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import b2.e;
import c2.a0;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.ricohimaging.imagesync.db.SvAppDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import m.d5;
import m.i6;
import p.a;
import s.b;
import s.d;
import s.g;
import s.o;

/* compiled from: r8-map-id-c9b316a91c3bd4bbe1b6558d2d0e21dc8cc48c6b20222ca4031b2a9ce3f49e32 */
/* loaded from: classes2.dex */
public class TitleActivity extends AppCompatActivity {
    public static final /* synthetic */ int d = 0;

    /* renamed from: a, reason: collision with root package name */
    public Intent f792a;
    public TitleActivity b;

    /* renamed from: c, reason: collision with root package name */
    public a0 f793c;

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v5, types: [java.util.ArrayList] */
    /* JADX WARN: Type inference failed for: r0v6, types: [java.util.List] */
    /* JADX WARN: Type inference failed for: r0v9, types: [java.util.List] */
    public static void l(TitleActivity titleActivity) {
        ?? arrayList;
        a0 a0Var = new a0(titleActivity);
        ArrayList arrayList2 = new ArrayList();
        arrayList2.add(a0Var.e(a.l));
        arrayList2.add(a0Var.e(a.k));
        arrayList2.add(a0Var.e(a.j));
        int size = arrayList2.size();
        int i3 = 1;
        boolean z2 = true;
        int i4 = 0;
        while (i4 < size) {
            Object obj = arrayList2.get(i4);
            i4++;
            String str = (String) obj;
            if (!str.isEmpty()) {
                String str2 = "";
                if (str.contains("GR_")) {
                    str2 = "RICOH GR III";
                } else if (str.contains("PENTAX_")) {
                    str2 = "PENTAX K-3 Mark III";
                }
                if (!str2.isEmpty()) {
                    g.d(titleActivity, str2, str);
                    z2 = false;
                }
            }
        }
        if (!z2) {
            a0Var.h(a.j, "");
            a0Var.h(a.k, "");
            a0Var.h(a.l, "");
        }
        File file = new File(titleActivity.getApplicationContext().getFilesDir().toString(), "BLEConnectionHistory");
        if (file.exists()) {
            arrayList = new ArrayList();
            File[] fileArrListFiles = file.listFiles(new b(i3));
            Arrays.sort(fileArrListFiles, new androidx.constraintlayout.core.utils.a(8));
            for (File file2 : fileArrListFiles) {
                arrayList.add(file2.getName());
            }
        } else {
            int i5 = o.f1842a;
            arrayList = Collections.EMPTY_LIST;
        }
        if (arrayList.isEmpty()) {
            return;
        }
        for (String str3 : arrayList) {
            Context applicationContext = titleActivity.getApplicationContext();
            synchronized (g.class) {
                File file3 = new File(applicationContext.getFilesDir().toString(), "BLEConnectionHistory");
                if (file3.exists()) {
                    for (File file4 : file3.listFiles(new d(str3, i3))) {
                        if (!file4.delete()) {
                            file4.getAbsolutePath();
                            int i6 = o.f1842a;
                        }
                    }
                } else {
                    int i7 = o.f1842a;
                }
            }
            String str4 = "";
            if (str3.contains("GR_")) {
                str4 = "RICOH GR III";
            } else if (str3.contains("PENTAX_")) {
                str4 = "PENTAX K-3 Mark III";
            }
            if (!str4.isEmpty()) {
                g.d(titleActivity, str4, str3);
            }
        }
    }

    public static void m() {
        File file = new File(androidx.activity.result.b.i(Environment.getExternalStorageDirectory().getAbsolutePath(), "/ImageSync/"));
        try {
            if (file.exists()) {
                return;
            }
            file.mkdirs();
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public final void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_title);
        this.b = this;
        SvAppDatabase.h(this);
        this.f793c = new a0(this);
        this.f792a = new Intent(this, (Class<?>) MainActivity.class);
        m();
        String strE = this.f793c.e(a.f1730r);
        if (strE.isEmpty() || !strE.equals(getString(R.string.terms_version))) {
            new Handler(Looper.getMainLooper()).postDelayed(new e(this, 13), 1000L);
            return;
        }
        FirebaseAnalytics.getInstance(this.b).setAnalyticsCollectionEnabled(true);
        ArrayList arrayList = new ArrayList();
        int i3 = o.f1842a;
        if (!g.B(this, "android.permission.WRITE_EXTERNAL_STORAGE")) {
            arrayList.add("android.permission.WRITE_EXTERNAL_STORAGE");
        }
        if (g.B(this, "android.permission.ACCESS_FINE_LOCATION")) {
            a0 a0Var = this.f793c;
            a aVar = a.B;
            if (a0Var.c(aVar)) {
                this.f793c.f(aVar, false);
            }
        } else {
            arrayList.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (!g.B(this, "android.permission.POST_NOTIFICATIONS")) {
            arrayList.add("android.permission.POST_NOTIFICATIONS");
        }
        if (getPackageManager().hasSystemFeature("android.hardware.bluetooth_le") && !g.B(this, "android.permission.BLUETOOTH_ADMIN")) {
            arrayList.add("android.permission.BLUETOOTH_ADMIN");
        }
        if (arrayList.isEmpty()) {
            new i6(this).c(new Void[0]);
            return;
        }
        ActivityResultLauncher activityResultLauncherRegisterForActivityResult = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), new androidx.core.view.inputmethod.a(this, 21));
        if (!arrayList.contains("android.permission.ACCESS_FINE_LOCATION") || this.f793c.c(a.B)) {
            activityResultLauncherRegisterForActivityResult.launch((String[]) arrayList.toArray(new String[0]));
        } else {
            new AlertDialog.Builder(this).setView(R.layout.dialog_button_addable).setMessage(R.string.msg_check_location_services_permission).setPositiveButton(getString(R.string.close), new d5(activityResultLauncherRegisterForActivityResult, arrayList)).show();
        }
    }
}
