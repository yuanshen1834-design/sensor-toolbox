package com.dsh.sensorkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 自绘控件：指南针、水平仪、MD3 底部导航、环形仪表。 */
public final class Views {

    /** 指南针：固定刻度盘 + 红色指北针。 */
    public static class CompassView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float azimuth = 0f;

        public CompassView(Context c) {
            super(c);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        public void setAzimuth(float deg) {
            azimuth = deg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f + 6f;
            float r = Math.min(w, h) / 2f - 26f;

            p.setStyle(Paint.Style.FILL);
            p.setColor(Md3.SURFACE_HIGH);
            canvas.drawCircle(cx, cy, r + 10f, p);

            // 刻度
            for (int i = 0; i < 72; i++) {
                float ang = (float) Math.toRadians(i * 5);
                boolean major = i % 18 == 0;
                boolean mid = i % 9 == 0;
                float len = major ? 14f : (mid ? 10f : 5f);
                float sx = cx + (float) Math.sin(ang) * (r - len);
                float sy = cy - (float) Math.cos(ang) * (r - len);
                float ex = cx + (float) Math.sin(ang) * r;
                float ey = cy - (float) Math.cos(ang) * r;
                p.setColor(major ? Md3.ON_SURFACE : Md3.OUTLINE_VARIANT);
                p.setStrokeWidth(major ? 3f : (mid ? 2f : 1f));
                p.setStyle(Paint.Style.STROKE);
                canvas.drawLine(sx, sy, ex, ey, p);
            }

            // 方位文字
            String[] names = {"北", "东", "南", "西"};
            int[] colors = {Md3.ERROR, Md3.ON_SURFACE_VARIANT, Md3.ON_SURFACE_VARIANT,
                    Md3.ON_SURFACE_VARIANT};
            tp.setTextSize(r * 0.17f);
            for (int i = 0; i < 4; i++) {
                float ang = (float) Math.toRadians(i * 90);
                float x = cx + (float) Math.sin(ang) * (r - 40f);
                float y = cy - (float) Math.cos(ang) * (r - 40f) + r * 0.06f;
                tp.setColor(colors[i]);
                canvas.drawText(names[i], x, y, tp);
            }

            // 指针：红端指北，白端指南
            float rot = (float) Math.toRadians(-azimuth);
            canvas.save();
            canvas.rotate((float) Math.toDegrees(rot), cx, cy);
            float len = r - 34f;
            drawNeedleHalf(canvas, cx, cy, len, Md3.ERROR, true);
            drawNeedleHalf(canvas, cx, cy, len, Color.parseColor("#D6D2E0"), false);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Md3.BG);
            canvas.drawCircle(cx, cy, r * 0.075f, p);
            p.setColor(Md3.PRIMARY);
            canvas.drawCircle(cx, cy, r * 0.045f, p);
            canvas.restore();

            // 中心读数
            tp.setColor(Md3.ON_SURFACE);
            tp.setTextSize(r * 0.16f);
            canvas.drawText(String.format(Locale.CHINA, "%.0f°", azimuth), cx, cy + r * 0.46f, tp);
        }

