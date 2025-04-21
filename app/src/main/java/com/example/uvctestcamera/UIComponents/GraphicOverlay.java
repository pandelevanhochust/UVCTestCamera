
package com.example.uvctestcamera.UIComponents;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import androidx.annotation.Nullable;
import android.util.AttributeSet;


public class GraphicOverlay extends View {

    private RectF faceRect = null;
    private String label = null;

    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    private final Paint rectPaint = new Paint();
    private final Paint textPaint = new Paint();

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);

        rectPaint.setColor(0xFFFF0000); // 🔴 Red
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(5f);

        textPaint.setColor(0xFFFF0000); // 🔴 Red
        textPaint.setTextSize(36f);
    }

    public void draw(Rect rect, float scaleX, float scaleY, String label) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;

        if (rect != null) {
            faceRect = new RectF(
                    rect.left * this.scaleX,
                    rect.top * this.scaleY,
                    rect.right * this.scaleX,
                    rect.bottom * this.scaleY
            );
            this.label = label;
        } else {
            faceRect = null;
            this.label = null;
        }

        postInvalidate();
    }

    public void clear() {
        faceRect = null;
        label = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faceRect != null) {
            canvas.drawRect(faceRect, rectPaint);
            if (label != null) {
                canvas.drawText(label, faceRect.left, faceRect.top - 10, textPaint);
            }
        }
    }
}
