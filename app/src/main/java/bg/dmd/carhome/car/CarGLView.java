package bg.dmd.carhome.car;

import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The brand pack's car model (assets/car.bin, baked by tools/bake_car.py) rendered in the launcher's style:
 * graphite metallic body, dark glass, a blue rim light and a lit floor. Turns slowly; drag to spin.
 */
public class CarGLView extends GLSurfaceView implements GLSurfaceView.Renderer {
    /** Anchor points (model space, metres: x right, y up, z forward) that the callout overlay points at. */
    public static final float[][] ANCHORS = {{0f, 0.95f, 1.75f}, {0.45f, 0.9f, 1.85f}, {0f, 0.5f, 2.25f}, {0.3f, 1.25f, 0.2f}, {-0.8f, 0.5f, -1.0f}};
    public final float[] screen = new float[ANCHORS.length * 2];   // projected anchors (px), read by the overlay

    private final float d;
    private FloatBuffer verts;
    private int count;
    private int prog, floorProg;
    private final float[] proj = new float[16], view = new float[16], model = new float[16], mvp = new float[16], tmp = new float[16];
    private volatile float yaw = 45, pitch = 18, zoom = 1f;   // camera orbit, driven by touch
    private volatile float velYaw, velPitch;                   // fling inertia (deg/frame)
    private float lastX, lastY, lastSpan;
    private volatile long touchAt;
    private volatile boolean dragging;
    private volatile boolean alert;
    private int w, h;
    private boolean flip;   // set if the baked model faces backwards

