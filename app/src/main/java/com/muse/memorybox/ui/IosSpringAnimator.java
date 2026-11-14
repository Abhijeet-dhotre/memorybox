package com.muse.memorybox.ui;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Real spring physics for a tactile, Apple-HIG feel — replaces fixed
 * duration linear/ease animations. Springs are interruptible and carry
 * momentum, exactly like iOS card presses and list entrances.
 */
public final class IosSpringAnimator {

    private IosSpringAnimator() {
    }

    // iOS App Store / Apple Music style tactile card press
    @SuppressLint("ClickableViewAccessibility")
    public static void attachPressSpring(View target) {
        SpringAnimation scaleX = new SpringAnimation(target, DynamicAnimation.SCALE_X, 1f);
        SpringAnimation scaleY = new SpringAnimation(target, DynamicAnimation.SCALE_Y, 1f);

        scaleX.getSpring().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.76f);
        scaleY.getSpring().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.76f);
        // Match the spec's sweet spot (stiffness 450 is between MEDIUM and HIGH).
        scaleX.getSpring().setStiffness(450f);
        scaleY.getSpring().setStiffness(450f);

        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scaleX.animateToFinalPosition(0.965f);
                    scaleY.animateToFinalPosition(0.965f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scaleX.animateToFinalPosition(1f);
                    scaleY.animateToFinalPosition(1f);
                    break;
                default:
                    break;
            }
            return false; // Allow standard onClickListener to fire normally
        });
    }

    // Staggered spring entrance for RecyclerView items (no robotic linear fade)
    public static void runCascadingSpringEntrance(View itemView, int position) {
        itemView.setAlpha(0f);
        itemView.setTranslationY(56f);
        itemView.setScaleX(0.97f);
        itemView.setScaleY(0.97f);

        long delay = Math.min(position * 28L, 180L);

        itemView.postDelayed(() -> {
            itemView.animate().alpha(1f).setDuration(160).start();

            SpringAnimation transY = new SpringAnimation(itemView, DynamicAnimation.TRANSLATION_Y, 0f);
            transY.getSpring()
                    .setStiffness(320f)
                    .setDampingRatio(0.82f); // Crisp settle without cartoonish wobble
            transY.start();

            SpringAnimation scaleX = new SpringAnimation(itemView, DynamicAnimation.SCALE_X, 1f);
            SpringAnimation scaleY = new SpringAnimation(itemView, DynamicAnimation.SCALE_Y, 1f);
            scaleX.getSpring().setStiffness(320f).setDampingRatio(0.82f);
            scaleY.getSpring().setStiffness(320f).setDampingRatio(0.82f);
            scaleX.start();
            scaleY.start();
        }, delay);
    }
}