        private void drawNeedleHalf(Canvas canvas, float cx, float cy, float len, int color,
                                    boolean north) {
            float dir = north ? -1f : 1f;
            Path path = new Path();
            path.moveTo(cx, cy + dir * len);
            path.lineTo(cx - len * 0.17f, cy);
            path.lineTo(cx + len * 0.17f, cy);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            canvas.drawPath(path, p);
        }
    }

    /** 水平仪：MD3 风格圆盘 + 气泡。 */
    public static class LevelView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float bx, by, tilt = 90f;

        public LevelView(Context c) {
            super(c);
        }

        public void setTilt(float tx, float ty, float total) {
            bx = Math.max(-1f, Math.min(1f, -tx / 25f));
            by = Math.max(-1f, Math.min(1f, ty / 25f));
            tilt = total;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - 18f;

            p.setStyle(Paint.Style.FILL);
            p.setColor(Md3.SURFACE_HIGH);
            canvas.drawCircle(cx, cy, r, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Md3.OUTLINE_VARIANT);
            canvas.drawCircle(cx, cy, r * 0.62f, p);
            canvas.drawOval(new RectF(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f), p);
            canvas.drawLine(cx - r, cy, cx + r, cy, p);
            canvas.drawLine(cx, cy - r, cx, cy + r, p);

            boolean level = tilt < 1.5f;
            float br = r * 0.2f;
            int color = level ? Md3.OK : Md3.TERTIARY;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setAlpha(50);
            canvas.drawCircle(cx + bx * (r - br), cy + by * (r - br), br * 1.6f, p);
            p.setAlpha(255);
            canvas.drawCircle(cx + bx * (r - br), cy + by * (r - br), br, p);
        }
    }

    /** MD3 底部导航栏（自绘图标）。 */
    public static class NavBar extends View {
        public interface Listener {
            void onSelect(int index);
        }

        private static final String[] LABELS = {"概览", "工具", "识别", "内核", "控制台"};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selected = 0;
        private Listener listener;

        public NavBar(Context c) {
            super(c);
            p.setStrokeWidth(3.4f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        public void setSelected(int i) {
            selected = i;
            invalidate();
        }

        public void setListener(Listener l) {
            listener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float itemW = w / 5f;
            tp.setTextSize(Md3.dp(getContext(), 11));
            for (int i = 0; i < 5; i++) {
                float cx = itemW * i + itemW / 2f;
                float pillCy = h * 0.36f;

                if (i == selected) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Md3.SECONDARY_CONTAINER);
                    float pw = Md3.dp(getContext(), 58), ph = Md3.dp(getContext(), 30);
                    canvas.drawRoundRect(new RectF(cx - pw / 2, pillCy - ph / 2,
                            cx + pw / 2, pillCy + ph / 2), ph / 2, ph / 2, p);
                }

                p.setStyle(Paint.Style.STROKE);
                p.setColor(i == selected ? Md3.ON_PRIMARY_CONTAINER : Md3.ON_SURFACE_VARIANT);
                drawIcon(canvas, i, cx, pillCy, Md3.dp(getContext(), 21));

                tp.setColor(i == selected ? Md3.PRIMARY : Md3.ON_SURFACE_VARIANT);
                canvas.drawText(LABELS[i], cx, h - Md3.dp(getContext(), 12), tp);
            }
        }

        private void drawIcon(Canvas canvas, int idx, float cx, float cy, float s) {
            float h = s / 2f;
            Path path = new Path();
            switch (idx) {
                case 0: // 首页
                    path.moveTo(cx - h, cy - h * 0.1f);
                    path.lineTo(cx, cy - h);
                    path.lineTo(cx + h, cy - h * 0.1f);
                    path.lineTo(cx + h * 0.72f, cy - h * 0.1f);
                    path.lineTo(cx + h * 0.72f, cy + h);
                    path.lineTo(cx + h * 0.18f, cy + h);
                    path.lineTo(cx + h * 0.18f, cy + h * 0.28f);
                    path.lineTo(cx - h * 0.18f, cy + h * 0.28f);
                    path.lineTo(cx - h * 0.18f, cy + h);
                    path.lineTo(cx - h * 0.72f, cy + h);
                    path.lineTo(cx - h * 0.72f, cy - h * 0.1f);
                    path.close();
                    canvas.drawPath(path, p);
                    break;
                case 1: // 工具：四个圆角块
                    float q = h * 0.34f;
                    float off = h * 0.52f;
                    canvas.drawRoundRect(new RectF(cx - off - q, cy - off - q, cx - off + q, cy - off + q), 5, 5, p);
                    canvas.drawRoundRect(new RectF(cx + off - q, cy - off - q, cx + off + q, cy - off + q), 5, 5, p);
                    canvas.drawRoundRect(new RectF(cx - off - q, cy + off - q, cx - off + q, cy + off + q), 5, 5, p);
                    canvas.drawRoundRect(new RectF(cx + off - q, cy + off - q, cx + off + q, cy + off + q), 5, 5, p);
                    break;
                case 2: // 识别：取景框
                    float c2 = h * 0.34f;
                    canvas.drawRect(cx - h, cy - h, cx - h + c2, cy - h + c2, p);
                    canvas.drawRect(cx + h - c2, cy - h, cx + h, cy - h + c2, p);
                    canvas.drawRect(cx - h, cy + h - c2, cx - h + c2, cy + h, p);
                    canvas.drawRect(cx + h - c2, cy + h - c2, cx + h, cy + h, p);
                    p.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, h * 0.18f, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                case 3: // 芯片
                    canvas.drawRoundRect(new RectF(cx - h * 0.62f, cy - h * 0.62f,
                            cx + h * 0.62f, cy + h * 0.62f), 5, 5, p);
                    canvas.drawRoundRect(new RectF(cx - h * 0.26f, cy - h * 0.26f,
                            cx + h * 0.26f, cy + h * 0.26f), 3, 3, p);
                    for (int k = -2; k <= 2; k++) {
                        float t = cx + k * h * 0.32f;
                        canvas.drawLine(t, cy - h * 0.62f, t, cy - h * 0.95f, p);
                        canvas.drawLine(t, cy + h * 0.62f, t, cy + h * 0.95f, p);
                        float v = cy + k * h * 0.32f;
                        canvas.drawLine(cx - h * 0.62f, v, cx - h * 0.95f, v, p);
                        canvas.drawLine(cx + h * 0.62f, v, cx + h * 0.95f, v, p);
                    }
                    break;
                default: // 控制台
                    canvas.drawRoundRect(new RectF(cx - h, cy - h * 0.72f, cx + h, cy + h * 0.72f), 4, 4, p);
                    path.moveTo(cx - h * 0.5f, cy - h * 0.25f);
                    path.lineTo(cx - h * 0.15f, cy);
                    path.lineTo(cx - h * 0.5f, cy + h * 0.25f);
                    canvas.drawPath(path, p);
                    canvas.drawLine(cx + h * 0.1f, cy + h * 0.28f, cx + h * 0.52f, cy + h * 0.28f, p);
                    break;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int idx = (int) (e.getX() / (getWidth() / 5f));
                if (idx >= 0 && idx < 5 && listener != null) listener.onSelect(idx);
                return true;
            }
            return true;
        }
    }

    /** 与检测器解耦的通用检测框数据。 */
    public static class DetBox {
        public String label = "";
        public float score, x1, y1, x2, y2;
        public boolean online;

        public DetBox() {
        }

        public DetBox(String label, float score, float x1, float y1, float x2, float y2,
                      boolean online) {
            this.label = label;
            this.score = score;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.online = online;
        }
    }

    /** 检测框叠加层。 */
    public static class DetectionOverlay extends View {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<DetBox> dets = new ArrayList<>();
        private int rotation = 90;
        private int bufW = 640, bufH = 480;

        public DetectionOverlay(Context c) {
            super(c);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(3f);
            txt.setTextSize(30f);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            fill.setColor(0xCC39D38A);
        }

        public void setDetections(List<DetBox> d, int rotation, int bw, int bh) {
            dets.clear();
            if (d != null) dets.addAll(d);
            this.rotation = rotation;
            this.bufW = bw;
            this.bufH = bh;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            for (DetBox d : dets) {
                float x1 = d.x1, y1 = d.y1, x2 = d.x2, y2 = d.y2;
                if (rotation == 90) {
                    x1 = 1 - d.y2;
                    y1 = d.x1;
                    x2 = 1 - d.y1;
                    y2 = d.x2;
                } else if (rotation == 270) {
                    x1 = d.y1;
                    y1 = 1 - d.x2;
                    x2 = d.y2;
                    y2 = 1 - d.x1;
                } else if (rotation == 180) {
                    x1 = 1 - d.x2;
                    y1 = 1 - d.y2;
                    x2 = 1 - d.x1;
                    y2 = 1 - d.y1;
                }
                boolean swap = rotation == 90 || rotation == 270;
                float dw = swap ? bufH : bufW;
                float dh = swap ? bufW : bufH;
                float scale = Math.max(w / dw, h / dh);
                float px = dw * scale, py = dh * scale;
                float ox = (px - w) / 2f, oy = (py - h) / 2f;
                float l = x1 * px - ox, t = y1 * py - oy;
                float r = x2 * px - ox, b = y2 * py - oy;
                canvas.drawRect(l, t, r, b, box);
                String label = d.label + " " + Math.round(d.score * 100) + "%";
                float tw = txt.measureText(label);
                canvas.drawRect(l, Math.max(0, t - 38), l + tw + 18, t, fill);
                txt.setColor(0xFF0B0F14);
                canvas.drawText(label, l + 9, Math.max(26, t - 11), txt);
            }
        }
    }



    /** 立体点云：每个物体采样成三维点团，透视投影 + 自转 + 深度排序。 */
    public static class PointCloudView extends View {
        public static class CloudObj {
            public String label = "";
            public float bearing;      // 方位角
            public float depth;        // 0 近 ~ 1 远
            public float wWorld = 1f;  // 世界坐标宽度
            public float hWorld = 1f;
            public int gw = 1, gh = 1;
            public int[] colors = new int[0];
            public boolean online;
        }

        private final List<CloudObj> objs = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long t0 = System.nanoTime();

        public PointCloudView(Context c) {
            super(c);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            grid.setStrokeWidth(1f);
        }

        public void setObjects(List<CloudObj> list) {
            objs.clear();
            if (list != null) objs.addAll(list);
            t0 = System.nanoTime();
            invalidate();
        }

        private float yaw() {
            return (float) ((System.nanoTime() - t0) / 1e9 * 0.45);
        }

        /** 横向槽位：多个物体并排分开，互不重叠。 */
        private float slotX(int i, int n) {
            if (n <= 1) return 0f;
            float spacing = Math.min(1.65f, 5.2f / n);
            return (i - (n - 1) / 2f) * spacing;
        }

        /** 透视投影：返回 {屏幕x, 屏幕y, 相机深度}，null 表示在相机后面。 */
        private float[] project(float x, float y, float z, float yaw, float w, float h) {
            float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
            float X = x * cy - z * sy;
            float Z = x * sy + z * cy;
            float eyeH = 1.35f, eyeD = -3.4f;
            float dz = Z - eyeD;
            if (dz < 0.15f) return null;
            float f = h * 0.85f;
            return new float[]{w / 2f + X / dz * f, h * 0.62f - (y - eyeH) / dz * f, dz};
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float yaw = yaw();

            // 三维地面网格
            grid.setColor(0x3349454F);
            for (int i = -4; i <= 4; i++) {
                float[] a = project(i * 0.7f, 0, 1.2f, yaw, w, h);
                float[] b = project(i * 0.7f, 0, 7.5f, yaw, w, h);
                if (a != null && b != null) canvas.drawLine(a[0], a[1], b[0], b[1], grid);
            }
            for (int i = 1; i <= 7; i++) {
                float z = 1.2f + i * 0.9f;
                float[] a = project(-2.8f, 0, z, yaw, w, h);
                float[] b = project(2.8f, 0, z, yaw, w, h);
                if (a != null && b != null) canvas.drawLine(a[0], a[1], b[0], b[1], grid);
            }

            // 收集所有点
            List<float[]> pts = new ArrayList<>();
            List<Integer> cols = new ArrayList<>();
            List<Boolean> online = new ArrayList<>();
            for (int oi = 0; oi < objs.size(); oi++) {
                CloudObj o = objs.get(oi);
                float ox = slotX(oi, objs.size());
                float oz = 2.7f;
                float gw2 = Math.min(0.92f, o.wWorld);
                float gh2 = Math.min(1.5f, o.hWorld);
                float bulge = 0.22f * Math.min(gw2, gh2);
                for (int j = 0; j < o.gh; j++) {
                    for (int i = 0; i < o.gw; i++) {
                        int c = o.colors[j * o.gw + i];
                        if (c == 0) continue;
                        float u = (i + 0.5f) / o.gw;
                        float v = (j + 0.5f) / o.gh;
                        float lx = (u - 0.5f) * gw2;
                        float ly = (0.5f - v) * gh2;
                        float rr = Math.max(0f, 1f - (2 * u - 1) * (2 * u - 1)
                                - (2 * v - 1) * (2 * v - 1));
                        float lz = bulge * (float) Math.sqrt(rr);
                        float[] sp = project(ox + lx, ly + gh2 * 0.5f + 0.06f, oz + lz, yaw, w, h);
                        if (sp == null) continue;
                        pts.add(sp);
                        cols.add(c);
                        online.add(o.online);
                    }
                }
            }

            // 由远到近绘制
            final List<Boolean> onl = online;
            Integer[] idx = new Integer[pts.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Float.compare(pts.get(b)[2], pts.get(a)[2]));
            for (int k = 0; k < idx.length; k++) {
                float[] sp = pts.get(idx[k]);
                int c = cols.get(idx[k]);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                if (onl.get(idx[k])) {
                    p.setColor(0xFF000000
                            | ((int) (90 + 110 * lum) << 16)
                            | ((int) (200 + 55 * lum) << 8)
                            | (int) (160 + 60 * lum));
                } else {
                    p.setColor(0xFF000000
                            | ((int) (25 + 115 * lum) << 16)
                            | ((int) (110 + 140 * lum) << 8)
                            | (int) (70 + 110 * lum));
                }
                p.setAlpha(228);
                float rad = Math.max(1.1f, Math.min(5.2f, 26f / sp[2]));
                canvas.drawCircle(sp[0], sp[1], rad, p);
            }

            // 标签
            tp.setTextSize(Md3.dp(getContext(), 10));
            for (int oi = 0; oi < objs.size(); oi++) {
                CloudObj o = objs.get(oi);
                float[] sp = project(slotX(oi, objs.size()), 0.02f, 2.7f, yaw, w, h);
                if (sp == null) continue;
                tp.setColor(o.online ? 0xFFEFB8C8 : 0xFF8CE99A);
                canvas.drawText(o.label + (o.online ? "·云" : ""), sp[0], sp[1] + Md3.dp(getContext(), 14), tp);
            }

            if (objs.isEmpty()) {
                tp.setTextSize(Md3.dp(getContext(), 11));
                tp.setColor(Md3.OUTLINE);
                canvas.drawText("等画面里的物体…", w / 2f, h * 0.5f, tp);
            } else {
                postInvalidateOnAnimation();
            }
        }
    }

    /** 摄像头雷达：右侧俯视示意图，按方位角和估计距离摆物体。 */
    public static class RadarView extends View {
        public static class Blip {
            public final String label;
            public final float bearing;   // 角度，0=正前，右正左负
            public final float dist;      // 0..1，越近越大
            public final boolean online;

            public Blip(String label, float bearing, float dist, boolean online) {
                this.label = label;
                this.bearing = bearing;
                this.dist = dist;
                this.online = online;
            }
        }

        private final List<Blip> blips = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

        public RadarView(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        public void setBlips(List<Blip> list) {
            blips.clear();
            if (list != null) blips.addAll(list);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h - 18f;
            float r = Math.min(w / 2f - 14f, h - 40f);

            p.setStrokeWidth(1.5f);
            p.setColor(Md3.OUTLINE_VARIANT);
            for (float k = 0.34f; k <= 1.001f; k += 0.33f) {
                canvas.drawCircle(cx, cy, r * k, p);
            }
            for (int a = -35; a <= 35; a += 17) {
                double rad = Math.toRadians(a);
                canvas.drawLine(cx, cy, cx + (float) Math.sin(rad) * r,
                        cy - (float) Math.cos(rad) * r, p);
            }

            tp.setTextSize(Md3.dp(getContext(), 9));
            tp.setColor(Md3.OUTLINE);
            canvas.drawText("远", cx, cy - r * 0.96f, tp);
            canvas.drawText("中", cx, cy - r * 0.64f, tp);
            canvas.drawText("近", cx, cy - r * 0.31f, tp);

            for (Blip b : blips) {
                double rad = Math.toRadians(b.bearing);
                float x = cx + (float) Math.sin(rad) * r * 0.82f * (0.35f + b.dist * 0.65f);
                float y = cy - (float) Math.cos(rad) * r * 0.82f * (0.35f + b.dist * 0.65f);
                int color = b.online ? Md3.TERTIARY : Md3.OK;

                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                p.setAlpha(40);
                canvas.drawCircle(x, y, Md3.dp(getContext(), 17), p);
                p.setAlpha(255);

                drawIcon(canvas, b.label, x, y, Md3.dp(getContext(), 11), color);

                tp.setTextSize(Md3.dp(getContext(), 10));
                tp.setColor(color);
                canvas.drawText(b.label + (b.online ? "·云" : ""), x, y + Md3.dp(getContext(), 24), tp);
                p.setStyle(Paint.Style.STROKE);
            }
        }

        /** 按类别画一个简化形状，认不出来的画圆点。 */
        private void drawIcon(Canvas canvas, String label, float cx, float cy, float s, int color) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(color);
            String l = label == null ? "" : label;
            if (l.contains("键盘")) {
                canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.6f),
                        3, 3, p);
                p.setStyle(Paint.Style.FILL);
                for (int i = -2; i <= 2; i++) {
                    for (int j = -1; j <= 1; j++) {
                        canvas.drawCircle(cx + i * s * 0.34f, cy + j * s * 0.29f, s * 0.08f, p);
                    }
                }
            } else if (l.contains("人") && !l.contains("手机")) {
                canvas.drawCircle(cx, cy - s * 0.45f, s * 0.3f, p);
                canvas.drawArc(new RectF(cx - s * 0.6f, cy - s * 0.05f, cx + s * 0.6f,
                        cy + s * 1.1f), 200, 140, false, p);
            } else if (l.contains("手机") || l.contains("遥控") || l.contains("电视")) {
                canvas.drawRoundRect(new RectF(cx - s * 0.55f, cy - s, cx + s * 0.55f, cy + s),
                        3, 3, p);
            } else if (l.contains("笔记本电脑")) {
                canvas.drawRect(cx - s * 0.75f, cy - s, cx + s * 0.75f, cy + s * 0.35f, p);
                canvas.drawLine(cx - s, cy + s * 0.6f, cx + s, cy + s * 0.6f, p);
            } else if (l.contains("杯") || l.contains("瓶")) {
                canvas.drawRect(cx - s * 0.45f, cy - s * 0.9f, cx + s * 0.45f, cy + s, p);
            } else if (l.contains("书")) {
                canvas.drawRect(cx - s, cy - s * 0.7f, cx - s * 0.05f, cy + s * 0.7f, p);
                canvas.drawRect(cx + s * 0.05f, cy - s * 0.7f, cx + s, cy + s * 0.7f, p);
            } else if (l.contains("车")) {
                canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.4f, cx + s, cy + s * 0.45f),
                        4, 4, p);
                canvas.drawCircle(cx - s * 0.55f, cy + s * 0.55f, s * 0.2f, p);
                canvas.drawCircle(cx + s * 0.55f, cy + s * 0.55f, s * 0.2f, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, s * 0.45f, p);
            }
            p.setStyle(Paint.Style.STROKE);
        }
    }


    /** 电池电流/温度迷你曲线。 */
    public static class Spark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] data = new float[120];
        private int n = 0;
        private String unit = "mA";
        private float minV = -2000, maxV = 2000;

        public Spark(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(Md3.PRIMARY);
            fillP.setColor(Md3.PRIMARY);
            fillP.setAlpha(30);
            tp.setTextSize(Md3.dp(c, 9));
            tp.setColor(Md3.OUTLINE);
        }

        public void push(float v, String unit) {
            if (n < data.length) {
                data[n++] = v;
            } else {
                System.arraycopy(data, 1, data, 0, data.length - 1);
                data[data.length - 1] = v;
            }
            this.unit = unit;
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (data[i] < mn) mn = data[i];
                if (data[i] > mx) mx = data[i];
            }
            minV = Math.min(mn, 0);
            maxV = Math.max(mx, 0);
            if (Math.abs(maxV - minV) < 1f) maxV = minV + 1f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (n < 2) return;
            p.setColor(Md3.OUTLINE_VARIANT);
            p.setStrokeWidth(1f);
            canvas.drawLine(0, h / 2f, w, h / 2f, p);
            p.setColor(Md3.PRIMARY);
            p.setStrokeWidth(2.5f);
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < n; i++) {
                float x = i * w / (float) (data.length - 1);
                float y = h - (data[i] - minV) / (maxV - minV) * (h - 8f) - 4f;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            canvas.drawPath(path, p);
            tp.setTextSize(Md3.dp(getContext(), 9));
            tp.setColor(Md3.ON_SURFACE_VARIANT);
            canvas.drawText(String.format(Locale.CHINA, "%.0f %s", data[n - 1], unit), 2, Md3.dp(getContext(), 11), tp);
        }
    }

    /** 环形指标（内核页 CPU/负载用）。 */
    public static class RingGauge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction = 0f;
        private String label = "";

        public RingGauge(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        public void set(float fraction, String label) {
            this.fraction = Math.max(0f, Math.min(1f, fraction));
            this.label = label;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - 8f;
            p.setStrokeWidth(10f);
            p.setColor(Md3.SURFACE_HIGH);
            canvas.drawCircle(cx, cy, r, p);
            p.setColor(Md3.PRIMARY);
            canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90, 360 * fraction, false, p);
            tp.setColor(Md3.ON_SURFACE);
            tp.setTextSize(r * 0.42f);
            canvas.drawText(label, cx, cy + r * 0.15f, tp);
        }
    }

    private Views() {
    }
}