    public CarGLView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        paint = c.getResources().getColor(bg.dmd.carhome.R.color.car_paint);
        setRenderer(this);
        load(c);
    }

    public void setAlert(boolean a) { alert = a; }

    /** Body colour: brand packs set it with <color name="car_paint"> (linear-ish RGB the shader lights). */
    private final int paint;

    private void load(Context c) {
        try {
            InputStream in = c.getAssets().open("car.bin");
            DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16));
            byte[] head = new byte[4];
            din.readFully(head);
            count = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] all = new byte[count * 9 * 4];
            din.readFully(all);
            din.close();
            ByteBuffer bb = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
            ByteBuffer src = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            verts = bb.asFloatBuffer();
            while (src.hasRemaining()) verts.put(src.getFloat());
            verts.position(0);
            flip = c.getSharedPreferences("car", Context.MODE_PRIVATE).getBoolean("modelFlip", false);
        } catch (Exception e) {
            count = 0;
        }
    }

    public boolean hasModel() { return count > 0; }

    private static final String VS =
            "uniform mat4 uMVP; uniform mat4 uModel;"
            + "attribute vec3 aPos; attribute vec3 aNrm; attribute vec3 aCol;"
            + "varying vec3 vN; varying vec3 vW; varying vec3 vCol; varying vec3 vO; varying float vSide;"
            + "void main(){ vN = (uModel * vec4(aNrm, 0.0)).xyz; vW = (uModel * vec4(aPos,1.0)).xyz; vCol = aCol; vO = aPos; vSide = aNrm.x;"
            + " gl_Position = uMVP * vec4(aPos,1.0); }";

    private static final String FS =
            "precision mediump float;"
            + "uniform vec3 uEye; uniform vec3 uRim; uniform vec3 uSky; uniform float uRimK;"
            + "varying vec3 vN; varying vec3 vW; varying vec3 vCol; varying vec3 vO; varying float vSide;"
            + "uniform sampler2D uLiv; uniform float uHasLiv; uniform vec3 uPaint;"
            // optional decal art (assets/livery.png), projected sideways onto the paint (box from photos of the author's van:
            // z -1.84 rear .. -1.00 front, y 0.88 .. 1.19). Top atlas half = side seen with the front on the
            // right (-x), bottom half = the other side, so the lettering reads correctly on both.
            + "vec4 livery(){"
            + " if (uHasLiv < 0.5 || abs(vSide) < 0.35) return vec4(0.0);"
            + " float f = (vO.z + 1.84) / 0.84; float u = vO.x > 0.0 ? 1.0 - f : f;"
            + " float v = (1.19 - vO.y) / 0.31;"
            + " if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) return vec4(0.0);"
            + " return texture2D(uLiv, vec2(u, v * 0.49 + (vO.x > 0.0 ? 0.505 : 0.0)));"
            + "}"
            + "void main(){"
            + " vec3 n = normalize(vN); vec3 v = normalize(uEye - vW);"
            + " if (dot(n, v) < 0.0) n = -n;"
            + " if (vCol.r > 1.0) { gl_FragColor = vec4((vCol - 1.0) * 1.1, 1.0); return; }"   // tail lights / indicators glow
            + " bool glass = vCol.r < 0.0; bool paint = vCol.r > 0.45; bool metal = !paint && vCol.r > 0.2;"
            + " vec3 base = glass ? vec3(0.015,0.02,0.03) : paint ? uPaint : metal ? vec3(0.42,0.44,0.48) : vec3(0.035,0.038,0.042);"
            + " if (paint) { vec4 lv = livery(); base = mix(base, lv.rgb * 0.8, lv.a); }"
            + " vec3 l1 = normalize(vec3(0.4, 1.0, 0.6)); vec3 l2 = normalize(vec3(-0.7, 0.4, -0.5));"
            + " float dif = max(dot(n,l1),0.0)*0.85 + max(dot(n,l2),0.0)*0.3 + 0.18;"
            + " vec3 hv = normalize(l1 + v);"
            + " float spec = pow(max(dot(n,hv),0.0), glass ? 90.0 : metal ? 60.0 : 32.0) * (glass ? 1.4 : metal ? 1.2 : paint ? 0.55 : 0.25);"
            + " float fres = pow(1.0 - max(dot(n,v),0.0), 3.0);"
            + " vec3 sky = uSky * max(reflect(-v, n).y, 0.0) * (paint ? base * 2.4 : glass ? vec3(1.0) : vec3(0.3));"   // cool studio reflection
            + " vec3 c = base * dif + vec3(spec) + sky + uRim * fres * (paint ? 0.4 : 0.45) * uRimK;"
            + " gl_FragColor = vec4(c, 1.0);"
            + "}";

    private static final String FVS =
            "uniform mat4 uMVP; attribute vec2 aXZ; varying vec2 vP;"
            + "void main(){ vP = aXZ; gl_Position = uMVP * vec4(aXZ.x, 0.0, aXZ.y, 1.0); }";
    private static final String FFS =
            "precision mediump float; uniform vec3 uRim; uniform vec3 uBg; varying vec2 vP;"
            + "void main(){ float r = length(vP / vec2(1.6, 2.9));"
            + " float glow = smoothstep(1.0, 0.0, r) * 0.35;"
            + " float ring = smoothstep(0.03, 0.0, abs(fract(r * 3.0) - 0.5) - 0.47) * 0.12 * step(r, 1.0);"
            + " float shadow = smoothstep(0.55, 0.2, r) * 0.6;"
            + " float edge = smoothstep(1.0, 0.75, length(vP) / 4.2);"
            + " vec3 c = mix(uBg, uRim, (glow + ring) * edge); gl_FragColor = vec4(c * (1.0 - shadow * 0.7), 1.0); }";

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) android.util.Log.e("CarGL", "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) android.util.Log.e("CarGL", "link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private FloatBuffer floor;
    private int liveryTex;

    private int loadTexture(String asset) {
        int[] t = new int[1];
        try {
            InputStream in = getContext().getAssets().open(asset);
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            b.recycle();
        } catch (Exception e) {
            return 0;   // the brand pack has no decal art
        }
        return t[0];
    }
    private float[] bgRgb;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        prog = program(VS, FS);
        floorProg = program(FVS, FFS);
        float s = 4.2f;
        float[] q = {-s, -s, s, -s, -s, s, s, s};
        floor = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        floor.put(q).position(0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        liveryTex = loadTexture("livery.png");
        int pageCol = bg.dmd.carhome.Theme.PAGE;
        bgRgb = new float[]{((pageCol >> 16) & 255) / 255f, ((pageCol >> 8) & 255) / 255f, (pageCol & 255) / 255f};
        if (!bg.dmd.carhome.Theme.day) bgRgb = new float[]{0.027f, 0.035f, 0.047f};
        GLES20.glClearColor(bgRgb[0], bgRgb[1], bgRgb[2], 1f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        w = width; h = height;
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 30f, (float) w / h, 0.5f, 40f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!dragging) {
            yaw += velYaw; pitch = clampPitch(pitch + velPitch);
            velYaw *= 0.94f; velPitch *= 0.94f;
            long idle = SystemClock.uptimeMillis() - touchAt;
            if (idle > 6000) {                       // drift back to the default view, then idle spin
                yaw += 0.15f;
                pitch += (18 - pitch) * 0.02f;
                zoom += (1f - zoom) * 0.02f;
            }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        float dist = 11f / zoom;
        float ex = (float) (Math.sin(yr) * Math.cos(pr) * dist), ez = (float) (Math.cos(yr) * Math.cos(pr) * dist);
        float ey = 0.65f + (float) (Math.sin(pr) * dist);
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0.65f, 0f, 0f, 1f, 0f);
        Matrix.setIdentityM(model, 0);
        if (flip) Matrix.rotateM(model, 0, 180f, 0f, 1f, 0f);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

        boolean day = bg.dmd.carhome.Theme.day;
        float[] rim = alert ? ((SystemClock.uptimeMillis() / 600) % 2 == 0 ? new float[]{1f, 0.71f, 0.28f} : new float[]{1f, 0.35f, 0.3f})
                : day ? new float[]{0f, 0.38f, 0.84f} : new float[]{0.29f, 0.64f, 1f};

        // floor
        float[] fm = new float[16];
        Matrix.multiplyMM(fm, 0, proj, 0, view, 0);
        GLES20.glUseProgram(floorProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(floorProg, "uMVP"), 1, false, fm, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(floorProg, "uRim"), 1, rim, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(floorProg, "uBg"), 1, bgRgb, 0);
        int axz = GLES20.glGetAttribLocation(floorProg, "aXZ");
        GLES20.glEnableVertexAttribArray(axz);
        GLES20.glVertexAttribPointer(axz, 2, GLES20.GL_FLOAT, false, 0, floor);
        GLES20.glDepthMask(false);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDepthMask(true);

        if (count > 0) {
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, liveryTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLiv"), 0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasLiv"), liveryTex != 0 ? 1f : 0f);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uPaint"), Color.red(paint) / 255f, Color.green(paint) / 255f, Color.blue(paint) / 255f);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, model, 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uEye"), ex, ey, ez);
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uRim"), 1, rim, 0);
            if (day) GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uSky"), 0.42f, 0.46f, 0.52f);   // bright daylight sky in the paint
            else GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uSky"), 0.10f, 0.16f, 0.26f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRimK"), day ? 0.35f : 1f);
            int ap = GLES20.glGetAttribLocation(prog, "aPos"), an = GLES20.glGetAttribLocation(prog, "aNrm"), ac = GLES20.glGetAttribLocation(prog, "aCol");
            verts.position(0);
            GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 36, verts);
            verts.position(3);
            GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, 36, verts);
            verts.position(6);
            GLES20.glVertexAttribPointer(ac, 3, GLES20.GL_FLOAT, false, 36, verts);
            GLES20.glEnableVertexAttribArray(ap);
            GLES20.glEnableVertexAttribArray(an);
            GLES20.glEnableVertexAttribArray(ac);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }

        // project callout anchors for the overlay
        float[] v4 = new float[4], o4 = new float[4];
        for (int i = 0; i < ANCHORS.length; i++) {
            v4[0] = ANCHORS[i][0]; v4[1] = ANCHORS[i][1]; v4[2] = ANCHORS[i][2] * (flip ? -1 : 1); v4[3] = 1;
            float[] full = new float[16];
            Matrix.multiplyMM(full, 0, proj, 0, view, 0);
            Matrix.multiplyMV(o4, 0, full, 0, v4, 0);
            screen[i * 2] = (o4[0] / o4[3] * 0.5f + 0.5f) * w;
            screen[i * 2 + 1] = (1f - (o4[1] / o4[3] * 0.5f + 0.5f)) * h;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        touchAt = SystemClock.uptimeMillis();
        int n = e.getPointerCount();
        float x = 0, y = 0;
        for (int i = 0; i < n; i++) { x += e.getX(i); y += e.getY(i); }
        x /= n; y /= n;
        float span = n > 1 ? (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) : 0;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true; velYaw = velPitch = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                break;                               // finger count changed: just re-anchor below
            case MotionEvent.ACTION_MOVE: {
                float dy = -(x - lastX) * 0.35f, dp = (y - lastY) * 0.25f;
                yaw += dy; pitch = clampPitch(pitch + dp);
                velYaw = dy; velPitch = dp;
                if (n > 1 && lastSpan > 0) zoom = Math.max(0.6f, Math.min(2.2f, zoom * span / lastSpan));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {   // exclude the lifting finger from the centroid
            int up = e.getActionIndex();
            x = 0; y = 0;
            for (int i = 0; i < n; i++) if (i != up) { x += e.getX(i); y += e.getY(i); }
            x /= (n - 1); y /= (n - 1);
            span = 0;
        }
        lastX = x; lastY = y; lastSpan = span;
        return true;
    }

    private static float clampPitch(float p) { return Math.max(2f, Math.min(75f, p)); }
}
