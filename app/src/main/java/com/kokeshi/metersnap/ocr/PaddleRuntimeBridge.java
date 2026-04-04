package com.kokeshi.metersnap.ocr;

import android.graphics.Bitmap;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

final class PaddleRuntimeBridge {
    private static final String TAG = "PaddleRuntimeBridge";

    private PaddleRuntimeBridge() {
    }

    static Object createPredictor(String modelPath, int threads) {
        try {
            Class<?> mobileConfigClass = Class.forName("com.baidu.paddle.lite.MobileConfig");
            Class<?> powerModeClass = Class.forName("com.baidu.paddle.lite.PowerMode");
            Class<?> predictorClass = Class.forName("com.baidu.paddle.lite.PaddlePredictor");

            Constructor<?>[] constructors = mobileConfigClass.getDeclaredConstructors();
            if (constructors.length == 0) return null;
            Constructor<?> ctor = constructors[0];
            ctor.setAccessible(true);
            Object config = ctor.newInstance();

            Method setModel = findMethod(mobileConfigClass, "setModelFromFile");
            if (setModel == null) return null;
            setModel.invoke(config, modelPath);

            Object highPower = null;
            Object[] enumValues = powerModeClass.getEnumConstants();
            if (enumValues != null) {
                for (Object enumValue : enumValues) {
                    if ("LITE_POWER_HIGH".equals(String.valueOf(enumValue))) {
                        highPower = enumValue;
                        break;
                    }
                }
            }

            Method setPowerMode = findMethod(mobileConfigClass, "setPowerMode");
            if (setPowerMode != null && highPower != null) {
                setPowerMode.invoke(config, highPower);
            }

            Method setThreads = findMethod(mobileConfigClass, "setThreads");
            if (setThreads != null) {
                setThreads.invoke(config, threads);
            }

            Method createPredictor = findMethod(predictorClass, "createPaddlePredictor");
            if (createPredictor == null) return null;
            return createPredictor.invoke(null, config);
        } catch (Exception e) {
            Log.e(TAG, "createPredictor failed", e);
            return null;
        }
    }

    static String runRecognition(Object predictor, Bitmap bitmap, List<String> labels) {
        if (predictor == null || bitmap == null || labels == null) return "";

        try {
            int modelH = 48;
            int modelW = 320;
            float[] inputData = preprocess(bitmap, modelW, modelH);

            Method getInput = findMethod(predictor.getClass(), "getInput");
            if (getInput == null) return "";
            Object inputTensor = getInput.invoke(predictor, 0);
            if (inputTensor == null) return "";

            Method resize = findMethod(inputTensor.getClass(), "resize");
            Method setData = findMethod(inputTensor.getClass(), "setData");
            if (resize == null || setData == null) return "";

            resize.invoke(inputTensor, new int[]{1, 1, modelH, modelW});
            setData.invoke(inputTensor, inputData);

            Method run = findMethod(predictor.getClass(), "run");
            if (run == null) return "";
            run.invoke(predictor);

            Method getOutput = findMethod(predictor.getClass(), "getOutput");
            if (getOutput == null) return "";
            Object outputTensor = getOutput.invoke(predictor, 0);
            if (outputTensor == null) return "";

            Method shapeMethod = findMethod(outputTensor.getClass(), "shape");
            Method getFloatData = findMethod(outputTensor.getClass(), "getFloatData");
            if (shapeMethod == null || getFloatData == null) return "";

            Object shapeObj = shapeMethod.invoke(outputTensor);
            Object logitsObj = getFloatData.invoke(outputTensor);
            if (!(shapeObj instanceof int[]) || !(logitsObj instanceof float[])) return "";

            return decodeCtc((float[]) logitsObj, (int[]) shapeObj, labels);
        } catch (Exception e) {
            Log.d(TAG, "runRecognition failed", e);
            return "";
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Method method : cls.getMethods()) {
            if (name.equals(method.getName())) return method;
        }
        return null;
    }

    private static float[] preprocess(Bitmap bitmap, int width, int height) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] out = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            float gray = (0.299f * r) + (0.587f * g) + (0.114f * b);
            out[i] = (gray / 255f - 0.5f) / 0.5f;
        }
        return out;
    }

    private static String decodeCtc(float[] logits, int[] shape, List<String> labels) {
        if (shape.length < 3) return "";
        int steps = shape[1];
        int classes = shape[2];
        if (steps <= 0 || classes <= 0) return "";
        if (logits.length < steps * classes) return "";

        int last = -1;
        StringBuilder sb = new StringBuilder();

        for (int t = 0; t < steps; t++) {
            int offset = t * classes;
            int bestIdx = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < classes; c++) {
                float score = logits[offset + c];
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = c;
                }
            }

            if (bestIdx != 0 && bestIdx != last) {
                int labelIndex = bestIdx - 1;
                if (labelIndex >= 0 && labelIndex < labels.size()) {
                    String token = labels.get(labelIndex);
                    if (token != null && token.length() == 1 && Character.isDigit(token.charAt(0))) {
                        sb.append(token);
                    }
                }
            }
            last = bestIdx;
        }

        String value = sb.toString();
        return value.length() > 5 ? value.substring(0, 5) : value;
    }
}

