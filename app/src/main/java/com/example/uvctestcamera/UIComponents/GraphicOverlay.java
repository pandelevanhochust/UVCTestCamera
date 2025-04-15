package com.example.uvctestcamera.UIComponents;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import androidx.annotation.Nullable;
import android.util.AttributeSet;


public class GraphicOverlay extends View {
    private static final String TAG  = "GraphicOverlay";

    private final Paint rectPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint labelPaint = new Paint();

    //Attributes of the View
    private String name = null;
    private RectF rectF = null;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    public GraphicOverlay(Context context,@Nullable AttributeSet attrs) {
        super(context,attrs);
        //draw manually
        setWillNotDraw(false);

        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(8.0f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30.0f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        labelPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(rectF != null){
            if(name != null && !name.trim().isEmpty() && !name.equalsIgnoreCase("unknown")){
                // Color the box
                rectPaint.setColor(Color.BLUE);
                labelPaint.setColor(Color.BLUE);

                // Draw label background
                float labelHeight = 40.0f;
                canvas.drawRect(
                        rectF.left,
                        rectF.bottom - labelHeight,
                        rectF.left + 0.75f * (rectF.right - rectF.left),
                        rectF.bottom,
                        labelPaint
                );

                canvas.drawText(name, rectF.left + 15f, rectF.bottom - 15f, textPaint);
            }else{
                rectPaint.setColor(Color.RED);
            }

            //draw RoundRect
            canvas.drawRoundRect(rectF,10f,10f,rectPaint);
        }
    }

    public void draw(Rect rect, float floatX, float floatY, String name){
        this.rectF = adjustBoundingRect(rect);
        this.scaleX = floatX;
        this.scaleY = floatY;
        this.name=name;
        postInvalidate();
    }

    private RectF adjustBoundingRect (Rect rect){
        if (rect != null){
            float padding = 10.0f;
            return new RectF(
                    translateX(rect.left) - padding,
                    translateY(rect.top) - padding,
                    translateX(rect.right) + padding,
                    translateY(rect.bottom) + padding
            );
        }
        return null;
    }

    public void clear() {
        this.rectF = null;
        this.name = null;
        postInvalidate(); // Triggers a redraw to clear the overlay
    }


    private float translateX(float x){return x * scaleX;}
    private float translateY(float y){return y * scaleY;}
}

