package com.ricohimaging.imagesync;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.b;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;
import com.ricoh.camera.sdk.wireless.api.CameraDevice;
import com.ricoh.camera.sdk.wireless.api.CameraImage;
import com.ricohimaging.imagesync.db.SvAppDatabase;
import j2.g;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import m.b1;
import m.c1;
import m.d1;
import m.f1;
import m.i1;
import m.l1;
import m.m1;
import m.u;
import q.a;
import s.o;

/* compiled from: r8-map-id-c9b316a91c3bd4bbe1b6558d2d0e21dc8cc48c6b20222ca4031b2a9ce3f49e32 */
/* loaded from: classes2.dex */
public class ImageViewActivity extends b1 implements View.OnClickListener {

    /* renamed from: z, reason: collision with root package name */
    public static boolean f656z = false;

    /* renamed from: a, reason: collision with root package name */
    public SvApplication f657a;
    public SvAppDatabase b;

    /* renamed from: c, reason: collision with root package name */
    public ViewPager2 f658c;
    public m1 d;
    public String f;

    /* renamed from: g, reason: collision with root package name */
    public a f659g;

    /* renamed from: h, reason: collision with root package name */
    public ArrayList f660h;

    /* renamed from: i, reason: collision with root package name */
    public u f661i;
    public boolean j;
    public Toolbar k;
    public ProgressBar l;

    /* renamed from: m, reason: collision with root package name */
    public ImageView f662m;

    /* renamed from: n, reason: collision with root package name */
    public ImageView f663n;

    /* renamed from: o, reason: collision with root package name */
    public ImageView f664o;

    /* renamed from: p, reason: collision with root package name */
    public ImageView f665p;

    /* renamed from: q, reason: collision with root package name */
    public i1 f666q;

    /* renamed from: r, reason: collision with root package name */
    public ImageViewActivity f667r;

    /* renamed from: v, reason: collision with root package name */
    public ActivityResultLauncher f671v;
    public ActivityResultLauncher w;

    /* renamed from: x, reason: collision with root package name */
    public ActivityResultLauncher f672x;

    /* renamed from: s, reason: collision with root package name */
    public boolean f668s = false;

    /* renamed from: t, reason: collision with root package name */
    public boolean f669t = false;

    /* renamed from: u, reason: collision with root package name */
    public d1 f670u = null;

