package com.ricohimaging.imagesync;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import b2.e;
import c0.o;
import c2.a0;
import com.ricoh.camera.sdk.wireless.api.BatteryUsed;
import com.ricoh.camera.sdk.wireless.api.CameraDevice;
import com.ricoh.camera.sdk.wireless.api.CameraEventListener;
import com.ricoh.camera.sdk.wireless.api.CameraStorage;
import com.ricoh.camera.sdk.wireless.api.Capture;
import com.ricoh.camera.sdk.wireless.api.DeviceInterface;
import com.ricoh.camera.sdk.wireless.api.Point;
import com.ricoh.camera.sdk.wireless.api.StoragePermission;
import com.ricoh.camera.sdk.wireless.api.response.Response;
import com.ricoh.camera.sdk.wireless.api.response.Result;
import com.ricoh.camera.sdk.wireless.api.response.StartCaptureResponse;
import com.ricoh.camera.sdk.wireless.api.setting.camera.CameraDeviceSetting;
import com.ricoh.camera.sdk.wireless.api.setting.camera.CardSlot;
import com.ricoh.camera.sdk.wireless.api.setting.camera.DualCardSlotsMode;
import com.ricoh.camera.sdk.wireless.api.setting.camera.LiveViewSpecification;
import com.ricoh.camera.sdk.wireless.api.setting.capture.AstroTrackingTime;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CameraOrientation;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureSetting;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CompositionAdjustment;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CompositionAdjustmentEnable;
import com.ricoh.camera.sdk.wireless.api.setting.capture.ExposureCompensation;
import com.ricoh.camera.sdk.wireless.api.setting.capture.ExposureProgram;
import com.ricoh.camera.sdk.wireless.api.setting.capture.FNumber;
import com.ricoh.camera.sdk.wireless.api.setting.capture.FlashMode;
import com.ricoh.camera.sdk.wireless.api.setting.capture.FocusMethod;
import com.ricoh.camera.sdk.wireless.api.setting.capture.FocusSetting;
import com.ricoh.camera.sdk.wireless.api.setting.capture.GradND;
import com.ricoh.camera.sdk.wireless.api.setting.capture.HyperExposureProgramEnable;
import com.ricoh.camera.sdk.wireless.api.setting.capture.ISO;
import com.ricoh.camera.sdk.wireless.api.setting.capture.MovieCaptureFormat;
import com.ricoh.camera.sdk.wireless.api.setting.capture.RemoconEnable;
import com.ricoh.camera.sdk.wireless.api.setting.capture.ShutterSpeed;
import com.ricoh.camera.sdk.wireless.api.setting.capture.StillImageCaptureFormat;
import com.ricoh.camera.sdk.wireless.api.setting.capture.Type3CaptureState;
import com.ricoh.camera.sdk.wireless.api.setting.capture.Type3PreCaptureResult;
import com.ricoh.camera.sdk.wireless.api.setting.capture.UserExposureProgram;
import com.ricoh.camera.sdk.wireless.api.setting.capture.WhiteBalance;
import com.ricoh.camera.sdk.wireless.api.setting.capture.ZoomLevel;
import com.ricohimaging.imagesync.view.GridLiveView;
import com.ricohimaging.imagesync.view.shooting.CaptureParameterView;
import com.ricohimaging.imagesync.view.shooting.parts.SettingValueSelectorView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import m.b0;
import m.b1;
import m.c4;
import m.e4;
import m.q3;
import m.s3;
import m.t3;
import m.u3;
import m.w3;
import m.x3;
import m.y3;
import n.c;
import p.a;
import s.g;

/* compiled from: r8-map-id-c9b316a91c3bd4bbe1b6558d2d0e21dc8cc48c6b20222ca4031b2a9ce3f49e32 */
/* loaded from: classes2.dex */
public class ShootingActivity extends b1 implements View.OnTouchListener, SensorEventListener {
    public static int A1 = 1;
    public ImageView A0;
    public ImageView B0;
    public ImageView C0;
    public TextView D0;
    public TextView E0;
    public TextView F0;
    public FocusMethod G;
    public ImageView G0;
    public int H;
    public ImageView H0;
    public BatteryUsed I;
    public ImageView I0;
    public ExposureProgram J;
    public ImageView J0;
    public UserExposureProgram K;
    public ImageButton K0;
    public HyperExposureProgramEnable L;
    public ImageButton L0;
    public StillImageCaptureFormat M;
    public ConstraintLayout M0;
    public MovieCaptureFormat N;
    public ConstraintLayout N0;
    public CaptureMethod O;
    public ConstraintLayout O0;
    public RemoconEnable P;
    public ConstraintLayout P0;
    public CompositionAdjustment Q;
    public ConstraintLayout Q0;
    public CompositionAdjustmentEnable R;
    public ConstraintLayout R0;
    public ShutterSpeed S;
    public ConstraintLayout S0;
    public FNumber T;
    public ConstraintLayout T0;
    public ZoomLevel U;
    public ConstraintLayout U0;
    public ISO V;
    public ConstraintLayout V0;
    public ExposureCompensation W;
    public ConstraintLayout W0;
    public WhiteBalance X;
    public ConstraintLayout X0;
    public DualCardSlotsMode Y;
    public ConstraintLayout Y0;
    public CardSlot Z;
    public ConstraintLayout Z0;

    /* renamed from: a0, reason: collision with root package name */
    public FocusSetting f710a0;

    /* renamed from: a1, reason: collision with root package name */
    public ConstraintLayout f711a1;

    /* renamed from: b0, reason: collision with root package name */
    public FlashMode f712b0;

    /* renamed from: b1, reason: collision with root package name */
    public ConstraintLayout f713b1;

    /* renamed from: c0, reason: collision with root package name */
    public CameraOrientation f715c0;

    /* renamed from: c1, reason: collision with root package name */
    public ConstraintLayout f716c1;

    /* renamed from: d0, reason: collision with root package name */
    public GradND f717d0;

    /* renamed from: d1, reason: collision with root package name */
    public ConstraintLayout f718d1;

    /* renamed from: e0, reason: collision with root package name */
    public Type3CaptureState f719e0;

    /* renamed from: e1, reason: collision with root package name */
    public ConstraintLayout f720e1;

    /* renamed from: f0, reason: collision with root package name */
    public Type3PreCaptureResult f721f0;

    /* renamed from: f1, reason: collision with root package name */
    public ConstraintLayout f722f1;

    /* renamed from: g0, reason: collision with root package name */
    public AstroTrackingTime f724g0;

    /* renamed from: g1, reason: collision with root package name */
    public ConstraintLayout f725g1;

    /* renamed from: h, reason: collision with root package name */
    public SensorManager f726h;

    /* renamed from: h0, reason: collision with root package name */
    public ConstraintLayout f727h0;

    /* renamed from: h1, reason: collision with root package name */
    public ConstraintLayout f728h1;

    /* renamed from: i, reason: collision with root package name */
    public SvApplication f729i;

    /* renamed from: i0, reason: collision with root package name */
    public CaptureParameterView f730i0;

    /* renamed from: i1, reason: collision with root package name */
    public LinearLayout f731i1;
    public a0 j;

    /* renamed from: j0, reason: collision with root package name */
    public GridLiveView f732j0;

    /* renamed from: j1, reason: collision with root package name */
    public ImageButton f733j1;
    public c k;

    /* renamed from: k0, reason: collision with root package name */
    public GridLiveView f734k0;

    /* renamed from: k1, reason: collision with root package name */
    public ImageButton f735k1;
    public CameraDevice l;

    /* renamed from: l0, reason: collision with root package name */
    public TextView f736l0;
    public ImageButton l1;

    /* renamed from: m0, reason: collision with root package name */
    public ImageButton f738m0;

    /* renamed from: m1, reason: collision with root package name */
    public ImageView f739m1;

    /* renamed from: n0, reason: collision with root package name */
    public TextView f741n0;

    /* renamed from: n1, reason: collision with root package name */
    public ImageView f742n1;

    /* renamed from: o, reason: collision with root package name */
    public Handler f743o;

    /* renamed from: o0, reason: collision with root package name */
    public TextView f744o0;

    /* renamed from: o1, reason: collision with root package name */
    public ImageView f745o1;

    /* renamed from: p, reason: collision with root package name */
    public ShootingActivity f746p;

    /* renamed from: p0, reason: collision with root package name */
    public ImageView f747p0;

    /* renamed from: p1, reason: collision with root package name */
    public ImageView f748p1;

    /* renamed from: q, reason: collision with root package name */
    public String f749q;

    /* renamed from: q0, reason: collision with root package name */
    public ImageButton f750q0;

    /* renamed from: q1, reason: collision with root package name */
    public ImageView f751q1;

    /* renamed from: r, reason: collision with root package name */
    public Capture f752r;
    public ImageButton r0;
    public ImageView r1;
    public ImageButton s0;

    /* renamed from: s1, reason: collision with root package name */
    public ImageView f754s1;
    public ImageButton t0;

    /* renamed from: t1, reason: collision with root package name */
    public ImageView f756t1;

    /* renamed from: u0, reason: collision with root package name */
    public ImageButton f758u0;

    /* renamed from: u1, reason: collision with root package name */
    public ImageView f759u1;

    /* renamed from: v0, reason: collision with root package name */
    public ImageButton f761v0;

    /* renamed from: v1, reason: collision with root package name */
    public ImageView f762v1;

    /* renamed from: w0, reason: collision with root package name */
    public ImageButton f763w0;

    /* renamed from: w1, reason: collision with root package name */
    public ImageView f764w1;

    /* renamed from: x0, reason: collision with root package name */
    public TextView f766x0;

    /* renamed from: x1, reason: collision with root package name */
    public AlertDialog f767x1;

    /* renamed from: y0, reason: collision with root package name */
    public TextView f769y0;

    /* renamed from: z0, reason: collision with root package name */
    public TextView f772z0;

    /* renamed from: a, reason: collision with root package name */
    public final float[] f709a = new float[16];
    public final float[] b = new float[16];

    /* renamed from: c, reason: collision with root package name */
    public final float[] f714c = new float[16];
    public final float[] d = new float[3];
    public float[] f = new float[3];

    /* renamed from: g, reason: collision with root package name */
    public float[] f723g = new float[3];

    /* renamed from: z1, reason: collision with root package name */
    public int f773z1 = 1;

    /* renamed from: m, reason: collision with root package name */
    public Timer f737m = null;

    /* renamed from: n, reason: collision with root package name */
    public Timer f740n = null;

    /* renamed from: s, reason: collision with root package name */
    public boolean f753s = true;

    /* renamed from: t, reason: collision with root package name */
    public boolean f755t = false;

    /* renamed from: u, reason: collision with root package name */
    public boolean f757u = true;

