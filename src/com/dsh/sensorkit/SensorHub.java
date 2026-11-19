package com.dsh.sensorkit;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.List;

/** 传感器驱动程序：把 Android 传感器注册成内核设备对象。 */
public class SensorHub implements SensorEventListener {

    public static final class Def {
        public final int type;
        public final String name;
        public final String cn;
        public final int rateUs;

        Def(int type, String name, String cn, int rateUs) {
            this.type = type;
            this.name = name;
            this.cn = cn;
            this.rateUs = rateUs;
        }
    }

    public static final Def[] DEFS = new Def[]{
            new Def(Sensor.TYPE_LIGHT, "light0", "光照", SensorManager.SENSOR_DELAY_UI),
            new Def(Sensor.TYPE_PROXIMITY, "prox0", "距离", SensorManager.SENSOR_DELAY_UI),
            new Def(Sensor.TYPE_ACCELEROMETER, "acc0", "加速度", SensorManager.SENSOR_DELAY_GAME),
            new Def(Sensor.TYPE_ROTATION_VECTOR, "rot0", "旋转矢量", SensorManager.SENSOR_DELAY_GAME),
            new Def(Sensor.TYPE_MAGNETIC_FIELD, "mag0", "磁场", SensorManager.SENSOR_DELAY_UI),
            new Def(Sensor.TYPE_PRESSURE, "press0", "气压", SensorManager.SENSOR_DELAY_UI),
            new Def(Sensor.TYPE_GYROSCOPE, "gyro0", "陀螺仪", SensorManager.SENSOR_DELAY_UI),
            new Def(Sensor.TYPE_STEP_COUNTER, "step0", "计步", SensorManager.SENSOR_DELAY_NORMAL)
    };

    private SensorManager sm;
    private final List<Rtos.Device> devices = new ArrayList<>();
    private final List<Sensor> sensors = new ArrayList<>();

    public void init(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Rtos.clearObjects(Rtos.ObjType.DEVICE);
        devices.clear();
        sensors.clear();
        for (Def d : DEFS) {
            Sensor s = sm.getDefaultSensor(d.type);
            if (s == null) continue;
            sensors.add(s);
            devices.add(Rtos.createDevice(d.name, d.cn, s.getVendor(), d.type));
        }
    }

    public List<Rtos.Device> devices() {
        return devices;
    }

    public Rtos.Device device(int androidType) {
        for (Rtos.Device d : devices) if (d.androidType == androidType) return d;
        return null;
    }

    public void openAll() {
        for (int i = 0; i < sensors.size(); i++) {
            Rtos.Device d = devices.get(i);
            if (d.open) sm.registerListener(this, sensors.get(i), rateOf(sensors.get(i)));
        }
    }

    private int rateOf(Sensor s) {
        for (Def d : DEFS) if (d.type == s.getType()) return d.rateUs;
        return SensorManager.SENSOR_DELAY_UI;
    }

    public void setOpen(Rtos.Device d, boolean open) {
        d.open = open;
        int idx = devices.indexOf(d);
        if (idx < 0 || idx >= sensors.size()) return;
        if (open) {
            sm.registerListener(this, sensors.get(idx), rateOf(sensors.get(idx)));
        } else {
            sm.unregisterListener(this, sensors.get(idx));
        }
    }

    /** 调整设备采样间隔（毫秒），映射到 SENSOR_DELAY_* 档位。 */
    public void setRate(Rtos.Device d, int ms) {
        d.rateMs = ms;
        int idx = devices.indexOf(d);
        if (idx < 0 || idx >= sensors.size()) return;
        int delay;
        if (ms <= 16) delay = SensorManager.SENSOR_DELAY_GAME;
        else if (ms <= 60) delay = SensorManager.SENSOR_DELAY_UI;
        else delay = SensorManager.SENSOR_DELAY_NORMAL;
        if (d.open) {
            sm.unregisterListener(this, sensors.get(idx));
            sm.registerListener(this, sensors.get(idx), delay);
        }
    }

    public void stop() {
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Rtos.Device d = device(e.sensor.getType());
        if (d == null || !d.open) return;
        d.feed(e.values.clone());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
