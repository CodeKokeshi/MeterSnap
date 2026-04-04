package com.kokeshi.metersnap.ocr;

import android.graphics.Bitmap;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

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

            Method getInput = findMethod(predictor.getClass(), "getInput");
            if (getInput == null) return "";
            Object inputTensor = invokeIndexed(getInput, predictor, 0);
            if (inputTensor == null) return "";

            Method run = findMethod(predictor.getClass(), "run");
            if (run == null) return "";

            Method getOutput = findMethod(predictor.getClass(), "getOutput");
            if (getOutput == null) return "";

            int modelW = computeRecWidth(bitmap, modelH, 320);
            List<InputVariant> variants = buildInputVariants(bitmap, modelW, modelH);

            String bestValue = "";
            float bestScore = Float.NEGATIVE_INFINITY;

            for (InputVariant variant : variants) {
                try {
                    int[] tensorShape = variant.nchw
                            ? new int[]{1, variant.channels, variant.height, variant.width}
                            : new int[]{1, variant.height, variant.width, variant.channels};

                    if (!invokeResize(inputTensor, tensorShape)) continue;
                    if (!invokeSetData(inputTensor, variant.data, tensorShape)) continue;

                    run.invoke(predictor);

                    Object outputTensor = invokeIndexed(getOutput, predictor, 0);
                    if (outputTensor == null) continue;

                    int[] outputShape = getTensorShape(outputTensor);
                    float[] logits = getTensorFloatData(outputTensor);
                    if (outputShape == null || logits == null) continue;

                    DecodeResult decoded = decodeCtc(logits, outputShape, labels);
                    float composite = decoded.value.length() * 1000f + decoded.score;
                    if (composite > bestScore) {
                        bestScore = composite;
                        bestValue = decoded.value;
                    }
                } catch (Exception variantError) {
                    Log.d(TAG, "Variant failed: " + variant.tag, variantError);
                }
            }

            return bestValue;
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

    private static Object invokeIndexed(Method method, Object target, int index) throws Exception {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return method.invoke(target);
        }

        Class<?> first = parameterTypes[0];
        if (first == int.class || first == Integer.class) {
            return method.invoke(target, index);
        }
        if (first == long.class || first == Long.class) {
            return method.invoke(target, (long) index);
        }

        return method.invoke(target, index);
    }

    private static int[] getTensorShape(Object tensor) {
        try {
            Method shapeMethod = findMethod(tensor.getClass(), "shape");
            if (shapeMethod == null) return null;
            Object shapeObj = shapeMethod.invoke(tensor);
            if (shapeObj instanceof int[]) {
                return (int[]) shapeObj;
            }
            if (shapeObj instanceof long[]) {
                long[] longShape = (long[]) shapeObj;
                int[] out = new int[longShape.length];
                for (int i = 0; i < longShape.length; i++) {
                    out[i] = (int) longShape[i];
                }
                return out;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not read tensor shape", e);
        }
        return null;
    }

    private static float[] getTensorFloatData(Object tensor) {
        try {
            Method getFloatData = findMethod(tensor.getClass(), "getFloatData");
            if (getFloatData != null) {
                Object value = getFloatData.invoke(tensor);
                if (value instanceof float[]) {
                    return (float[]) value;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "getFloatData failed", e);
        }

        try {
            Method getData = findMethod(tensor.getClass(), "getData");
            if (getData != null) {
                Object value = getData.invoke(tensor);
                if (value instanceof float[]) {
                    return (float[]) value;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "getData failed", e);
        }

        return null;
    }

    private static boolean invokeResize(Object tensor, int[] shape) {
        List<Method> resizeMethods = findMethods(tensor.getClass(), "resize");
        if (resizeMethods.isEmpty()) return false;

        for (Method method : resizeMethods) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;

            try {
                if (params[0] == int[].class) {
                    method.invoke(tensor, shape);
                    return true;
                }
                if (params[0] == long[].class) {
                    long[] longShape = new long[shape.length];
                    for (int i = 0; i < shape.length; i++) {
                        longShape[i] = shape[i];
                    }
                    method.invoke(tensor, longShape);
                    return true;
                }
            } catch (Exception ignored) {
                // Try next overload
            }
        }

        return false;
    }

    private static boolean invokeSetData(Object tensor, float[] data, int[] shape) {
        List<Method> methods = findMethods(tensor.getClass(), "setData");
        if (methods.isEmpty()) return false;

        for (Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            try {
                if (params.length == 1) {
                    if (params[0] == float[].class || Object.class.equals(params[0])) {
                        method.invoke(tensor, data);
                        return true;
                    }
                }

                if (params.length == 2) {
                    if ((params[0] == float[].class || Object.class.equals(params[0])) && params[1] == int[].class) {
                        method.invoke(tensor, data, shape);
                        return true;
                    }

                    if ((params[0] == float[].class || Object.class.equals(params[0])) && params[1] == long[].class) {
                        long[] longShape = new long[shape.length];
                        for (int i = 0; i < shape.length; i++) {
                            longShape[i] = shape[i];
                        }
                        method.invoke(tensor, data, longShape);
                        return true;
                    }

                    if ((params[0] == float[].class || Object.class.equals(params[0])) &&
                            (params[1] == int.class || params[1] == Integer.class)) {
                        method.invoke(tensor, data, data.length);
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // Try next overload
            }
        }

        return false;
    }

    private static List<Method> findMethods(Class<?> cls, String name) {
        List<Method> methods = new ArrayList<>();
        for (Method method : cls.getMethods()) {
            if (name.equals(method.getName())) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static int computeRecWidth(Bitmap bitmap, int targetHeight, int maxWidth) {
        if (bitmap.getHeight() <= 0) return maxWidth;
        float ratio = (float) bitmap.getWidth() / (float) bitmap.getHeight();
        int width = Math.round(targetHeight * ratio);
        width = Math.max(32, Math.min(maxWidth, width));
        return ((width + 3) / 4) * 4;
    }

    private static List<InputVariant> buildInputVariants(Bitmap bitmap, int width, int height) {
        List<InputVariant> variants = new ArrayList<>();
        variants.add(new InputVariant(preprocessGray(bitmap, width, height, false, true), width, height, 1, true, "G11"));
        variants.add(new InputVariant(preprocessGray(bitmap, width, height, true, true), width, height, 1, true, "G11_INV"));
        variants.add(new InputVariant(preprocessGray(bitmap, width, height, false, false), width, height, 1, true, "G01"));

        variants.add(new InputVariant(preprocessColorNchw(bitmap, width, height, false, true), width, height, 3, true, "RGB11_NCHW"));
        variants.add(new InputVariant(preprocessColorNchw(bitmap, width, height, true, true), width, height, 3, true, "BGR11_NCHW"));
        variants.add(new InputVariant(preprocessColorNchw(bitmap, width, height, false, false), width, height, 3, true, "RGB01_NCHW"));

        variants.add(new InputVariant(preprocessColorNhwc(bitmap, width, height, false, true), width, height, 3, false, "RGB11_NHWC"));
        variants.add(new InputVariant(preprocessColorNhwc(bitmap, width, height, true, true), width, height, 3, false, "BGR11_NHWC"));
        variants.add(new InputVariant(preprocessColorNhwc(bitmap, width, height, false, false), width, height, 3, false, "RGB01_NHWC"));

        return variants;
    }

    private static float[] preprocessGray(Bitmap bitmap, int width, int height, boolean invert, boolean minusOneToOne) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] out = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;
            float gray = (0.299f * r) + (0.587f * g) + (0.114f * b);
            if (invert) gray = 1f - gray;
            out[i] = minusOneToOne ? ((gray - 0.5f) / 0.5f) : gray;
        }
        return out;
    }

    private static float[] preprocessColorNchw(Bitmap bitmap, int width, int height, boolean bgrOrder, boolean minusOneToOne) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] out = new float[width * height * 3];
        int hw = width * height;

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;

            if (minusOneToOne) {
                r = (r - 0.5f) / 0.5f;
                g = (g - 0.5f) / 0.5f;
                b = (b - 0.5f) / 0.5f;
            }

            if (bgrOrder) {
                out[i] = b;
                out[hw + i] = g;
                out[(hw * 2) + i] = r;
            } else {
                out[i] = r;
                out[hw + i] = g;
                out[(hw * 2) + i] = b;
            }
        }
        return out;
    }

    private static float[] preprocessColorNhwc(Bitmap bitmap, int width, int height, boolean bgrOrder, boolean minusOneToOne) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] out = new float[width * height * 3];
        int offset = 0;

        for (int p : pixels) {
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;

            if (minusOneToOne) {
                r = (r - 0.5f) / 0.5f;
                g = (g - 0.5f) / 0.5f;
                b = (b - 0.5f) / 0.5f;
            }

            if (bgrOrder) {
                out[offset++] = b;
                out[offset++] = g;
                out[offset++] = r;
            } else {
                out[offset++] = r;
                out[offset++] = g;
                out[offset++] = b;
            }
        }

        return out;
    }

    private static DecodeResult decodeCtc(float[] logits, int[] shape, List<String> labels) {
        List<DecodeLayout> layouts = buildDecodeLayouts(shape, logits.length, labels.size());
        DecodeResult best = new DecodeResult("", Float.NEGATIVE_INFINITY);

        for (DecodeLayout layout : layouts) {
            DecodeResult a = decodeCtcLayout(logits, layout, labels, 0, 1);
            DecodeResult b = decodeCtcLayout(logits, layout, labels, layout.classes - 1, 0);
            DecodeResult c = decodeCtcLayout(logits, layout, labels, 0, 0);

            best = pickBetter(best, a);
            best = pickBetter(best, b);
            best = pickBetter(best, c);
        }

        return best;
    }

    private static DecodeResult decodeCtcLayout(
            float[] logits,
            DecodeLayout layout,
            List<String> labels,
            int blankIndex,
            int labelShift
    ) {
        int steps = layout.steps;
        int classes = layout.classes;
        if (steps <= 0 || classes <= 0) return new DecodeResult("", Float.NEGATIVE_INFINITY);

        int frameSize = steps * classes;
        if (frameSize <= 0 || logits.length < frameSize) return new DecodeResult("", Float.NEGATIVE_INFINITY);

        int last = -1;
        StringBuilder sb = new StringBuilder();
        float scoreSum = 0f;
        int scoreCount = 0;

        for (int t = 0; t < steps; t++) {
            int bestIdx = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < classes; c++) {
                int index = layout.transposed ? (c * steps + t) : (t * classes + c);
                if (index < 0 || index >= logits.length) continue;
                float score = logits[index];
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = c;
                }
            }

            scoreSum += bestScore;
            scoreCount++;

            if (bestIdx != blankIndex && bestIdx != last) {
                int labelIndex = bestIdx - labelShift;
                String token = mapIndexToToken(labelIndex, classes, labels);
                if (token != null && !token.isEmpty()) {
                    sb.append(token);
                }
            }
            last = bestIdx;
        }

        String value = sb.toString();
        if (value.length() > 8) {
            value = value.substring(0, 8);
        }
        value = value.replaceAll("[^0-9.]", "");
        float avgScore = (scoreCount > 0) ? (scoreSum / scoreCount) : Float.NEGATIVE_INFINITY;
        return new DecodeResult(value, avgScore);
    }

    private static DecodeResult pickBetter(DecodeResult a, DecodeResult b) {
        float scoreA = (a.value.length() * 1000f) + a.score;
        float scoreB = (b.value.length() * 1000f) + b.score;
        return (scoreB > scoreA) ? b : a;
    }

    private static List<DecodeLayout> buildDecodeLayouts(int[] shape, int logitsSize, int labelCount) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<DecodeLayout> layouts = new ArrayList<>();

        if (shape != null) {
            int n = shape.length;
            if (n >= 2) {
                addLayout(layouts, seen, shape[n - 2], shape[n - 1], false, logitsSize);
                addLayout(layouts, seen, shape[n - 1], shape[n - 2], true, logitsSize);
            }
            if (n >= 3) {
                addLayout(layouts, seen, shape[n - 3], shape[n - 2], false, logitsSize);
                addLayout(layouts, seen, shape[n - 2], shape[n - 3], true, logitsSize);
            }
        }

        int inferredClasses = Math.max(11, labelCount + 1);
        if (logitsSize >= inferredClasses && logitsSize % inferredClasses == 0) {
            int inferredSteps = logitsSize / inferredClasses;
            addLayout(layouts, seen, inferredSteps, inferredClasses, false, logitsSize);
        }

        return layouts;
    }

    private static void addLayout(
            List<DecodeLayout> layouts,
            LinkedHashSet<String> seen,
            int steps,
            int classes,
            boolean transposed,
            int logitsSize
    ) {
        if (steps <= 0 || classes <= 0) return;
        long frameSize = (long) steps * (long) classes;
        if (frameSize > logitsSize) return;

        String key = steps + "x" + classes + "_" + (transposed ? "T" : "N");
        if (seen.contains(key)) return;
        seen.add(key);
        layouts.add(new DecodeLayout(steps, classes, transposed));
    }

    private static String mapIndexToToken(int labelIndex, int classes, List<String> labels) {
        if (labelIndex < 0) return null;

        if (labelIndex < labels.size()) {
            String token = labels.get(labelIndex);
            if (token == null || token.isEmpty()) return null;
            if (".".equals(token)) return token;
            if (token.length() == 1 && Character.isDigit(token.charAt(0))) {
                int n = Character.getNumericValue(token.charAt(0));
                return (n >= 0 && n <= 9) ? String.valueOf((char) ('0' + n)) : null;
            }
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (Character.isDigit(ch)) {
                    int n = Character.getNumericValue(ch);
                    if (n >= 0 && n <= 9) {
                        return String.valueOf((char) ('0' + n));
                    }
                }
            }
        }

        // Fallback for numeric models when labels and class count don't align perfectly.
        if (classes <= 12) {
            if (labelIndex >= 0 && labelIndex <= 9) return String.valueOf((char) ('0' + labelIndex));
            if (labelIndex == 10) return ".";
        }

        // Last-resort fallback for common English models with digits near low indices.
        if (labelIndex >= 0 && labelIndex <= 9) {
            return String.valueOf((char) ('0' + labelIndex));
        }

        return null;
    }

    private static final class InputVariant {
        final float[] data;
        final int width;
        final int height;
        final int channels;
        final boolean nchw;
        final String tag;

        InputVariant(float[] data, int width, int height, int channels, boolean nchw, String tag) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.nchw = nchw;
            this.tag = tag;
        }
    }

    private static final class DecodeLayout {
        final int steps;
        final int classes;
        final boolean transposed;

        DecodeLayout(int steps, int classes, boolean transposed) {
            this.steps = steps;
            this.classes = classes;
            this.transposed = transposed;
        }
    }

    private static final class DecodeResult {
        final String value;
        final float score;

        DecodeResult(String value, float score) {
            this.value = value;
            this.score = score;
        }
    }
}