    /* renamed from: v, reason: collision with root package name */
    public boolean f760v = true;
    public boolean w = true;

    /* renamed from: x, reason: collision with root package name */
    public boolean f765x = false;

    /* renamed from: y, reason: collision with root package name */
    public boolean f768y = true;

    /* renamed from: z, reason: collision with root package name */
    public boolean f771z = false;
    public boolean A = false;
    public boolean B = false;
    public boolean C = false;
    public boolean D = false;
    public float E = 0.0f;
    public float F = 0.0f;

    /* renamed from: y1, reason: collision with root package name */
    public final ConstraintSet f770y1 = new ConstraintSet();

    public static void H(View view, float f, float f3, float f4) {
        RotateAnimation rotateAnimation = new RotateAnimation(f4, f4, f / 2.0f, f3 / 2.0f);
        rotateAnimation.setDuration(0L);
        rotateAnimation.setFillAfter(true);
        rotateAnimation.setFillEnabled(true);
        view.startAnimation(rotateAnimation);
    }

    public static Response l(ShootingActivity shootingActivity, Point point, CaptureMethod captureMethod) {
        if (!shootingActivity.j.e(a.f1727o).equals("AF+") || !shootingActivity.f753s) {
            return (shootingActivity.f749q.equals("G900 SE") || shootingActivity.f710a0.equals(FocusSetting.STAR_AF)) ? shootingActivity.l.focus() : shootingActivity.l.focus(point);
        }
        Handler handler = new Handler(Looper.getMainLooper());
        c4 c4Var = new c4(shootingActivity);
        String str = shootingActivity.f749q;
        if (str != null) {
            switch (b0.a(str).ordinal()) {
                case 10:
                case 11:
                case 12:
                case 13:
                    handler.post(new u3(shootingActivity, c4Var, 0));
                    break;
            }
        }
        int iOrdinal = b0.a(shootingActivity.f749q).ordinal();
        StartCaptureResponse startCaptureResponseStartCapture = ((iOrdinal == 3 || iOrdinal == 4) && shootingActivity.f710a0.equals(FocusSetting.STAR_AF)) ? shootingActivity.l.startCapture(true) : shootingActivity.l.startCapture(point);
        if (startCaptureResponseStartCapture.getResult() == Result.ERROR) {
            CameraEventListener[] eventListeners = shootingActivity.l.getEventListeners();
            int length = eventListeners.length;
            int i3 = 0;
            while (true) {
                if (i3 < length) {
                    CameraEventListener cameraEventListener = eventListeners[i3];
                    if (cameraEventListener instanceof c4) {
                        handler.post(new u3(shootingActivity, (c4) cameraEventListener, 1));
                    } else {
                        i3++;
                    }
                }
            }
        }
        if (captureMethod.equals(CaptureMethod.MOVIE)) {
            shootingActivity.f753s = false;
        }
        return startCaptureResponseStartCapture;
    }

