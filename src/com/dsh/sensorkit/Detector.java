package com.dsh.sensorkit;

import android.content.Context;
import android.content.res.AssetManager;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 离线目标检测：TFLite + SSD MobileNet（COCO 80 类，量化模型）。
 * 输入 YUV_420_888 帧，输出归一化检测框。
 */
public class Detector {

    public static class Det {
        public final String label;
        public final float score;
        public final float x1, y1, x2, y2; // 归一化，左上/右下
        public boolean online = false;

        Det(String label, float score, float x1, float y1, float x2, float y2) {
            this.label = label;
            this.score = score;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private Interpreter interpreter;
    private int inW = 300, inH = 300;
    private ByteBuffer input;
    private final List<String> labels = new ArrayList<>();
    private Object[] outputs;
    private int boxIdx = -1, clsIdx = -1, scoreIdx = -1, countIdx = -1;
    private boolean ready;
    private final java.util.List<Integer> rank2 = new java.util.ArrayList<>();
    private boolean resolved;
    private int offX, offY, scaleW = 300, scaleH = 300;
    private String error = "";

    public boolean ready() { return ready; }
    public String error() { return error; }
    public int inputSize() { return inW; }

    public void init(Context ctx, boolean useNnapi) {
        try {
            AssetManager am = ctx.getAssets();
            BufferedReader br = new BufferedReader(new InputStreamReader(am.open("labelmap.txt"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) labels.add(line.trim());
            br.close();

            byte[] modelBytes = readAll(am.open("detect.tflite"));
            ByteBuffer model = ByteBuffer.allocateDirect(modelBytes.length);
            model.order(ByteOrder.nativeOrder());
            model.put(modelBytes);
            model.rewind();

            Interpreter.Options opt = new Interpreter.Options();
            opt.setNumThreads(4);
            if (useNnapi) {
                try {
                    opt.setUseNNAPI(true);
                } catch (Throwable ignored) {
                }
            }
            interpreter = new Interpreter(model, opt);
            interpreter.allocateTensors();

            Tensor in = interpreter.getInputTensor(0);
            int[] s = in.shape(); // [1,h,w,3]
            inH = s[1];
            inW = s[2];
            input = ByteBuffer.allocateDirect(inW * inH * 3);
            input.order(ByteOrder.nativeOrder());

            int n = interpreter.getOutputTensorCount();
            outputs = new Object[n];
            for (int i = 0; i < n; i++) {
                Tensor t = interpreter.getOutputTensor(i);
                int[] ts = t.shape();
                DataType dt = t.dataType();
                if (ts.length == 3 && ts[ts.length - 1] == 4) {
                    boxIdx = i;
                    outputs[i] = new float[ts[0]][ts[1]][4];
                } else if (ts.length == 2) {
                    if (dt == DataType.FLOAT32) {
                        rank2.add(i);
                        outputs[i] = new float[ts[0]][ts[1]];
                    } else {
                        clsIdx = i;
                        outputs[i] = new float[ts[0]][ts[1]];
                    }
                } else {
                    countIdx = i;
                    outputs[i] = new float[1];
                }
            }
            ready = true;
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            ready = false;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
        is.close();
        return bos.toByteArray();
    }

    /** 在 YUV 帧的中心正方形区域内检测。 */
    public List<Det> detect(byte[] yuv, int w, int h, float threshold) {
        List<Det> out = new ArrayList<>();
        if (!ready) return out;
        try {
            fillInput(yuv, w, h);
            input.rewind();
            if (interpreter == null) return out;
            interpreter.run(input, outputs);

            int n = 10;
            if (boxIdx >= 0) n = ((float[][][]) outputs[boxIdx]).length == 0 ? 10
                    : ((float[][][]) outputs[boxIdx])[0].length;
            if (!resolved && rank2.size() >= 2) {
                float[][] a = (float[][]) outputs[rank2.get(0)];
                float[][] b = (float[][]) outputs[rank2.get(1)];
                if (fractional(a) > fractional(b)) {
                    scoreIdx = rank2.get(0);
                    clsIdx = rank2.get(1);
                } else {
                    scoreIdx = rank2.get(1);
                    clsIdx = rank2.get(0);
                }
                resolved = true;
            }
            float[][][] boxes = boxIdx >= 0 ? (float[][][]) outputs[boxIdx] : null;
            float[][] classes = clsIdx >= 0 ? (float[][]) outputs[clsIdx] : null;
            float[][] scores = scoreIdx >= 0 ? (float[][]) outputs[scoreIdx] : null;
            if (boxes == null || scores == null) return out;

            for (int i = 0; i < n; i++) {
                float sc = scores[0][i];
                if (sc < threshold) continue;
                int cls = classes != null ? (int) classes[0][i] : 0;
                String label = cls >= 0 && cls < labels.size() ? toChinese(labels.get(cls)) : ("类" + cls);
                float[] b = boxes[0][i];
                float x1 = unX(b[1]), y1 = unY(b[0]);
                float x2 = unX(b[3]), y2 = unY(b[2]);
                if (x2 - x1 < 0.01f || y2 - y1 < 0.01f) continue;
                out.add(new Det(label, sc, x1, y1, x2, y2));
            }
            Collections.sort(out, (a, b) -> Float.compare(b.score, a.score));
            if (out.size() > 6) out = out.subList(0, 6);
        } catch (Throwable e) {
            error = e.getClass().getSimpleName();
        }
        return out;
    }

    private float unX(float v) {
        float x = (v * inW - offX) / (float) Math.max(1, scaleW);
        return Math.max(0f, Math.min(1f, x));
    }

    private float unY(float v) {
        float y = (v * inH - offY) / (float) Math.max(1, scaleH);
        return Math.max(0f, Math.min(1f, y));
    }

    private float fractional(float[][] v) {
        int frac = 0, n = 0;
        for (float[] row : v) {
            for (float x : row) {
                if (n > 200) break;
                float d = Math.abs(x - Math.round(x));
                if (d > 0.001f && x <= 1.0f) frac++;
                n++;
            }
        }
        return n == 0 ? 0 : frac / (float) n;
    }

    /** YUV(NV21) -> 模型输入：letterbox 等比缩放 + 居中灰边，避免丢边缘目标。 */
    private void fillInput(byte[] yuv, int w, int h) {
        input.rewind();
        float scale = Math.min(inW / (float) w, inH / (float) h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        offX = (inW - nw) / 2;
        offY = (inH - nh) / 2;
        scaleW = nw;
        scaleH = nh;

        byte[] row = new byte[inW * 3];
        for (int y = 0; y < inH; y++) {
            int dy = y - offY;
            boolean insideY = dy >= 0 && dy < nh;
            int sy = insideY ? (int) (dy / scale) : 0;
            if (sy >= h) sy = h - 1;
            int base = sy * w;
            for (int x = 0; x < inW; x++) {
                int dx = x - offX;
                boolean inside = insideY && dx >= 0 && dx < nw;
                if (!inside) {
                    row[x * 3] = 114;
                    row[x * 3 + 1] = 114;
                    row[x * 3 + 2] = 114;
                    continue;
                }
                int sx = (int) (dx / scale);
                if (sx >= w) sx = w - 1;
                int Y = yuv[base + sx] & 0xff;
                int uv = w * h + (sy / 2) * w + (sx / 2) * 2;
                int V = 0, U = 0;
                if (uv + 1 < yuv.length) {
                    V = yuv[uv] & 0xff;
                    U = yuv[uv + 1] & 0xff;
                }
                int c = Math.max(0, Y - 16);
                int d = U - 128, e = V - 128;
                int r = Math.max(0, Math.min(255, (298 * c + 409 * e + 128) >> 8));
                int g = Math.max(0, Math.min(255, (298 * c - 100 * d - 208 * e + 128) >> 8));
                int b2 = Math.max(0, Math.min(255, (298 * c + 516 * d + 128) >> 8));
                row[x * 3] = (byte) r;
                row[x * 3 + 1] = (byte) g;
                row[x * 3 + 2] = (byte) b2;
            }
            input.put(row);
        }
        input.rewind();
    }

    private static final String[][] CN = {
            {"person", "人"}, {"bicycle", "自行车"}, {"car", "汽车"}, {"motorcycle", "摩托车"},
            {"airplane", "飞机"}, {"bus", "公交车"}, {"train", "火车"}, {"truck", "卡车"},
            {"boat", "船"}, {"traffic light", "红绿灯"}, {"fire hydrant", "消防栓"},
            {"stop sign", "停车标志"}, {"parking meter", "停车计时器"}, {"bench", "长椅"},
            {"bird", "鸟"}, {"cat", "猫"}, {"dog", "狗"}, {"horse", "马"}, {"sheep", "羊"},
            {"cow", "牛"}, {"elephant", "大象"}, {"bear", "熊"}, {"zebra", "斑马"},
            {"giraffe", "长颈鹿"}, {"backpack", "背包"}, {"umbrella", "雨伞"}, {"handbag", "手提包"},
            {"tie", "领带"}, {"suitcase", "行李箱"}, {"frisbee", "飞盘"}, {"skis", "滑雪板"},
            {"snowboard", "单板"}, {"sports ball", "球"}, {"kite", "风筝"}, {"bat", "球棒"},
            {"glove", "手套"}, {"skateboard", "滑板"}, {"surfboard", "冲浪板"},
            {"tennis racket", "网球拍"}, {"bottle", "瓶子"}, {"wine glass", "酒杯"},
            {"cup", "杯子"}, {"fork", "叉子"}, {"knife", "刀"}, {"spoon", "勺子"},
            {"bowl", "碗"}, {"banana", "香蕉"}, {"apple", "苹果"}, {"sandwich", "三明治"},
            {"orange", "橙子"}, {"broccoli", "西兰花"}, {"carrot", "胡萝卜"}, {"hot dog", "热狗"},
            {"pizza", "披萨"}, {"donut", "甜甜圈"}, {"cake", "蛋糕"}, {"chair", "椅子"},
            {"couch", "沙发"}, {"potted plant", "盆栽"}, {"bed", "床"}, {"dining table", "桌子"},
            {"toilet", "马桶"}, {"tv", "电视"}, {"laptop", "笔记本电脑"}, {"mouse", "鼠标"},
            {"remote", "遥控器"}, {"keyboard", "键盘"}, {"cell phone", "手机"}, {"microwave", "微波炉"},
            {"oven", "烤箱"}, {"toaster", "烤面包机"}, {"sink", "水槽"}, {"refrigerator", "冰箱"},
            {"book", "书"}, {"clock", "时钟"}, {"vase", "花瓶"}, {"scissors", "剪刀"},
            {"teddy bear", "玩具熊"}, {"hair drier", "吹风机"}, {"toothbrush", "牙刷"}
    };

    private static String toChinese(String label) {
        for (String[] kv : CN) {
            if (kv[0].equalsIgnoreCase(label)) return kv[1];
        }
        return label;
    }

    public void close() {
        try {
            if (interpreter != null) interpreter.close();
        } catch (Throwable ignored) {
        }
        interpreter = null;
        ready = false;
    }
}
