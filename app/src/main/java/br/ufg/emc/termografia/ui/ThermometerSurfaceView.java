package br.ufg.emc.termografia.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ViewModelProviders;
import br.ufg.emc.termografia.Meter;
import br.ufg.emc.termografia.R;
import br.ufg.emc.termografia.util.RelativePoint;
import br.ufg.emc.termografia.viewmodel.ThermalImageViewModel;

public class ThermometerSurfaceView extends SurfaceView implements Runnable, LifecycleObserver {
    private static final String LOG_TAG = ThermometerSurfaceView.class.getSimpleName();

    private AppCompatActivity activity;
    private ThermalImageViewModel imageViewModel;
    private boolean moved = false;

    private SurfaceHolder surfaceHolder;
    private Thread thread;
    private boolean running = false;
    private RenderNotifyer renderNotifyer = new RenderNotifyer();

    private Paint paint;
    private Rect bounds;
    private int meterOuterSize, meterInnerSize, meterBoundSize, textMargin;
    private int defaultColor, ambientColor;

    public ThermometerSurfaceView(Context context) {
        super(context);
        init(context);
    }

    public ThermometerSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ThermometerSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public ThermometerSurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        // TODO: Encontrar alternativa para esse cast (Chamar a função init explicitamente?)
        activity = (AppCompatActivity)context;
        activity.getLifecycle().addObserver(this);

        imageViewModel = ViewModelProviders.of(activity).get(ThermalImageViewModel.class);
        imageViewModel.getImage().observe(activity, image -> renderNotifyer.request());
        imageViewModel.getMeterList().observe(activity, list -> renderNotifyer.request());

        surfaceHolder = getHolder();
        bounds = new Rect();

        Resources res = getResources();
        meterBoundSize = res.getDimensionPixelSize(R.dimen.size_thermometer_meter_bound);
        meterOuterSize = res.getDimensionPixelSize(R.dimen.size_thermometer_meter_outer);
        meterInnerSize = res.getDimensionPixelSize(R.dimen.size_thermometer_meter_inner);
        textMargin = res.getDimensionPixelSize(R.dimen.margin_thermometer_text);
        defaultColor = ResourcesCompat.getColor(res, R.color.meter_default, null);
        ambientColor = ResourcesCompat.getColor(res, R.color.meter_ambient, null);

        paint = new Paint();
        paint.setColor(defaultColor);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(res.getDimensionPixelSize(R.dimen.textsize_thermometer_paint));
        paint.setShadowLayer(3, 0, 0, Color.BLACK);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    public void onResume() {
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    public void onPause() {
        running = false;
        try {
            renderNotifyer.releaseThread();
            thread.join();
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Error releasing thread: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        Canvas canvas;
        Bitmap image;
        List<Meter> meterList;

        while (running) {
            if (!surfaceHolder.getSurface().isValid()) continue;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                canvas = surfaceHolder.lockHardwareCanvas();
            else
                canvas = surfaceHolder.lockCanvas();

            bounds.set(0, 0, canvas.getWidth(), canvas.getHeight());

            image = imageViewModel.getImage().getValue();
            if (image != null) {
                canvas.drawBitmap(image, null, bounds, null);
            } else {
                paint.setColor(Color.BLACK);
                canvas.drawRect(bounds, paint);
            }

            // Draws Marks
            meterList = imageViewModel.getMeterList().getValue();
            if (meterList != null) {
                for (int i = 0; i < meterList.size(); i++)
                    drawMeter(canvas, meterList.get(i));
            }

            surfaceHolder.unlockCanvasAndPost(canvas);
            renderNotifyer.waitRequest();
        }
    }

    private void drawMeter(Canvas canvas, Meter meter) {
        final int centerX = meter.getXForWidth(canvas.getWidth());
        final int centerY = meter.getYForHeight(canvas.getHeight());
        final boolean isAmbient = meter.isAmbient();

        int color = defaultColor;
        if (isAmbient) color = ambientColor;
        paint.setColor(color);

        final Drawable outer = ContextCompat.getDrawable(getContext(), R.drawable.ic_all_meter).mutate();
        outer.setBounds(getBoundsFor(centerX, centerY, meterOuterSize));
        outer.setTint(color);
        outer.draw(canvas);

        final Drawable inner = ContextCompat.getDrawable(getContext(), R.drawable.ic_all_add).mutate();
        inner.setBounds(getBoundsFor(centerX, centerY, meterInnerSize));
        inner.setTint(color);
        inner.draw(canvas);

        final double temp = (isAmbient ? meter.getTemperature() : meter.getDifference());
        String text = activity.getString(R.string.thermometer_temperature, temp);
        if (!isAmbient && temp >= 0) text = "+" + text;

        float textY = (meterOuterSize/2f) + textMargin + paint.getTextSize();
        if (centerY + textY >= canvas.getHeight()) textY = - (textY - paint.getTextSize());
        canvas.drawText(text, centerX, centerY + textY, paint);
    }

    private Rect getBoundsFor(Rect rect, int centerX, int centerY, int size) {
        if (rect == null) rect = new Rect();
        int half = size / 2;
        rect.set(centerX - half, centerY - half, centerX + half, centerY + half);
        return rect;
    }

    private Rect getBoundsFor(int centerX, int centerY, int size) {
        return getBoundsFor(bounds, centerX, centerY, size);
    }

    private Meter getMeterAt(int x, int y, int width, int height) {
        List<Meter> meterList = imageViewModel.getMeterList().getValue();
        if (meterList == null) return null;

        Rect rect = new Rect();
        Meter meter;
        for (int i = meterList.size() - 1; i >= 0; i--) {
            meter = meterList.get(i);
            getBoundsFor(rect, meter.getXForWidth(width), meter.getYForHeight(height), meterBoundSize);
            if (rect.contains(x, y)) return meter;
        }

        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);

        int x = (int)event.getX(), y = (int)event.getY();
        int width = getWidth(), height = getHeight();

        if (x < 0) x = 0;
        else if (x >= width) x = width - 1;

        if (y < 0) y = 0;
        else if (y >= height) y = height - 1;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                moved = false;
                Meter target = getMeterAt(x, y, width, height);
                if (target == null) return false;
                imageViewModel.setTarget(target);
                return true;
            }

            case MotionEvent.ACTION_MOVE:
                moved = true;
                imageViewModel.changeTargetPosition(new RelativePoint(x, y, width, height));
                return true;

            case MotionEvent.ACTION_UP: {
                if (!moved) {
                    Meter target = imageViewModel.getTarget();
                    if (target != null) imageViewModel.setSelectedMeter(target);
                }

                imageViewModel.setTarget(null);
                return true;
            }
        }

        return true;
    }

    public static class RenderNotifyer {
        private boolean renderRequested = false;

        public RenderNotifyer() {}

        public synchronized void waitRequest() {
            if (!renderRequested) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG + ".R", "Can't wait");
                }
            }

            renderRequested = false;
        }

        public synchronized void request() {
            renderRequested = true;
            releaseThread();
        }

        public synchronized void releaseThread() {
            this.notifyAll();
        }
    }
}