    public static DisplayMetrics x(ShootingActivity shootingActivity) {
        WindowManager windowManager = (WindowManager) shootingActivity.getSystemService("window");
        DisplayMetrics displayMetrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT < 30) {
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
            return displayMetrics;
        }
        Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        displayMetrics.widthPixels = bounds.width();
        displayMetrics.heightPixels = bounds.height();
        DisplayMetrics displayMetrics2 = shootingActivity.getResources().getDisplayMetrics();
        displayMetrics.density = displayMetrics2.density;
        displayMetrics.densityDpi = displayMetrics2.densityDpi;
        return displayMetrics;
    }

    public static CameraStorage y(CameraDevice cameraDevice) {
        List<CameraStorage> storages = cameraDevice.getStorages();
        return !g.N(cameraDevice) ? storages.get(0) : storages.get(1).isAvailable() ? storages.get(1) : storages.get(0);
    }

    public final String A(CameraDevice cameraDevice) {
        if (cameraDevice == null) {
            return "00:00";
        }
        CameraStorage cameraStorageV = v(cameraDevice);
        int recordableTime = g.P(cameraDevice) ? cameraStorageV.getRecordableTime() : cameraStorageV.getRemainingPictures();
        int i3 = recordableTime / 60;
        return i3 > 99 ? "99:59" : String.format(getString(R.string.parser_movie_recorded_time), Integer.valueOf(i3), Integer.valueOf(recordableTime % 60));
    }

    public final void B() {
        this.f762v1.setAnimation(null);
        this.f762v1.setVisibility(4);
    }

    public final boolean C() {
        int i3 = (!this.f757u || this.O.equals(CaptureMethod.MOVIE)) ? 1 : A1;
        return (this.f773z1 == 1 && m.a.b(i3)) || !(this.f773z1 == 1 || m.a.b(i3));
    }

    public final void D() {
        this.f757u = false;
        if (this.f773z1 == 1) {
            this.f732j0.setVisibility(4);
        } else {
            this.f734k0.setVisibility(4);
        }
        this.M0.setVisibility(8);
        this.O0.setVisibility(8);
        L();
        this.f764w1.setVisibility(0);
        this.f759u1.setVisibility(8);
        this.f739m1.setVisibility(8);
        this.f747p0.setVisibility(8);
        this.l1.setImageResource(R.drawable.liveview_button_off);
        B();
    }

    /* JADX WARN: Removed duplicated region for block: B:27:0x005e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void E(com.ricoh.camera.sdk.wireless.api.CameraDevice r5) {
        /*
            r4 = this;
            int r0 = s.o.f1842a
            boolean r0 = r4.f753s
            if (r0 != 0) goto L12
            com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod r0 = r4.O
            com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod r1 = com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod.MOVIE
            boolean r0 = r0.equals(r1)
            if (r0 != 0) goto L12
            goto La1
        L12:
            java.lang.String r0 = r4.f749q
            java.lang.String r1 = "RICOH WG-M2"
            boolean r0 = r0.equals(r1)
            r1 = 0
            if (r0 != 0) goto L8b
            java.lang.String r0 = r4.f749q
            java.lang.String r2 = "G900 SE"
            boolean r0 = r0.equals(r2)
            if (r0 == 0) goto L28
            goto L8b
        L28:
            com.ricoh.camera.sdk.wireless.api.CameraDevice r0 = r4.l
            com.ricoh.camera.sdk.wireless.api.CameraStatus r0 = r0.getStatus()
            com.ricoh.camera.sdk.wireless.api.Capture r0 = r0.getCurrentCapture()
            if (r0 != 0) goto L35
            goto L8b
        L35:
            com.ricoh.camera.sdk.wireless.api.CaptureState r2 = r0.getState()
            com.ricoh.camera.sdk.wireless.api.CaptureState r3 = com.ricoh.camera.sdk.wireless.api.CaptureState.EXECUTING
            if (r2 == r3) goto L3e
            goto L8b
        L3e:
            com.ricoh.camera.sdk.wireless.api.SelfTimer r0 = r0.getSelfTimer()
            if (r0 != 0) goto L45
            goto L8b
        L45:
            com.ricoh.camera.sdk.wireless.api.SelfTimer$State r0 = r0.getCountDownState()
            java.lang.String r2 = r4.f749q
            m.b0 r2 = m.b0.a(r2)
            int r2 = r2.ordinal()
            r3 = 3
            if (r2 == r3) goto L5e
            r3 = 4
            if (r2 == r3) goto L5e
            switch(r2) {
                case 10: goto L5e;
                case 11: goto L5e;
                case 12: goto L5e;
                case 13: goto L5e;
                default: goto L5c;
            }
        L5c:
            r2 = r1
            goto L5f
        L5e:
            r2 = 1
        L5f:
            com.ricoh.camera.sdk.wireless.api.SelfTimer$State r3 = com.ricoh.camera.sdk.wireless.api.SelfTimer.State.BUSY2S
            boolean r3 = r0.equals(r3)
            if (r3 == 0) goto L69
            if (r2 != 0) goto L79
        L69:
            com.ricoh.camera.sdk.wireless.api.SelfTimer$State r2 = com.ricoh.camera.sdk.wireless.api.SelfTimer.State.BUSY10S
            boolean r2 = r0.equals(r2)
            if (r2 != 0) goto L79
            com.ricoh.camera.sdk.wireless.api.SelfTimer$State r2 = com.ricoh.camera.sdk.wireless.api.SelfTimer.State.BUSY12S
            boolean r0 = r0.equals(r2)
            if (r0 == 0) goto L8b
        L79:
            m.k1 r5 = new m.k1
            com.ricoh.camera.sdk.wireless.api.CameraDevice r0 = r4.l
            b2.d r2 = new b2.d
            r2.<init>(r4)
            r5.<init>(r0, r2)
            java.lang.Void[] r0 = new java.lang.Void[r1]
            r5.c(r0)
            return
        L8b:
            r4.u()
            boolean r0 = r4.w
            if (r0 == 0) goto La1
            boolean r0 = r4.f765x
            if (r0 == 0) goto L97
            goto La1
        L97:
            m.p3 r0 = new m.p3
            r0.<init>(r4, r5)
            java.lang.Void[] r5 = new java.lang.Void[r1]
            r0.c(r5)
        La1:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.E(com.ricoh.camera.sdk.wireless.api.CameraDevice):void");
    }

    public final boolean F() {
        CameraDevice cameraDevice = this.l;
        return cameraDevice == null || !cameraDevice.isConnected(DeviceInterface.WLAN);
    }

    /* JADX WARN: Removed duplicated region for block: B:125:0x048b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void G() {
        /*
            Method dump skipped, instructions count: 1467
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.G():void");
    }

    public final void I() {
        if (this.f724g0.getValue() == null) {
            this.F0.setTextSize(22.0f);
            this.F0.setText("-‘--“");
            return;
        }
        int i3 = Integer.parseInt(this.f724g0.getValue().toString());
        if (i3 == 0) {
            this.F0.setTextSize(22.0f);
            this.F0.setText("-‘--“");
        } else {
            this.F0.setTextSize(16.0f);
            this.F0.setText(String.format(getString(R.string.parser_tracking_time), Integer.valueOf(i3 / 60), Integer.valueOf(i3 % 60)));
        }
    }

    public final void J(int i3, int i4, int i5) {
        if (this.B) {
            return;
        }
        this.B = true;
        CompositionAdjustment compositionAdjustment = new CompositionAdjustment();
        if (m.a.g(new CaptureSetting[]{compositionAdjustment}, this.l) != Result.OK) {
            return;
        }
        List list = (List) compositionAdjustment.getValue().get();
        int iIntValue = ((Integer) list.get(0)).intValue();
        int iIntValue2 = ((Integer) list.get(1)).intValue();
        int iIntValue3 = ((Integer) list.get(2)).intValue();
        ArrayList arrayList = new ArrayList();
        if (i3 == 0 && i4 == 0 && i5 == 0) {
            arrayList.add(0);
            arrayList.add(0);
            arrayList.add(0);
        } else {
            arrayList.add(Integer.valueOf(iIntValue + i3));
            arrayList.add(Integer.valueOf(iIntValue2 + i4));
            arrayList.add(Integer.valueOf(iIntValue3 + i5));
        }
        new s3(this, arrayList, i3, i4, i5).c(new Void[0]);
    }

    public final void K(boolean z2) {
        if (!this.w || !this.f757u || this.Q.getValue() == null || this.R.equals(CompositionAdjustmentEnable.OFF)) {
            return;
        }
        CaptureMethod captureMethod = this.O;
        CaptureMethod captureMethod2 = CaptureMethod.MOVIE;
        if (captureMethod.equals(captureMethod2)) {
            return;
        }
        int iD = this.O.equals(captureMethod2) ? 1 : A1;
        int iH = m.a.h(this.f773z1);
        if (iH == 1) {
            iD = m.a.d(iD);
        } else if (iH == 2) {
            iD = m.a.c(iD);
        }
        int i3 = iD;
        int iH2 = m.a.h(i3);
        if (iH2 == 0) {
            m(this.f732j0, this.Z0, this.f711a1, this.f716c1, this.f720e1, this.f718d1, this.f713b1, null, this.f722f1, 0.0f, i3);
        } else if (iH2 == 1) {
            m(this.f734k0, this.f716c1, this.f720e1, this.f718d1, this.f713b1, null, this.f722f1, this.Z0, this.f711a1, 0.5f, i3);
        } else if (iH2 == 2) {
            m(this.f732j0, this.f718d1, this.f713b1, null, this.f722f1, this.Z0, this.f711a1, this.f716c1, this.f720e1, 0.0f, i3);
        } else if (iH2 == 3) {
            m(this.f734k0, null, this.f722f1, this.Z0, this.f711a1, this.f716c1, this.f720e1, this.f718d1, this.f713b1, 0.5f, i3);
        }
        ConstraintSet constraintSet = new ConstraintSet();
        int iH3 = m.a.h(i3);
        if (iH3 == 0) {
            int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_width);
            int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_height);
            int dimensionPixelSize3 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_margin);
            constraintSet.constrainWidth(this.N0.getId(), dimensionPixelSize);
            constraintSet.constrainHeight(this.N0.getId(), dimensionPixelSize2);
            constraintSet.connect(this.N0.getId(), 3, this.M0.getId(), 4);
            constraintSet.connect(this.N0.getId(), 2, this.f732j0.getId(), 2);
            constraintSet.connect(this.N0.getId(), 1, this.f732j0.getId(), 1);
            constraintSet.applyTo(this.f727h0);
            H(this.N0, dimensionPixelSize, dimensionPixelSize2, 0.0f);
            this.N0.setTranslationY(dimensionPixelSize3);
            return;
        }
        if (iH3 == 1) {
            if (z2) {
                n(-90.0f);
                return;
            }
            int dimensionPixelSize4 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_width);
            int dimensionPixelSize5 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_height);
            int dimensionPixelSize6 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_button_margin);
            constraintSet.constrainWidth(this.N0.getId(), dimensionPixelSize4);
            constraintSet.constrainHeight(this.N0.getId(), dimensionPixelSize5);
            constraintSet.setMargin(this.N0.getId(), 4, dimensionPixelSize6);
            constraintSet.setVerticalBias(this.N0.getId(), 0.75f);
            constraintSet.connect(this.N0.getId(), 3, this.M0.getId(), 3);
            constraintSet.connect(this.N0.getId(), 2, this.M0.getId(), 2);
            constraintSet.connect(this.N0.getId(), 4, this.M0.getId(), 4);
            constraintSet.applyTo(this.f727h0);
            H(this.N0, dimensionPixelSize4, dimensionPixelSize5, -90.0f);
            this.N0.setTranslationY(((dimensionPixelSize4 - dimensionPixelSize5) / 2) - dimensionPixelSize6);
            return;
        }
        if (iH3 == 2) {
            int dimensionPixelSize7 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_width);
            int dimensionPixelSize8 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_height);
            int dimensionPixelSize9 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_button_margin);
            constraintSet.constrainWidth(this.N0.getId(), dimensionPixelSize7);
            constraintSet.constrainHeight(this.N0.getId(), dimensionPixelSize8);
            constraintSet.connect(this.N0.getId(), 3, this.M0.getId(), 4);
            constraintSet.connect(this.N0.getId(), 2, this.f732j0.getId(), 2);
            constraintSet.connect(this.N0.getId(), 1, this.f732j0.getId(), 1);
            constraintSet.applyTo(this.f727h0);
            H(this.N0, dimensionPixelSize7, dimensionPixelSize8, 180.0f);
            this.N0.setTranslationY((dimensionPixelSize7 * 2) + dimensionPixelSize9);
            return;
        }
        if (iH3 != 3) {
            return;
        }
        if (z2) {
            n(90.0f);
            return;
        }
        int dimensionPixelSize10 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_width);
        int dimensionPixelSize11 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_height);
        int dimensionPixelSize12 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_button_margin);
        constraintSet.constrainWidth(this.N0.getId(), dimensionPixelSize10);
        constraintSet.constrainHeight(this.N0.getId(), dimensionPixelSize11);
        constraintSet.setMargin(this.N0.getId(), 4, dimensionPixelSize12);
        constraintSet.setVerticalBias(this.N0.getId(), 0.25f);
        constraintSet.connect(this.N0.getId(), 3, this.M0.getId(), 3);
        constraintSet.connect(this.N0.getId(), 1, this.M0.getId(), 1);
        constraintSet.connect(this.N0.getId(), 4, this.M0.getId(), 4);
        constraintSet.applyTo(this.f727h0);
        H(this.N0, dimensionPixelSize10, dimensionPixelSize11, 90.0f);
        this.N0.setTranslationY(((dimensionPixelSize10 - dimensionPixelSize11) / 2) - dimensionPixelSize12);
    }

    public final void L() {
        int i3 = this.l.isConnected(DeviceInterface.BLE) ? R.drawable.ble_connected_shooting : R.drawable.wifi_connected_shooting;
        int iH = m.a.h(this.f773z1);
        if (iH != 1 && iH != 2) {
            ViewGroup.LayoutParams layoutParams = this.f732j0.getLayoutParams();
            this.f764w1.setImageResource(i3);
            this.f764w1.setLayoutParams(layoutParams);
            return;
        }
        this.f764w1.setLayoutParams(this.f734k0.getLayoutParams());
        Bitmap bitmapDecodeResource = BitmapFactory.decodeResource(getResources(), i3);
        int width = bitmapDecodeResource.getWidth();
        int height = bitmapDecodeResource.getHeight();
        float f = this.f773z1 == 2 ? 270.0f : 90.0f;
        Matrix matrix = new Matrix();
        matrix.setRotate(f, width / 2, height / 2);
        this.f764w1.setImageBitmap(Bitmap.createBitmap(bitmapDecodeResource, 0, 0, width, height, matrix, true));
    }

    public final void M(MotionEvent motionEvent) {
        float y2;
        float x2;
        Float fValueOf;
        Float fValueOf2;
        float x3;
        float y3;
        Float fValueOf3;
        Float fValueOf4;
        float f;
        float y4;
        float x4;
        float y5;
        float x5;
        float y6;
        float y7;
        int i3;
        LiveViewSpecification.LiveViewImage liveViewImage;
        float f3 = 0.0f;
        Float fValueOf5 = Float.valueOf(0.0f);
        if (!this.w || this.f765x) {
            return;
        }
        LiveViewSpecification liveViewSpecification = new LiveViewSpecification();
        this.l.getCameraDeviceSettings(Arrays.asList(liveViewSpecification));
        CameraDeviceSetting.Value value = liveViewSpecification.getValue();
        List<Point> arrayList = new ArrayList<>();
        if (value != null && (liveViewImage = (LiveViewSpecification.LiveViewImage) value.get()) != null) {
            arrayList = liveViewImage.getFocusArea();
        }
        if (arrayList.isEmpty()) {
            arrayList.add(new Point(0.111f, 0.125f));
            arrayList.add(new Point(0.889f, 0.125f));
            arrayList.add(new Point(0.889f, 0.875f));
            arrayList.add(new Point(0.111f, 0.875f));
        }
        float f4 = x(this).widthPixels;
        float f5 = (f4 / 3.0f) * 2.0f;
        int width = this.f734k0.getWidth();
        int height = this.f734k0.getHeight();
        int i4 = this.O.equals(CaptureMethod.MOVIE) ? 1 : A1;
        int iH = m.a.h(this.f773z1);
        if (iH != 0) {
            if (iH != 1) {
                if (iH != 2) {
                    fValueOf3 = fValueOf5;
                    fValueOf4 = fValueOf3;
                    y4 = 0.0f;
                } else if (m.a.b(i4)) {
                    y2 = motionEvent.getY();
                    float f6 = width;
                    x2 = f6 - motionEvent.getX();
                    fValueOf = Float.valueOf(motionEvent.getY() / height);
                    fValueOf2 = Float.valueOf((f6 - motionEvent.getX()) / f6);
                    Float f7 = fValueOf2;
                    y4 = y2;
                    f3 = x2;
                    fValueOf3 = fValueOf;
                    fValueOf4 = f7;
                } else {
                    x3 = motionEvent.getX();
                    y3 = motionEvent.getY();
                    fValueOf3 = Float.valueOf(motionEvent.getX() / f4);
                    fValueOf4 = Float.valueOf(motionEvent.getY() / f5);
                }
            } else if (m.a.b(i4)) {
                float f8 = height;
                y4 = f8 - motionEvent.getY();
                float x6 = motionEvent.getX();
                Float fValueOf6 = Float.valueOf((f8 - motionEvent.getY()) / f8);
                fValueOf4 = Float.valueOf(motionEvent.getX() / width);
                f = x6;
                fValueOf3 = fValueOf6;
                f3 = f;
            } else {
                x3 = motionEvent.getX();
                y3 = motionEvent.getY();
                fValueOf3 = Float.valueOf(motionEvent.getX() / f4);
                fValueOf4 = Float.valueOf(motionEvent.getY() / f5);
            }
            f = y3;
            y4 = x3;
            f3 = f;
        } else if (m.a.b(i4)) {
            x3 = motionEvent.getX();
            y3 = motionEvent.getY();
            fValueOf3 = Float.valueOf(motionEvent.getX() / f4);
            fValueOf4 = Float.valueOf(motionEvent.getY() / f5);
            f = y3;
            y4 = x3;
            f3 = f;
        } else {
            y2 = motionEvent.getY();
            float f9 = width;
            x2 = f9 - motionEvent.getX();
            fValueOf = Float.valueOf(motionEvent.getY() / height);
            fValueOf2 = Float.valueOf((f9 - motionEvent.getX()) / f9);
            Float f72 = fValueOf2;
            y4 = y2;
            f3 = x2;
            fValueOf3 = fValueOf;
            fValueOf4 = f72;
        }
        if (this.f773z1 == 1) {
            if (m.a.b(i4)) {
                x4 = arrayList.get(0).getX() * f4;
                y5 = arrayList.get(0).getY() * f5;
                float x7 = arrayList.get(2).getX() * f4;
                y6 = arrayList.get(2).getY() * f5;
                x5 = x7;
            } else {
                float f10 = height;
                float x8 = arrayList.get(0).getX() * f10;
                float f11 = width;
                y7 = arrayList.get(0).getY() * f11;
                float x9 = f10 * arrayList.get(2).getX();
                y6 = arrayList.get(2).getY() * f11;
                x5 = x9;
                x4 = x8;
                y5 = y7;
            }
        } else if (m.a.b(i4)) {
            float f12 = height;
            x4 = arrayList.get(0).getX() * f12;
            float f13 = width;
            y7 = arrayList.get(0).getY() * f13;
            x5 = f12 * arrayList.get(2).getX();
            y6 = arrayList.get(2).getY() * f13;
            y5 = y7;
        } else {
            x4 = arrayList.get(0).getX() * f4;
            y5 = arrayList.get(0).getY() * f5;
            x5 = arrayList.get(2).getX() * f4;
            y6 = arrayList.get(2).getY() * f5;
        }
        if (x4 > y4 || y4 > x5 || y5 > f3 || f3 > y6) {
            return;
        }
        this.E = fValueOf3.floatValue();
        this.F = fValueOf4.floatValue();
        if (!this.f749q.equals("G900 SE") && !this.f710a0.equals(FocusSetting.STAR_AF)) {
            int iH2 = m.a.h(this.f773z1);
            if (iH2 == 0) {
                int iH3 = m.a.h(i4);
                if (iH3 == 0) {
                    this.f747p0.setX((this.f732j0.getX() + y4) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + f3) - (this.f747p0.getHeight() / 2));
                } else if (iH3 == 1) {
                    this.f747p0.setX((this.f734k0.getX() + (width - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + (height - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                } else if (iH3 == 2) {
                    this.f747p0.setX((this.f732j0.getX() + (f4 - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + (f5 - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                } else if (iH3 == 3) {
                    this.f747p0.setX((this.f734k0.getX() + motionEvent.getX()) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + motionEvent.getY()) - (this.f747p0.getHeight() / 2));
                }
            } else if (iH2 == 1) {
                int iH4 = m.a.h(i4);
                if (iH4 == 0) {
                    this.f747p0.setX((this.f734k0.getX() + motionEvent.getX()) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + motionEvent.getY()) - (this.f747p0.getHeight() / 2));
                } else if (iH4 == 1) {
                    this.f747p0.setX((this.f732j0.getX() + (f4 - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + (f5 - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                } else if (iH4 == 2) {
                    this.f747p0.setX((this.f734k0.getX() + (width - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + (height - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                } else if (iH4 == 3) {
                    this.f747p0.setX((this.f732j0.getX() + y4) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + f3) - (this.f747p0.getHeight() / 2));
                }
            } else if (iH2 == 2) {
                int iH5 = m.a.h(i4);
                if (iH5 == 0) {
                    this.f747p0.setX((this.f734k0.getX() + motionEvent.getX()) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + motionEvent.getY()) - (this.f747p0.getHeight() / 2));
                } else if (iH5 == 1) {
                    this.f747p0.setX((this.f732j0.getX() + y4) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + f3) - (this.f747p0.getHeight() / 2));
                } else if (iH5 == 2) {
                    this.f747p0.setX((this.f734k0.getX() + (width - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f734k0.getY() + (height - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                } else if (iH5 == 3) {
                    this.f747p0.setX((this.f732j0.getX() + (f4 - motionEvent.getX())) - (this.f747p0.getWidth() / 2));
                    this.f747p0.setY((this.f732j0.getY() + (f5 - motionEvent.getY())) - (this.f747p0.getHeight() / 2));
                }
            }
        }
        if (this.f771z) {
            return;
        }
        this.f771z = true;
        String strE = this.j.e(a.f1727o);
        if (g.H(this.f749q) && this.G.equals(FocusMethod.AUTO) && this.f710a0.equals(FocusSetting.STAR_AF) && !strE.equals("AF+") && !this.A) {
            i3 = 1;
            this.A = true;
        } else {
            i3 = 1;
        }
        new t3(this, y4, (!(this.f773z1 == i3 && m.a.b(i4)) && (this.f773z1 == i3 || m.a.b(i4))) ? this.f734k0.getHeight() : this.f732j0.getWidth(), f3, (!(this.f773z1 == 1 && m.a.b(i4)) && (this.f773z1 == 1 || m.a.b(i4))) ? this.f734k0.getWidth() : this.f732j0.getHeight(), strE).c(new Void[0]);
    }

    public final void N() {
        this.f734k0.getViewTreeObserver().addOnGlobalLayoutListener(new w3(this, 1));
    }

    public final void O() {
        this.f732j0.getViewTreeObserver().addOnGlobalLayoutListener(new w3(this, 0));
    }

    public final void P() {
        Integer num;
        List<CameraStorage> storages = this.l.getStorages();
        Q(y(this.l));
        if (g.K(this.f749q)) {
            Q(storages.get(1));
            if (this.O.equals(CaptureMethod.MOVIE)) {
                this.l.getCameraDeviceSettings(Arrays.asList(this.Z));
                if (!this.Z.equals(CardSlot.SD2) || (num = (Integer) this.H0.getTag()) == null) {
                    return;
                }
                this.G0.setImageResource(num.intValue());
                this.R0.setVisibility(4);
            }
        }
    }

    public final void Q(CameraStorage cameraStorage) {
        if (cameraStorage.getPermission() == StoragePermission.UNKNOWN) {
            if (!g.K(this.f749q)) {
                this.G0.setImageResource(R.drawable.sd_no_card);
                this.G0.setTag(Integer.valueOf(R.drawable.sd_no_card));
                return;
            } else if (cameraStorage.getId().equals("sd1")) {
                this.G0.setImageResource(R.drawable.sd_1_nocard);
                this.G0.setTag(Integer.valueOf(R.drawable.sd_1_nocard));
                return;
            } else {
                this.H0.setImageResource(R.drawable.sd_2_nocard);
                this.H0.setTag(Integer.valueOf(R.drawable.sd_2_nocard));
                return;
            }
        }
        if (cameraStorage.getPermission() != StoragePermission.READ_WRITE) {
            if (!g.K(this.f749q)) {
                this.G0.setImageResource(R.drawable.sd_protect);
                this.G0.setTag(Integer.valueOf(R.drawable.sd_protect));
                return;
            } else if (cameraStorage.getId().equals("sd1")) {
                this.G0.setImageResource(R.drawable.sd_1_protect);
                this.G0.setTag(Integer.valueOf(R.drawable.sd_1_protect));
                return;
            } else {
                this.H0.setImageResource(R.drawable.sd_2_protect);
                this.H0.setTag(Integer.valueOf(R.drawable.sd_2_protect));
                return;
            }
        }
        if (g.K(this.f749q)) {
            if (cameraStorage.getId().equals("sd1")) {
                this.G0.setImageResource(R.drawable.sd_1);
                this.G0.setTag(Integer.valueOf(R.drawable.sd_1));
                return;
            } else {
                this.H0.setImageResource(R.drawable.sd_2);
                this.H0.setTag(Integer.valueOf(R.drawable.sd_2));
                return;
            }
        }
        if (cameraStorage.getId().equals("in")) {
            this.G0.setImageResource(R.drawable.inner);
            this.G0.setTag(Integer.valueOf(R.drawable.inner));
        } else {
            this.G0.setImageResource(R.drawable.sd_normal);
            this.G0.setTag(Integer.valueOf(R.drawable.sd_normal));
        }
    }

    public final void R(int i3) {
        switch (m.a.h(i3)) {
            case 0:
                this.f741n0.setText(R.string.capture_error_msg_can_not_capture);
                break;
            case 1:
                this.f741n0.setText(R.string.capture_error_msg_can_not_mmode_capture_operation_mode);
                break;
            case 2:
                this.f741n0.setText(R.string.capture_error_msg_can_not_capture_bulb);
                break;
            case 3:
                this.f741n0.setText(R.string.capture_error_msg_can_not_capture_kp);
                break;
            case 4:
                this.f741n0.setText(R.string.capture_error_msg_can_not_mmode_capture_bulb);
                break;
            case 5:
                this.f741n0.setText(R.string.capture_error_msg_can_not_capture_astro_tracer_type3);
                break;
            case 6:
                this.f741n0.setText(R.string.capture_error_msg_can_not_capture_grad_nd);
                break;
        }
        if (this.f744o0.getVisibility() != 0) {
            int iH = m.a.h(i3);
            if (iH == 0 || iH == 2 || iH == 3 || iH == 4) {
                this.f741n0.setVisibility(0);
            }
        }
    }

    public final void S() {
        this.f762v1.setAnimation(null);
        this.f762v1.setVisibility(0);
    }

    public final void T(int i3) {
        int iH = m.a.h(i3);
        if (iH == 1) {
            this.f744o0.setText(R.string.capture_error_msg_no_card);
        } else if (iH == 2) {
            this.f744o0.setText(R.string.capture_error_msg_card_locked);
        } else if (iH == 3) {
            this.f744o0.setText(R.string.capture_error_msg_not_formatted);
        } else if (iH == 4) {
            this.f744o0.setText(R.string.capture_error_msg_no_spase);
        }
        if (this.f741n0.getVisibility() != 0) {
            int iH2 = m.a.h(i3);
            if (iH2 == 1 || iH2 == 2 || iH2 == 3 || iH2 == 4) {
                this.f744o0.setVisibility(0);
            } else {
                this.f744o0.setVisibility(4);
            }
        }
    }

    public final void U() {
        int iOrdinal;
        CompositionAdjustmentEnable compositionAdjustmentEnable;
        CaptureMethod captureMethod;
        if (F() && this.l.connect(DeviceInterface.WLAN).getResult() == Result.ERROR) {
            return;
        }
        this.f757u = true;
        String str = this.f749q;
        if (str != null && (((iOrdinal = b0.a(str).ordinal()) == 3 || iOrdinal == 4 || iOrdinal == 7) && (compositionAdjustmentEnable = this.R) != null && compositionAdjustmentEnable.equals(CompositionAdjustmentEnable.ON) && (captureMethod = this.O) != null && !captureMethod.equals(CaptureMethod.MOVIE) && this.w && !this.f765x)) {
            this.M0.setVisibility(0);
            this.O0.setVisibility(0);
        }
        this.l1.setImageResource(R.drawable.liveview_button_on);
        boolean zC = this.j.c(a.C);
        CaptureMethod captureMethod2 = this.O;
        CaptureMethod captureMethod3 = CaptureMethod.MOVIE;
        if (captureMethod2.equals(captureMethod3) || (g.J(this.l) && zC)) {
            B();
        } else {
            S();
        }
        if (g.J(this.l) && this.f715c0.getValue() != null && zC) {
            A1 = m.a.a(this.f715c0.getValue().toString());
        }
        int i3 = this.O.equals(captureMethod3) ? 1 : A1;
        int iH = m.a.h(this.f773z1);
        if (iH != 0) {
            if (iH == 1 || iH == 2) {
                if (m.a.b(i3)) {
                    this.f732j0.setVisibility(4);
                    this.f734k0.setVisibility(0);
                    N();
                    o(this.f760v);
                    K(this.f760v);
                    q();
                } else {
                    this.f734k0.setVisibility(4);
                    this.f732j0.setVisibility(0);
                    if (this.f773z1 == 3) {
                        this.f732j0.setRotation(m.a.f(m.a.c(i3)));
                    } else {
                        this.f732j0.setRotation(m.a.f(m.a.d(i3)));
                    }
                    O();
                    K(this.f760v);
                    r();
                }
            }
        } else if (m.a.b(i3)) {
            this.f734k0.setVisibility(4);
            this.f732j0.setVisibility(0);
            this.f732j0.setRotation(m.a.f(i3));
            O();
            K(this.f760v);
            r();
        } else {
            this.f732j0.setVisibility(4);
            this.f734k0.setVisibility(0);
            N();
            o(this.f760v);
            K(this.f760v);
            q();
        }
        if (!g.H(this.f749q) || this.J.equals(ExposureProgram.SFP)) {
            this.f747p0.setVisibility(4);
        } else if (this.G.equals(FocusMethod.AUTO)) {
            this.f747p0.setVisibility(0);
        } else if (this.f749q.equals("G900 SE") && this.G.getValue() == null) {
            this.f747p0.setVisibility(0);
        } else {
            this.f747p0.setVisibility(4);
        }
        this.f759u1.setVisibility(0);
        if (g.M(this.f749q) && !this.J.equals(ExposureProgram.ASTRO_TRACER_TYPE3)) {
            this.f733j1.setVisibility(0);
        }
        new q3(this, 1).c(new Void[0]);
        this.f764w1.setVisibility(4);
        if (g.Q(this.f749q)) {
            this.f739m1.setVisibility(0);
        }
    }

    public final void V() {
        if (F()) {
            return;
        }
        this.f757u = false;
        this.M0.setVisibility(4);
        this.O0.setVisibility(4);
        this.l1.setImageResource(R.drawable.liveview_button_off);
        this.f732j0.setVisibility(4);
        this.f734k0.setVisibility(4);
        this.f732j0.setImageDrawable(null);
        this.f734k0.setImageDrawable(null);
        this.f747p0.setVisibility(4);
        this.M0.setVisibility(4);
        this.O0.setVisibility(4);
        B();
        if (C()) {
            r();
        } else {
            q();
        }
        new q3(this, 2).c(new Void[0]);
        if (!g.Q(this.f749q)) {
            this.f764w1.setVisibility(4);
            return;
        }
        L();
        this.f764w1.setVisibility(0);
        this.f739m1.setVisibility(0);
    }

    public final void W() {
        if (!this.O.equals(CaptureMethod.MOVIE) || this.f753s) {
            return;
        }
        new q3(this, 0).c(new Void[0]);
    }

    public final void X(CompositionAdjustment compositionAdjustment) {
        if (compositionAdjustment == null || compositionAdjustment.getValue() == null) {
            return;
        }
        List list = (List) compositionAdjustment.getValue().get();
        int iIntValue = ((Integer) list.get(0)).intValue();
        int iIntValue2 = ((Integer) list.get(1)).intValue();
        int iIntValue3 = ((Integer) list.get(2)).intValue();
        this.f766x0.setText(String.valueOf(iIntValue));
        this.f769y0.setText(String.valueOf(iIntValue2));
        this.f772z0.setText(String.valueOf(-iIntValue3));
    }

    public final void m(View view, View view2, View view3, View view4, View view5, View view6, View view7, View view8, View view9, float f, int i3) throws Resources.NotFoundException {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.connect(this.M0.getId(), 3, view.getId(), 3);
        constraintSet.connect(this.M0.getId(), 2, view.getId(), 2);
        constraintSet.connect(this.M0.getId(), 4, view.getId(), 4);
        constraintSet.connect(this.M0.getId(), 1, view.getId(), 1);
        constraintSet.applyTo(this.f727h0);
        ConstraintSet constraintSet2 = new ConstraintSet();
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_button_size);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_button_margin);
        if (view9 != null && view3 != null && view2 != null) {
            constraintSet2.constrainWidth(view2.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view2.getId(), dimensionPixelSize);
            constraintSet2.setVerticalBias(view2.getId(), f);
            constraintSet2.connect(view2.getId(), 3, view3.getId(), 3);
            constraintSet2.connect(view2.getId(), 4, view9.getId(), 4);
            constraintSet2.connect(view2.getId(), 1, view9.getId(), 1);
            int iH = m.a.h(i3);
            if (iH == 0) {
                float f3 = dimensionPixelSize;
                H(view2, f3, f3, 0.0f);
            } else if (iH == 1) {
                float f4 = dimensionPixelSize;
                H(view2, f4, f4, -90.0f);
            } else if (iH == 2) {
                float f5 = dimensionPixelSize;
                H(view2, f5, f5, 0.0f);
            } else if (iH == 3) {
                float f6 = dimensionPixelSize;
                H(view2, f6, f6, 90.0f);
            }
        }
        if (view3 != null) {
            constraintSet2.constrainWidth(view3.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view3.getId(), dimensionPixelSize);
            constraintSet2.setMargin(view3.getId(), 3, dimensionPixelSize2);
            constraintSet2.connect(view3.getId(), 3, 0, 3);
            constraintSet2.connect(view3.getId(), 2, 0, 2);
            constraintSet2.connect(view3.getId(), 1, 0, 1);
            int iH2 = m.a.h(i3);
            if (iH2 == 0) {
                float f7 = dimensionPixelSize;
                H(view3, f7, f7, 0.0f);
            } else if (iH2 == 1) {
                float f8 = dimensionPixelSize;
                H(view3, f8, f8, -90.0f);
            } else if (iH2 == 2) {
                float f9 = dimensionPixelSize;
                H(view3, f9, f9, 180.0f);
            } else if (iH2 == 3) {
                float f10 = dimensionPixelSize;
                H(view3, f10, f10, 90.0f);
            }
        }
        if (view5 != null && view3 != null && view4 != null) {
            constraintSet2.constrainWidth(view4.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view4.getId(), dimensionPixelSize);
            constraintSet2.setVerticalBias(view4.getId(), f);
            constraintSet2.connect(view4.getId(), 3, view3.getId(), 3);
            constraintSet2.connect(view4.getId(), 2, view5.getId(), 2);
            constraintSet2.connect(view4.getId(), 4, view5.getId(), 4);
            int iH3 = m.a.h(i3);
            if (iH3 == 0) {
                float f11 = dimensionPixelSize;
                H(view4, f11, f11, 0.0f);
            } else if (iH3 == 1) {
                float f12 = dimensionPixelSize;
                H(view4, f12, f12, -90.0f);
            } else if (iH3 == 2) {
                float f13 = dimensionPixelSize;
                H(view4, f13, f13, 0.0f);
            } else if (iH3 == 3) {
                float f14 = dimensionPixelSize;
                H(view4, f14, f14, 90.0f);
            }
        }
        if (view5 != null) {
            constraintSet2.constrainWidth(view5.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view5.getId(), dimensionPixelSize);
            constraintSet2.setMargin(view5.getId(), 7, dimensionPixelSize2);
            constraintSet2.connect(view5.getId(), 3, 0, 3);
            constraintSet2.connect(view5.getId(), 2, 0, 2);
            constraintSet2.connect(view5.getId(), 4, 0, 4);
            int iH4 = m.a.h(i3);
            if (iH4 == 0) {
                float f15 = dimensionPixelSize;
                H(view5, f15, f15, 0.0f);
            } else if (iH4 == 1) {
                float f16 = dimensionPixelSize;
                H(view5, f16, f16, -90.0f);
            } else if (iH4 == 2) {
                float f17 = dimensionPixelSize;
                H(view5, f17, f17, 180.0f);
            } else if (iH4 == 3) {
                float f18 = dimensionPixelSize;
                H(view5, f18, f18, 90.0f);
            }
        }
        if (view5 != null && view7 != null && view6 != null) {
            constraintSet2.constrainWidth(view6.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view6.getId(), dimensionPixelSize);
            constraintSet2.setVerticalBias(view6.getId(), 1.0f - f);
            constraintSet2.connect(view6.getId(), 3, view5.getId(), 3);
            constraintSet2.connect(view6.getId(), 2, view5.getId(), 2);
            constraintSet2.connect(view6.getId(), 4, view7.getId(), 4);
            int iH5 = m.a.h(i3);
            if (iH5 == 0) {
                float f19 = dimensionPixelSize;
                H(view6, f19, f19, 0.0f);
            } else if (iH5 == 1) {
                float f20 = dimensionPixelSize;
                H(view6, f20, f20, -90.0f);
            } else if (iH5 == 2) {
                float f21 = dimensionPixelSize;
                H(view6, f21, f21, 180.0f);
            } else if (iH5 == 3) {
                float f22 = dimensionPixelSize;
                H(view6, f22, f22, 90.0f);
            }
        }
        if (view7 != null) {
            constraintSet2.constrainWidth(view7.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view7.getId(), dimensionPixelSize);
            constraintSet2.setMargin(view7.getId(), 4, dimensionPixelSize2);
            constraintSet2.connect(view7.getId(), 2, 0, 2);
            constraintSet2.connect(view7.getId(), 4, 0, 4);
            constraintSet2.connect(view7.getId(), 1, 0, 1);
            int iH6 = m.a.h(i3);
            if (iH6 == 0) {
                float f23 = dimensionPixelSize;
                H(view7, f23, f23, 0.0f);
            } else if (iH6 == 1) {
                float f24 = dimensionPixelSize;
                H(view7, f24, f24, -90.0f);
            } else if (iH6 == 2) {
                float f25 = dimensionPixelSize;
                H(view7, f25, f25, 180.0f);
            } else if (iH6 == 3) {
                float f26 = dimensionPixelSize;
                H(view7, f26, f26, 90.0f);
            }
        }
        if (view9 != null && view7 != null && view8 != null) {
            constraintSet2.constrainWidth(view8.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view8.getId(), dimensionPixelSize);
            constraintSet2.setVerticalBias(view8.getId(), 1.0f - f);
            constraintSet2.connect(view8.getId(), 3, view9.getId(), 3);
            constraintSet2.connect(view8.getId(), 4, view7.getId(), 4);
            constraintSet2.connect(view8.getId(), 1, view9.getId(), 1);
            int iH7 = m.a.h(i3);
            if (iH7 == 0) {
                float f27 = dimensionPixelSize;
                H(view8, f27, f27, 0.0f);
            } else if (iH7 == 1) {
                float f28 = dimensionPixelSize;
                H(view8, f28, f28, -90.0f);
            } else if (iH7 == 2) {
                float f29 = dimensionPixelSize;
                H(view8, f29, f29, 180.0f);
            } else if (iH7 == 3) {
                float f30 = dimensionPixelSize;
                H(view8, f30, f30, 90.0f);
            }
        }
        if (view9 != null) {
            constraintSet2.constrainWidth(view9.getId(), dimensionPixelSize);
            constraintSet2.constrainHeight(view9.getId(), dimensionPixelSize);
            constraintSet2.setMargin(view9.getId(), 6, dimensionPixelSize2);
            constraintSet2.connect(view9.getId(), 3, 0, 3);
            constraintSet2.connect(view9.getId(), 4, 0, 4);
            constraintSet2.connect(view9.getId(), 1, 0, 1);
            int iH8 = m.a.h(i3);
            if (iH8 == 0) {
                float f31 = dimensionPixelSize;
                H(view9, f31, f31, 0.0f);
            } else if (iH8 == 1) {
                float f32 = dimensionPixelSize;
                H(view9, f32, f32, -90.0f);
            } else if (iH8 == 2) {
                float f33 = dimensionPixelSize;
                H(view9, f33, f33, 180.0f);
            } else if (iH8 == 3) {
                float f34 = dimensionPixelSize;
                H(view9, f34, f34, 90.0f);
            }
        }
        constraintSet2.applyTo(this.M0);
    }

    public final void n(float f) throws Resources.NotFoundException {
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_width);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.composition_adjustment_value_height);
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.constrainWidth(this.N0.getId(), dimensionPixelSize);
        constraintSet.constrainHeight(this.N0.getId(), dimensionPixelSize2);
        constraintSet.connect(this.N0.getId(), 3, this.M0.getId(), 3);
        constraintSet.connect(this.N0.getId(), 2, this.M0.getId(), 2);
        constraintSet.connect(this.N0.getId(), 4, this.M0.getId(), 4);
        constraintSet.connect(this.N0.getId(), 1, this.M0.getId(), 1);
        constraintSet.applyTo(this.f727h0);
        H(this.N0, dimensionPixelSize, dimensionPixelSize2, f);
        this.f734k0.getViewTreeObserver().addOnGlobalLayoutListener(new y3(this, dimensionPixelSize2));
    }

    public final void o(boolean z2) {
        int i3 = this.O.equals(CaptureMethod.MOVIE) ? 1 : A1;
        if (this.f757u) {
            if (this.f773z1 == 1 && this.f734k0.getRotation() == m.a.f(m.a.d(i3))) {
                return;
            }
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.setDimensionRatio(this.f734k0.getId(), "2:3");
            constraintSet.connect(this.f734k0.getId(), 3, R.id.info_bar, 3);
            constraintSet.connect(this.f734k0.getId(), 2, this.f727h0.getId(), 2);
            constraintSet.connect(this.f734k0.getId(), 1, this.f727h0.getId(), 1);
            int iH = m.a.h(this.f773z1);
            if (iH == 0) {
                constraintSet.setRotation(this.f734k0.getId(), m.a.f(m.a.d(i3)));
            } else if (iH == 1 || iH == 2) {
                constraintSet.setRotation(this.f734k0.getId(), m.a.f(i3));
            }
            float width = this.f727h0.getWidth();
            float height = this.f727h0.getHeight();
            float dimensionPixelSize = getResources().getDimensionPixelSize(z2 ? R.dimen.liveview_horizontal_min_y_screen_top_margin_when_on_disp : R.dimen.liveview_horizontal_min_y_screen_top_margin_when_off_disp);
            float dimensionPixelSize2 = getResources().getDimensionPixelSize(z2 ? R.dimen.liveview_horizontal_max_y_screen_bottom_margin : R.dimen.shooting_shutter_bar_height);
            if (((height - dimensionPixelSize) - dimensionPixelSize2) / width < 1.5f) {
                constraintSet.setMargin(this.f734k0.getId(), 4, (int) dimensionPixelSize2);
                constraintSet.connect(this.f734k0.getId(), 4, this.f727h0.getId(), 4);
            }
            constraintSet.applyTo(this.f727h0);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:308:0x0b31  */
    /* JADX WARN: Removed duplicated region for block: B:309:0x0b58  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0638 A[FALL_THROUGH] */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void onCreate(android.os.Bundle r28) {
        /*
            Method dump skipped, instructions count: 3560
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.onCreate(android.os.Bundle):void");
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onDestroy() {
        super.onDestroy();
        Handler handler = this.f743o;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            this.f743o = null;
        }
        CameraDevice cameraDevice = this.l;
        if (cameraDevice != null) {
            for (CameraEventListener cameraEventListener : cameraDevice.getEventListeners()) {
                if (cameraEventListener instanceof e4) {
                    this.l.removeEventListener(cameraEventListener);
                    return;
                }
            }
        }
    }

    @Override // androidx.appcompat.app.AppCompatActivity, android.app.Activity, android.view.KeyEvent.Callback
    public final boolean onKeyDown(int i3, KeyEvent keyEvent) {
        if (i3 != 4) {
            if (i3 != 24 && i3 != 66) {
                return false;
            }
            E(this.l);
            return false;
        }
        V();
        W();
        Intent intent = new Intent(this, (Class<?>) MainActivity.class);
        intent.putExtra("MOVE_TO_PREVIOUS_TAB", true);
        intent.setFlags(68157440);
        startActivity(intent);
        finish();
        return true;
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public final void onPause() {
        super.onPause();
        this.f726h.unregisterListener(this);
        Timer timer = this.f740n;
        if (timer != null) {
            timer.cancel();
            this.f740n = null;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        this.f743o = handler;
        handler.postDelayed(new e(this, 7), 60000L);
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0036, code lost:
    
        if ((r0 == null ? false : s.g.Q(r0.getModel())) == false) goto L52;
     */
    /* JADX WARN: Removed duplicated region for block: B:24:0x008b  */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0097  */
    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void onResume() {
        /*
            Method dump skipped, instructions count: 310
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.onResume():void");
    }

    @Override // android.hardware.SensorEventListener
    public final void onSensorChanged(SensorEvent sensorEvent) {
        float[] fArr;
        try {
            if (Settings.System.getInt(getContentResolver(), "accelerometer_rotation") == 0) {
                this.f773z1 = 1;
                return;
            }
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        int type = sensorEvent.sensor.getType();
        if (type == 1) {
            this.f723g = (float[]) sensorEvent.values.clone();
        } else if (type == 2) {
            this.f = (float[]) sensorEvent.values.clone();
        }
        float[] fArr2 = this.f;
        if (fArr2 == null || (fArr = this.f723g) == null) {
            return;
        }
        float[] fArr3 = this.f714c;
        float[] fArr4 = this.f709a;
        SensorManager.getRotationMatrix(fArr4, fArr3, fArr, fArr2);
        float[] fArr5 = this.b;
        SensorManager.remapCoordinateSystem(fArr4, 1, 3, fArr5);
        float[] fArr6 = this.d;
        SensorManager.getOrientation(fArr5, fArr6);
        int i3 = this.f773z1;
        double d = fArr6[2];
        this.f773z1 = (d >= -0.7853981633974483d || d <= -2.356194490192345d) ? (d <= 0.7853981633974483d || d >= 2.356194490192345d) ? 1 : 2 : 3;
        ArrayList arrayList = new ArrayList();
        arrayList.add(this.f748p1);
        arrayList.add(this.f751q1);
        arrayList.add(this.f756t1);
        arrayList.add(this.A0);
        arrayList.add(this.I0);
        arrayList.add(this.G0);
        arrayList.add(this.J0);
        arrayList.add(this.H0);
        arrayList.add(this.l1);
        arrayList.add(this.f759u1);
        arrayList.add(this.f739m1);
        if (g.O(this.f749q)) {
            arrayList.add(this.f735k1);
        }
        String str = this.f749q;
        if (str != null && (str.equals("RICOH WG-M2") || str.equals("G900 SE"))) {
            arrayList.add(this.K0);
            arrayList.add(this.L0);
        }
        boolean zC = this.j.c(a.C);
        if (this.f757u && !this.O.equals(CaptureMethod.MOVIE) && (!g.J(this.l) || !zC)) {
            arrayList.add(this.f762v1);
        }
        if (g.G(this.l) && this.J.equals(ExposureProgram.ASTRO_TRACER_TYPE3)) {
            arrayList.add(this.f731i1);
        }
        ArrayList arrayList2 = new ArrayList();
        SettingValueSelectorView selectorContainer = this.f730i0.getSelectorContainer();
        ArrayList arrayList3 = new ArrayList();
        if (this.f749q.equals("G900 SE")) {
            ConstraintLayout constraintLayout = (ConstraintLayout) findViewById(R.id.macro_mode);
            LinearLayout linearLayout = this.f730i0.getCenterLeftItemAccessor().f234g;
            LinearLayout linearLayout2 = this.f730i0.getCenterItemAccessor().f234g;
            LinearLayout linearLayout3 = this.f730i0.getCenterRightItemAccessor().f234g;
            LinearLayout linearLayout4 = this.f730i0.getRightItemAccessor().f234g;
            arrayList2.add(constraintLayout);
            arrayList2.add(linearLayout);
            arrayList2.add(linearLayout2);
            arrayList2.add(linearLayout3);
            arrayList2.add(linearLayout4);
            ConstraintLayout constraintLayout2 = (ConstraintLayout) findViewById(R.id.arrow_count1);
            ConstraintLayout constraintLayout3 = (ConstraintLayout) findViewById(R.id.arrow_count2);
            arrayList2.add(constraintLayout2);
            arrayList2.add(constraintLayout3);
            arrayList3.add(((o) selectorContainer.getCenterSelector()).getElementContainerLayout());
        } else {
            ConstraintLayout constraintLayout4 = (ConstraintLayout) findViewById(R.id.exposure_program);
            LinearLayout linearLayout5 = this.f730i0.getLeftItemAccessor().f234g;
            LinearLayout linearLayout6 = this.f730i0.getCenterLeftItemAccessor().f234g;
            LinearLayout linearLayout7 = this.f730i0.getCenterItemAccessor().f234g;
            LinearLayout linearLayout8 = this.f730i0.getCenterRightItemAccessor().f234g;
            LinearLayout linearLayout9 = this.f730i0.getRightItemAccessor().f234g;
            arrayList2.add(constraintLayout4);
            arrayList2.add(linearLayout5);
            arrayList2.add(linearLayout6);
            arrayList2.add(linearLayout7);
            arrayList2.add(linearLayout8);
            arrayList2.add(linearLayout9);
            ConstraintLayout constraintLayout5 = (ConstraintLayout) findViewById(R.id.arrow_count1);
            ConstraintLayout constraintLayout6 = (ConstraintLayout) findViewById(R.id.arrow_count2);
            arrayList2.add(constraintLayout5);
            arrayList2.add(constraintLayout6);
            LinearLayout elementContainerLayout = ((o) selectorContainer.getLeftSelector()).getElementContainerLayout();
            LinearLayout elementContainerLayout2 = ((o) selectorContainer.getCenterLeftSelector()).getElementContainerLayout();
            LinearLayout elementContainerLayout3 = ((o) selectorContainer.getCenterSelector()).getElementContainerLayout();
            arrayList3.add(elementContainerLayout);
            arrayList3.add(elementContainerLayout2);
            arrayList3.add(elementContainerLayout3);
        }
        LinearLayout elementContainerLayout4 = selectorContainer.getRightSelector() != null ? ((o) selectorContainer.getRightSelector()).getElementContainerLayout() : null;
        if (g.J(this.l) && this.f715c0.getValue() != null && zC) {
            A1 = m.a.a(this.f715c0.getValue().toString());
        }
        boolean zB = m.a.b((!this.f757u || this.O.equals(CaptureMethod.MOVIE)) ? 1 : A1);
        ConstraintSet constraintSet = this.f770y1;
        if (zB) {
            int iH = m.a.h(this.f773z1);
            if (iH != 0) {
                if ((iH == 1 || iH == 2) && !this.f768y) {
                    this.f732j0.setImageDrawable(null);
                    this.f732j0.setVisibility(4);
                    if (this.f757u) {
                        this.f734k0.setVisibility(0);
                    }
                    L();
                    if (!this.f760v) {
                        this.P0.setVisibility(8);
                    }
                    N();
                    int size = arrayList.size();
                    int i4 = 0;
                    while (i4 < size) {
                        Object obj = arrayList.get(i4);
                        i4++;
                        ((View) obj).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                    }
                    int size2 = arrayList2.size();
                    int i5 = 0;
                    while (i5 < size2) {
                        Object obj2 = arrayList2.get(i5);
                        i5++;
                        ((ViewGroup) obj2).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                    }
                    int size3 = arrayList3.size();
                    int i6 = 0;
                    while (i6 < size3) {
                        Object obj3 = arrayList3.get(i6);
                        i6++;
                        ViewGroup viewGroup = (ViewGroup) obj3;
                        for (int i7 = 0; i7 < viewGroup.getChildCount(); i7++) {
                            ((TextView) viewGroup.getChildAt(i7)).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                        }
                    }
                    if (elementContainerLayout4 != null) {
                        for (int i8 = 0; i8 < elementContainerLayout4.getChildCount(); i8++) {
                            ((ImageView) elementContainerLayout4.getChildAt(i8)).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                        }
                    }
                    constraintSet.clear(R.id.imageview_focus_icon);
                    constraintSet.connect(R.id.imageview_focus_icon, 3, R.id.imageview_liveview_horizontal, 3);
                    constraintSet.connect(R.id.imageview_focus_icon, 4, R.id.imageview_liveview_horizontal, 4);
                    constraintSet.connect(R.id.imageview_focus_icon, 1, R.id.imageview_liveview_horizontal, 1);
                    constraintSet.connect(R.id.imageview_focus_icon, 2, R.id.imageview_liveview_horizontal, 2);
                    o(this.f760v);
                    K(this.f760v);
                    q();
                    this.f768y = true;
                    return;
                }
                return;
            }
            if (this.f768y) {
                this.f734k0.setImageDrawable(null);
                this.f734k0.setVisibility(4);
                if (this.f757u) {
                    this.f732j0.setVisibility(0);
                    this.f732j0.setRotation(m.a.f(r6));
                }
                L();
                if (!this.f760v) {
                    this.P0.setVisibility(4);
                    this.Q0.setVisibility(4);
                }
                O();
                int size4 = arrayList.size();
                int i9 = 0;
                while (i9 < size4) {
                    Object obj4 = arrayList.get(i9);
                    i9++;
                    ((View) obj4).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                }
                int size5 = arrayList2.size();
                int i10 = 0;
                while (i10 < size5) {
                    Object obj5 = arrayList2.get(i10);
                    i10++;
                    ViewGroup viewGroup2 = (ViewGroup) obj5;
                    if (viewGroup2 != null) {
                        viewGroup2.startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                    }
                }
                int size6 = arrayList3.size();
                int i11 = 0;
                while (i11 < size6) {
                    Object obj6 = arrayList3.get(i11);
                    i11++;
                    ViewGroup viewGroup3 = (ViewGroup) obj6;
                    if (viewGroup3 != null) {
                        for (int i12 = 0; i12 < viewGroup3.getChildCount(); i12++) {
                            ((TextView) viewGroup3.getChildAt(i12)).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                        }
                    }
                }
                if (elementContainerLayout4 != null) {
                    for (int i13 = 0; i13 < elementContainerLayout4.getChildCount(); i13++) {
                        ((ImageView) elementContainerLayout4.getChildAt(i13)).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                    }
                }
                constraintSet.clear(R.id.imageview_focus_icon);
                constraintSet.connect(R.id.imageview_focus_icon, 3, R.id.imageview_liveview, 3);
                constraintSet.connect(R.id.imageview_focus_icon, 4, R.id.imageview_liveview, 4);
                constraintSet.connect(R.id.imageview_focus_icon, 1, R.id.imageview_liveview, 1);
                constraintSet.connect(R.id.imageview_focus_icon, 2, R.id.imageview_liveview, 2);
                constraintSet.clear(R.id.textview_capture_method_msg);
                constraintSet.connect(R.id.textview_capture_method_msg, 3, R.id.imageview_liveview, 3);
                constraintSet.connect(R.id.textview_capture_method_msg, 4, R.id.imageview_liveview, 4);
                constraintSet.connect(R.id.textview_capture_method_msg, 1, R.id.imageview_liveview, 1);
                constraintSet.connect(R.id.textview_capture_method_msg, 2, R.id.imageview_liveview, 2);
                K(this.f760v);
                r();
                this.f768y = false;
                return;
            }
            return;
        }
        int iH2 = m.a.h(this.f773z1);
        if (iH2 == 0) {
            if (this.f768y) {
                this.f732j0.setImageDrawable(null);
                this.f732j0.setVisibility(4);
                if (this.f757u) {
                    this.f734k0.setVisibility(0);
                }
                L();
                if (!this.f760v) {
                    this.P0.setVisibility(8);
                }
                N();
                int size7 = arrayList.size();
                int i14 = 0;
                while (i14 < size7) {
                    Object obj7 = arrayList.get(i14);
                    i14++;
                    ((View) obj7).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                }
                int size8 = arrayList2.size();
                int i15 = 0;
                while (i15 < size8) {
                    Object obj8 = arrayList2.get(i15);
                    i15++;
                    ViewGroup viewGroup4 = (ViewGroup) obj8;
                    if (viewGroup4 != null) {
                        viewGroup4.startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                    }
                }
                int size9 = arrayList3.size();
                int i16 = 0;
                while (i16 < size9) {
                    Object obj9 = arrayList3.get(i16);
                    i16++;
                    ViewGroup viewGroup5 = (ViewGroup) obj9;
                    if (viewGroup5 != null) {
                        for (int i17 = 0; i17 < viewGroup5.getChildCount(); i17++) {
                            ((TextView) viewGroup5.getChildAt(i17)).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                        }
                    }
                }
                if (elementContainerLayout4 != null) {
                    for (int i18 = 0; i18 < elementContainerLayout4.getChildCount(); i18++) {
                        ((ImageView) elementContainerLayout4.getChildAt(i18)).startAnimation(i3 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_0_from_counter_clockwise_90));
                    }
                }
                constraintSet.clear(R.id.imageview_focus_icon);
                constraintSet.connect(R.id.imageview_focus_icon, 3, R.id.imageview_liveview_horizontal, 3);
                constraintSet.connect(R.id.imageview_focus_icon, 4, R.id.imageview_liveview_horizontal, 4);
                constraintSet.connect(R.id.imageview_focus_icon, 1, R.id.imageview_liveview_horizontal, 1);
                constraintSet.connect(R.id.imageview_focus_icon, 2, R.id.imageview_liveview_horizontal, 2);
                o(this.f760v);
                K(this.f760v);
                q();
                this.f768y = false;
                return;
            }
            return;
        }
        if ((iH2 == 1 || iH2 == 2) && !this.f768y) {
            this.f734k0.setImageDrawable(null);
            this.f734k0.setVisibility(4);
            if (this.f757u) {
                this.f732j0.setVisibility(0);
                if (this.f773z1 == 3) {
                    this.f732j0.setRotation(m.a.f(m.a.c(r6)));
                } else {
                    this.f732j0.setRotation(m.a.f(m.a.d(r6)));
                }
            }
            L();
            if (!this.f760v) {
                this.P0.setVisibility(4);
                this.Q0.setVisibility(4);
            }
            O();
            int size10 = arrayList.size();
            int i19 = 0;
            while (i19 < size10) {
                Object obj10 = arrayList.get(i19);
                i19++;
                ((View) obj10).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
            }
            int size11 = arrayList2.size();
            int i20 = 0;
            while (i20 < size11) {
                Object obj11 = arrayList2.get(i20);
                i20++;
                ((ViewGroup) obj11).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
            }
            int size12 = arrayList3.size();
            int i21 = 0;
            while (i21 < size12) {
                Object obj12 = arrayList3.get(i21);
                i21++;
                ViewGroup viewGroup6 = (ViewGroup) obj12;
                for (int i22 = 0; i22 < viewGroup6.getChildCount(); i22++) {
                    ((TextView) viewGroup6.getChildAt(i22)).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                }
            }
            if (elementContainerLayout4 != null) {
                for (int i23 = 0; i23 < elementContainerLayout4.getChildCount(); i23++) {
                    ((ImageView) elementContainerLayout4.getChildAt(i23)).startAnimation(this.f773z1 == 3 ? AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_clockwise_90) : AnimationUtils.loadAnimation(this.f746p, R.anim.rotation_counter_clockwise_90));
                }
            }
            constraintSet.clear(R.id.imageview_focus_icon);
            constraintSet.connect(R.id.imageview_focus_icon, 3, R.id.imageview_liveview, 3);
            constraintSet.connect(R.id.imageview_focus_icon, 4, R.id.imageview_liveview, 4);
            constraintSet.connect(R.id.imageview_focus_icon, 1, R.id.imageview_liveview, 1);
            constraintSet.connect(R.id.imageview_focus_icon, 2, R.id.imageview_liveview, 2);
            constraintSet.clear(R.id.textview_capture_method_msg);
            constraintSet.connect(R.id.textview_capture_method_msg, 3, R.id.imageview_liveview, 3);
            constraintSet.connect(R.id.textview_capture_method_msg, 4, R.id.imageview_liveview, 4);
            constraintSet.connect(R.id.textview_capture_method_msg, 1, R.id.imageview_liveview, 1);
            constraintSet.connect(R.id.textview_capture_method_msg, 2, R.id.imageview_liveview, 2);
            K(this.f760v);
            r();
            this.f768y = true;
        }
    }

    @Override // android.view.View.OnTouchListener
    public final boolean onTouch(View view, MotionEvent motionEvent) {
        return false;
    }

    public final void p(View view, ImageView imageView, float f, int i3) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.connect(view.getId(), 3, imageView.getId(), 3);
        constraintSet.connect(view.getId(), 2, imageView.getId(), 2);
        constraintSet.connect(view.getId(), 4, imageView.getId(), 4);
        constraintSet.connect(view.getId(), 1, imageView.getId(), 1);
        constraintSet.applyTo(this.f727h0);
        view.getViewTreeObserver().addOnGlobalLayoutListener(new x3(this, imageView, i3, view, f));
    }

    public final void q() {
        this.f736l0.setGravity(48);
        int i3 = this.f773z1;
        p(this.f725g1, this.f734k0, (i3 != 1 ? i3 != 2 : A1 != 2) ? 90.0f : -90.0f, R.dimen.capture_method_message_margin_horizontal);
        p(this.f728h1, this.f734k0, this.f773z1 == 2 ? -90.0f : 90.0f, R.dimen.capture_method_error_message_margin_horizontal);
    }

    public final void r() {
        int i3;
        this.f736l0.setGravity(80);
        ConstraintSet constraintSet = new ConstraintSet();
        int i4 = A1;
        if (i4 == 3 || (((i3 = this.f773z1) == 2 && i4 == 2) || (i3 == 3 && i4 == 4))) {
            constraintSet.connect(this.f725g1.getId(), 3, this.f732j0.getId(), 3);
            constraintSet.connect(this.f725g1.getId(), 4, this.f732j0.getId(), 4);
        } else {
            constraintSet.connect(this.f725g1.getId(), 3, R.id.info_bar, 4);
            constraintSet.connect(this.f725g1.getId(), 4, this.f732j0.getId(), 3);
        }
        constraintSet.connect(this.f725g1.getId(), 2, this.f732j0.getId(), 2);
        constraintSet.connect(this.f725g1.getId(), 1, this.f732j0.getId(), 1);
        constraintSet.applyTo(this.f727h0);
        this.f725g1.getViewTreeObserver().addOnGlobalLayoutListener(new w3(this, 2));
        p(this.f728h1, this.f732j0, 0.0f, R.dimen.capture_method_error_message_margin_vertical);
    }

    public final void s(int i3, int i4, int i5) {
        int i6;
        int i7;
        int i8 = 16;
        if (this.f749q.equals("PENTAX K-3 Mark III") || this.f749q.equals("PENTAX K-3 Mark III Monochrome")) {
            i6 = 24;
        } else {
            i6 = 16;
            i8 = 8;
        }
        int i9 = i5 == 0 ? i6 : i8;
        if (i5 != 0) {
            i6 = i8;
        }
        if (i3 == i9) {
            this.f761v0.setVisibility(4);
        } else {
            this.f761v0.setVisibility(0);
        }
        if (i3 == (-i9)) {
            this.f758u0.setVisibility(4);
        } else {
            this.f758u0.setVisibility(0);
        }
        if (i4 == i6) {
            this.s0.setVisibility(4);
        } else {
            this.s0.setVisibility(0);
        }
        if (i4 == (-i6)) {
            this.t0.setVisibility(4);
        } else {
            this.t0.setVisibility(0);
        }
        if (i5 != 0) {
            if (i5 == 8) {
                this.r0.setVisibility(4);
            } else {
                this.r0.setVisibility(0);
            }
            if (i5 == -8) {
                this.f750q0.setVisibility(4);
                return;
            } else {
                this.f750q0.setVisibility(0);
                return;
            }
        }
        if (i3 > i8 || i3 < (i7 = -i8) || i4 > i8 || i4 < i7) {
            this.f750q0.setVisibility(4);
            this.r0.setVisibility(4);
        } else {
            this.f750q0.setVisibility(0);
            this.r0.setVisibility(0);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:19:0x0034  */
    /* JADX WARN: Removed duplicated region for block: B:26:0x004b A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final int t(com.ricoh.camera.sdk.wireless.api.CameraStorage r5) {
        /*
            r4 = this;
            com.ricoh.camera.sdk.wireless.api.StoragePermission r0 = r5.getPermission()
            com.ricoh.camera.sdk.wireless.api.StoragePermission r1 = com.ricoh.camera.sdk.wireless.api.StoragePermission.UNKNOWN
            r2 = 1
            if (r0 != r1) goto Lb
            r0 = 2
            goto Lc
        Lb:
            r0 = r2
        Lc:
            com.ricoh.camera.sdk.wireless.api.StoragePermission r1 = r5.getPermission()
            com.ricoh.camera.sdk.wireless.api.StoragePermission r3 = com.ricoh.camera.sdk.wireless.api.StoragePermission.READ_WRITE
            if (r1 == r3) goto L15
            r0 = 3
        L15:
            boolean r1 = r5.isFormatted()
            if (r1 != 0) goto L1c
            r0 = 4
        L1c:
            boolean r1 = m.a.e(r0, r2)
            if (r1 == 0) goto L4d
            com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod r1 = r4.O
            com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod r3 = com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod.MOVIE
            boolean r1 = r1.equals(r3)
            r3 = 0
            if (r1 != 0) goto L36
            int r5 = r5.getRemainingPictures()
            if (r5 > 0) goto L34
            goto L49
        L34:
            r2 = r3
            goto L49
        L36:
            com.ricoh.camera.sdk.wireless.api.CameraDevice r1 = r4.l
            boolean r1 = s.g.P(r1)
            if (r1 == 0) goto L43
            int r5 = r5.getRecordableTime()
            goto L47
        L43:
            int r5 = r5.getRemainingPictures()
        L47:
            if (r5 > 0) goto L34
        L49:
            if (r2 == 0) goto L4d
            r5 = 5
            return r5
        L4d:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.t(com.ricoh.camera.sdk.wireless.api.CameraStorage):int");
    }

    public final void u() {
        ArrayList arrayList = new ArrayList();
        List<CameraStorage> storages = this.l.getStorages();
        CameraStorage cameraStorageY = y(this.l);
        if (g.K(this.f749q)) {
            CameraStorage cameraStorage = storages.get(1);
            if (cameraStorageY.isWritingTarget()) {
                arrayList.add(cameraStorageY);
            }
            if (cameraStorage.isWritingTarget()) {
                arrayList.add(cameraStorage);
            }
        } else if (cameraStorageY.isWritingTarget()) {
            arrayList.add(cameraStorageY);
        }
        if (arrayList.isEmpty()) {
            T(2);
            V();
            this.f765x = true;
            return;
        }
        if (arrayList.size() == 1) {
            int iT = t((CameraStorage) arrayList.get(0));
            if (m.a.e(iT, 1)) {
                this.f744o0.setVisibility(4);
                this.f765x = false;
                return;
            } else {
                T(iT);
                V();
                this.f765x = true;
                return;
            }
        }
        if (arrayList.size() == 2) {
            int iT2 = t((CameraStorage) arrayList.get(0));
            int iT3 = t((CameraStorage) arrayList.get(1));
            if (m.a.e(iT2, 1) && m.a.e(iT3, 1)) {
                this.f744o0.setVisibility(4);
                this.f765x = false;
                return;
            }
            if (m.a.e(iT2, 1)) {
                T(iT3);
            } else {
                T(iT2);
            }
            V();
            this.f765x = true;
        }
    }

    public final CameraStorage v(CameraDevice cameraDevice) {
        if (cameraDevice == null) {
            throw new NullPointerException("Camera device instance is null.");
        }
        List<CameraStorage> storages = cameraDevice.getStorages();
        if (!g.N(cameraDevice)) {
            return this.Z.equals(CardSlot.SD2) ? storages.get(1) : storages.get(0);
        }
        CameraStorage cameraStorage = storages.get(1);
        return cameraStorage.isAvailable() ? cameraStorage : storages.get(0);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue
    java.lang.NullPointerException: Cannot invoke "java.util.List.iterator()" because the return value of "jadx.core.dex.visitors.regions.SwitchOverStringVisitor$SwitchData.getNewCases()" is null
    	at jadx.core.dex.visitors.regions.SwitchOverStringVisitor.restoreSwitchOverString(SwitchOverStringVisitor.java:109)
    	at jadx.core.dex.visitors.regions.SwitchOverStringVisitor.visitRegion(SwitchOverStringVisitor.java:66)
    	at jadx.core.dex.visitors.regions.DepthRegionTraversal.traverseIterativeStepInternal(DepthRegionTraversal.java:77)
    	at jadx.core.dex.visitors.regions.DepthRegionTraversal.traverseIterativeStepInternal(DepthRegionTraversal.java:82)
     */
    /* JADX WARN: Removed duplicated region for block: B:130:0x019e A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:131:0x019f A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:39:0x00ac A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final int w(com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod r18, com.ricoh.camera.sdk.wireless.api.setting.capture.ExposureProgram r19, com.ricoh.camera.sdk.wireless.api.setting.capture.GradND r20) {
        /*
            Method dump skipped, instructions count: 520
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.ricohimaging.imagesync.ShootingActivity.w(com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod, com.ricoh.camera.sdk.wireless.api.setting.capture.ExposureProgram, com.ricoh.camera.sdk.wireless.api.setting.capture.GradND):int");
    }

    public final boolean z() {
        return this.j.c(a.f1735x);
    }

    @Override // android.hardware.SensorEventListener
    public final void onAccuracyChanged(Sensor sensor, int i3) {
    }
}