    /* renamed from: y, reason: collision with root package name */
    public final ActivityResultLauncher f673y = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new c1(this, 3));

    public static void l(ImageViewActivity imageViewActivity, a aVar) {
        String strB = a.a.B(aVar.f1793c);
        if (strB.equalsIgnoreCase("mov") || strB.equalsIgnoreCase("avi")) {
            g.N(imageViewActivity, new androidx.constraintlayout.motion.widget.a(13, imageViewActivity, aVar));
        } else {
            imageViewActivity.q(aVar);
        }
    }

    public static void s(Fragment fragment, String str, boolean z2, u uVar) {
        Intent intent = new Intent(fragment.getActivity(), (Class<?>) ImageViewActivity.class);
        intent.setAction("android.intent.action.VIEW");
        intent.putExtra("UUID", str);
        intent.putExtra("IS_CAMERA_THUMBNAIL", z2);
        intent.putExtra("SELETED_TAB", uVar);
        fragment.startActivity(intent);
    }

    public final boolean m(int i3) {
        CameraDevice cameraDevice = this.f657a.f783a;
        if (cameraDevice == null || !s.g.R(cameraDevice)) {
            return false;
        }
        String str = this.b.j().a(o(i3).f1467a.b).f1793c;
        String lowerCase = str.substring(str.lastIndexOf(".") + 1).toLowerCase();
        return lowerCase.equals("jpeg") || lowerCase.equals("jpg");
    }

    public final void n(boolean z2) {
        int i3 = o.f1842a;
        if (!s.g.B(this.f667r, "android.permission.WRITE_EXTERNAL_STORAGE")) {
            p(z2 ? 2 : 1);
            return;
        }
        this.f668s = z2;
        f656z = true;
        this.f658c.setEnabled(false);
        this.f658c.setUserInputEnabled(false);
        r((l1) this.f660h.get(this.f658c.getCurrentItem()));
    }

    public final l1 o(int i3) {
        if (this.f660h.size() != 0 && i3 <= this.f660h.size() && i3 >= 0) {
            return (l1) this.f660h.get(i3);
        }
        return null;
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View view) {
        int id = view.getId();
        this.f658c.getCurrentItem();
        switch (id) {
            case R.id.menu_lower_next /* 2131231297 */:
                if (!f656z || this.f658c.isEnabled()) {
                    ViewPager2 viewPager2 = this.f658c;
                    viewPager2.setCurrentItem(viewPager2.getCurrentItem() + 1);
                    break;
                }
                break;
            case R.id.menu_lower_prev /* 2131231298 */:
                if (!f656z || this.f658c.isEnabled()) {
                    ViewPager2 viewPager22 = this.f658c;
                    viewPager22.setCurrentItem(viewPager22.getCurrentItem() - 1);
                    break;
                }
                break;
            case R.id.menu_lower_resize_transfer /* 2131231299 */:
                n(true);
                break;
            case R.id.menu_lower_transfer /* 2131231300 */:
                n(false);
                break;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:28:0x0139  */
    /* JADX WARN: Removed duplicated region for block: B:41:0x01a9  */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0214  */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void onCreate(android.os.Bundle r15) {
        /*
            Method dump skipped, instructions count: 972
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ImageViewActivity.onCreate(android.os.Bundle):void");
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onDestroy() {
        super.onDestroy();
        m1 m1Var = this.d;
        if (m1Var != null) {
            this.f658c.unregisterOnPageChangeCallback(m1Var);
            this.d = null;
        }
        this.f670u.remove();
    }

    public final void p(int i3) {
        int iH = m.a.h(i3);
        if (iH == 0) {
            this.w.launch("android.permission.WRITE_EXTERNAL_STORAGE");
        } else if (iH == 1) {
            this.f671v.launch("android.permission.WRITE_EXTERNAL_STORAGE");
        } else {
            if (iH != 2) {
                return;
            }
            this.f672x.launch("android.permission.WRITE_EXTERNAL_STORAGE");
        }
    }

    public final void q(a aVar) {
        String strJ;
        Uri uriForFile;
        String path = getApplication().getExternalCacheDir().getPath();
        try {
            File file = new File(path);
            if (!file.exists()) {
                file.mkdirs();
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        String str = aVar.f1793c;
        int i3 = Build.VERSION.SDK_INT;
        if (24 > i3 || 26 <= i3) {
            strJ = b.j(path, "/", str);
        } else {
            StringBuilder sbW = b.w(path, "/");
            sbW.append(a.a.C(str));
            sbW.append(".");
            sbW.append(a.a.B(str).toLowerCase());
            strJ = sbW.toString();
        }
        String str2 = aVar.f1795h;
        this.f = str2;
        if (str2 == null) {
            return;
        }
        File file2 = new File(this.f);
        File file3 = new File(strJ);
        a.a.p(file2, file3);
        Intent intent = new Intent("android.intent.action.SEND");
        intent.setType("*/*");
        ImageViewActivity imageViewActivity = this.f667r;
        if (24 <= i3) {
            uriForFile = FileProvider.getUriForFile(imageViewActivity, imageViewActivity.getPackageName(), file3);
        } else {
            uriForFile = Uri.parse("file://" + file3.getPath());
        }
        intent.putExtra("android.intent.extra.STREAM", uriForFile);
        this.f673y.launch(Intent.createChooser(intent, getString(R.string.text_share_composition_setting)));
    }

    public final void r(l1 l1Var) {
        this.k.getMenu().clear();
        this.k.inflateMenu(R.menu.menu_upper_transfer);
        this.k.setOnMenuItemClickListener(new f1(this));
        this.l.setProgress(0);
        this.l.setVisibility(0);
        this.f664o.setVisibility(4);
        this.f665p.setVisibility(4);
        this.f662m.setVisibility(4);
        this.f663n.setVisibility(4);
        CameraImage cameraImage = l1Var.b;
        a aVar = l1Var.f1467a;
        String str = getFilesDir().getPath() + "/Temp/";
        File file = new File(str);
        try {
            if (!file.exists()) {
                file.mkdirs();
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        String name = cameraImage.getName();
        String strI = b.i(str, name);
        CameraDevice cameraDevice = this.f657a.f783a;
        String firmwareVersion = cameraDevice != null ? cameraDevice.getFirmwareVersion() : "";
        if ("mounted".equals(Environment.getExternalStorageState())) {
            try {
            } catch (FileNotFoundException e3) {
                e = e3;
            }
            try {
                i1 i1Var = new i1(this, new FileOutputStream(new File(str, name)), aVar.b, cameraImage, strI, firmwareVersion);
                this.f666q = i1Var;
                if (this.f668s) {
                    cameraImage.getExtraSmallDataAsync(i1Var);
                } else {
                    cameraImage.getDataAsync(i1Var);
                }
            } catch (FileNotFoundException e4) {
                e = e4;
                e.printStackTrace();
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:102:0x0290  */
    /* JADX WARN: Removed duplicated region for block: B:157:0x0402  */
    /* JADX WARN: Removed duplicated region for block: B:210:0x051b  */
    /* JADX WARN: Removed duplicated region for block: B:234:0x015c A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:236:0x01ae A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:68:0x01a2  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void t(int r30) {
        /*
            Method dump skipped, instructions count: 1393
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ImageViewActivity.t(int):void");
    }
}
